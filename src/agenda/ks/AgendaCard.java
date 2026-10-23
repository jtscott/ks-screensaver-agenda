// SPDX-License-Identifier: Apache-2.0
package agenda.ks;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;

/**
 * The agenda card, laid out like Calendar Card Pro: a date block per day, then
 * each event with an accent bar in its calendar's color, its title, time and countdown. It sits over photos,
 * so it keeps white text on its own dark glass whatever the KS theme is.
 */
final class AgendaCard implements OverlayFactory {
    private static final int TEXT = 0xFFFFFFFF;
    private static final int MUTED = 0xBFFFFFFF;
    /** Width of the event column, so every countdown lines up on the right. Less when the screen has no room for it. */
    private static final int EVENTS_DP = 400;

    private final List<AgendaEvent> events;
    private final AgendaSettings settings;
    /** Shown when nothing is left in Days ahead, or null to hide the card then. */
    final String emptyMessage;
    /** The day the answers run up to, exclusive; the message shows only while they cover the window. */
    final LocalDate coveredUntil;

    AgendaCard(List<AgendaEvent> events, AgendaSettings settings, String emptyMessage, LocalDate coveredUntil) {
        this.events = events;
        this.settings = settings;
        this.emptyMessage = emptyMessage;
        this.coveredUntil = coveredUntil;
    }

    @Override public View create(Context context, KsTheme theme) {
        return new Card(context, theme, events, settings, emptyMessage, coveredUntil);
    }

    @Override public void onDestroy(View view) {
        if (view instanceof Card) ((Card) view).halt();
    }

    /** Redraws on each minute so countdowns and finished events stay right while the screensaver runs. */
    private static final class Card extends LinearLayout {
        private final KsTheme theme;
        private final List<AgendaEvent> events;
        private final AgendaSettings settings;
        private final String emptyMessage;
        private final LocalDate coveredUntil;
        private final float k;
        /** Accent color per calendar entity. */
        private final Map<String, Integer> colors = new HashMap<>();
        /** For a calendar whose color does not parse. Read here, not in a static, so JVM tests never touch Color. */
        private final int fallback = Color.parseColor(AgendaSettings.DEFAULT_COLOR);
        private boolean running;
        private final Runnable tick = new Runnable() {
            @Override public void run() {
                render();
                schedule();
            }
        };

        Card(Context context, KsTheme theme, List<AgendaEvent> events, AgendaSettings settings, String emptyMessage, LocalDate coveredUntil) {
            super(context);
            this.theme = theme;
            this.events = events;
            this.settings = settings;
            this.emptyMessage = emptyMessage;
            this.coveredUntil = coveredUntil;
            this.k = settings.scale / 100f;
            for (Map.Entry<String, AgendaSettings.Style> calendar : settings.calendars.entrySet()) colors.put(calendar.getKey(), parseColor(calendar.getValue().color));
            setOrientation(VERTICAL);
            setPadding(dp(18), dp(16), dp(22), dp(16));
            if (settings.opacity > 0) {
                GradientDrawable glass = new GradientDrawable();
                glass.setColor(Color.argb(Math.round(settings.opacity * 2.55f), 0, 0, 0));
                glass.setCornerRadius(dp(theme.radiusCard()));
                setBackground(glass);
            }
            render();
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            running = true;
            schedule();
        }

        @Override protected void onDetachedFromWindow() {
            halt();
            super.onDetachedFromWindow();
        }

        void halt() {
            running = false;
            removeCallbacks(tick);
        }

        private void schedule() {
            if (!running) return;
            removeCallbacks(tick);
            long now = System.currentTimeMillis();
            postDelayed(tick, 60_000 - now % 60_000 + 250);
        }

