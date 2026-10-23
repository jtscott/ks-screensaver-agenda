// SPDX-License-Identifier: Apache-2.0
package agenda.ks;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Turns events into the days the card draws, Calendar Card Pro style: a date
 * block per day and, per event, its title, its time and how far off it is.
 * Kept free of Android so the JVM tests cover it.
 */
final class Agenda {
    static final class Day {
        final LocalDate date;
        /** "Today", "Tomorrow" or "Sat, Oct 10", for the heading layout. */
        final String heading;
        final String weekday;
        final String dayOfMonth;
        final String month;
        final List<Item> items = new ArrayList<>();

        Day(LocalDate date, String heading, String weekday, String dayOfMonth, String month) {
            this.date = date;
            this.heading = heading;
            this.weekday = weekday;
            this.dayOfMonth = dayOfMonth;
            this.month = month;
        }
    }

    static final class Item {
        final String title;
        /** "All day", "6:30 PM - 8:00 PM", or the start alone without end times. */
        final String time;
        /** "now", "in 5 hours", "tomorrow", "in 2 days", or empty. */
        final String relative;
        final String location;
        /** The calendar entity, which picks the accent color. */
        final String calendar;

        Item(String title, String time, String relative, String location, String calendar) {
            this.title = title;
            this.time = time;
            this.relative = relative;
            this.location = location;
            this.calendar = calendar;
        }

        @Override public String toString() { return title + " | " + time + " | " + relative; }
    }

    private static final class Placed {
        final AgendaEvent event;
        final LocalDate day;
        Placed(AgendaEvent event, LocalDate day) { this.event = event; this.day = day; }
    }

    private Agenda() {}

    /**
     * Ended events drop out. An event already under way sits under today,
     * so a multi-day or overnight event shows once rather than per day.
     */
    static List<Day> days(List<AgendaEvent> events, ZonedDateTime now, AgendaSettings settings, Locale locale) {
        LocalDate today = now.toLocalDate();
        LocalDate windowEnd = today.plusDays(settings.days);
        List<Placed> placed = new ArrayList<>();
        for (AgendaEvent event : events) {
            LocalDate day;
            if (event.allDay) {
                if (!event.endDate.isAfter(today)) continue;
                day = event.startDate.isBefore(today) ? today : event.startDate;
            } else {
                boolean point = event.end.equals(event.start);
                if (point ? event.start.isBefore(now.toInstant()) : !event.end.isAfter(now.toInstant())) continue;
                LocalDate startDay = event.start.atZone(now.getZone()).toLocalDate();
                day = startDay.isBefore(today) ? today : startDay;
            }
            if (day.isBefore(windowEnd)) placed.add(new Placed(event, day));
        }
        Collections.sort(placed, new Comparator<Placed>() {
            @Override public int compare(Placed a, Placed b) {
                int byDay = a.day.compareTo(b.day);
                if (byDay != 0) return byDay;
                if (a.event.allDay != b.event.allDay) return a.event.allDay ? -1 : 1;
                if (a.event.allDay) return a.event.summary.compareToIgnoreCase(b.event.summary);
                return a.event.start.compareTo(b.event.start);
            }
        });
        if (placed.size() > settings.maxEvents) placed = placed.subList(0, settings.maxEvents);

        DateTimeFormatter time = DateTimeFormatter.ofPattern(settings.clock24 ? "HH:mm" : "h:mm a", locale);
        DateTimeFormatter heading = DateTimeFormatter.ofPattern("EEE, MMM d", locale);
        DateTimeFormatter weekday = DateTimeFormatter.ofPattern("EEE", locale);
        DateTimeFormatter month = DateTimeFormatter.ofPattern("MMM", locale);
        List<Day> days = new ArrayList<>();
        Day current = null;
        for (Placed p : placed) {
            if (current == null || !current.date.equals(p.day)) {
                String label = p.day.equals(today) ? "Today" : p.day.equals(today.plusDays(1)) ? "Tomorrow" : heading.format(p.day);
                current = new Day(p.day, label, weekday.format(p.day), String.valueOf(p.day.getDayOfMonth()), month.format(p.day).toUpperCase(locale));
                days.add(current);
            }
            AgendaEvent e = p.event;
            String when;
            if (e.allDay) when = "All day";
            else {
                when = time.format(e.start.atZone(now.getZone()));
                if (settings.endTime && e.end.isAfter(e.start)) when += " - " + time.format(e.end.atZone(now.getZone()));
            }
            current.items.add(new Item(e.summary, when, relative(e, p.day, now), e.location, e.calendar));
        }
        return days;
    }

    /**
     * The span of time answers were read for. An empty answer says nothing outside it, so the window shown must sit
     * inside: after midnight, a wider Days ahead, a time zone change or a clock set back, it may not.
     */
    static final class Window {
        final Instant from;
        final Instant until;

        Window(Instant from, Instant until) {
            this.from = from;
            this.until = until;
        }

        /** Whether it holds today's window, from midnight in now's zone through Days ahead. */
        boolean covers(ZonedDateTime now, int days) {
            ZonedDateTime start = now.toLocalDate().atStartOfDay(now.getZone());
            return !start.toInstant().isBefore(from) && !start.plusDays(days).toInstant().isAfter(until);
        }

        @Override public boolean equals(Object other) {
            return other instanceof Window && from.equals(((Window) other).from) && until.equals(((Window) other).until);
        }

        @Override public int hashCode() { return Objects.hash(from, until); }
    }

    /** Calendar Card Pro's countdown: minutes and hours today, then days. Nothing for today's all-day events. */
    static String relative(AgendaEvent e, LocalDate day, ZonedDateTime now) {
        LocalDate today = now.toLocalDate();
        if (!e.allDay && !e.start.isAfter(now.toInstant())) return "now";
        if (day.equals(today)) {
            if (e.allDay) return "";
            long minutes = Math.max(1, Duration.between(now.toInstant(), e.start).toMinutes());
            if (minutes < 60) return minutes == 1 ? "in 1 minute" : "in " + minutes + " minutes";
            long hours = minutes / 60;
            return hours == 1 ? "in 1 hour" : "in " + hours + " hours";
        }
        long daysAway = ChronoUnit.DAYS.between(today, day);
        return daysAway == 1 ? "tomorrow" : "in " + daysAway + " days";
    }
}
