package com.limelight.stats;

import com.limelight.nvstream.http.AppStats;
import com.limelight.nvstream.http.TestGameStats;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Pages of {@code /appstats} as the host sends them, for the Stats section's tests: a year of
 * months, a month of days, a week. Built through Android's org.json, so the tests using them run
 * under Robolectric.
 */
final class StatsPageFixtures {
    private StatsPageFixtures() {
    }

    /** A year's page: twelve monthly buckets, the first of each month. */
    static AppStats year(int offset, String today, int year) {
        return year(offset, today, year, "2020-01-01");
    }

    /** A year's page whose GameActivity sessions start on {@code trackingSince}. */
    static AppStats year(int offset, String today, int year, String trackingSince) {
        String[] dates = new String[12];
        for (int m = 0; m < 12; m++) {
            dates[m] = String.format(Locale.US, "%04d-%02d-01", year, m + 1);
        }
        return page(AppStats.RANGE_YEAR, offset, today, dates[0], year + 1 + "-01-01", dates,
                trackingSince);
    }

    /** A month's page: one bucket a day. */
    static AppStats month(int offset, String today, int year, int month) {
        return month(offset, today, year, month, "2020-01-01");
    }

    /** A month's page whose GameActivity sessions start on {@code trackingSince}. */
    static AppStats month(int offset, String today, int year, int month, String trackingSince) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        c.clear();
        c.set(year, month - 1, 1);
        int days = c.getActualMaximum(Calendar.DAY_OF_MONTH);
        String[] dates = new String[days];
        for (int d = 0; d < days; d++) {
            dates[d] = String.format(Locale.US, "%04d-%02d-%02d", year, month, d + 1);
        }
        c.add(Calendar.MONTH, 1);
        String to = String.format(Locale.US, "%04d-%02d-01", c.get(Calendar.YEAR),
                c.get(Calendar.MONTH) + 1);
        return page(AppStats.RANGE_MONTH, offset, today, dates[0], to, dates, trackingSince);
    }

    /** A week's page from its Monday, seven days. */
    static AppStats week(int offset, String today, String... days) {
        return page(AppStats.RANGE_WEEK, offset, today, days[0], days[days.length - 1], days,
                "2020-01-01");
    }

    private static AppStats page(String range, int offset, String today, String from, String to,
                                 String[] dates, String trackingSince) {
        try {
            JSONArray buckets = new JSONArray();
            for (String date : dates) {
                buckets.put(new JSONObject().put("date", date).put("seconds", 3600));
            }
            JSONObject json = new JSONObject()
                    .put("range", range)
                    .put("offset", offset)
                    .put("from", from)
                    .put("to", to)
                    .put("today", today)
                    .put("activity", true)
                    .put("tracking_since", trackingSince)
                    .put("buckets", buckets);
            return TestGameStats.appStats(json);
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
    }
}