        /** Main thread, outside the callbacks KS guards: an exception here would end the app, so none escapes. */
        private void render() {
            try {
                removeAllViews();
                ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
                List<Agenda.Day> days = Agenda.days(events, now, settings, Locale.getDefault());
                // Nothing left in Days ahead, such as after today's last event: the chosen message, or no card. Past
                // midnight the answers no longer cover the window, so no message until the next read.
                String message = Agenda.covers(coveredUntil, now.toLocalDate(), settings.days) ? emptyMessage : null;
                if (days.isEmpty() && message == null) {
                    setVisibility(GONE);
                    return;
                }
                setVisibility(VISIBLE);
                boolean first = true;
                if (!settings.title.isEmpty()) {
                    TextView title = label(settings.title.toUpperCase(Locale.getDefault()), settings.titleSize, 600, MUTED);
                    title.setLetterSpacing(0.12f);
                    addView(title);
                    first = false;
                }
                if (days.isEmpty()) {
                    TextView text = label(message, settings.eventSize, 500, TEXT);
                    text.setMaxWidth(dp(EVENTS_DP));
                    addView(text, below(LayoutParams.WRAP_CONTENT, first ? 0 : 10));
                    return;
                }
                for (Agenda.Day day : days) {
                    addView(day(day), below(LayoutParams.WRAP_CONTENT, first ? 0 : 14));
                    first = false;
                }
            } catch (RuntimeException failed) {
                setVisibility(GONE);
            }
        }

        private LinearLayout day(Agenda.Day day) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            if (settings.dateColumn) {
                LinearLayout date = new LinearLayout(getContext());
                date.setOrientation(VERTICAL);
                date.setGravity(Gravity.CENTER_HORIZONTAL);
                date.setMinimumWidth(dp(56));
                date.addView(aligned(label(day.weekday, settings.weekdaySize, 400, TEXT)), below(LayoutParams.MATCH_PARENT, 0));
                date.addView(aligned(label(day.dayOfMonth, settings.daySize, 500, TEXT)), below(LayoutParams.MATCH_PARENT, 2));
                date.addView(aligned(label(day.month, settings.monthSize, 500, MUTED)), below(LayoutParams.MATCH_PARENT, 2));
                row.addView(date);
            }
            LinearLayout items = new LinearLayout(getContext());
            items.setOrientation(VERTICAL);
            if (!settings.dateColumn) {
                LayoutParams params = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
                params.bottomMargin = dp(6);
                items.addView(label(day.heading, settings.headingSize, 500, MUTED), params);
            }
            boolean first = true;
            for (Agenda.Item item : day.items) {
                LinearLayout event = new LinearLayout(getContext());
                event.setOrientation(HORIZONTAL);
                if (settings.accentBar) {
                    View bar = new View(getContext());
                    bar.setBackgroundColor(color(item.calendar));
                    LayoutParams params = new LayoutParams(dp(3), LayoutParams.MATCH_PARENT);
                    params.rightMargin = dp(14);
                    event.addView(bar, params);
                }
                LinearLayout text = item(item);
                // Space as padding, not margin, so a day's bars meet in one unbroken line.
                text.setPadding(0, first ? 0 : dp(8), 0, 0);
                // One width lines the countdowns up, the way Calendar Card Pro right-aligns them.
                event.addView(text, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
                items.addView(event);
                first = false;
            }
            LayoutParams params = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            params.leftMargin = settings.dateColumn ? dp(14) : 0;
            row.addView(items, params);
            return row;
        }

