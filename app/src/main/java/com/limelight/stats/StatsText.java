package com.limelight.stats;

import android.content.Context;
import android.content.res.Resources;
import android.text.format.DateFormat;

import com.limelight.R;
import com.limelight.nvstream.http.AppStats;
import com.limelight.nvstream.http.Iso8601;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * The statistics' wording, in the reader's language: {@link StatsFormat}'s rules dressed in this
 * app's string resources. Dates follow the device's locale, so a month reads "ottobre 2026" in
 * Italian and "October 2026" in English, with the first letter capitalised only where a label
 * starts with it, as Italian wants.
 */
public class StatsText implements StatsFormat.Words {
    private final Context context;
    private final Resources res;
    private final Locale locale;
    private final StatsFormat.Words words;

    public StatsText(Context context) {
        this.context = context;
        this.res = context.getResources();
        Locale configured = res.getConfiguration().locale;
        this.locale = configured != null ? configured : Locale.getDefault();
        this.words = words(res);
    }

    /**
     * The duration words alone, for a surface with no StatsText of its own. The stream's session
     * length is worded with them too, so the panel reads "1h 05m" while streaming and on Stats
     * alike.
     */
    public static StatsFormat.Words words(Resources res) {
        return new StatsFormat.Words() {
            @Override
            public String minutes(long minutes) {
                return res.getString(R.string.stats_duration_minutes, (int) minutes);
            }

            @Override
            public String hours(long hours, long minutes) {
                return minutes == 0
                        ? res.getString(R.string.stats_duration_hours, (int) hours)
                        : res.getString(R.string.stats_duration_hours_minutes, (int) hours,
                                (int) minutes);
            }
        };
    }

    @Override
    public String minutes(long minutes) {
        return words.minutes(minutes);
    }

    @Override
    public String hours(long hours, long minutes) {
        return words.hours(hours, minutes);
    }

    public String duration(long seconds) {
        return StatsFormat.duration(seconds, this);
    }

    public String number(long n) {
        return NumberFormat.getIntegerInstance(locale).format(n);
    }

    public String hoursTotal(long seconds) {
        return res.getString(R.string.stats_hours_total, number(StatsFormat.roundHours(seconds)));
    }

    public Locale getLocale() {
        return locale;
    }

    /** "This week", "Last week", "28 Sep – 4 Oct", "October 2026" or "2026". */
    public String periodTitle(AppStats s) {
        Calendar from = Iso8601.parseDay(s.getFrom());
        if (from == null) {
            return "";
        }
        switch (s.getRange()) {
            case AppStats.RANGE_YEAR:
                return String.valueOf(from.get(Calendar.YEAR));
            case AppStats.RANGE_MONTH:
                return capitalize(format(from.getTime(), "MMMMyyyy"));
            default:
                if (s.getOffset() == 0) {
                    return res.getString(R.string.stats_this_week);
                }
                if (s.getOffset() == -1) {
                    return res.getString(R.string.stats_last_week);
                }
                Calendar to = Iso8601.parseDay(s.getTo());
                if (to == null) {
                    return format(from.getTime(), "dMMM");
                }
                to.add(Calendar.DAY_OF_MONTH, -1);
                return format(from.getTime(), "dMMM") + " – " + format(to.getTime(), "dMMM");
        }
    }

    /**
     * Compared with the period before — while this one is under way, with the period before up
     * to the same point. Null when that comparison would be noise (see {@link AppStats#isComparable()}).
     */
    public String delta(AppStats s) {
        if (!s.isComparable()) {
            return null;
        }
        boolean running = s.isRunning();
        int than;
        switch (s.getRange()) {
            case AppStats.RANGE_MONTH:
                than = running ? R.string.stats_than_month_running : R.string.stats_than_month_done;
                break;
            case AppStats.RANGE_YEAR:
                than = running ? R.string.stats_than_year_running : R.string.stats_than_year_done;
                break;
            default:
                than = running ? R.string.stats_than_week_running : R.string.stats_than_week_done;
                break;
        }
        String thanText = res.getString(than);
        String signed = deltaAmount(s);
        if (signed == null) {
            return res.getString(R.string.stats_same, thanText);
        }
        return res.getString(R.string.stats_diff, signed, thanText);
    }

    /**
     * Under a chart whose first bars come before GameActivity's first session: "No session data
     * before October" on a year, "… before 9 October" on a week or a month.
     */
    public String trackingNote(AppStats s) {
        Calendar since = Iso8601.parseDay(s.getTrackingSince());
        if (since == null) {
            return "";
        }
        String when = AppStats.RANGE_YEAR.equals(s.getRange())
                ? format(since.getTime(), "MMMM")
                : format(since.getTime(), "dMMMM");
        return res.getString(R.string.stats_no_data_before, when);
    }

