// SPDX-License-Identifier: Apache-2.0
package agenda.ks;

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
        CommandCallback pendingRead;
        boolean older;
        boolean missingClass;
        String status;
        boolean statusError;
        public void executeCommand(String command, Map<String, Object> arguments, CommandCallback callback) {
            assert command.equals("isScreensaverActive");
            pendingRead = callback;
        }
        /** The pending get_events callback per calendar, in call order. */
        final Map<String, CommandCallback> haCalls = new LinkedHashMap<>();
        final Set<String> haSubscriptions = new LinkedHashSet<>();
        CommandCallback subscribed;
        boolean noHa;
        public void haCall(Map<String, Object> message, CommandCallback callback) {
            if (noHa) throw new UnsupportedOperationException("Home Assistant access is unavailable");
            assert "call_service".equals(message.get("type")) && "get_events".equals(message.get("service")) && Boolean.TRUE.equals(message.get("return_response"));
            Map<?, ?> data = (Map<?, ?>) message.get("service_data");
            assert ((String) data.get("start_date_time")).matches("\\d{4}-\\d{2}-\\d{2}T00:00:00(Z|[-+]\\d{2}:\\d{2})") : data;
            String calendar = (String) ((Map<?, ?>) message.get("target")).get("entity_id");
            assert !haCalls.containsKey(calendar) : "a second read of " + calendar + " while one is out";
            // KS's limit. haCall and executeCommand share it; the screensaver read and the subscription are answered by then.
            if (haCalls.size() >= 8) throw new IllegalStateException("too_many_pending");
            haCalls.put(calendar, callback);
        }
        public void haSubscribe(String key, Map<String, Object> message, CommandCallback callback) {
            if (noHa) throw new UnsupportedOperationException("Home Assistant access is unavailable");
            assert "subscribe_trigger".equals(message.get("type"));
            boolean added = haSubscriptions.add(key);
            assert added : "Subscription " + key + " is already active";
            subscribed = callback;
        }
        public void haUnsubscribe(String key) { haSubscriptions.remove(key); }
        public void subscribe(String event) { subscriptions.add(event); }
        public void unsubscribe(String event) { subscriptions.remove(event); }
        public void showOverlay(String key, OverlaySpec spec, OverlayFactory factory) {
            if (older) throw new UnsupportedOperationException("Native overlays are unavailable");
            if (missingClass) throw new NoClassDefFoundError("Failed resolution of: Lme/jxl/kiosk/plugins/OverlaySpec;");
            assert key.equals(AgendaPlugin.OVERLAY) && factory != null;
            this.spec = spec;
            calls.add("show");
        }
        public void hideOverlay(String key) { calls.add("hide"); }
        public void showWindow(String title, String message, String button) {}
        public void hideWindow() {}
        public void log(String message) {}
        public void status(String message, boolean error) { status = message; statusError = error; }
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
