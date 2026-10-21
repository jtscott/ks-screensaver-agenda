// SPDX-License-Identifier: Apache-2.0
package agenda.ks;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;

/**
 * One event as calendar.get_events returns it. All-day events carry dates
 * with an exclusive end, the way Home Assistant reports them. Timed events
 * carry instants.
 */
final class AgendaEvent {
    /** The calendar entity it came from, which picks its color. */
    final String calendar;
    final String summary;
    final String location;
    final boolean allDay;
    /** All-day only. */
    final LocalDate startDate;
    /** All-day only, exclusive. */
    final LocalDate endDate;
    /** Timed only. */
    final Instant start;
    /** Timed only. */
    final Instant end;

    private AgendaEvent(String calendar, String summary, String location, boolean allDay, LocalDate startDate, LocalDate endDate, Instant start, Instant end) {
        this.calendar = calendar;
        this.summary = summary;
        this.location = location;
        this.allDay = allDay;
        this.startDate = startDate;
        this.endDate = endDate;
        this.start = start;
        this.end = end;
    }

    /** Null when the entry has no usable start, so one bad event never hides the rest. */
    static AgendaEvent parse(Object raw, ZoneId zone, String calendar) {
        if (!(raw instanceof Map)) return null;
        Map<?, ?> map = (Map<?, ?>) raw;
        Object rawStart = map.get("start");
        if (!(rawStart instanceof String)) return null;
        String startText = ((String) rawStart).trim();
        String endText = map.get("end") instanceof String ? ((String) map.get("end")).trim() : "";
        String summary = text(map.get("summary"));
        if (summary.isEmpty()) summary = "(No title)";
        String location = text(map.get("location"));
        try {
            if (isDate(startText)) {
                LocalDate startDate = LocalDate.parse(startText);
                LocalDate endDate = isDate(endText) ? LocalDate.parse(endText) : startDate.plusDays(1);
                if (!endDate.isAfter(startDate)) endDate = startDate.plusDays(1);
                return new AgendaEvent(calendar, summary, location, true, startDate, endDate, null, null);
            }
            Instant start = instant(startText, zone);
            Instant end = endText.isEmpty() ? start : instant(endText, zone);
            if (end.isBefore(start)) end = start;
            return new AgendaEvent(calendar, summary, location, false, null, null, start, end);
        } catch (DateTimeException badDate) {
            return null;
        }
    }

    private static boolean isDate(String text) { return text.length() == 10 && text.charAt(4) == '-'; }

    /** Home Assistant sends an offset. A bare local time is read in the kiosk's zone. */
    private static Instant instant(String text, ZoneId zone) {
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeException noOffset) {
            return LocalDateTime.parse(text).atZone(zone).toInstant();
        }
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().replaceAll("\\s+", " ").trim();
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof AgendaEvent)) return false;
        AgendaEvent e = (AgendaEvent) other;
        return allDay == e.allDay && calendar.equals(e.calendar) && summary.equals(e.summary) && location.equals(e.location)
            && Objects.equals(startDate, e.startDate) && Objects.equals(endDate, e.endDate)
            && Objects.equals(start, e.start) && Objects.equals(end, e.end);
    }

    @Override public int hashCode() {
        return Objects.hash(calendar, summary, location, allDay, startDate, endDate, start, end);
    }
}