    /**
     * What to say instead of a period's figures: that GameActivity is missing, or that its
     * sessions start after this period.
     */
    public String untracked(AppStats s) {
        if (!s.hasActivity()) {
            return res.getString(R.string.stats_no_activity);
        }
        Calendar since = Iso8601.parseDay(s.getTrackingSince());
        if (since == null) {
            return res.getString(R.string.stats_untracked);
        }
        return res.getString(R.string.stats_untracked_since, format(since.getTime(), "dMMMM"));
    }

    /** The label under a bar: the weekday, the month's initial, or every fifth day of a month. */
    public String barLabel(String range, String date, int index) {
        return barLabel(range, date, index, false);
    }

    /**
     * As {@link #barLabel(String, String, int)}; {@code weekly} labels a month's days a week
     * apart ("1 8 15 22 29"), for a narrow window where every fifth day would crowd.
     */
    public String barLabel(String range, String date, int index, boolean weekly) {
        Calendar day = Iso8601.parseDay(date);
        if (day == null) {
            return "";
        }
        if (AppStats.RANGE_WEEK.equals(range)) {
            String[] weekdays = res.getStringArray(R.array.stats_weekdays);
            return index >= 0 && index < weekdays.length ? weekdays[index] : "";
        }
        if (AppStats.RANGE_YEAR.equals(range)) {
            String[] months = res.getStringArray(R.array.stats_months);
            return months[day.get(Calendar.MONTH)];
        }
        // A month: every fifth day, or every seventh, so thirty labels don't collide.
        int d = day.get(Calendar.DAY_OF_MONTH);
        if (weekly) {
            return (d - 1) % 7 == 0 ? String.valueOf(d) : "";
        }
        return d == 1 || d % 5 == 0 ? String.valueOf(d) : "";
    }

    /**
     * The chart's tooltip over a bar: "Sat 17 Oct · 4h 05m", or "October · 35h 50m" for a
     * year's month. Short, since it is drawn over the bars.
     */
    public String barTooltip(String range, String date, long seconds) {
        Calendar day = Iso8601.parseDay(date);
        if (day == null) {
            return "";
        }
        String when = AppStats.RANGE_YEAR.equals(range)
                ? format(day.getTime(), "MMMM")
                : format(day.getTime(), "EEEdMMM");
        return res.getString(R.string.stats_bar_caption, capitalize(when), duration(seconds));
    }

    /**
     * The value over a bar: "3:20" over a week's day, "42h" over a year's month, and none over
     * a month's 31 days, which have no room for it.
     */
    public String barValue(String range, long seconds) {
        if (AppStats.RANGE_YEAR.equals(range)) {
            return StatsFormat.yearBarValue(seconds, this);
        }
        return AppStats.RANGE_WEEK.equals(range) ? StatsFormat.shortDuration(seconds) : "";
    }

    /** "October": a month's name alone, for a crumb or a hint that has its year beside it. */
    public String monthName(String date) {
        Calendar day = Iso8601.parseDay(date);
        return day == null ? "" : capitalize(format(day.getTime(), "MMMM"));
    }

    /**
     * The period row's title, where the climb chip already says the year: "2026", "October",
     * "This week", "28 Sep – 4 Oct".
     */
    public String periodShortTitle(AppStats s) {
        if (AppStats.RANGE_MONTH.equals(s.getRange())) {
            return monthName(s.getFrom());
        }
        return periodTitle(s);
    }

    /**
     * The period inside a sentence: "in 2026", "in October" (or "in October 2025" for another
     * year than today's), "this week", "last week", "in the week of 28 Sep". It follows
     * "Played", "Most played" and an unlock count.
     */
    public String periodPhrase(AppStats s) {
        Calendar from = Iso8601.parseDay(s.getFrom());
        if (from == null) {
            return "";
        }
        switch (s.getRange()) {
            case AppStats.RANGE_YEAR:
                return res.getString(R.string.stats_phrase_in, String.valueOf(from.get(Calendar.YEAR)));
            case AppStats.RANGE_MONTH: {
                Calendar today = Iso8601.parseDay(s.getToday());
                boolean thisYear = today != null && today.get(Calendar.YEAR) == from.get(Calendar.YEAR);
                return res.getString(R.string.stats_phrase_in, thisYear
                        ? monthName(s.getFrom()) : capitalize(format(from.getTime(), "MMMMyyyy")));
            }
            default:
                if (s.getOffset() == 0) {
                    return res.getString(R.string.stats_phrase_this_week);
                }
                if (s.getOffset() == -1) {
                    return res.getString(R.string.stats_phrase_last_week);
                }
                return res.getString(R.string.stats_phrase_week_of, format(from.getTime(), "dMMM"));
        }
    }

