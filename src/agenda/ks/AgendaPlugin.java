// SPDX-License-Identifier: Apache-2.0
package agenda.ks;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Shows the agenda card over the screensaver. The plugin reads each listed
 * calendar through KS's own Home Assistant connection, so it needs no token
 * and no helper sensor, and shows or hides one overlay with the screensaver.
 */
public final class AgendaPlugin implements KioskPlugin {
    static final String OVERLAY = "agenda";
    static final String SAVER_EVENT = "screensaver.state";
    /** A Home Assistant time trigger: plugins have no timer of their own. */
    static final String REFRESH = "refresh";
    /** The Calendar field of each Calendars row, whose picker lists the calendar entities. */
    static final String CALENDAR_CHOICES = "calendars.calendar";
    /** A one-answer subscription, numbered per attempt: render_template is the light way to list one domain's entities. */
    static final String LIST = "calendarList";
    /**
     * The most bytes one character of a name can take in the event KS measures, utf8.encode(jsonEncode(...)): Dart writes
     * control characters as six-byte escapes such as \u000b, and anything else in at most four UTF-8 bytes.
     */
    static final int NAME_CHAR_BYTES = 6;
    /**
     * [rows, total]: [[entity_id, name], ...] by name and how many calendars HA has. HA sends it as JSON. The rows stop at
     * KS's 200 choices or at 25,000 bytes, counting each name character at NAME_CHAR_BYTES (entity IDs are ASCII), so the
     * answer stays under KS's 30 KB event limit with room for the event's own fields.
     */
    static final String LIST_TEMPLATE = "{%- set ns = namespace(rows=[], size=0) -%}"
        + "{%- for s in states.calendar | sort(attribute='name') -%}"
        + "{%- set name = s.name[:80] -%}{%- set cost = (s.entity_id | length) + (name | length) * " + NAME_CHAR_BYTES + " + 8 -%}"
        + "{%- if ns.rows | length < 200 and ns.size + cost <= 25000 -%}"
        + "{%- set ns.rows = ns.rows + [[s.entity_id, name]] -%}{%- set ns.size = ns.size + cost -%}{%- endif -%}"
        + "{%- endfor -%}{{ [ns.rows, states.calendar | count] }}";

    private PluginHost host;
    private boolean alive;
    private AgendaSettings settings;
    private int saverRevision;
    private boolean saverActive;
    private boolean previewing;
    private boolean noHomeAssistant;
    /** Whether the card can show, with the screensaver or a preview, as follow last saw it. */
    private boolean visible;
    /** Whether the 10-minute refresh is subscribed. It runs only while the card can show. */
    private boolean following;
    /** Numbers each subscribe, so a late reply to an earlier one cannot overwrite the status. */
    private int subscribeAttempt;
    /** The key of the listing under way, or null. Each attempt has its own, so a late reply to an older one is ignored. */
    private String listKey;
    private int listAttempt;
    /** The Refresh run waiting on that list, or null when the settings page asked for it. */
    private String listRun;
    /** Why the 10-minute refresh is not running, or null while it is. */
    private String refreshError;
    /** get_events calls still out. KS answers each within 25 seconds, with a timeout at worst. */
    private int pending;
    /** A read was asked for while calls were out, so one runs when they are all back. */
    private boolean fetchAgain;
    /** That read came from start or a settings change, so it runs even if the card is hidden by then. */
    private boolean fetchAgainRequired;
    /** The latest answer per calendar. It stays until the next one lands, so a refresh never blanks the card. */
    private final Map<String, List<AgendaEvent>> fetched = new HashMap<>();
    /** The span each calendar's latest answer was read for, so the empty message knows what it covers. */
    private final Map<String, Agenda.Window> readWindows = new HashMap<>();
    private final Map<String, String> errors = new LinkedHashMap<>();
    private List<AgendaEvent> events = Collections.emptyList();
    private boolean shown;
    private List<AgendaEvent> shownEvents;
    private AgendaSettings shownSettings;
    private String shownEmpty;
    private Agenda.Window shownCovered;
    /** Null for the device's clock and current zone. Tests fix the time and the zone. */
    Clock clock;

