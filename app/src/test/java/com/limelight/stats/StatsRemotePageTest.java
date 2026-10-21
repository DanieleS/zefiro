package com.limelight.stats;

import android.content.res.Resources;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.companion.CompanionPage;
import com.limelight.main.PageFocus;
import com.limelight.nvstream.http.AppStats;
import com.limelight.ui.GamepadHintsView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The Stats section's lower half as the companion panel draws it: which stops each mode lays
 * out and in what order the d-pad walks them, the period buttons at their bounds, where the
 * d-pad stands when the page changes under it, the tabs that hide with their lists, and the
 * pad's hints for each range.
 */
// Robolectric for the hints' string resources.
@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class StatsRemotePageTest {

    private static final List<StatsRemotePage.UnlockRow> UNLOCKS = Arrays.asList(
            new StatsRemotePage.UnlockRow("Wheels Champion", "Sea of Stars", "7 October", "a"),
            new StatsRemotePage.UnlockRow("Cowpoke", "Stardew Valley", "2 October", "b"));
    private static final List<StatsRemotePage.ResumeRow> RESUME = Arrays.asList(
            new StatsRemotePage.ResumeRow("Hades", "42 h", "March 2026", "c"),
            new StatsRemotePage.ResumeRow("Celeste", "12 h", "May 2026", "d"),
            new StatsRemotePage.ResumeRow("Tunic", "9 h", "June 2026", "e"));
    private static final StatsRemotePage.Totals TOTALS = new StatsRemotePage.Totals(
            "1,284 h", "13", "games, 11 installed", "2", "9");

    private static StatsRemotePage.Builder remote(boolean previous, boolean next) {
        return new StatsRemotePage.Builder(AppStats.RANGE_YEAR)
                .period("2025", "2026", "2027", previous, next);
    }

    private static StatsRemotePage full(StatsRemotePage.Tab tab,
                                        List<StatsRemotePage.UnlockRow> unlocks,
                                        List<StatsRemotePage.ResumeRow> resume) {
        return remote(true, false)
                .lists(tab, "64", unlocks, String.valueOf(resume.size()), resume)
                .totals(TOTALS)
                .build();
    }

    // Walks DOWN from the first stop to the bottom, then the period row sideways.
    private static void assertWalk(PageFocus stops, int... expected) {
        int stop = stops.first();
        for (int i = 0; i < expected.length; i++) {
            assertEquals("step " + i, expected[i], stop);
            stop = stops.move(stop, PageFocus.Nav.DOWN);
        }
        assertEquals(PageFocus.EXIT_DOWN, stop);
    }

    // --- Stops per mode ---

    @Test
    public void full_tabsThenRowsThenRangesThenPeriod() {
        StatsRemotePage page = full(StatsRemotePage.Tab.UNLOCKS, UNLOCKS, RESUME);
        PageFocus stops = page.stops();

        assertEquals(StatsRemotePage.Mode.FULL, page.mode);
        assertEquals(StatsRemotePage.Tab.UNLOCKS, page.tab);
        assertEquals(StatsRemotePage.tabStop(StatsRemotePage.Tab.UNLOCKS), page.entryStop());
        // The tabs side by side, then one stop per unlock, then the ranges (on the year), then
        // previous (next is off at the period under way).
        assertEquals(1, stops.move(0, PageFocus.Nav.RIGHT));
        assertEquals(PageFocus.EXIT_UP, stops.move(0, PageFocus.Nav.UP));
        assertWalk(stops, 0, 100, 101, 10, 20);
        assertEquals(12, stops.move(11, PageFocus.Nav.RIGHT));
        assertEquals(PageFocus.STAY, stops.move(20, PageFocus.Nav.RIGHT));

        assertSame(UNLOCKS.get(1), page.unlockAt(StatsRemotePage.rowStop(1)));
        assertNull(page.resumeAt(StatsRemotePage.rowStop(1)));
        assertNull(page.unlockAt(StatsRemotePage.rowStop(2)));
    }

    @Test
    public void full_theOtherTabListsItsOwnRows() {
        StatsRemotePage page = full(StatsRemotePage.Tab.RESUME, UNLOCKS, RESUME);
        assertEquals(StatsRemotePage.Tab.RESUME, page.tab);
        assertEquals(3, page.rowCount());
        assertEquals(StatsRemotePage.tabStop(StatsRemotePage.Tab.RESUME), page.entryStop());
        assertSame(RESUME.get(2), page.resumeAt(StatsRemotePage.rowStop(2)));
        assertNull(page.unlockAt(StatsRemotePage.rowStop(0)));
        assertWalk(page.stops(), 0, 100, 101, 102, 10, 20);
    }

    @Test
    public void remoteOnly_rangesThenPeriod_andEntersOnTheChosenRange() {
        StatsRemotePage page = remote(true, true).build();
        assertEquals(StatsRemotePage.Mode.REMOTE_ONLY, page.mode);
        assertNull(page.tab);
        assertTrue(page.visibleTabs().isEmpty());
        assertNull(page.totals);
        assertFalse(page.saysNothing());
        assertEquals(StatsRemotePage.rangeStop(2), page.entryStop());

        PageFocus stops = page.stops();
        assertEquals(10, stops.first());
        assertEquals(PageFocus.EXIT_UP, stops.move(10, PageFocus.Nav.UP));
        assertEquals(20, stops.move(10, PageFocus.Nav.DOWN));
        // Down from Year keeps the column, clamped to the period row's last button.
        assertEquals(21, stops.move(12, PageFocus.Nav.DOWN));
        assertEquals(PageFocus.EXIT_DOWN, stops.move(21, PageFocus.Nav.DOWN));
    }

    @Test
    public void remoteOnly_untracked_keepsTheTotals() {
        StatsRemotePage page = remote(true, false).totals(TOTALS).build();
        assertEquals(StatsRemotePage.Mode.REMOTE_ONLY, page.mode);
        assertEquals(TOTALS, page.totals);
        assertEquals(0, page.rowCount());
    }

    // --- Period buttons ---

    @Test
    public void period_buttonsDisabledAtTheirBounds() {
        PageFocus latest = remote(true, false).build().stops();
        assertTrue(latest.isEnabledStop(StatsRemotePage.STOP_PREVIOUS));
        assertFalse(latest.isEnabledStop(StatsRemotePage.STOP_NEXT));
        assertEquals(StatsRemotePage.STOP_PREVIOUS, latest.move(12, PageFocus.Nav.DOWN));

        PageFocus oldest = remote(false, true).build().stops();
        assertFalse(oldest.isEnabledStop(StatsRemotePage.STOP_PREVIOUS));
        assertTrue(oldest.isEnabledStop(StatsRemotePage.STOP_NEXT));
        assertEquals(StatsRemotePage.STOP_NEXT, oldest.move(10, PageFocus.Nav.DOWN));

        PageFocus middle = remote(true, true).build().stops();
        assertEquals(StatsRemotePage.STOP_NEXT,
                middle.move(StatsRemotePage.STOP_PREVIOUS, PageFocus.Nav.RIGHT));
    }

    @Test
    public void settle_aButtonThatWentOffHandsOverToTheOther() {
        // Next pressed onto the period under way: next goes off, previous takes the d-pad.
        StatsRemotePage page = remote(true, false).build();
        assertEquals(StatsRemotePage.STOP_PREVIOUS, page.settle(StatsRemotePage.STOP_NEXT));
        assertEquals(StatsRemotePage.STOP_PREVIOUS, page.settle(StatsRemotePage.STOP_PREVIOUS));
        // Neither: the chosen range.
        assertEquals(StatsRemotePage.rangeStop(2),
                remote(false, false).build().settle(StatsRemotePage.STOP_NEXT));
    }

    @Test
    public void settle_rowsAndTabsThatWent() {
        StatsRemotePage shorter = full(StatsRemotePage.Tab.UNLOCKS, UNLOCKS, RESUME);
        assertEquals(StatsRemotePage.rowStop(1), shorter.settle(StatsRemotePage.rowStop(5)));
        assertEquals(StatsRemotePage.rowStop(0), shorter.settle(StatsRemotePage.rowStop(0)));
        assertEquals(CompanionPage.NO_STOP, shorter.settle(CompanionPage.NO_STOP));

        // No list any more (the PC stopped answering): the rows and tabs go to the range.
        StatsRemotePage none = remote(true, false).build();
        assertEquals(StatsRemotePage.rangeStop(2), none.settle(StatsRemotePage.rowStop(0)));
        assertEquals(StatsRemotePage.rangeStop(2),
                none.settle(StatsRemotePage.tabStop(StatsRemotePage.Tab.RESUME)));
    }

    // --- Tabs ---

    @Test
    public void tabs_aTabHidesWithItsList() {
        List<StatsRemotePage.UnlockRow> noUnlocks = Collections.emptyList();
        StatsRemotePage page = full(StatsRemotePage.Tab.UNLOCKS, noUnlocks, RESUME);
        // Unlocks asked for, but it has nothing: Pick up again is the one drawn.
        assertEquals(Collections.singletonList(StatsRemotePage.Tab.RESUME), page.visibleTabs());
        assertEquals(StatsRemotePage.Tab.RESUME, page.tab);
        assertFalse(page.stops().isEnabledStop(
                StatsRemotePage.tabStop(StatsRemotePage.Tab.UNLOCKS)));
        assertEquals(StatsRemotePage.tabStop(StatsRemotePage.Tab.RESUME), page.entryStop());
        assertEquals(StatsRemotePage.tabStop(StatsRemotePage.Tab.RESUME),
                page.settle(StatsRemotePage.tabStop(StatsRemotePage.Tab.UNLOCKS)));

        List<StatsRemotePage.ResumeRow> noResume = Collections.emptyList();
        StatsRemotePage onlyUnlocks = full(StatsRemotePage.Tab.RESUME, UNLOCKS, noResume);
        assertEquals(Collections.singletonList(StatsRemotePage.Tab.UNLOCKS),
                onlyUnlocks.visibleTabs());
        assertEquals(StatsRemotePage.Tab.UNLOCKS, onlyUnlocks.tab);
    }

    @Test
    public void tabs_bothEmpty_noTabsAndTheRangeIsTheEntry() {
        StatsRemotePage page = full(StatsRemotePage.Tab.UNLOCKS,
                Collections.<StatsRemotePage.UnlockRow>emptyList(),
                Collections.<StatsRemotePage.ResumeRow>emptyList());
        assertNull(page.tab);
        assertTrue(page.visibleTabs().isEmpty());
        assertTrue(page.saysNothing());
        assertEquals(StatsRemotePage.rangeStop(2), page.entryStop());
        assertEquals(10, page.stops().first());
    }

    // --- Values ---

    @Test
    public void withFocus_sameContentOtherStop() {
        StatsRemotePage page = full(StatsRemotePage.Tab.UNLOCKS, UNLOCKS, RESUME);
        StatsRemotePage again = full(StatsRemotePage.Tab.UNLOCKS, UNLOCKS, RESUME);
        assertEquals(page, again);
        assertEquals(page.hashCode(), again.hashCode());

        StatsRemotePage focused = page.withFocus(StatsRemotePage.rowStop(1));
        assertEquals(StatsRemotePage.rowStop(1), focused.focusedStop());
        assertNotEquals(page, focused);
        assertEquals(page, focused.withFocus(CompanionPage.NO_STOP));
        assertTrue(page.sameRows(focused));

        assertNotEquals(page, full(StatsRemotePage.Tab.RESUME, UNLOCKS, RESUME));
        assertFalse(page.sameRows(full(StatsRemotePage.Tab.RESUME, UNLOCKS, RESUME)));
    }

    // --- Hints ---

    private static Resources res() {
        return ApplicationProvider.getApplicationContext().getResources();
    }

    private static String text(GamepadHintsView.Hint[] hints) {
        StringBuilder out = new StringBuilder();
        for (GamepadHintsView.Hint hint : hints) {
            if (out.length() > 0) {
                out.append(" · ");
            }
            out.append(String.join(" ", hint.keys())).append(' ').append(hint.label);
        }
        return out.toString();
    }

    @Test
    public void hints_yearWithAMonthToOpen() {
        assertEquals("A Open October · LB RB Year · X Y Range",
                text(StatsRemotePage.hints(res(), AppStats.RANGE_YEAR, "October")));
    }

    @Test
    public void hints_yearWithNothingToOpen() {
        assertEquals("LB RB Year · X Y Range",
                text(StatsRemotePage.hints(res(), AppStats.RANGE_YEAR, null)));
    }

    @Test
    public void hints_monthClimbsWithB() {
        assertEquals("B Up · LB RB Month · X Y Range",
                text(StatsRemotePage.hints(res(), AppStats.RANGE_MONTH, null)));
    }

    @Test
    public void hints_week() {
        assertEquals("LB RB Week · X Y Range",
                text(StatsRemotePage.hints(res(), AppStats.RANGE_WEEK, null)));
    }

    @Test
    public void hints_travelInThePage() {
        StatsRemotePage a = remote(true, false)
                .hints(StatsRemotePage.hints(res(), AppStats.RANGE_YEAR, "October")).build();
        StatsRemotePage b = remote(true, false)
                .hints(StatsRemotePage.hints(res(), AppStats.RANGE_YEAR, null)).build();
        // "Open October" coming or going is a new value, so the panel redraws its hints.
        assertNotEquals(a, b);
        assertEquals(3, a.hints.size());
    }
}
