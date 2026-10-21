package com.limelight.stats;

import java.text.NumberFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * The arithmetic behind the statistics' wording, kept apart from the screens so it can be tested
 * on its own: how a duration is rounded, what a bar says above it, which rarity band an
 * achievement falls in, how far back a day is. The words themselves come from resources, through
 * {@link Words}, so English and Italian share one set of rules — the same ones CouchPilot uses.
 */
public final class StatsFormat {
    private StatsFormat() {
    }

    /** The two duration shapes, in the reader's language: "45 min", and "3h 05m" or "3h". */
    public interface Words {
        String minutes(long minutes);

        /** {@code minutes} is 0 for a round number of hours. */
        String hours(long hours, long minutes);
    }

    /** A length of time to the nearest minute: minutes under an hour, hours and minutes above. */
    public static String duration(long seconds, Words words) {
        return durationMinutes(Math.round(Math.max(0, seconds) / 60.0), words);
    }

    /**
     * As {@link #duration}, for a length already counted in whole minutes: a running clock (the
     * stream's session length), which has to tick over on the minute rather than round to it.
     */
    public static String durationMinutes(long minutes, Words words) {
        minutes = Math.max(0, minutes);
        if (minutes < 60) {
            return words.minutes(minutes);
        }
        return words.hours(minutes / 60, minutes % 60);
    }

    /**
     * Above a week's bars, where there is room for little: "3:20", "45′", and nothing at all for
     * a day off.
     */
    public static String shortDuration(long seconds) {
        long minutes = Math.round(Math.max(0, seconds) / 60.0);
        if (minutes == 0) {
            return "";
        }
        if (minutes < 60) {
            return minutes + "′";
        }
        return (minutes / 60) + ":" + (minutes % 60 < 10 ? "0" : "") + (minutes % 60);
    }

    /** Whole hours, for the library's lifetime totals, where minutes would be noise. */
    public static long roundHours(long seconds) {
        return Math.round(Math.max(0, seconds) / 3600.0);
    }

    /** SuccessStory's own bands, for the few achievements that deserve a word. */
    public enum Rarity {
        ULTRA_RARE,
        RARE,
        UNCOMMON
    }

    /** The band for a share of players, or null when it is common or nobody knows. */
    public static Rarity rarity(Double percent) {
        if (percent == null) {
            return null;
        }
        if (percent < 2) {
            return Rarity.ULTRA_RARE;
        }
        if (percent < 10) {
            return Rarity.RARE;
        }
        if (percent < 30) {
            return Rarity.UNCOMMON;
        }
        return null;
    }

    /** "0.4", "7.5", "42": one decimal below 10%, where it still tells things apart. */
    public static String percent(double percent, Locale locale) {
        NumberFormat format = NumberFormat.getNumberInstance(locale);
        if (percent < 10) {
            format.setMaximumFractionDigits(1);
            format.setMinimumFractionDigits(0);
            return format.format(percent);
        }
        format.setMaximumFractionDigits(0);
        return format.format(Math.round(percent));
    }

    /**
     * How many calendar days ago an instant was, in the device's zone: 0 for today, 1 for
     * yesterday. Negative for the future, which a host clock running ahead can produce.
     */
    public static int daysAgo(long millis, long nowMillis) {
        return (int) Math.round((startOfDay(nowMillis) - startOfDay(millis)) / 86_400_000.0);
    }

    private static long startOfDay(long millis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    /** Below a minute either way, two periods count as the same. */
    public static boolean isSame(long seconds, long previousSeconds) {
        return Math.abs(seconds - previousSeconds) < 60;
    }

    /**
     * The next period range in the cycle week, month, year (step 1) or back (step -1), as the
     * X and Y buttons move through them.
     */
    public static String cycleRange(String range, int step) {
        String[] ranges = {"week", "month", "year"};
        int index = 0;
        for (int i = 0; i < ranges.length; i++) {
            if (ranges[i].equals(range)) {
                index = i;
            }
        }
        return ranges[Math.floorMod(index + step, ranges.length)];
    }

    /** One step along the periods, never into the future nor past what the host will answer. */
    public static int stepOffset(int offset, int step, int minOffset) {
        return Math.max(minOffset, Math.min(0, offset + step));
    }

    /**
     * A game's share of the period's play, as the strip prints it: a whole percent, rounded
     * half up, 0 when nothing was played, never above 100 (the host's per-game seconds and its
     * total are counted separately, and a session across midnight can tip one over the other).
     */
    public static int sharePercent(long seconds, long totalSeconds) {
        if (totalSeconds <= 0 || seconds <= 0) {
            return 0;
        }
        long percent = Math.round(seconds * 100.0 / totalSeconds);
        return (int) Math.min(100, percent);
    }

    /**
     * Whether a game's time in a period is worth a place among its most played: at least the
     * "1 min" {@link #duration} would print, never a "0 min" card.
     */
    public static boolean countsAsPlayed(long seconds) {
        return Math.round(Math.max(0, seconds) / 60.0) > 0;
    }

    /**
     * The chart's height for the room the window leaves it, in pixels.
     *
     * <p>With the companion panel showing, the top screen holds only the summary, the chart and
     * the strip, so the chart takes whatever height the strip leaves, up to {@code max}. Inline,
     * the lower half follows on the same scroll, so the chart keeps its {@code standard} height
     * and only gives way in a short window, so the strip still shows under it. Never below
     * {@code min}, where the bars would stop reading; the page scrolls from there.
     *
     * @param room the scroll's visible height, less everything from its top to the end of the
     *             strip other than the chart
     * @param split whether the companion panel is showing
     */
    public static int chartHeight(int room, boolean split, int min, int standard, int max) {
        int upper = split ? max : standard;
        return Math.max(min, Math.min(upper, room));
    }

    /**
     * Above a year's bars: whole hours ("42h" as {@code hours}), or the short form below half
     * an hour, so a month with a little play is not labelled "0h".
     *
     * @return the label, or the empty string for a month without play
     */
    public static String yearBarValue(long seconds, Words words) {
        long hours = roundHours(seconds);
        if (hours == 0) {
            return shortDuration(seconds);
        }
        return words.hours(hours, 0);
    }
}