    /**
     * Under "Most played": what the percentages are of. With the number of games played known
     * (only for the year under way, which the library counts), "top 5 of the 9 games played,
     * share of the year"; otherwise "share of the month".
     *
     * @param shown how many games the strip shows
     */
    public String stripSubtitle(AppStats s, int shown) {
        int share;
        switch (s.getRange()) {
            case AppStats.RANGE_YEAR:
                share = R.string.stats_strip_share_year;
                break;
            case AppStats.RANGE_MONTH:
                share = R.string.stats_strip_share_month;
                break;
            default:
                share = R.string.stats_strip_share_week;
                break;
        }
        int played = s.getLibrary().getPlayedThisYear();
        if (AppStats.RANGE_YEAR.equals(s.getRange()) && s.getOffset() == 0 && played >= shown
                && played > 0) {
            return res.getString(R.string.stats_strip_top_of, shown, played, res.getString(share));
        }
        return res.getString(share);
    }

    /**
     * The signed difference inside {@link #delta}: "+43h 30m" or "−2h", or null when the delta
     * says "Same as" or nothing at all. The screen colours this part.
     */
    public String deltaAmount(AppStats s) {
        if (!s.isComparable() || StatsFormat.isSame(s.getTotalSeconds(), s.getPreviousTotalSeconds())) {
            return null;
        }
        long diff = s.getTotalSeconds() - s.getPreviousTotalSeconds();
        return (diff > 0 ? "+" : "−") + duration(Math.abs(diff));
    }

    /**
     * A period named from its range and offset alone, for the remote's buttons, which follow the
     * period asked for before any page of it has come: "2025", "October" ("Dec 2025" in another
     * year than today's), "This week", "Last week", "Next week", or a week's Monday, "28 Sep".
     *
     * @param today the PC's play day, as a page carries it; the device's day when no page has
     *              come yet
     */
    public String periodLabel(String range, int offset, String today) {
        Calendar day = Iso8601.parseDay(today);
        if (day == null) {
            day = Calendar.getInstance();
        }
        int thisYear = day.get(Calendar.YEAR);
        switch (range) {
            case AppStats.RANGE_YEAR:
                return String.valueOf(thisYear + offset);
            case AppStats.RANGE_MONTH:
                day.set(Calendar.DAY_OF_MONTH, 1);
                day.add(Calendar.MONTH, offset);
                return day.get(Calendar.YEAR) == thisYear
                        ? capitalize(format(day.getTime(), "MMMM"))
                        : capitalize(format(day.getTime(), "MMMyyyy"));
            default:
                switch (offset) {
                    case 0:
                        return res.getString(R.string.stats_this_week);
                    case -1:
                        return res.getString(R.string.stats_last_week);
                    case 1:
                        return res.getString(R.string.stats_next_week);
                    default:
                        // The host's weeks start on Monday (see its week pages).
                        int back = (day.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7;
                        day.add(Calendar.DAY_OF_MONTH, -back + 7 * offset);
                        return format(day.getTime(), "dMMM");
                }
        }
    }

    /** "October 2026", for when a game was last played. */
    public String monthYear(String iso) {
        long millis = Iso8601.parseMillis(iso);
        return millis == 0 ? "" : format(new Date(millis), "MMMMyyyy");
    }

    /**
     * "Today at 14:30", "Yesterday at 9:05", or the date (with the year once it is that old).
     * Lower-case in the middle of a sentence; only the relative words are lowered, so an English
     * month keeps its capital.
     */
    public String unlockedLabel(String iso, boolean lower) {
        long millis = Iso8601.parseMillis(iso);
        if (millis == 0) {
            return res.getString(R.string.stats_unlocked_label);
        }
        int days = StatsFormat.daysAgo(millis, System.currentTimeMillis());
        String time = android.text.format.DateFormat.getTimeFormat(context).format(new Date(millis));
        String relative = null;
        if (days == 0) {
            relative = res.getString(R.string.stats_today_at, time);
        } else if (days == 1) {
            relative = res.getString(R.string.stats_yesterday_at, time);
        }
        if (relative != null) {
            return lower ? relative.substring(0, 1).toLowerCase(locale) + relative.substring(1) : relative;
        }
        return date(millis, days > 300);
    }

    /** "7 October", or "7 October 2025" once it is that long ago. */
    public String date(long millis, boolean withYear) {
        return format(new Date(millis), withYear ? "dMMMMyyyy" : "dMMMM");
    }

    private String format(Date date, String skeleton) {
        String pattern = DateFormat.getBestDateTimePattern(locale, skeleton);
        return new SimpleDateFormat(pattern, locale).format(date);
    }

    /** Capitalises the first letter only: «Mercoledì 7 ottobre», not «7 Ottobre». */
    public String capitalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.substring(0, 1).toUpperCase(locale) + text.substring(1);
    }
}
