package com.limelight.stats;

import com.limelight.nvstream.http.AppStats;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

// Robolectric for Android's own org.json, which builds the pages.
@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class StatsViewModelTest {

    private static final String TODAY = "2026-10-20";

    private StatsViewModel model;

    @Before
    public void setUp() {
        model = new StatsViewModel();
        model.forHost("host-1");
    }

    @After
    public void tearDown() {
        model.loader().shutdownNow();
    }

    @Test
    public void theTargetMovesAtOnceWhileTheShownPageStays() {
        AppStats week = StatsPageFixtures.week(0, TODAY, "2026-10-19", "2026-10-20",
                "2026-10-21", "2026-10-22", "2026-10-23", "2026-10-24", "2026-10-25");
        model.setShown(week);
        model.setRange(AppStats.RANGE_YEAR);
        model.setOffset(-1);

        assertEquals(AppStats.RANGE_YEAR, model.range());
        assertEquals(-1, model.offset());
        assertSame(week, model.shown());
        assertEquals(AppStats.RANGE_WEEK, model.shown().getRange());
    }

    @Test
    public void theSelectedBarDefaultsToToday() {
        model.setShown(StatsPageFixtures.year(0, TODAY, 2026));
        assertEquals(9, model.selectedBar());
        model.setShown(StatsPageFixtures.month(0, TODAY, 2026, 10));
        assertEquals(19, model.selectedBar());
    }

    @Test
    public void theSelectedBarOfAPeriodThatIsOverIsItsLast() {
        model.setShown(StatsPageFixtures.year(-1, TODAY, 2025));
        assertEquals(11, model.selectedBar());
    }

    @Test
    public void aDrillLandsOnTheMonthAndMovesTheTarget() {
        model.setRange(AppStats.RANGE_YEAR);
        model.setShown(StatsPageFixtures.year(0, TODAY, 2026));
        int request = model.startDrill(-2, 7);

        assertTrue(model.isLatest(request));
        assertNotNull(model.pendingDrill());
        assertEquals(AppStats.RANGE_YEAR, model.range());

        StatsViewModel.PendingDrill drill = model.takeDrill(request, true);
        assertNotNull(drill);
        assertEquals(7, drill.bar);
        assertNull(model.pendingDrill());
        assertEquals(AppStats.RANGE_MONTH, model.range());
        assertEquals(-2, model.offset());
    }

    @Test
    public void aFailedDrillLeavesTheYear() {
        model.setRange(AppStats.RANGE_YEAR);
        int request = model.startDrill(-2, 7);

        assertNotNull(model.takeDrill(request, false));
        assertEquals(AppStats.RANGE_YEAR, model.range());
        assertEquals(0, model.offset());
    }

    @Test
    public void aDrillsAnswerIsDroppedAfterANewerRequest() {
        model.setRange(AppStats.RANGE_YEAR);
        int drill = model.startDrill(-2, 7);
        int newer = model.nextRequest();

        assertFalse(model.isLatest(drill));
        assertTrue(model.isLatest(newer));
        assertNull(model.pendingDrill());
        assertNull(model.takeDrill(drill, true));
        assertEquals(AppStats.RANGE_YEAR, model.range());
    }

    @Test
    public void climbingPicksTheMonthOnTheYearItLandsOn() {
        model.setRange(AppStats.RANGE_MONTH);
        model.setOffset(-10);
        model.climbTo(-1, 11);

        assertEquals(AppStats.RANGE_YEAR, model.range());
        assertEquals(-1, model.offset());
        model.setShown(StatsPageFixtures.year(-1, TODAY, 2025));
        assertEquals(11, model.selectedBar());

        // Only once: the next page picks its own default.
        model.setShown(StatsPageFixtures.year(0, TODAY, 2026));
        assertEquals(9, model.selectedBar());
    }

    @Test
    public void anotherHostStartsOver() {
        model.setRange(AppStats.RANGE_YEAR);
        model.setShown(StatsPageFixtures.year(0, TODAY, 2026));
        int request = model.startDrill(-1, 8);

        assertTrue(model.forHost("host-2"));
        assertEquals(AppStats.RANGE_WEEK, model.range());
        assertNull(model.shown());
        assertEquals(-1, model.selectedBar());
        assertNull(model.pendingDrill());
        assertFalse(model.isLatest(request));
    }
}
