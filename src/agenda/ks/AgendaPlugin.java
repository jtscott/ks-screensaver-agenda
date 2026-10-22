// SPDX-License-Identifier: Apache-2.0
package agenda.ks;

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

    private PluginHost host;
    private boolean alive;
    private AgendaSettings settings;
    private int saverRevision;
    private boolean saverActive;
    private boolean previewing;
    private boolean noHomeAssistant;
    /** Whether the 10-minute refresh is subscribed. It runs only while the card can show. */
    private boolean following;
    /** Why the 10-minute refresh is not running, or null while it is. */
    private String refreshError;
    /** get_events calls still out. KS answers each within 25 seconds, with a timeout at worst. */
    private int pending;
    /** A read was asked for while calls were out, so one runs when they are all back. */
    private boolean fetchAgain;
    /** The latest answer per calendar. It stays until the next one lands, so a refresh never blanks the card. */
    private final Map<String, List<AgendaEvent>> fetched = new HashMap<>();
    private final Map<String, String> errors = new LinkedHashMap<>();
    private List<AgendaEvent> events = Collections.emptyList();
    private boolean shown;
    private List<AgendaEvent> shownEvents;
    private AgendaSettings shownSettings;

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
        fetch();
        refresh();
    }

    @Override public void configure(Map<String, Object> values) {
        AgendaSettings next = AgendaSettings.parse(values);
        // A new name, icon or color only redraws; a different set of calendars or days reads again.
        boolean refetch = !next.calendars.keySet().equals(settings.calendars.keySet()) || next.days != settings.days;
        settings = next;
        fetched.keySet().retainAll(next.calendars.keySet());
        errors.keySet().retainAll(next.calendars.keySet());
        if (refetch) fetch();
        merge();
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
                fetch();
            }
        }
    }

    @Override public void stop() {
        // KS revokes the host, its subscriptions and the overlay before calling stop.
        alive = false;
        host = null;
    }

    /** Subscribes to the 10-minute refresh and reads right away when the card can show, and unsubscribes when it can't. */
    private void follow(boolean visible) {
        if (visible == following || noHomeAssistant) return;
        following = visible;
        try {
            if (!visible) {
                host.haUnsubscribe(REFRESH);
                return;
            }
            Map<String, Object> trigger = new HashMap<>();
            trigger.put("platform", "time_pattern");
            trigger.put("minutes", "/10");
            Map<String, Object> message = new HashMap<>();
            message.put("type", "subscribe_trigger");
            message.put("trigger", trigger);
            // HA only lets admin users subscribe to triggers, so a refusal must show, not hide.
            host.haSubscribe(REFRESH, message, (ok, data, error) -> {
                if (!alive) return;
                refreshError = ok ? null : error;
                report();
            });
        } catch (UnsupportedOperationException | LinkageError olderHost) {
            noHomeAssistant = true;
        }
        fetch();
    }

    /** One calendar.get_events per calendar, so a calendar too big for KS's 32 KB answer limit only costs itself. */
    private void fetch() {
        if (!alive || host == null || noHomeAssistant) {
            report();
            return;
        }
        // KS allows 8 pending requests, so a second read waits instead of overlapping the first.
        if (pending > 0) {
            fetchAgain = true;
            return;
        }
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime from = LocalDate.now(zone).atStartOfDay(zone);
        for (final String calendar : settings.calendars.keySet()) {
            try {
                host.haCall(getEvents(calendar, from, from.plusDays(settings.days)), (ok, data, error) -> {
                    if (!alive) return;
                    pending--;
                    // A calendar removed while its read was out stays out.
                    if (settings.calendars.containsKey(calendar)) {
                        if (ok) {
                            fetched.put(calendar, parseResponse(data, calendar));
                            errors.remove(calendar);
                        } else {
                            errors.put(calendar, error);
                        }
                        merge();
                        refresh();
                    }
                    if (pending == 0 && fetchAgain) {
                        fetchAgain = false;
                        fetch();
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
        report();
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
        return OverlaySpec.at(settings.anchor).inset(Math.round(24 * settings.scale / 100f)).onTop(true).touchable(false).closeOnBack(false);
    }

    private void refresh() {
        if (!alive || host == null) return;
        follow(saverActive || previewing);
        boolean wanted = (saverActive || previewing) && !events.isEmpty();
        try {
            if (wanted && (!shown || !events.equals(shownEvents) || !settings.equals(shownSettings))) {
                host.showOverlay(OVERLAY, spec(settings), new AgendaCard(events, settings));
                shown = true;
                shownEvents = events;
                shownSettings = settings;
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

    private void report() {
        if (host == null) return;
        if (noHomeAssistant) {
            host.status("Reading calendars needs Kiosk Satellite 2026.10.25 or later.", true);
        } else if (settings.calendars.isEmpty()) {
            host.status("Add a calendar in Settings.", true);
        } else if (refreshError != null) {
            host.status("Auto-refresh stopped: " + refreshError + ". Events update only when the screensaver starts or a calendar setting changes.", true);
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
