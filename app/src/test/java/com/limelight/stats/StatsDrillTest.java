package com.limelight.stats;

import com.limelight.nvstream.http.AppStats;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// Robolectric for Android's own org.json, which builds the pages.
@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class StatsDrillTest {

    private static final String TODAY = "2026-10-20";

    @Test
    public void theCurrentMonthIsOffsetZero() {
        assertEquals(0, StatsDrill.monthOffset(0, 9, TODAY));
    }

    @Test
    public void earlierMonthsCountBackFromToday() {
        assertEquals(-7, StatsDrill.monthOffset(0, 2, TODAY));
        assertEquals(-9, StatsDrill.monthOffset(0, 0, TODAY));
    }

    @Test
    public void offsetsCrossTheYearBoundary() {
        // December of last year is ten months before this October.
        assertEquals(-10, StatsDrill.monthOffset(-1, 11, TODAY));
        assertEquals(-21, StatsDrill.monthOffset(-1, 0, TODAY));
        // In January, last December is the month before.
        assertEquals(-1, StatsDrill.monthOffset(-1, 11, "2027-01-03"));
    }

    @Test
    public void offsetsStopWhereTheHostDoes() {
        assertEquals(AppStats.MIN_OFFSET, StatsDrill.monthOffset(-100, 0, TODAY));
    }

    @Test
    public void monthsAfterTodaysAreTheFuture() {
        assertFalse(StatsDrill.isFuture(0, 9, TODAY));
        assertTrue(StatsDrill.isFuture(0, 10, TODAY));
        assertTrue(StatsDrill.isFuture(0, 11, TODAY));
        assertFalse(StatsDrill.isFuture(-1, 11, TODAY));
        assertTrue(StatsDrill.isFuture(1, 0, TODAY));
    }

    @Test
    public void yearOfClimbsBackToTheMonthsYear() {
        assertArrayEquals(new int[]{0, 9}, StatsDrill.yearOf(0, TODAY));
        assertArrayEquals(new int[]{0, 0}, StatsDrill.yearOf(-9, TODAY));
        assertArrayEquals(new int[]{-1, 11}, StatsDrill.yearOf(-10, TODAY));
        assertArrayEquals(new int[]{-2, 9}, StatsDrill.yearOf(-24, TODAY));
    }

    @Test
    public void yearOfRoundTripsMonthOffset() {
        for (int year = -3; year <= 0; year++) {
            for (int month = 0; month < 12; month++) {
                int offset = StatsDrill.monthOffset(year, month, TODAY);
                assertArrayEquals(new int[]{year, month}, StatsDrill.yearOf(offset, TODAY));
            }
        }
    }

    @Test
    public void anUnreadableTodayLeavesNothingInTheFuture() {
        assertFalse(StatsDrill.isFuture(0, 11, ""));
        assertEquals(0, StatsDrill.monthOffset(0, 11, null));
    }

    @Test
    public void calendarYearCountsFromToday() {
        assertEquals(2026, StatsDrill.calendarYear(0, TODAY));
        assertEquals(2025, StatsDrill.calendarYear(-1, TODAY));
        assertEquals(-1, StatsDrill.calendarYear(0, ""));
    }

    @Test
    public void firstFutureBarOfTheCurrentYearIsNextMonth() {
        assertEquals(10, StatsDrill.firstFutureBar(StatsPageFixtures.year(0, TODAY, 2026)));
    }

    @Test
    public void aYearThatIsOverHasNoFutureBar() {
        assertEquals(12, StatsDrill.firstFutureBar(StatsPageFixtures.year(-1, TODAY, 2025)));
    }

    @Test
    public void firstFutureBarOfTheCurrentMonthIsTomorrow() {
        assertEquals(20, StatsDrill.firstFutureBar(StatsPageFixtures.month(0, TODAY, 2026, 10)));
    }

    @Test
    public void theDefaultBarIsTodaysElseTheLastThatHappened() {
        assertEquals(9, StatsDrill.defaultBar(StatsPageFixtures.year(0, TODAY, 2026)));
        assertEquals(11, StatsDrill.defaultBar(StatsPageFixtures.year(-1, TODAY, 2025)));
        assertEquals(19, StatsDrill.defaultBar(StatsPageFixtures.month(0, TODAY, 2026, 10)));
        assertEquals(29, StatsDrill.defaultBar(StatsPageFixtures.month(-1, TODAY, 2026, 9)));
    }
}