    @Override public void start(PluginHost host, Map<String, Object> values) {
        this.host = host;
        alive = true;
        settings = AgendaSettings.parse(values);
        host.subscribe(SAVER_EVENT);
        final int revision = saverRevision;
        host.executeCommand("isScreensaverActive", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
            // An event that arrived after the read was sent is newer, so it wins.
            if (!alive || revision != saverRevision) return;
            if (ok) {
                saverActive = Boolean.TRUE.equals(data);
                refresh();
            }
        });
        fetch(true);
        refresh();
    }

    @Override public void configure(Map<String, Object> values) {
        AgendaSettings next = AgendaSettings.parse(values);
        // A new name, icon or color only redraws; a different set of calendars or days reads again.
        boolean refetch = !next.calendars.keySet().equals(settings.calendars.keySet()) || next.days != settings.days;
        settings = next;
        fetched.keySet().retainAll(next.calendars.keySet());
        errors.keySet().retainAll(next.calendars.keySet());
        readWindows.keySet().retainAll(next.calendars.keySet());
        merge();
        if (refetch) fetch(true);
        refresh();
    }

    @Override public void execute(String command, Map<String, Object> arguments) {
        if ("preview".equals(command)) previewing = true;
        else if ("endPreview".equals(command)) previewing = false;
        else throw new IllegalArgumentException("Unknown command " + command);
        refresh();
    }

    @Override public void onEvent(String event, Map<String, Object> payload) {
        if (event.equals("ks." + SAVER_EVENT)) {
            saverRevision++;
            saverActive = Boolean.TRUE.equals(payload.get("active"));
            refresh();
        } else if (event.equals("ks.ha.subscription." + REFRESH)) {
            Object type = payload.get("type");
            if ("ended".equals(type)) {
                // HA refused the subscription after a reconnect, so no more refreshes come.
                Object error = payload.get("error");
                Object message = error instanceof Map ? ((Map<?, ?>) error).get("message") : null;
                refreshError = message != null ? message.toString() : "ended";
                report();
            } else if ("event".equals(type) || "resubscribed".equals(type)) {
                // A reconnect may have missed changes, so it reads again too.
                refreshError = null;
                fetch(false);
            }
        } else if (event.equals("settings.page")) {
            // Read the calendars while someone looks at the settings, so the picker is fresh without polling.
            if (Boolean.TRUE.equals(payload.get("open"))) listCalendars(null);
        } else if (event.equals("settings.choices")) {
            if (CALENDAR_CHOICES.equals(payload.get("key"))) listCalendars((String) payload.get("run"));
        } else if (listKey != null && event.equals("ks.ha.subscription." + listKey)) {
            Object type = payload.get("type");
            Object answer = payload.get("event");
            if ("event".equals(type) && answer instanceof Map) {
                Object error = ((Map<?, ?>) answer).get("error");
                if (error != null) listed("error", "Home Assistant could not list the calendars: " + error);
                else publish(((Map<?, ?>) answer).get("result"));
            } else if ("too_large".equals(type)) {
                listed("error", "The calendar list is too large for Kiosk Satellite. Press Refresh to try again.");
            } else if ("ended".equals(type) || "overflow".equals(type)) {
                listed("error", "Home Assistant stopped listing the calendars. Press Refresh to try again.");
            }
        } else if (event.equals("settings.cancel")) {
            // A canceled Refresh needs no answer, so the listing stops instead of reconnecting for nothing.
            if (listRun != null && listRun.equals(payload.get("run"))) {
                listRun = null;
                listed(null, null);
            }
        }
    }

    /** Lists the calendar entities in the Calendar picker, so no other kind of entity can be picked. */
    private void listCalendars(String run) {
        if (!alive || host == null) return;
        // The settings page opening joins a listing on its way without dropping the Refresh run waiting on it.
        if (run != null || listKey == null) listRun = run;
        if (listKey != null) return; // the answer on its way finishes this run too
        if (noHomeAssistant) {
            listed("error", "Listing calendars needs Kiosk Satellite 2026.10.35 or later.");
            return;
        }
        Map<String, Object> message = new HashMap<>();
        message.put("type", "render_template");
        message.put("template", LIST_TEMPLATE);
        message.put("report_errors", true);
        final String key = LIST + ++listAttempt;
        listKey = key;
        try {
            host.haSubscribe(key, message, (ok, data, error) -> {
                if (alive && !ok && key.equals(listKey)) listed("error", "Home Assistant could not list the calendars: " + error);
            });
        } catch (IllegalStateException busy) {
            listKey = null;
            listed("error", "Kiosk Satellite is busy. Press Refresh to try again.");
        } catch (UnsupportedOperationException | LinkageError olderHost) {
            listKey = null;
            noHomeAssistant = true;
            listed("error", "Listing calendars needs Kiosk Satellite 2026.10.35 or later.");
        }
    }

    private void publish(Object result) {
        List<?> answer = result instanceof List ? (List<?>) result : null;
        List<String[]> calendars = answer != null && answer.size() == 2 && answer.get(1) instanceof Number ? parseCalendars(answer.get(0)) : null;
        if (calendars == null) {
            listed("error", "Home Assistant sent the calendars in a form this plugin cannot read.");
            return;
        }
        String[] ids = new String[calendars.size()];
        String[] labels = new String[calendars.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = calendars.get(i)[0];
            labels[i] = calendars.get(i)[1];
        }
        try {
            host.setSettingChoices(CALENDAR_CHOICES, ids, labels);
        } catch (IllegalArgumentException refused) {
            // KS checks the list again. A refusal must not escape onEvent, which would end the plugin.
            listed("error", "Kiosk Satellite refused the calendar list: " + refused.getMessage());
            return;
        }
        int total = ((Number) answer.get(1)).intValue();
        if (ids.length < total) listed("success", "Listed " + ids.length + " of " + total + " calendars. The rest do not fit in Kiosk Satellite's limits.");
        else listed("success", ids.length == 0 ? "Home Assistant has no calendars." : ids.length == 1 ? "Found 1 calendar." : "Found " + ids.length + " calendars.");
    }

    /** Ends the listing: one answer is all it needs. Reports on the Refresh run waiting, if any. */
    private void listed(String state, String message) {
        if (listKey != null) host.haUnsubscribe(listKey);
        listKey = null;
        if (listRun != null) host.setupResult(listRun, state, message);
        listRun = null;
    }

    /** The template's [[entity_id, name], ...], calendars only, within KS's choice limits: 200, IDs of 512 characters, labels of 80. Null if it is not a list. */
    static List<String[]> parseCalendars(Object result) {
        if (!(result instanceof List)) return null;
        List<String[]> out = new ArrayList<>();
        for (Object row : (List<?>) result) {
            if (!(row instanceof List) || ((List<?>) row).size() != 2) continue;
            Object id = ((List<?>) row).get(0);
            Object name = ((List<?>) row).get(1);
            if (!(id instanceof String) || !((String) id).startsWith("calendar.") || ((String) id).length() > 512 || out.size() == 200) continue;
            String label = name instanceof String && !((String) name).trim().isEmpty() ? ((String) name).trim() : (String) id;
            out.add(new String[] {(String) id, label.length() > 80 ? label.substring(0, 79) + "…" : label});
        }
        return out;
    }

    @Override public void stop() {
        // KS revokes the host, its subscriptions and the overlay before calling stop.
        alive = false;
        host = null;
    }

    /**
     * The 10-minute refresh runs only while the card can show: subscribe and read once when it appears, unsubscribe
     * when it goes. A subscribe KS turned away as busy is tried again, without another read, once the reads are back.
     */
    private void follow(boolean now) {
        if (noHomeAssistant) return;
        if (now == visible) {
            if (now && !following && pending == 0) subscribe();
            return;
        }
        visible = now;
        if (now) {
            subscribe();
            fetch(false);
        } else if (following) {
            following = false;
            host.haUnsubscribe(REFRESH);
        }
    }

    private void subscribe() {
        Map<String, Object> trigger = new HashMap<>();
        trigger.put("platform", "time_pattern");
        trigger.put("minutes", "/10");
        Map<String, Object> message = new HashMap<>();
        message.put("type", "subscribe_trigger");
        message.put("trigger", trigger);
        final int attempt = ++subscribeAttempt;
        try {
            // HA only lets admin users subscribe to triggers, so a refusal must show, not hide.
            host.haSubscribe(REFRESH, message, (ok, data, error) -> {
                if (!alive || attempt != subscribeAttempt) return;
                refreshError = ok ? null : error;
                report();
            });
            following = true;
        } catch (IllegalStateException busy) {
            // Over KS's shared limit of 8 pending requests or 20 a second. follow tries again when reads come back,
            // but if KS turned those away too nothing comes back, so the status must say the refresh stopped.
            refreshError = "Kiosk Satellite was busy";
        } catch (UnsupportedOperationException | LinkageError olderHost) {
            noHomeAssistant = true;
        }
    }

    /** One calendar.get_events per calendar, so a calendar too big for KS's 32 KB answer limit only costs itself. */
    private void fetch(boolean required) {
        if (!alive || host == null || noHomeAssistant) {
            report();
            return;
        }
        // KS allows 8 pending requests, so a second read waits instead of overlapping the first.
        if (pending > 0) {
            fetchAgain = true;
            fetchAgainRequired |= required;
            return;
        }
        // The end from its own date, as in Agenda.Window.covers: where daylight saving skips midnight the day starts at
        // 01:00, and a read running an hour past the window could pull in an event too big for KS's 32 KB answer.
        ZoneId zone = clock().getZone();
        LocalDate today = LocalDate.now(clock());
        ZonedDateTime from = today.atStartOfDay(zone);
        ZonedDateTime to = today.plusDays(settings.days).atStartOfDay(zone);
        final Agenda.Window window = new Agenda.Window(from.toInstant(), to.toInstant());
        for (final String calendar : settings.calendars.keySet()) {
            try {
                host.haCall(getEvents(calendar, from, to), (ok, data, error) -> {
                    if (!alive) return;
                    pending--;
                    // A calendar removed while its read was out stays out.
                    if (settings.calendars.containsKey(calendar)) {
                        if (ok) {
                            fetched.put(calendar, parseResponse(data, calendar));
                            readWindows.put(calendar, window);
                            errors.remove(calendar);
                        } else {
                            errors.put(calendar, error);
                        }
                        merge();
                        refresh();
                    }
                    if (pending == 0 && fetchAgain) {
                        // A read the card asked for is dropped once it is hidden; one settings asked for still runs.
                        boolean settingsRead = fetchAgainRequired;
                        fetchAgain = false;
                        fetchAgainRequired = false;
                        if (settingsRead || visible) fetch(settingsRead);
                    }
                });
                pending++;
            } catch (UnsupportedOperationException | LinkageError olderHost) {
                noHomeAssistant = true;
                break;
            } catch (IllegalStateException busy) {
                errors.put(calendar, "busy, trying again at the next refresh");
            }
        }
        // A batch that starts, or that KS turned away whole, can change what the card may say, and no answer may come.
        refresh();
    }

    static Map<String, Object> getEvents(String calendar, ZonedDateTime from, ZonedDateTime to) {
        Map<String, Object> data = new HashMap<>();
        data.put("start_date_time", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(from));
        data.put("end_date_time", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(to));
        Map<String, Object> message = new HashMap<>();
        message.put("type", "call_service");
        message.put("domain", "calendar");
        message.put("service", "get_events");
        message.put("target", Collections.singletonMap("entity_id", calendar));
        message.put("service_data", data);
        message.put("return_response", true);
        return message;
    }

    /** A call_service result: {context, response: {calendar.x: {events: [...]}}}. */
    static List<AgendaEvent> parseResponse(Object data, String calendar) {
        Object response = data instanceof Map ? ((Map<?, ?>) data).get("response") : null;
        Object forCalendar = response instanceof Map ? ((Map<?, ?>) response).get(calendar) : null;
        Object raw = forCalendar instanceof Map ? ((Map<?, ?>) forCalendar).get("events") : null;
        if (!(raw instanceof List)) return Collections.emptyList();
        ZoneId zone = ZoneId.systemDefault();
        List<AgendaEvent> parsed = new ArrayList<>();
        // No cap here: finished events come first, and KS's 32 KB answer limit already bounds the list.
        for (Object item : (List<?>) raw) {
            AgendaEvent event = AgendaEvent.parse(item, zone, calendar);
            if (event != null) parsed.add(event);
        }
        return parsed;
    }

    /** Only calendars still in the list count, so removing one clears its events at once. */
    private void merge() {
        List<AgendaEvent> all = new ArrayList<>();
        for (String calendar : settings.calendars.keySet()) {
            List<AgendaEvent> found = fetched.get(calendar);
            if (found != null) all.addAll(found);
        }
        events = Collections.unmodifiableList(all);
    }

    static OverlaySpec spec(AgendaSettings settings) {
        // Over the screensaver like the voice overlay, visual only so a tap still wakes the kiosk, and Back leaves it alone.
        OverlaySpec spec = OverlaySpec.at(settings.anchor).onTop(true).touchable(false).closeOnBack(false);
        // From the top left with no inset, the offset is the card's position.
        if (settings.custom) return spec.offset(settings.x, settings.y);
        return spec.inset(Math.round(24 * settings.scale / 100f));
    }

    private void refresh() {
        if (!alive || host == null) return;
        follow(saverActive || previewing);
        // The empty message needs an answer from every calendar, none failed, covering the window shown now. The card
        // checks the coverage again each minute, since midnight moves the window before any refresh runs.
        Agenda.Window covered = settings.emptyMessage == null ? null : covered();
        String empty = covered != null && covered.covers(ZonedDateTime.now(clock()), settings.days) ? settings.emptyMessage : null;
        boolean wanted = (saverActive || previewing) && (!events.isEmpty() || empty != null);
        try {
            if (wanted && (!shown || !events.equals(shownEvents) || !settings.equals(shownSettings)
                    || !Objects.equals(empty, shownEmpty) || !Objects.equals(covered, shownCovered))) {
                host.showOverlay(OVERLAY, spec(settings), new AgendaCard(events, settings, empty, covered));
                shown = true;
                shownEvents = events;
                shownSettings = settings;
                shownEmpty = empty;
                shownCovered = covered;
            } else if (!wanted && shown) {
                host.hideOverlay(OVERLAY);
                shown = false;
            }
        } catch (UnsupportedOperationException | LinkageError olderHost) {
            // Apps before native overlays either refuse the call or, up to 2026.10.12, lack the
            // OverlaySpec class and showOverlay method entirely (NoClassDefFoundError, NoSuchMethodError).
            host.status("This Kiosk Satellite version has no native overlays. Update the app to a release after 2026.10.12.", true);
            return;
        } catch (IllegalStateException rateLimited) {
            // Over eight shows a second. The next screensaver or calendar change retries with current data.
            return;
        }
        report();
    }

    /** The span every calendar's latest answer covers, or null while one has none or one failed. */
    private Agenda.Window covered() {
        if (!errors.isEmpty() || settings.calendars.isEmpty()) return null;
        Instant from = Instant.MIN;
        Instant until = Instant.MAX;
        for (String calendar : settings.calendars.keySet()) {
            Agenda.Window read = readWindows.get(calendar);
            if (read == null) return null;
            if (read.from.isAfter(from)) from = read.from;
            if (read.until.isBefore(until)) until = read.until;
        }
        return new Agenda.Window(from, until);
    }

    /** Looked up on each use, so a time zone change takes effect at once. */
    private Clock clock() {
        return clock != null ? clock : Clock.systemDefaultZone();
    }

    private void report() {
        if (host == null) return;
        if (noHomeAssistant) {
            host.status("Reading calendars needs Kiosk Satellite 2026.10.25 or later.", true);
        } else if (settings.calendars.isEmpty()) {
            host.status("Add a calendar in Settings.", true);
        } else if (refreshError != null) {
            host.status("Auto-refresh stopped: " + refreshError + ". Events update only when the screensaver or preview starts or a calendar setting changes.", true);
        } else if (!errors.isEmpty()) {
            StringBuilder text = new StringBuilder();
            for (Map.Entry<String, String> error : errors.entrySet()) {
                if (text.length() > 0) text.append(' ');
                boolean tooLarge = error.getValue() != null && error.getValue().startsWith("too_large");
                text.append(error.getKey()).append(tooLarge ? " has more event detail than fits. Lower Days ahead." : ": " + error.getValue() + ".");
            }
            host.status(text.toString(), true);
        } else if (fetched.isEmpty()) {
            host.status("Reading calendars.", false);
        } else {
            String count = events.size() == 1 ? "1 event" : events.size() + " events";
            int calendars = settings.calendars.size();
            host.status(count + " from " + calendars + (calendars == 1 ? " calendar. " : " calendars. ")
                + (shown ? "Showing." : "Shows with the screensaver."), false);
        }
    }
}
