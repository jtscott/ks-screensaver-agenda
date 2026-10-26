// SPDX-License-Identifier: Apache-2.0
package agenda.ks;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/** Parsing, agenda rules and the show/hide lifecycle. Drawing the card needs a device. */
public final class AgendaTest {
    static final ZoneId ZONE = ZoneId.of("America/Denver");

    static final class Host implements PluginHost {
        final Set<String> subscriptions = new LinkedHashSet<>();
        final List<String> calls = new ArrayList<>();
        OverlaySpec spec;
        OverlayFactory factory;
        CommandCallback pendingRead;
        boolean older;
        boolean missingClass;
        String status;
        boolean statusError;
        /** Every KS command after the startup screensaver read, as "command {arguments}", and the latest callback per command. */
        final List<String> commands = new ArrayList<>();
        final Map<String, CommandCallback> commandCallbacks = new HashMap<>();
        /** Turns commands away the way KS does over its limits: it throws. */
        boolean commandsBusy;
        public void executeCommand(String command, Map<String, Object> arguments, CommandCallback callback) {
            if (command.equals("isScreensaverActive")) {
                pendingRead = callback;
                return;
            }
            if (commandsBusy) throw new IllegalStateException("At most 8 KS commands may be pending");
            commands.add(command + " " + arguments);
            commandCallbacks.put(command, callback);
        }
        /** The pending get_events callback per calendar, in call order. */
        final Map<String, CommandCallback> haCalls = new LinkedHashMap<>();
        final Set<String> haSubscriptions = new LinkedHashSet<>();
        CommandCallback subscribed;
        /** The end_date_time of the latest get_events. */
        String lastEnd;
        boolean noHa;
        /** Turn subscribes or calls away the way KS does over its shared request limits. */
        boolean subscribeBusy, callBusy;
        public void haCall(Map<String, Object> message, CommandCallback callback) {
            if (noHa) throw new UnsupportedOperationException("Home Assistant access is unavailable");
            if (callBusy) throw new IllegalStateException("too_many_pending");
            assert "call_service".equals(message.get("type")) && "get_events".equals(message.get("service")) && Boolean.TRUE.equals(message.get("return_response"));
            Map<?, ?> data = (Map<?, ?>) message.get("service_data");
            // Midnight, or 01:00 where daylight saving skips midnight.
            assert ((String) data.get("start_date_time")).matches("\\d{4}-\\d{2}-\\d{2}T0[01]:00:00(Z|[-+]\\d{2}:\\d{2})") : data;
            lastEnd = (String) data.get("end_date_time");
            String calendar = (String) ((Map<?, ?>) message.get("target")).get("entity_id");
            assert !haCalls.containsKey(calendar) : "a second read of " + calendar + " while one is out";
            // KS's limit. haCall and executeCommand share it; the screensaver read and the subscription are answered by then.
            if (haCalls.size() >= 8) throw new IllegalStateException("too_many_pending");
            haCalls.put(calendar, callback);
        }
        public void haSubscribe(String key, Map<String, Object> message, CommandCallback callback) {
            if (noHa) throw new UnsupportedOperationException("Home Assistant access is unavailable");
            boolean list = key.startsWith(AgendaPlugin.LIST);
            assert list ? "render_template".equals(message.get("type")) : "subscribe_trigger".equals(message.get("type"));
            if (subscribeBusy) throw new IllegalStateException("too_many_pending");
            boolean added = haSubscriptions.add(key);
            assert added : "Subscription " + key + " is already active";
            if (list) {
                listed = callback;
                listKey = key;
            }
            else subscribed = callback;
        }
        CommandCallback listed;
        /** The key of the latest calendar listing; each attempt has its own. */
        String listKey;
        boolean listing() {
            for (String key : haSubscriptions) if (key.startsWith(AgendaPlugin.LIST)) return true;
            return false;
        }
        String choicesKey;
        String[] choiceIds;
        String[] choiceLabels;
        final List<String> results = new ArrayList<>();
        public void setSettingChoices(String key, String[] ids, String[] labels) {
            // KS's own checks, from PluginSetup: a refusal there throws into the plugin.
            if (ids.length > 200 || new HashSet<>(Arrays.asList(ids)).size() != ids.length) throw new IllegalArgumentException("Choices are at most 200 unique IDs");
            for (int i = 0; i < ids.length; i++) {
                if (ids[i].isEmpty() || ids[i].length() > 512 || labels[i].length() > 80) throw new IllegalArgumentException("Choice IDs are 1 to 512 characters, labels at most 80");
            }
            choicesKey = key;
            choiceIds = ids;
            choiceLabels = labels;
        }
        public boolean setupResult(String run, String state, String message) {
            results.add(run + " " + state + ": " + message);
            return true;
        }
        public void haUnsubscribe(String key) { haSubscriptions.remove(key); }
        public void subscribe(String event) { subscriptions.add(event); }
        public void unsubscribe(String event) { subscriptions.remove(event); }
        public void showOverlay(String key, OverlaySpec spec, OverlayFactory factory) {
            if (older) throw new UnsupportedOperationException("Native overlays are unavailable");
            if (missingClass) throw new NoClassDefFoundError("Failed resolution of: Lme/jxl/kiosk/plugins/OverlaySpec;");
            assert key.equals(AgendaPlugin.OVERLAY) && factory != null;
            this.spec = spec;
            this.factory = factory;
            calls.add("show");
        }
        public void hideOverlay(String key) { calls.add("hide"); }
        public void showWindow(String title, String message, String button) {}
        public void hideWindow() {}
        public void log(String message) {}
        public void status(String message, boolean error) { status = message; statusError = error; }
    }

    /** The bytes one code point takes in utf8.encode(jsonEncode(...)), as Dart's JSON encoder escapes it. */
    static int dartJsonBytes(int codePoint) {
        if (codePoint == '"' || codePoint == '\\' || codePoint == '\b' || codePoint == '\t' || codePoint == '\n' || codePoint == '\f' || codePoint == '\r') return 2;
        if (codePoint < 0x20) return 6; // \u000b and the like
        return codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
    }

