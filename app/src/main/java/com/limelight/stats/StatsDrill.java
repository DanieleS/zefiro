package com.limelight.stats;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.limelight.nvstream.http.AppStats;

import java.util.List;

/**
 * The chart's way down from a year into one of its months, and back up, in {@code /appstats}
 * offsets. Pure, so the arithmetic is tested on its own.
 *
 * <p>The host has no "this month of that year" request: a period is a range and an offset from
 * the period under way, and a year's offset counts years while a month's counts months. Both are
 * relative to the PC's own play day ({@link AppStats#getToday()}), never the device's clock, so
 * the month a bar stands for and the month the host answers with are the same one:
 *
 * <pre>
 *   today 2026-10-20     year offset  0, October (9)   ──▶ month offset   0
 *                        year offset  0, March (2)     ──▶ month offset  -7
 *                        year offset -1, December (11) ──▶ month offset  -10
 *   yearOf(-10)          ──▶ year offset -1, December        (the climb back up)
 * </pre>
 *
 * A month is the deepest level: the host has no day range, so a day's bar only says what it is.
 * Months after today's, and days after today, are not there yet: their bars are drawn dim and
 * cannot be picked ({@link #firstFutureBar}). An unreadable {@code today} counts as the last
 * month of the year, so nothing is taken for the future and offsets stay at or below zero.
 */
public final class StatsDrill {
    private StatsDrill() {
    }

    /** The month offset of a year page's month, never further back than the host answers. */
    public static int monthOffset(int yearOffset, int monthIndex, @Nullable String today) {
        long offset = (long) yearOffset * 12 + monthIndex - month(today);
        return (int) Math.max(AppStats.MIN_OFFSET, offset);
    }

    /** Whether that month of that year is still to come. */
    public static boolean isFuture(int yearOffset, int monthIndex, @Nullable String today) {
        return yearOffset > 0 || (yearOffset == 0 && monthIndex > month(today));
    }

    /**
     * The year a month offset falls in, and the month in it: {@code {yearOffset, monthIndex}},
     * monthIndex 0 for January.
     */
    @NonNull
    public static int[] yearOf(int monthOffset, @Nullable String today) {
        int absolute = month(today) + monthOffset;
        return new int[]{Math.floorDiv(absolute, 12), Math.floorMod(absolute, 12)};
    }

    /**
     * The index of the first bar after today: a month after today's on a year page, a day after
     * today on a week's or a month's. The number of bars when there is none, as for any period
     * that is over.
     */
    public static int firstFutureBar(@NonNull AppStats page) {
        List<AppStats.Bucket> buckets = page.getBuckets();
        String today = key(page.getRange(), page.getToday());
        if (today == null) {
            return buckets.size();
        }
        for (int i = 0; i < buckets.size(); i++) {
            String date = key(page.getRange(), buckets.get(i).getDate());
            if (date != null && date.compareTo(today) > 0) {
                return i;
            }
        }
        return buckets.size();
    }

    /**
     * The index of the first bar GameActivity has sessions for: the month of its first session
     * on a year page, the day on a week's or a month's. The bars before it are not "nothing
     * played" but "nothing known", and are drawn as such. 0 when every bar is recorded or the
     * first session is unknown; the number of bars when none is.
     */
    public static int firstTrackedBar(@NonNull AppStats page) {
        List<AppStats.Bucket> buckets = page.getBuckets();
        String since = key(page.getRange(), page.getTrackingSince());
        if (since == null) {
            return 0;
        }
        for (int i = 0; i < buckets.size(); i++) {
            String date = key(page.getRange(), buckets.get(i).getDate());
            if (date != null && date.compareTo(since) >= 0) {
                return i;
            }
        }
        return buckets.size();
    }

    /** Today's bar (this month's on a year page), or -1 when the page does not hold today. */
    public static int todayBar(@NonNull AppStats page) {
        List<AppStats.Bucket> buckets = page.getBuckets();
        String today = key(page.getRange(), page.getToday());
        if (today == null) {
            return -1;
        }
        for (int i = 0; i < buckets.size(); i++) {
            if (today.equals(key(page.getRange(), buckets.get(i).getDate()))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The bar picked when a page is first shown: today's, else the last one that has happened,
     * which for a period that is over is its last. -1 for a page without bars.
     */
    public static int defaultBar(@NonNull AppStats page) {
        int today = todayBar(page);
        if (today >= 0) {
            return today;
        }
        return firstFutureBar(page) - 1;
    }

    /**
     * The calendar year a year offset stands for, counted from the PC's today ("2026" for 0 in
     * 2026), for the climb chip; -1 when today cannot be read.
     */
    public static int calendarYear(int yearOffset, @Nullable String today) {
        if (today == null || today.length() < 4) {
            return -1;
        }
        try {
            return Integer.parseInt(today.substring(0, 4)) + yearOffset;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The month (0 for January) of a {@code yyyy-MM-dd} day, or -1 when it cannot be read. */
    public static int monthIndex(@Nullable String date) {
        if (date == null || date.length() < 7) {
            return -1;
        }
        try {
            int month = Integer.parseInt(date.substring(5, 7)) - 1;
            return month >= 0 && month < 12 ? month : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int month(@Nullable String today) {
        int month = monthIndex(today);
        return month >= 0 ? month : 11;
    }

    // What a bar is compared by: the month (yyyy-MM) on a year page, the day otherwise.
    @Nullable
    private static String key(String range, @Nullable String date) {
        if (date == null || date.length() < 10) {
            return null;
        }
        return AppStats.RANGE_YEAR.equals(range) ? date.substring(0, 7) : date;
    }
}
