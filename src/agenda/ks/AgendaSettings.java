// SPDX-License-Identifier: Apache-2.0
package agenda.ks;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.jxl.kiosk.plugins.OverlaySpec;

/** The plugin's settings, clamped to the manifest ranges. Immutable. */
final class AgendaSettings {
    static final String DEFAULT_COLOR = "#4FA3E0";
    /** One get_events call per calendar. KS allows 8 pending calls, and start already has the screensaver read and the refresh subscription out. */
    static final int MAX_CALENDARS = 6;

    /** How one calendar looks on the card. An empty name or icon shows nothing for it. */
    static final class Style {
        final String color;
        final String name;
        final String icon;

        Style(String color, String name, String icon) {
            this.color = color;
            this.name = name;
            this.icon = icon;
        }
    }

    /** Each calendar's entity and style, in list order. */
    final Map<String, Style> calendars;
    final String title;
    final int days;
    final int maxEvents;
    final boolean showLocation;
    final boolean clock24;
    final String anchor;
    /** Position "Custom": the card's exact place, x and y dp from the top left of the screen. */
    final boolean custom;
    final int x, y;
    final int scale;
    final int opacity;
    final boolean dateColumn;
    /** "Left", "Center" or "Right": how the date column's three lines line up. */
    final String dateAlign;
    final boolean accentBar;
    final boolean endTime;
    final boolean timeIcon;
    final boolean relativeTime;
    final int titleLines;
    /** Text sizes in sp, before Size scales the whole card. */
    final int titleSize, weekdaySize, daySize, monthSize, headingSize, eventSize, detailSize, relativeSize;
    /** What the card was built from; equal values mean an equal card. */
    private final Map<String, Object> values;

    private AgendaSettings(Map<String, Object> values) {
        this.values = new HashMap<>(values);
        calendars = calendars(values.get("calendars"));
        title = string(values.get("title"), "Family");
        days = number(values.get("days"), 3, 1, 7);
        maxEvents = number(values.get("maxEvents"), 5, 1, 10);
        showLocation = flag(values.get("showLocation"), false);
        clock24 = "24-hour".equals(values.get("clock"));
        anchor = anchor(values.get("corner"));
        custom = "Custom".equals(values.get("corner"));
        x = number(values.get("x"), 24, 0, 4096);
        y = number(values.get("y"), 24, 0, 4096);
        scale = number(values.get("scale"), 100, 60, 200);
        opacity = number(values.get("opacity"), 55, 0, 90);
        dateColumn = flag(values.get("dateColumn"), true);
        dateAlign = "Left".equals(values.get("dateAlign")) || "Right".equals(values.get("dateAlign")) ? (String) values.get("dateAlign") : "Center";
        accentBar = flag(values.get("accentBar"), true);
        endTime = flag(values.get("endTime"), true);
        timeIcon = flag(values.get("timeIcon"), true);
        relativeTime = flag(values.get("relativeTime"), true);
        titleLines = number(values.get("titleLines"), 2, 1, 3);
        titleSize = number(values.get("titleSize"), 12, 8, 30);
        weekdaySize = number(values.get("weekdaySize"), 15, 8, 30);
        daySize = number(values.get("daySize"), 34, 12, 60);
        monthSize = number(values.get("monthSize"), 13, 8, 30);
        headingSize = number(values.get("headingSize"), 13, 8, 30);
        eventSize = number(values.get("eventSize"), 17, 8, 36);
        detailSize = number(values.get("detailSize"), 14, 8, 30);
        relativeSize = number(values.get("relativeSize"), 14, 8, 30);
    }

    static AgendaSettings parse(Map<String, Object> values) {
        return new AgendaSettings(values);
    }

    /** Blank and repeated calendars drop out. None until the user adds one. */
    private static Map<String, Style> calendars(Object rows) {
        Map<String, Style> out = new LinkedHashMap<>();
        if (!(rows instanceof List)) return Collections.unmodifiableMap(out);
        for (Object row : (List<?>) rows) {
            if (!(row instanceof Map) || out.size() >= MAX_CALENDARS) continue;
            Map<?, ?> r = (Map<?, ?>) row;
            String id = string(r.get("calendar"), "");
            if (!id.isEmpty() && !out.containsKey(id)) {
                out.put(id, new Style(string(r.get("color"), DEFAULT_COLOR), string(r.get("name"), ""), string(r.get("icon"), "")));
            }
        }
        return Collections.unmodifiableMap(out);
    }

    private static String string(Object value, String fallback) {
        return value instanceof String ? ((String) value).trim() : fallback;
    }

    private static boolean flag(Object value, boolean fallback) {
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    private static int number(Object value, int fallback, int min, int max) {
        int n = value instanceof Number ? (int) Math.round(((Number) value).doubleValue()) : fallback;
        return Math.max(min, Math.min(max, n));
    }

    /** The Position setting, saved under its first name "corner" so existing choices carry over. */
    private static String anchor(Object position) {
        switch (String.valueOf(position)) {
            case "Top middle": return OverlaySpec.TOP;
            case "Top right": return OverlaySpec.TOP_RIGHT;
            case "Left middle": return OverlaySpec.LEFT;
            case "Center": return OverlaySpec.CENTER;
            case "Right middle": return OverlaySpec.RIGHT;
            case "Bottom left": return OverlaySpec.BOTTOM_LEFT;
            case "Bottom middle": return OverlaySpec.BOTTOM;
            case "Bottom right": return OverlaySpec.BOTTOM_RIGHT;
            default: return OverlaySpec.TOP_LEFT; // Custom too, where x and y place the card from the top left
        }
    }

    @Override public boolean equals(Object other) {
        return other instanceof AgendaSettings && values.equals(((AgendaSettings) other).values);
    }

    @Override public int hashCode() { return values.hashCode(); }
}