        private LinearLayout item(Agenda.Item item) {
            LinearLayout text = new Column();
            AgendaSettings.Style style = settings.calendars.get(item.calendar);
            TextView title = label(item.title, settings.eventSize, 500, TEXT);
            title.setMaxLines(settings.titleLines);
            title.setEllipsize(TextUtils.TruncateAt.END);
            Drawable mark = style == null || style.icon.isEmpty() ? null : icon(style.icon, color(item.calendar));
            if (mark == null) {
                text.addView(title);
            } else {
                // The calendar's icon leads the title, sized to it and in the calendar's color.
                LinearLayout line = new LinearLayout(getContext());
                line.setOrientation(HORIZONTAL);
                ImageView image = new ImageView(getContext());
                image.setImageDrawable(mark);
                int size = dp(settings.eventSize + 2);
                LayoutParams params = new LayoutParams(size, size);
                params.rightMargin = dp(6);
                line.addView(image, params);
                line.addView(title);
                text.addView(line);
            }
            LinearLayout time = detail("mdi:clock-outline", item.time);
            if (style != null && !style.name.isEmpty()) {
                TextView name = label("·  " + style.name, settings.detailSize, 500, color(item.calendar));
                name.setSingleLine(true);
                LayoutParams params = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
                params.leftMargin = dp(8);
                time.addView(name, params);
            }
            if (settings.relativeTime && !item.relative.isEmpty()) {
                // On the time's line, right-aligned like Calendar Card Pro. The time side gives way when
                // space runs out, so the countdown always shows.
                LinearLayout line = new LinearLayout(getContext());
                line.setOrientation(HORIZONTAL);
                line.setGravity(Gravity.CENTER_VERTICAL);
                line.addView(time, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
                TextView relative = label(item.relative, settings.relativeSize, 400, MUTED);
                LayoutParams params = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
                params.leftMargin = dp(16);
                line.addView(relative, params);
                time = line;
            }
            text.addView(time, below(LayoutParams.MATCH_PARENT, 4));
            if (settings.showLocation && !item.location.isEmpty()) text.addView(detail("mdi:map-marker-outline", item.location), below(LayoutParams.MATCH_PARENT, 2));
            return text;
        }

        /**
         * The event column: EVENTS_DP wide, or what is left when the screen is narrower. The horizontal layouts above
         * it pass down the width the date column, gaps and padding leave, so the title wraps and the time line shortens
         * instead of running off the screen.
         */
        private final class Column extends LinearLayout {
            Column() {
                super(Card.this.getContext());
                setOrientation(VERTICAL);
            }

            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width = dp(EVENTS_DP);
                if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) width = Math.min(width, MeasureSpec.getSize(widthSpec));
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightSpec);
            }
        }

        /** A muted line with an optional leading icon: the time, or the location. */
        private LinearLayout detail(String icon, String value) {
            LinearLayout line = new LinearLayout(getContext());
            line.setOrientation(HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            Drawable drawable = settings.timeIcon ? icon(icon, MUTED) : null;
            if (drawable != null) {
                ImageView image = new ImageView(getContext());
                image.setImageDrawable(drawable);
                int size = dp(settings.detailSize + 2); // the icon follows its text
                LayoutParams params = new LayoutParams(size, size);
                params.rightMargin = dp(6);
                line.addView(image, params);
            }
            TextView text = label(value, settings.detailSize, 400, MUTED);
            text.setSingleLine(true);
            text.setEllipsize(TextUtils.TruncateAt.END);
            line.addView(text);
            return line;
        }

        /** KS draws the icon. Versions before KsTheme.icon throw a LinkageError, so the line just goes without. */
        private Drawable icon(String name, int tint) {
            try {
                Drawable drawable = theme.icon(name, "onSurface");
                if (drawable != null) drawable.setTint(tint);
                return drawable;
            } catch (LinkageError olderHost) {
                return null;
            }
        }

        private TextView label(String text, float sp, int weight, int color) {
            TextView view = new TextView(getContext());
            view.setText(text);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * k);
            view.setTypeface(theme.typeface(weight));
            view.setTextColor(color);
            view.setShadowLayer(theme.px(3), 0, theme.px(1), 0x99000000);
            // Without these, Android pads each line for font padding and fallback fonts, which spread the card out.
            view.setIncludeFontPadding(false);
            if (Build.VERSION.SDK_INT >= 28) {
                view.setFallbackLineSpacing(false);
                view.setLineHeight(Math.round(view.getTextSize() * 1.2f));
            }
            return view;
        }

        /** Lines the text up within the date column, however wide the column's widest line makes it. */
        private TextView aligned(TextView view) {
            view.setGravity("Left".equals(settings.dateAlign) ? Gravity.LEFT : "Right".equals(settings.dateAlign) ? Gravity.RIGHT : Gravity.CENTER_HORIZONTAL);
            return view;
        }

        private int color(String calendar) {
            Integer color = colors.get(calendar);
            return color == null ? fallback : color;
        }

        private LayoutParams below(int width, float topDp) {
            LayoutParams params = new LayoutParams(width, LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(topDp);
            return params;
        }

        private int dp(float value) { return theme.px(value * k); }

        private int parseColor(String value) {
            try {
                return Color.parseColor(value);
            } catch (IllegalArgumentException | NullPointerException badColor) {
                return fallback;
            }
        }
    }
}