    static Map<String, Object> event(String summary, String start, String end) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("summary", summary);
        e.put("start", start);
        e.put("end", end);
        return e;
    }

    static Map<String, Object> settings(Object... pairs) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }

    /** One calendar with no name, icon or color set. */
    static Map<String, Object> family() {
        return settings("calendars", Collections.<Object>singletonList(settings("calendar", "calendar.family")));
    }

    static final ZonedDateTime NOW = ZonedDateTime.of(2026, 10, 8, 10, 0, 0, 0, ZONE); // a Thursday

    /** Each day as "# Thu 8 OCT (Today)" followed by its events as "title | time | relative". */
    static List<String> rows(List<AgendaEvent> events, AgendaSettings s) {
        List<String> out = new ArrayList<>();
        for (Agenda.Day day : Agenda.days(events, NOW, s, Locale.US)) {
            out.add("# " + day.weekday + " " + day.dayOfMonth + " " + day.month + " (" + day.heading + ")");
            for (Agenda.Item item : day.items) out.add(item.toString());
        }
        return out;
    }

    /** A get_events call_service result for one calendar. */
    static Map<String, Object> answer(String calendar, Map<String, Object>... raw) {
        Map<String, Object> events = new HashMap<>();
        events.put("events", Arrays.asList((Object[]) raw));
        Map<String, Object> result = new HashMap<>();
        result.put("response", Collections.singletonMap(calendar, events));
        return result;
    }

    static List<AgendaEvent> parse(Map<String, Object>... raw) {
        return AgendaPlugin.parseResponse(answer("calendar.family", raw), "calendar.family");
    }

    public static void main(String[] args) {
        // Parsing keeps good events, skips unusable ones and names untitled ones.
        Map<String, Object> noTitle = event(null, "2026-10-08T12:00:00-06:00", "2026-10-08T13:00:00-06:00");
        List<AgendaEvent> parsed = parse(event("Dentist", "2026-10-08T15:00:00-06:00", "2026-10-08T16:00:00-06:00"),
            event("Bad", "not a date", "x"), noTitle, event("Holiday", "2026-10-12", "2026-10-13"));
        assert parsed.size() == 3 : parsed.size();
        assert parsed.get(1).summary.equals("(No title)");
        assert parsed.get(2).allDay && parsed.get(2).startDate.toString().equals("2026-10-12");
        assert parsed.get(0).calendar.equals("calendar.family");
        // A busy calendar's finished events never crowd out what is still to come.
        List<Map<String, Object>> busyDay = new ArrayList<>();
        for (int i = 0; i < 60; i++) busyDay.add(event("Done " + i, "2026-10-08T07:00:00-06:00", "2026-10-08T07:30:00-06:00"));
        busyDay.add(event("Dentist", "2026-10-08T15:00:00-06:00", "2026-10-08T16:00:00-06:00"));
        @SuppressWarnings("unchecked") Map<String, Object>[] busyRaw = busyDay.toArray(new Map[0]);
        assert rows(parse(busyRaw), AgendaSettings.parse(new HashMap<String, Object>())).size() == 2 : "the dentist must show";
        assert AgendaPlugin.parseResponse(null, "calendar.x").isEmpty() && AgendaPlugin.parseResponse(answer("calendar.other"), "calendar.x").isEmpty();

        // Agenda rules at 10:00 on Thursday 8 October.
        Map<String, Object> located = event("Soccer", "2026-10-09T08:00:00-06:00", "2026-10-09T09:00:00-06:00");
        located.put("location", "City Park");
        List<AgendaEvent> week = parse(
            event("Ended", "2026-10-08T08:00:00-06:00", "2026-10-08T09:00:00-06:00"),
            event("Standup", "2026-10-08T09:30:00-06:00", "2026-10-08T10:30:00-06:00"),
            event("Dentist", "2026-10-08T15:00:00-06:00", "2026-10-08T16:00:00-06:00"),
            event("Trip", "2026-10-07", "2026-10-09"),
            event("Garbage", "2026-10-08", "2026-10-09"),
            located,
            event("Party", "2026-10-10T19:00:00-06:00", "2026-10-10T22:00:00-06:00"),
            event("Too far", "2026-10-11T09:00:00-06:00", "2026-10-11T10:00:00-06:00"));
        AgendaSettings defaults = AgendaSettings.parse(new HashMap<String, Object>());
        assert rows(week, defaults).equals(Arrays.asList(
            "# Thu 8 OCT (Today)", "Garbage | All day | ", "Trip | All day | ", "Standup | 9:30 AM - 10:30 AM | now", "Dentist | 3:00 PM - 4:00 PM | in 5 hours",
            "# Fri 9 OCT (Tomorrow)", "Soccer | 8:00 AM - 9:00 AM | tomorrow")) : rows(week, defaults);

        AgendaSettings wide = AgendaSettings.parse(settings("maxEvents", 10.0, "days", 3.0, "clock", "24-hour", "endTime", false));
        List<String> all = rows(week, wide);
        assert all.contains("Soccer | 08:00 | tomorrow") : all;
        assert all.contains("# Sat 10 OCT (Sat, Oct 10)") && all.contains("Party | 19:00 | in 2 days") : all;
        assert !all.toString().contains("Too far") && !all.toString().contains("Ended") : all;
        assert Agenda.days(week, NOW, wide, Locale.US).get(1).items.get(0).location.equals("City Park");
        assert rows(week, AgendaSettings.parse(settings("days", 1, "maxEvents", 2))).equals(Arrays.asList("# Thu 8 OCT (Today)", "Garbage | All day | ", "Trip | All day | "));
        assert rows(Collections.<AgendaEvent>emptyList(), defaults).isEmpty();

        // The countdown: minutes under an hour, whole hours today, then days.
        List<AgendaEvent> soon = parse(event("Soon", "2026-10-08T10:30:00-06:00", "2026-10-08T11:00:00-06:00"),
            event("Hour", "2026-10-08T11:00:00-06:00", "2026-10-08T12:00:00-06:00"),
            event("Long weekend", "2026-10-09", "2026-10-12"));
        assert rows(soon, defaults).equals(Arrays.asList(
            "# Thu 8 OCT (Today)", "Soon | 10:30 AM - 11:00 AM | in 30 minutes", "Hour | 11:00 AM - 12:00 PM | in 1 hour",
            "# Fri 9 OCT (Tomorrow)", "Long weekend | All day | tomorrow")) : rows(soon, defaults);

        // Every Position maps to its anchor, and an unknown one falls back to the top left.
        String[][] positions = {
            {"Top left", OverlaySpec.TOP_LEFT}, {"Top middle", OverlaySpec.TOP}, {"Top right", OverlaySpec.TOP_RIGHT},
            {"Left middle", OverlaySpec.LEFT}, {"Center", OverlaySpec.CENTER}, {"Right middle", OverlaySpec.RIGHT},
            {"Bottom left", OverlaySpec.BOTTOM_LEFT}, {"Bottom middle", OverlaySpec.BOTTOM}, {"Bottom right", OverlaySpec.BOTTOM_RIGHT},
            {"Custom", OverlaySpec.TOP_LEFT}, {"Sideways", OverlaySpec.TOP_LEFT}};
        for (String[] position : positions) {
            String anchor = AgendaSettings.parse(settings("corner", position[0])).anchor;
            assert anchor.equals(position[1]) : position[0] + " -> " + anchor;
        }
        // Custom places the card exactly, x and y dp from the top left with no inset; the others keep their margin.
        OverlaySpec custom = AgendaPlugin.spec(AgendaSettings.parse(settings("corner", "Custom", "x", 40.0, "y", 180.0)));
        assert custom.anchor().equals(OverlaySpec.TOP_LEFT) && custom.offsetX() == 40 && custom.offsetY() == 180 && custom.inset() == 0;
        OverlaySpec clampedCustom = AgendaPlugin.spec(AgendaSettings.parse(settings("corner", "Custom", "x", -5, "y", 99999)));
        assert clampedCustom.offsetX() == 0 && clampedCustom.offsetY() == 4096;
        OverlaySpec corner = AgendaPlugin.spec(AgendaSettings.parse(settings("corner", "Top left", "x", 40.0, "y", 180.0, "scale", 150)));
        assert corner.offsetX() == 0 && corner.offsetY() == 0 && corner.insetLeft() == 36 && corner.insetTop() == 36 : "x and y only count for Custom";
        assert AgendaSettings.parse(settings("corner", "Center", "x", 40.0)).equals(AgendaSettings.parse(settings("corner", "Center", "x", 41.0))) : "hidden x does not redraw";
        assert !AgendaSettings.parse(settings("corner", "Custom", "x", 40.0)).equals(AgendaSettings.parse(settings("corner", "Custom", "x", 41.0)));

        // When there are no events: hide by default, a canned message as is, or a custom one; a blank custom one hides.
        assert defaults.emptyMessage == null;
        assert "All done for today".equals(AgendaSettings.parse(settings("whenEmpty", "All done for today")).emptyMessage);
        assert "Gone fishing".equals(AgendaSettings.parse(settings("whenEmpty", "Custom", "emptyMessage", " Gone fishing ")).emptyMessage);
        assert AgendaSettings.parse(settings("whenEmpty", "Custom", "emptyMessage", "  ")).emptyMessage == null;
        assert AgendaSettings.parse(settings("whenEmpty", "Hide the card", "emptyMessage", "Gone fishing")).emptyMessage == null;
        assert AgendaSettings.parse(settings("whenEmpty", "Nothing planned", "emptyMessage", "a"))
            .equals(AgendaSettings.parse(settings("whenEmpty", "Nothing planned", "emptyMessage", "b"))) : "a hidden message does not redraw";

        // Maximum width: none by default, kept within the manifest range, and part of the card, so a change redraws it.
        assert defaults.maxWidth == 0 && AgendaSettings.parse(settings("maxWidth", 360.0)).maxWidth == 360;
        assert AgendaSettings.parse(settings("maxWidth", -5)).maxWidth == 0 && AgendaSettings.parse(settings("maxWidth", 99999)).maxWidth == 4096;
        assert !AgendaSettings.parse(settings("maxWidth", 360.0)).equals(AgendaSettings.parse(settings("maxWidth", 420.0)));

        // When tapped: by default taps go through the card; the calendar or an app make it touchable and give it a command.
        assert defaults.tapCommand == null && !AgendaPlugin.spec(defaults).touchable();
        AgendaSettings toCalendar = AgendaSettings.parse(settings("tap", "Open Kiosk Satellite's calendar"));
        assert "openCalendar".equals(toCalendar.tapCommand) && toCalendar.tapPackage.isEmpty() && AgendaPlugin.spec(toCalendar).touchable();
        AgendaSettings toApp = AgendaSettings.parse(settings("tap", "Open an app", "app", "com.example.calendar"));
        assert "launchApp".equals(toApp.tapCommand) && toApp.tapPackage.equals("com.example.calendar");
        assert AgendaSettings.parse(settings("tap", "Open an app")).tapCommand == null : "no app picked, nothing to open";
        assert AgendaSettings.parse(settings("tap", "Open Kiosk Satellite's calendar", "app", "a"))
            .equals(AgendaSettings.parse(settings("tap", "Open Kiosk Satellite's calendar", "app", "b"))) : "a hidden app does not redraw";

        // Settings clamp to the manifest ranges and map corners.
        AgendaSettings clamped = AgendaSettings.parse(settings("days", 40, "maxEvents", 0, "scale", 500, "corner", "Bottom right"));
        assert clamped.days == 7 && clamped.maxEvents == 1 && clamped.scale == 200 && clamped.anchor.equals(OverlaySpec.BOTTOM_RIGHT);
        assert defaults.calendars.isEmpty() && defaults.anchor.equals(OverlaySpec.TOP_LEFT);
        AgendaSettings.Style family = AgendaSettings.parse(family()).calendars.get("calendar.family");
        assert family.color.equals("#4FA3E0") && family.name.isEmpty() && family.icon.isEmpty();
        List<Object> rows = new ArrayList<>();
        rows.add(settings("calendar", "calendar.a", "color", "#FF0000", "name", " Alice ", "icon", "mdi:account"));
        rows.add(settings("calendar", "", "color", "#00FF00"));
        rows.add(settings("calendar", "calendar.a", "color", "#0000FF"));
        for (int i = 0; i < 10; i++) rows.add(settings("calendar", "calendar.n" + i, "color", "#123456"));
        AgendaSettings many = AgendaSettings.parse(settings("calendars", rows));
        AgendaSettings.Style alice = many.calendars.get("calendar.a");
        assert many.calendars.size() == AgendaSettings.MAX_CALENDARS && alice.color.equals("#FF0000") && alice.name.equals("Alice") && alice.icon.equals("mdi:account");
        assert AgendaSettings.parse(settings("calendars", new ArrayList<Object>())).calendars.isEmpty();
        assert defaults.dateColumn && defaults.accentBar && defaults.endTime && defaults.timeIcon && defaults.relativeTime && defaults.titleLines == 2;
        AgendaSettings plain = AgendaSettings.parse(settings("dateColumn", false, "accentBar", false, "titleLines", 9));
        assert !plain.dateColumn && !plain.accentBar && plain.titleLines == 3;
        assert defaults.dateAlign.equals("Center") && AgendaSettings.parse(settings("dateAlign", "Right")).dateAlign.equals("Right");
        assert AgendaSettings.parse(settings("dateAlign", "Sideways")).dateAlign.equals("Center");
        assert defaults.titleSize == 12 && defaults.daySize == 34 && defaults.eventSize == 17 && defaults.relativeSize == 14;
        AgendaSettings sized = AgendaSettings.parse(settings("eventSize", 22.0, "daySize", 200, "detailSize", 1));
        assert sized.eventSize == 22 && sized.daySize == 60 && sized.detailSize == 8;
        assert sized.equals(AgendaSettings.parse(settings("eventSize", 22.0, "daySize", 200, "detailSize", 1))) && !sized.equals(defaults);

        // Lifecycle: it reads the calendar and shows nothing, nor refreshes, until the screensaver runs.
        Host host = new Host();
        AgendaPlugin plugin = new AgendaPlugin();
        plugin.start(host, family());
        assert host.subscriptions.equals(Collections.singleton("screensaver.state")) : host.subscriptions;
        assert host.haSubscriptions.isEmpty() : "no refresh while the screensaver is off";
        assert host.haCalls.keySet().equals(Collections.singleton("calendar.family")) && host.status.equals("Reading calendars.");
        Map<String, Object> dentist = event("Dentist", "2099-10-08T15:00:00-06:00", "2099-10-08T16:00:00-06:00");
        host.haCalls.remove("calendar.family").onResult(true, answer("calendar.family", dentist), null);
        assert host.calls.isEmpty() && !host.statusError && host.status.startsWith("1 event from 1 calendar") : host.status;

        // The initial screensaver read shows the card with the over-the-screensaver, pass-through spec.
        host.pendingRead.onResult(true, true, null);
        assert host.calls.equals(Arrays.asList("show"));
        // The screensaver starts the 10-minute refresh and reads once right away.
        assert host.haSubscriptions.equals(Collections.singleton(AgendaPlugin.REFRESH)) && host.haCalls.containsKey("calendar.family");
        host.haCalls.remove("calendar.family").onResult(true, answer("calendar.family", dentist), null);
        assert host.calls.equals(Arrays.asList("show")) : "the same events do not rebuild the card";
        assert host.spec.onTop() && !host.spec.touchable() && !host.spec.closeOnBack() && host.spec.anchor().equals(OverlaySpec.TOP_LEFT);

        // The 10-minute trigger reads again. The same events do not rebuild the card; a setting change does.
        Map<String, Object> tick = settings("type", "event");
        plugin.onEvent("ks.ha.subscription." + AgendaPlugin.REFRESH, tick);
        host.haCalls.remove("calendar.family").onResult(true, answer("calendar.family", dentist), null);
        assert host.calls.size() == 1;
        Map<String, Object> topRight = family();
        topRight.put("corner", "Top right");
        plugin.configure(topRight);
        assert host.calls.equals(Arrays.asList("show", "show")) && host.spec.anchor().equals(OverlaySpec.TOP_RIGHT);
        assert host.haCalls.isEmpty() : "a layout change must not read the calendars again";

        // Screensaver off hides it and stops the refresh; a stale read after a newer event is ignored.
        Map<String, Object> idle = settings("active", false);
        plugin.onEvent("ks.screensaver.state", idle);
        assert host.calls.get(host.calls.size() - 1).equals("hide");
        assert host.haSubscriptions.isEmpty() && host.haCalls.isEmpty();
        int before = host.calls.size();
        host.pendingRead.onResult(true, true, null);
        assert host.calls.size() == before && host.haSubscriptions.isEmpty();

        // Preview shows it without the screensaver, and refreshes like it.
        plugin.execute("preview", new HashMap<String, Object>());
        assert host.calls.get(host.calls.size() - 1).equals("show");
        assert host.haSubscriptions.equals(Collections.singleton(AgendaPlugin.REFRESH));

        // Two calendars: the new list is read once the preview's read is back, whose answer is dropped, and the removed calendar's events go.
        PluginHost.CommandCallback stale = host.haCalls.remove("calendar.family");
        List<Object> two = new ArrayList<>();
        two.add(settings("calendar", "calendar.work", "color", "#FF0000"));
        two.add(settings("calendar", "calendar.kids", "color", "#00FF00"));
        plugin.configure(settings("calendars", two));
        assert host.calls.get(host.calls.size() - 1).equals("hide") : "family events must go with the family calendar";
        assert host.haCalls.isEmpty() : "the new read waits for the one still out";
        stale.onResult(true, answer("calendar.family", dentist), null);
        assert host.calls.get(host.calls.size() - 1).equals("hide");
        assert host.haCalls.keySet().equals(new LinkedHashSet<>(Arrays.asList("calendar.work", "calendar.kids"))) : host.haCalls.keySet();
        host.haCalls.remove("calendar.work").onResult(true, answer("calendar.work", event("Standup", "2099-10-08T09:00:00-06:00", "2099-10-08T09:15:00-06:00")), null);
        host.haCalls.remove("calendar.kids").onResult(false, null, "too_large: Result is larger than 32 KB");
        assert host.calls.get(host.calls.size() - 1).equals("show");
        assert host.statusError && host.status.equals("calendar.kids has more event detail than fits. Lower Days ahead.") : host.status;

        // A later good answer clears the error.
        plugin.onEvent("ks.ha.subscription." + AgendaPlugin.REFRESH, settings("type", "resubscribed"));
        host.haCalls.remove("calendar.work").onResult(true, answer("calendar.work"), null);
        host.haCalls.remove("calendar.kids").onResult(true, answer("calendar.kids", event("Recital", "2099-10-09T18:00:00-06:00", "2099-10-09T19:00:00-06:00")), null);
        assert !host.statusError && host.status.startsWith("1 event from 2 calendars") : host.status;

        // A refused or dropped refresh shows in the status instead of leaving the card silently stale.
        host.subscribed.onResult(false, null, "unauthorized: Unauthorized");
        assert host.statusError && host.status.startsWith("Auto-refresh stopped: unauthorized") : host.status;
        plugin.onEvent("ks.ha.subscription." + AgendaPlugin.REFRESH, settings("type", "resubscribed"));
        host.haCalls.remove("calendar.work").onResult(true, answer("calendar.work"), null);
        host.haCalls.remove("calendar.kids").onResult(true, answer("calendar.kids", event("Recital", "2099-10-09T18:00:00-06:00", "2099-10-09T19:00:00-06:00")), null);
        assert !host.statusError : host.status;
        plugin.onEvent("ks.ha.subscription." + AgendaPlugin.REFRESH, settings("type", "ended", "error", settings("code", "invalid_format", "message", "Unknown trigger")));
        assert host.statusError && host.status.startsWith("Auto-refresh stopped: Unknown trigger") : host.status;
        assert host.haCalls.isEmpty() : "an ended subscription must not read again";
        List<Object> renamed = new ArrayList<>();
        renamed.add(settings("calendar", "calendar.work", "color", "#FF0000", "name", "Work"));
        renamed.add(settings("calendar", "calendar.kids", "color", "#0000FF", "icon", "mdi:school"));
        int shows = Collections.frequency(host.calls, "show");
        plugin.configure(settings("calendars", renamed));
        assert host.haCalls.isEmpty() : "a new name, icon or color must not read the calendars again";
        assert Collections.frequency(host.calls, "show") == shows + 1 : "but it redraws the card";
        plugin.configure(settings("calendars", new ArrayList<Object>()));
        assert host.statusError && host.status.equals("Add a calendar in Settings.") : host.status;
        plugin.execute("endPreview", new HashMap<String, Object>());
        assert host.haSubscriptions.isEmpty() : "no refresh once the card is gone";

        boolean rejected = false;
        try { plugin.execute("nope", new HashMap<String, Object>()); } catch (IllegalArgumentException expected) { rejected = true; }
        assert rejected;
        plugin.stop();

        // A fresh install has no calendar and asks for one instead of reading anything.
        Host fresh = new Host();
        new AgendaPlugin().start(fresh, new HashMap<String, Object>());
        assert fresh.haCalls.isEmpty() && fresh.statusError && fresh.status.equals("Add a calendar in Settings.") : fresh.status;

        // Before plugins could call Home Assistant it says which version it needs.
        Host noHa = new Host();
        noHa.noHa = true;
        new AgendaPlugin().start(noHa, family());
        assert noHa.statusError && noHa.status.contains("2026.10.25") : noHa.status;

        // Changing Days ahead with every calendar's read out keeps those answers, then reads each calendar once more.
        Host busy = new Host();
        List<Object> full = new ArrayList<>();
        for (int i = 1; i <= AgendaSettings.MAX_CALENDARS; i++) full.add(settings("calendar", "calendar.c" + i));
        AgendaPlugin waiting = new AgendaPlugin();
        waiting.start(busy, settings("calendars", full));
        waiting.configure(settings("calendars", full, "days", 7));
        assert busy.haCalls.size() == AgendaSettings.MAX_CALENDARS : busy.haCalls.keySet();
        for (int i = 1; i <= AgendaSettings.MAX_CALENDARS; i++) busy.haCalls.remove("calendar.c" + i).onResult(true, answer("calendar.c" + i, dentist), null);
        assert busy.status.startsWith(AgendaSettings.MAX_CALENDARS + " events from " + AgendaSettings.MAX_CALENDARS + " calendars") : busy.status;
        assert busy.haCalls.size() == AgendaSettings.MAX_CALENDARS : "one more read with the new days";

        // Screensaver and preview overlap: the card and its refresh stay until both end, with no second read.
        Host both = new Host();
        AgendaPlugin overlap = new AgendaPlugin();
        overlap.start(both, family());
        both.haCalls.remove("calendar.family").onResult(true, answer("calendar.family", dentist), null);
        overlap.execute("preview", new HashMap<String, Object>());
        PluginHost.CommandCallback firstSubscribe = both.subscribed;
        both.haCalls.remove("calendar.family").onResult(true, answer("calendar.family", dentist), null);
        overlap.onEvent("ks.screensaver.state", settings("active", true));
        assert both.haSubscriptions.equals(Collections.singleton(AgendaPlugin.REFRESH)) && both.haCalls.isEmpty();
        assert both.calls.get(both.calls.size() - 1).equals("show") : both.calls;
        overlap.execute("endPreview", new HashMap<String, Object>());
        assert both.haSubscriptions.equals(Collections.singleton(AgendaPlugin.REFRESH)) && both.calls.get(both.calls.size() - 1).equals("show");
        overlap.execute("preview", new HashMap<String, Object>());
        overlap.onEvent("ks.screensaver.state", settings("active", false));
        assert both.haSubscriptions.equals(Collections.singleton(AgendaPlugin.REFRESH)) && both.calls.get(both.calls.size() - 1).equals("show");
        overlap.execute("endPreview", new HashMap<String, Object>());
        assert both.haSubscriptions.isEmpty() && both.calls.get(both.calls.size() - 1).equals("hide");
        // A late reply to that first subscribe does not overwrite the status of the current one.
        overlap.execute("preview", new HashMap<String, Object>());
        firstSubscribe.onResult(false, null, "unauthorized: Unauthorized");
        assert !both.statusError : both.status;

        // A subscribe KS turns away as busy still reads and shows the card, and is tried again once the reads are back.
        Host crowded = new Host();
        AgendaPlugin retry = new AgendaPlugin();
        retry.start(crowded, family());
        crowded.haCalls.remove("calendar.family").onResult(true, answer("calendar.family", dentist), null);
        crowded.subscribeBusy = true;
        retry.onEvent("ks.screensaver.state", settings("active", true));
        assert crowded.calls.equals(Arrays.asList("show")) && crowded.haSubscriptions.isEmpty() && crowded.haCalls.containsKey("calendar.family");
        crowded.subscribeBusy = false;
        crowded.haCalls.remove("calendar.family").onResult(true, answer("calendar.family", dentist), null);
        assert crowded.haSubscriptions.equals(Collections.singleton(AgendaPlugin.REFRESH)) && crowded.haCalls.isEmpty() : "retried without reading again";
        crowded.subscribed.onResult(true, null, null);
        assert !crowded.statusError : crowded.status;

        // With every request turned away no reply comes back to retry from, so the status says the refresh stopped,
        // and the next screensaver start tries again.
        Host jammed = new Host();
        AgendaPlugin stuck = new AgendaPlugin();
        stuck.start(jammed, family());
        jammed.haCalls.remove("calendar.family").onResult(true, answer("calendar.family", dentist), null);
        jammed.subscribeBusy = true;
        jammed.callBusy = true;
        stuck.onEvent("ks.screensaver.state", settings("active", true));
        assert jammed.calls.equals(Arrays.asList("show")) && jammed.haSubscriptions.isEmpty() && jammed.haCalls.isEmpty();
        assert jammed.statusError && jammed.status.startsWith("Auto-refresh stopped: Kiosk Satellite was busy") : jammed.status;
        jammed.subscribeBusy = false;
        jammed.callBusy = false;
        stuck.onEvent("ks.screensaver.state", settings("active", false));
        stuck.onEvent("ks.screensaver.state", settings("active", true));
        jammed.subscribed.onResult(true, null, null);
        jammed.haCalls.remove("calendar.family").onResult(true, answer("calendar.family", dentist), null);
        assert !jammed.statusError && jammed.haSubscriptions.equals(Collections.singleton(AgendaPlugin.REFRESH)) : jammed.status;

        // A read the card asked for while the startup read was out is dropped once the card is hidden again.
        Host blink = new Host();
        AgendaPlugin brief = new AgendaPlugin();
        brief.start(blink, family());
        brief.onEvent("ks.screensaver.state", settings("active", true));
        brief.onEvent("ks.screensaver.state", settings("active", false));
        blink.haCalls.remove("calendar.family").onResult(true, answer("calendar.family", dentist), null);
        assert blink.haCalls.isEmpty() && blink.haSubscriptions.isEmpty() : "no read while hidden";

        // An empty agenda shows the chosen message, but only once every calendar has answered without an error.
        Host quiet = new Host();
        AgendaPlugin calm = new AgendaPlugin();
        List<Object> pair = new ArrayList<>();
        pair.add(settings("calendar", "calendar.family"));
        pair.add(settings("calendar", "calendar.work"));
        calm.start(quiet, settings("calendars", pair, "whenEmpty", "No upcoming events"));
        calm.execute("preview", new HashMap<String, Object>());
        assert quiet.calls.isEmpty() : "nothing while the reads are out";
        quiet.haCalls.remove("calendar.family").onResult(true, answer("calendar.family"), null);
        assert quiet.calls.isEmpty() : "one calendar is still out";
        quiet.haCalls.remove("calendar.work").onResult(false, null, "unknown_error: Calendar unavailable");
        assert quiet.calls.isEmpty() : "a failed read is not an empty calendar";
        quiet.haCalls.remove("calendar.family").onResult(true, answer("calendar.family"), null);
        quiet.haCalls.remove("calendar.work").onResult(true, answer("calendar.work"), null);
        assert quiet.calls.equals(Arrays.asList("show")) && "No upcoming events".equals(((AgendaCard) quiet.factory).emptyMessage) : quiet.calls;
        calm.configure(settings("calendars", pair));
        assert quiet.calls.get(quiet.calls.size() - 1).equals("hide") : "Hide the card takes it away again";
        // The card keeps the message for when today's events end, and drops it once a calendar's latest read fails.
        calm.configure(settings("calendars", pair, "whenEmpty", "No upcoming events"));
        Map<String, Object> recital = event("Recital", "2099-10-09T18:00:00-06:00", "2099-10-09T19:00:00-06:00");
        calm.onEvent("ks.ha.subscription." + AgendaPlugin.REFRESH, tick);
        quiet.haCalls.remove("calendar.family").onResult(true, answer("calendar.family", recital), null);
        quiet.haCalls.remove("calendar.work").onResult(true, answer("calendar.work"), null);
        assert "No upcoming events".equals(((AgendaCard) quiet.factory).emptyMessage);
        calm.onEvent("ks.ha.subscription." + AgendaPlugin.REFRESH, tick);
        quiet.haCalls.remove("calendar.family").onResult(true, answer("calendar.family", recital), null);
        quiet.haCalls.remove("calendar.work").onResult(false, null, "unknown_error: Calendar unavailable");
        assert ((AgendaCard) quiet.factory).emptyMessage == null : "stale answers must not promise an empty calendar";

        // Reads out: a routine refresh keeps the message up, a wider Days ahead hides it until the new window is read,
        // and a refresh KS turns away whole takes it down.
        Host waits = new Host();
        AgendaPlugin patient = new AgendaPlugin();
        List<Object> one = Collections.<Object>singletonList(settings("calendar", "calendar.family"));
        patient.start(waits, settings("calendars", one, "whenEmpty", "Nothing planned", "days", 1));
        patient.execute("preview", new HashMap<String, Object>());
        waits.haCalls.remove("calendar.family").onResult(true, answer("calendar.family"), null);
        assert waits.haCalls.containsKey("calendar.family") : "the preview's read follows the startup read";
        assert waits.calls.equals(Arrays.asList("show")) && "Nothing planned".equals(((AgendaCard) waits.factory).emptyMessage)
            : "a full read of this window may say it is empty while a refresh is out";
        waits.haCalls.remove("calendar.family").onResult(true, answer("calendar.family"), null);
        patient.onEvent("ks.ha.subscription." + AgendaPlugin.REFRESH, tick);
        assert waits.calls.equals(Arrays.asList("show")) : "a routine refresh keeps the message up";
        waits.haCalls.remove("calendar.family").onResult(true, answer("calendar.family"), null);
        patient.configure(settings("calendars", one, "whenEmpty", "Nothing planned", "days", 3));
        assert waits.calls.get(waits.calls.size() - 1).equals("hide") : "a one-day answer cannot say three days are empty";
        waits.haCalls.remove("calendar.family").onResult(true, answer("calendar.family"), null);
        assert waits.calls.get(waits.calls.size() - 1).equals("show") && "Nothing planned".equals(((AgendaCard) waits.factory).emptyMessage);
        patient.onEvent("ks.ha.subscription." + AgendaPlugin.REFRESH, tick);
        patient.configure(settings("calendars", one, "whenEmpty", "Nothing planned", "days", 7));
        assert waits.calls.get(waits.calls.size() - 1).equals("hide") : "a wider window queued behind a refresh hides it too";
        waits.haCalls.remove("calendar.family").onResult(true, answer("calendar.family"), null);
        assert waits.calls.get(waits.calls.size() - 1).equals("hide") && waits.haCalls.containsKey("calendar.family") : "until the week is read";
        waits.haCalls.remove("calendar.family").onResult(true, answer("calendar.family"), null);
        assert waits.calls.get(waits.calls.size() - 1).equals("show") && "Nothing planned".equals(((AgendaCard) waits.factory).emptyMessage);
        waits.callBusy = true;
        patient.onEvent("ks.ha.subscription." + AgendaPlugin.REFRESH, tick);
        assert waits.calls.get(waits.calls.size() - 1).equals("hide") && waits.haCalls.isEmpty() : "a refresh turned away whole takes the message down";

        // Answers cover the window they were read for. Midnight moves the window, so yesterday's empty answer cannot
        // say today is empty: the message waits for today's read.
        Agenda.Window oct23 = new Agenda.Window(Instant.parse("2026-10-23T06:00:00Z"), Instant.parse("2026-10-24T06:00:00Z"));
        assert oct23.covers(ZonedDateTime.of(2026, 10, 23, 23, 50, 0, 0, ZONE), 1);
        assert !oct23.covers(ZonedDateTime.of(2026, 10, 24, 0, 10, 0, 0, ZONE), 1) : "midnight moved the window";
        assert !oct23.covers(ZonedDateTime.of(2026, 10, 23, 12, 0, 0, 0, ZONE), 2) : "a wider window";
        assert !oct23.covers(ZonedDateTime.of(2026, 10, 22, 12, 0, 0, 0, ZONE), 1) : "a clock set back";
        assert !oct23.covers(ZonedDateTime.of(2026, 10, 23, 12, 0, 0, 0, ZoneId.of("Asia/Tokyo")), 1) : "the same date in another zone";
        // Across the end of daylight saving the day is 25 hours long, and a read of it still covers it.
        ZonedDateTime fallBack = ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, ZONE);
        assert new Agenda.Window(fallBack.toInstant(), fallBack.plusDays(1).toInstant()).covers(fallBack.plusHours(23), 1);
        // Where daylight saving skips midnight the day starts at 01:00, yet still ends at the next midnight.
        ZoneId santiago = ZoneId.of("America/Santiago");
        assert LocalDate.of(2026, 9, 6).atStartOfDay(santiago).getHour() == 1 : "September 6 starts at 01:00 in Santiago";
        Agenda.Window twoDays = new Agenda.Window(LocalDate.of(2026, 9, 5).atStartOfDay(santiago).toInstant(),
            LocalDate.of(2026, 9, 7).atStartOfDay(santiago).toInstant());
        assert twoDays.covers(ZonedDateTime.of(2026, 9, 6, 12, 0, 0, 0, santiago), 1) : "a read of September 5 and 6 covers September 6";
        Host night = new Host();
        AgendaPlugin late = new AgendaPlugin();
        late.clock = Clock.fixed(Instant.parse("2026-10-24T05:50:00Z"), ZONE); // 23:50 on October 23
        late.start(night, settings("calendars", one, "whenEmpty", "All done for today", "days", 1));
        late.execute("preview", new HashMap<String, Object>());
        night.haCalls.remove("calendar.family").onResult(true, answer("calendar.family"), null);
        night.haCalls.remove("calendar.family").onResult(true, answer("calendar.family"), null);
        AgendaCard evening = (AgendaCard) night.factory;
        assert "All done for today".equals(evening.emptyMessage) && evening.covered.until.equals(Instant.parse("2026-10-24T06:00:00Z"));
        late.clock = Clock.fixed(Instant.parse("2026-10-24T06:10:00Z"), ZONE); // 00:10 on October 24
        late.onEvent("ks.ha.subscription." + AgendaPlugin.REFRESH, tick);
        assert night.calls.get(night.calls.size() - 1).equals("hide") : "yesterday's answer cannot say today is empty";
        night.haCalls.remove("calendar.family").onResult(true, answer("calendar.family"), null);
        AgendaCard morning = (AgendaCard) night.factory;
        assert night.calls.get(night.calls.size() - 1).equals("show") && "All done for today".equals(morning.emptyMessage)
            && morning.covered.until.equals(Instant.parse("2026-10-25T06:00:00Z"));
        // A clock set back to a day never read, and a move to another zone, wait for a read of the window shown.
        late.clock = Clock.fixed(Instant.parse("2026-10-23T18:00:00Z"), ZONE);
        late.onEvent("ks.ha.subscription." + AgendaPlugin.REFRESH, tick);
        assert night.calls.get(night.calls.size() - 1).equals("hide") : "October 24's answer says nothing about October 23";
        night.haCalls.remove("calendar.family").onResult(true, answer("calendar.family"), null);
        assert night.calls.get(night.calls.size() - 1).equals("show");
        late.clock = Clock.fixed(Instant.parse("2026-10-23T18:00:00Z"), ZoneId.of("Asia/Tokyo"));
        late.onEvent("ks.ha.subscription." + AgendaPlugin.REFRESH, tick);
        assert night.calls.get(night.calls.size() - 1).equals("hide") : "Denver's October 23 is not Tokyo's October 24";
        night.haCalls.remove("calendar.family").onResult(true, answer("calendar.family"), null);
        assert night.calls.get(night.calls.size() - 1).equals("show") && ((AgendaCard) night.factory).covered.from.equals(Instant.parse("2026-10-23T15:00:00Z"));

        // Where daylight saving skips midnight, a read still ends at the next midnight, so no event past the window can push
        // the answer over KS's 32 KB limit.
        Host chile = new Host();
        AgendaPlugin southern = new AgendaPlugin();
        southern.clock = Clock.fixed(ZonedDateTime.of(2026, 9, 6, 12, 0, 0, 0, santiago).toInstant(), santiago);
        southern.start(chile, settings("calendars", one, "days", 1));
        assert chile.lastEnd.startsWith("2026-09-07T00:00:00") : chile.lastEnd;

        // With the device's own clock, a time zone change takes effect at once rather than keeping the zone it started in.
        TimeZone homeZone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
            Host moving = new Host();
            AgendaPlugin traveller = new AgendaPlugin();
            traveller.start(moving, settings("calendars", one, "whenEmpty", "Nothing planned"));
            traveller.execute("preview", new HashMap<String, Object>());
            moving.haCalls.remove("calendar.family").onResult(true, answer("calendar.family"), null);
            moving.haCalls.remove("calendar.family").onResult(true, answer("calendar.family"), null);
            assert moving.calls.get(moving.calls.size() - 1).equals("show");
            TimeZone.setDefault(TimeZone.getTimeZone("America/Denver"));
            traveller.onEvent("ks.ha.subscription." + AgendaPlugin.REFRESH, tick);
            assert moving.calls.get(moving.calls.size() - 1).equals("hide") : "Tokyo's day is not Denver's";
        } finally {
            TimeZone.setDefault(homeZone);
        }

        // Settings that change before their answer lands: the card carries the newest coverage, not the one it was built with.
        Host flip = new Host();
        AgendaPlugin fickle = new AgendaPlugin();
        fickle.clock = Clock.fixed(Instant.parse("2026-10-24T05:50:00Z"), ZONE);
        fickle.start(flip, settings("calendars", one, "whenEmpty", "All done for today", "days", 1));
        fickle.execute("preview", new HashMap<String, Object>());
        flip.haCalls.remove("calendar.family").onResult(true, answer("calendar.family"), null);
        flip.haCalls.remove("calendar.family").onResult(true, answer("calendar.family"), null);
        fickle.configure(settings("calendars", one, "whenEmpty", "All done for today", "days", 7));
        fickle.configure(settings("calendars", one, "whenEmpty", "All done for today", "days", 1));
        fickle.clock = Clock.fixed(Instant.parse("2026-10-24T06:10:00Z"), ZONE);
        flip.haCalls.remove("calendar.family").onResult(true, answer("calendar.family"), null);
        assert ((AgendaCard) flip.factory).covered.covers(ZonedDateTime.now(fickle.clock), 1) : "the week's answer covers today";

        // A calendar removed and added back must be read again before the card may say the agenda is empty.
        Host again = new Host();
        AgendaPlugin returning = new AgendaPlugin();
        returning.start(again, settings("calendars", pair, "whenEmpty", "Nothing planned"));
        returning.execute("preview", new HashMap<String, Object>());
        for (int round = 0; round < 2; round++) {
            again.haCalls.remove("calendar.family").onResult(true, answer("calendar.family"), null);
            again.haCalls.remove("calendar.work").onResult(true, answer("calendar.work"), null);
        }
        assert again.calls.get(again.calls.size() - 1).equals("show");
        returning.configure(settings("calendars", one, "whenEmpty", "Nothing planned"));
        again.haCalls.remove("calendar.family").onResult(true, answer("calendar.family"), null);
        returning.configure(settings("calendars", pair, "whenEmpty", "Nothing planned"));
        assert again.calls.get(again.calls.size() - 1).equals("hide") && again.haCalls.containsKey("calendar.work") : "work's old answer is gone";

        // The Calendar picker lists only calendar entities, read from Home Assistant while the settings page is open.
        Host page = new Host();
        AgendaPlugin picker = new AgendaPlugin();
        picker.start(page, family());
        picker.onEvent("settings.page", settings("open", true));
        assert page.listing() : page.haSubscriptions;
        page.listed.onResult(true, null, null);
        String longName = new String(new char[100]).replace('\0', 'x');
        List<Object> listRows = new ArrayList<>();
        listRows.add(Arrays.asList("calendar.work", "Work"));
        listRows.add(Arrays.asList("light.kitchen", "Kitchen"));
        listRows.add(Arrays.asList("calendar.unnamed", " "));
        listRows.add("not a row");
        listRows.add(Arrays.asList("calendar.long", longName));
        picker.onEvent("ks.ha.subscription." + page.listKey, settings("type", "event", "event", settings("result", Arrays.asList(listRows, 3))));
        assert AgendaPlugin.CALENDAR_CHOICES.equals(page.choicesKey);
        assert Arrays.equals(page.choiceIds, new String[] {"calendar.work", "calendar.unnamed", "calendar.long"}) : Arrays.toString(page.choiceIds);
        assert page.choiceLabels[0].equals("Work") && page.choiceLabels[1].equals("calendar.unnamed") && page.choiceLabels[2].length() == 80;
        assert !page.listing() : "one answer is enough";
        assert page.results.isEmpty() : "an open page needs no run report";
        picker.onEvent("settings.page", settings("open", false));
        assert !page.listing() : "closing the page reads nothing";
        // Refresh reports on its run: a template error, then a list.
        picker.onEvent("settings.choices", settings("key", AgendaPlugin.CALENDAR_CHOICES, "run", "r1"));
        picker.onEvent("ks.ha.subscription." + page.listKey, settings("type", "event", "event", settings("error", "UndefinedError: boom", "level", "ERROR")));
        assert page.results.get(page.results.size() - 1).startsWith("r1 error: Home Assistant could not list the calendars") : page.results;
        picker.onEvent("settings.choices", settings("key", AgendaPlugin.CALENDAR_CHOICES, "run", "r2"));
        picker.onEvent("settings.choices", settings("key", AgendaPlugin.CALENDAR_CHOICES, "run", "r3"));
        picker.onEvent("ks.ha.subscription." + page.listKey, settings("type", "event", "event", settings("result", Arrays.asList(listRows, 3))));
        assert page.results.get(page.results.size() - 1).equals("r3 success: Found 3 calendars.") : "a second Refresh waits on the same answer";
        picker.onEvent("settings.choices", settings("key", "something.else", "run", "r4"));
        assert !page.listing() : "only the Calendar field's Refresh lists calendars";
        page.subscribeBusy = true;
        picker.onEvent("settings.choices", settings("key", AgendaPlugin.CALENDAR_CHOICES, "run", "r5"));
        assert page.results.get(page.results.size() - 1).startsWith("r5 error: Kiosk Satellite is busy") : page.results;
        page.subscribeBusy = false;
        assert AgendaPlugin.parseCalendars("[['calendar.a', 'A']]") == null : "an unparsed string is not a list";
        // The template's byte budget counts each name character at the most KS's measure can give it, so a list that
        // passes the budget can never come back too_large. A vertical tab (U+000B) takes six bytes.
        for (int codePoint = 0; codePoint <= 0x10FFFF; codePoint++) {
            if (codePoint >= 0xD800 && codePoint <= 0xDFFF) continue;
            assert dartJsonBytes(codePoint) <= AgendaPlugin.NAME_CHAR_BYTES : "U+" + Integer.toHexString(codePoint);
        }
        assert AgendaPlugin.LIST_TEMPLATE.contains("(name | length) * " + AgendaPlugin.NAME_CHAR_BYTES + " + 8") && AgendaPlugin.LIST_TEMPLATE.contains("ns.size + cost <= 25000");
        picker.onEvent("settings.choices", settings("key", AgendaPlugin.CALENDAR_CHOICES, "run", "r5b"));
        picker.onEvent("ks.ha.subscription." + page.listKey, settings("type", "event", "event", settings("result", listRows)));
        assert page.results.get(page.results.size() - 1).startsWith("r5b error: Home Assistant sent the calendars in a form") : "rows without their total";
        // KS's choice limits hold whatever Home Assistant sends, so publishing never throws into onEvent.
        List<Object> plenty = new ArrayList<>();
        for (int i = 0; i < 205; i++) plenty.add(Arrays.asList("calendar.c" + i, "Calendar " + i));
        plenty.add(0, Arrays.asList("calendar." + new String(new char[600]).replace('\0', 'x'), "Too long an ID"));
        picker.onEvent("settings.choices", settings("key", AgendaPlugin.CALENDAR_CHOICES, "run", "r6"));
        picker.onEvent("ks.ha.subscription." + page.listKey, settings("type", "event", "event", settings("result", Arrays.asList(plenty, plenty.size()))));
        assert page.choiceIds.length == 200 && page.choiceIds[0].equals("calendar.c0") : page.choiceIds.length;
        assert page.results.get(page.results.size() - 1).equals("r6 success: Listed 200 of 206 calendars. The rest do not fit in Kiosk Satellite's limits.") : page.results;
        picker.onEvent("settings.choices", settings("key", AgendaPlugin.CALENDAR_CHOICES, "run", "r7"));
        picker.onEvent("ks.ha.subscription." + page.listKey, settings("type", "event", "event", settings("result", Arrays.asList(new ArrayList<Object>(), 0))));
        assert page.choiceIds.length == 0 && page.results.get(page.results.size() - 1).equals("r7 success: Home Assistant has no calendars.");
        // A list too big for KS's events ends with an error, and the next Refresh reads again.
        picker.onEvent("settings.choices", settings("key", AgendaPlugin.CALENDAR_CHOICES, "run", "r8"));
        picker.onEvent("ks.ha.subscription." + page.listKey, settings("type", "too_large"));
        assert page.results.get(page.results.size() - 1).startsWith("r8 error: The calendar list is too large") && !page.listing();
        picker.onEvent("settings.choices", settings("key", AgendaPlugin.CALENDAR_CHOICES, "run", "r9"));
        assert page.listing() : "Refresh is not stuck behind the failed listing";
        // The page opening again while that Refresh waits keeps its run, so the run still gets its answer.
        picker.onEvent("settings.page", settings("open", true));
        picker.onEvent("ks.ha.subscription." + page.listKey, settings("type", "event", "event", settings("result", Arrays.asList(listRows, 3))));
        assert page.results.get(page.results.size() - 1).equals("r9 success: Found 3 calendars.") : page.results;
        // A canceled Refresh stops the listing instead of leaving it to reconnect for nothing.
        picker.onEvent("settings.choices", settings("key", AgendaPlugin.CALENDAR_CHOICES, "run", "r10"));
        int reports = page.results.size();
        picker.onEvent("settings.cancel", settings("run", "r10", "reason", "canceled"));
        assert !page.listing() && page.results.size() == reports : "nothing to report on a canceled run";
        picker.onEvent("settings.cancel", settings("run", "someone-else", "reason", "canceled"));
        // KS answers a canceled subscribe late. That reply belongs to the canceled attempt, not to the one replacing it.
        picker.onEvent("settings.choices", settings("key", AgendaPlugin.CALENDAR_CHOICES, "run", "r11"));
        PluginHost.CommandCallback canceledReply = page.listed;
        String canceledKey = page.listKey;
        picker.onEvent("settings.cancel", settings("run", "r11", "reason", "replaced"));
        picker.onEvent("settings.choices", settings("key", AgendaPlugin.CALENDAR_CHOICES, "run", "r12"));
        assert !page.listKey.equals(canceledKey) : "each attempt has its own key";
        canceledReply.onResult(false, null, "unsubscribed: Subscription canceled");
        picker.onEvent("ks.ha.subscription." + canceledKey, settings("type", "too_large"));
        assert page.listing() && !page.results.get(page.results.size() - 1).startsWith("r12") : "the replacement is untouched";
        picker.onEvent("ks.ha.subscription." + page.listKey, settings("type", "event", "event", settings("result", Arrays.asList(listRows, 3))));
        assert page.results.get(page.results.size() - 1).equals("r12 success: Found 3 calendars.") : page.results;

        // A tap on the card opens the app, a failure says what it needs, and over KS's limits a tap does nothing rather
        // than throw on the main thread.
        Host tapper = new Host();
        AgendaPlugin tapping = new AgendaPlugin();
        Map<String, Object> openApp = family();
        openApp.put("tap", "Open an app");
        openApp.put("app", "com.example.calendar");
        tapping.start(tapper, openApp);
        tapper.haCalls.remove("calendar.family").onResult(true, answer("calendar.family", dentist), null);
        tapping.execute("preview", new HashMap<String, Object>());
        AgendaCard tappable = (AgendaCard) tapper.factory;
        assert tapper.spec.touchable() && tappable.onTap != null;
        tappable.onTap.run();
        assert tapper.commands.contains("launchApp {package=com.example.calendar}") : tapper.commands;
        tapper.commandCallbacks.get("launchApp").onResult(false, null, "Unknown command launchApp");
        assert tapper.statusError && tapper.status.contains("2026.10.39") : tapper.status;
        tapper.commandsBusy = true;
        tappable.onTap.run();
        tapper.commandsBusy = false;
        // The App picker lists the launcher apps while the settings page is open, and Refresh reports on its run.
        tapper.commands.clear();
        tapping.onEvent("settings.page", settings("open", true));
        assert tapper.commands.contains("getLauncherApps {}") : tapper.commands;
        List<Object> launcher = new ArrayList<>();
        launcher.add(settings("package", "com.example.calendar", "label", "Calendar"));
        launcher.add(settings("package", "", "label", "No package"));
        launcher.add(settings("package", "com.example.calendar", "label", "Twice"));
        launcher.add(settings("package", "org.example.notes", "label", " "));
        tapper.commandCallbacks.get("getLauncherApps").onResult(true, launcher, null);
        assert AgendaPlugin.APP_CHOICES.equals(tapper.choicesKey) && Arrays.equals(tapper.choiceIds, new String[] {"com.example.calendar", "org.example.notes"}) : Arrays.toString(tapper.choiceIds);
        assert tapper.choiceLabels[0].equals("Calendar") && tapper.choiceLabels[1].equals("org.example.notes");
        tapping.onEvent("settings.choices", settings("key", AgendaPlugin.APP_CHOICES, "run", "a1"));
        tapper.commandCallbacks.get("getLauncherApps").onResult(false, null, "Unknown command getLauncherApps");
        assert tapper.results.get(tapper.results.size() - 1).startsWith("a1 error: Kiosk Satellite could not list its apps") && tapper.results.get(tapper.results.size() - 1).contains("2026.10.39");
        tapper.commandsBusy = true;
        tapping.onEvent("settings.choices", settings("key", AgendaPlugin.APP_CHOICES, "run", "a2"));
        assert tapper.results.get(tapper.results.size() - 1).startsWith("a2 error: Kiosk Satellite is busy");
        tapper.commandsBusy = false;
        // Wake the screen leaves the card visual only, with nothing to run on a tap.
        tapping.configure(family());
        assert !tapper.spec.touchable() && ((AgendaCard) tapper.factory).onTap == null;

        // An older KS without overlays reports it instead of failing.
        Host old = new Host();
        old.older = true;
        AgendaPlugin legacy = new AgendaPlugin();
        legacy.start(old, family());
        old.haCalls.remove("calendar.family").onResult(true, answer("calendar.family", dentist), null);
        legacy.execute("preview", new HashMap<String, Object>());
        assert old.statusError && old.status.contains("native overlays") : old.status;

        // Up to 2026.10.12 the classes are missing outright, which must not escape the callback.
        Host missing = new Host();
        missing.missingClass = true;
        AgendaPlugin early = new AgendaPlugin();
        early.start(missing, family());
        missing.haCalls.remove("calendar.family").onResult(true, answer("calendar.family", dentist), null);
        early.execute("preview", new HashMap<String, Object>());
        assert missing.statusError && missing.status.contains("after 2026.10.12") : missing.status;

        System.out.println("AgendaTest passed");
    }
}
