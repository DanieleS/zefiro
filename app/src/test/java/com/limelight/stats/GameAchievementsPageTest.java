package com.limelight.stats;

import com.limelight.companion.CompanionPage;
import com.limelight.library.GameActionsSheet;
import com.limelight.main.PageFocus;
import com.limelight.nvstream.http.GameAchievements;

import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static com.limelight.nvstream.http.TestGameStats.achievements;
import static com.limelight.nvstream.http.TestGameStats.json;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * A game page's lower half as the companion panel draws it: which stops each filter lays out,
 * that a value differing only by its stop is the same page otherwise, and where the d-pad stands
 * after the filter changes.
 */
// Robolectric for org.json, which the plain unit-test android.jar only stubs.
@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class GameAchievementsPageTest {
    private static final long NOW = 1_791_460_800_000L;

    // Two unlocked, three locked (one of them hidden), in the host's order.
    private static final GameAchievements DATA = achievements(json(
            "total", 5, "unlocked", 2,
            "items", new JSONArray(Arrays.asList(
                    json("id", "u1", "name", "One", "unlocked", true,
                            "unlocked_at", "2026-10-08T11:35:00Z"),
                    json("id", "u2", "name", "Two", "unlocked", true,
                            "unlocked_at", "2026-10-02T10:00:00Z"),
                    json("id", "l1", "name", "Three", "unlocked", false),
                    json("id", "l2", "name", "Four", "unlocked", false, "hidden", true),
                    json("id", "l3", "name", "Five", "unlocked", false)))));

    private static GameAchievementsPage page(AchievementFilter.Filter filter) {
        return GameAchievementsPage.list("Sea of Stars", DATA, filter,
                Collections.<String>emptySet(), null, NOW);
    }

    @Test
    public void stops_filterRowThenOneRowPerAchievementTheFilterKeeps() {
        for (AchievementFilter.Filter filter : AchievementFilter.Filter.values()) {
            GameAchievementsPage page = page(filter);
            PageFocus stops = page.stops();
            int kept = AchievementFilter.apply(DATA.getItems(), filter).size();
            assertEquals(kept, page.shown().size());

            // The segments are one row, side by side.
            assertEquals(0, stops.first());
            assertEquals(1, stops.move(0, PageFocus.Nav.RIGHT));
            assertEquals(2, stops.move(1, PageFocus.Nav.RIGHT));
            assertEquals(PageFocus.STAY, stops.move(2, PageFocus.Nav.RIGHT));
            assertEquals(PageFocus.EXIT_UP, stops.move(1, PageFocus.Nav.UP));

            // Then the rows, one stop each, from 100, down to the last and no further.
            int stop = stops.move(2, PageFocus.Nav.DOWN);
            for (int i = 0; i < kept; i++) {
                assertEquals(filter + " row " + i, GameAchievementsPage.rowStop(i), stop);
                assertSame(page.shown().get(i), page.rowAt(stop));
                stop = stops.move(stop, PageFocus.Nav.DOWN);
            }
            assertEquals(PageFocus.EXIT_DOWN, stop);
            assertFalse(stops.isEnabledStop(GameAchievementsPage.rowStop(kept)));
            assertNull(page.rowAt(GameAchievementsPage.rowStop(kept)));
        }
        // The rows under Locked are the locked ones.
        assertEquals("l1", page(AchievementFilter.Filter.LOCKED)
                .rowAt(GameAchievementsPage.rowStop(0)).getId());
    }

    @Test
    public void entryStop_isTheFirstRowOrTheChosenSegmentWhenTheFilterKeepsNone() {
        assertEquals(GameAchievementsPage.STOP_ROW, page(AchievementFilter.Filter.ALL).entryStop());

        GameAchievements allDone = achievements(json("total", 1, "unlocked", 1,
                "items", new JSONArray(Collections.singletonList(
                        json("id", "u1", "name", "One", "unlocked", true)))));
        GameAchievementsPage none = GameAchievementsPage.list("Hades", allDone,
                AchievementFilter.Filter.LOCKED, Collections.<String>emptySet(), null, NOW);
        assertTrue(none.shown().isEmpty());
        assertEquals(GameAchievementsPage.filterStop(AchievementFilter.Filter.LOCKED),
                none.entryStop());
    }

    @Test
    public void statusPages_haveOneStop() {
        for (GameAchievementsPage page : Arrays.asList(GameAchievementsPage.loading("Hades"),
                GameAchievementsPage.failed("Hades"))) {
            PageFocus stops = page.stops();
            assertEquals(GameAchievementsPage.STOP_STATUS, stops.first());
            assertEquals(PageFocus.EXIT_DOWN,
                    stops.move(GameAchievementsPage.STOP_STATUS, PageFocus.Nav.DOWN));
            assertEquals(GameAchievementsPage.STOP_STATUS, page.entryStop());
        }
        assertNotEquals(GameAchievementsPage.loading("Hades"), GameAchievementsPage.failed("Hades"));
    }

    @Test
    public void withFocus_differsOnlyByItsStop() {
        GameAchievementsPage page = page(AchievementFilter.Filter.ALL);
        CompanionPage focused = page.withFocus(101);

        assertEquals(101, focused.focusedStop());
        assertNotEquals(page, focused);
        assertEquals(page, focused.withFocus(CompanionPage.NO_STOP));
        assertEquals(focused, page.withFocus(101));
        assertEquals(focused.hashCode(), page.withFocus(101).hashCode());

        // The revealed ids are copied: the set the page was given changing later is not the
        // page changing.
        Set<String> revealed = new HashSet<>();
        GameAchievementsPage before = GameAchievementsPage.list("Sea of Stars", DATA,
                AchievementFilter.Filter.ALL, revealed, null, NOW);
        revealed.add("l2");
        assertEquals(page, before);
        assertNotEquals(page, GameAchievementsPage.list("Sea of Stars", DATA,
                AchievementFilter.Filter.ALL, revealed, null, NOW));

        // Another answer, another filter or another moment for NEW is another page.
        assertNotEquals(page, page(AchievementFilter.Filter.LOCKED));
        assertNotEquals(page, GameAchievementsPage.list("Sea of Stars", DATA,
                AchievementFilter.Filter.ALL, Collections.<String>emptySet(), null, NOW + 1));
    }

    @Test
    public void aFilterChangeKeepsTheFocusOnTheFilterRow() {
        GameAchievementsPage onSegment = page(AchievementFilter.Filter.ALL)
                .withFocus(GameAchievementsPage.filterStop(AchievementFilter.Filter.LOCKED));
        GameAchievementsPage locked = onSegment.withFilter(AchievementFilter.Filter.LOCKED);
        assertEquals(AchievementFilter.Filter.LOCKED, locked.filter);
        assertEquals(GameAchievementsPage.filterStop(AchievementFilter.Filter.LOCKED),
                locked.focusedStop());

        // On a row (a segment tapped on the panel meanwhile): up to the chosen segment, since
        // the row now names another achievement.
        GameAchievementsPage onRow = page(AchievementFilter.Filter.ALL)
                .withFocus(GameAchievementsPage.rowStop(3));
        assertEquals(GameAchievementsPage.filterStop(AchievementFilter.Filter.UNLOCKED),
                onRow.withFilter(AchievementFilter.Filter.UNLOCKED).focusedStop());

        // Up top: stays up top.
        assertEquals(CompanionPage.NO_STOP, page(AchievementFilter.Filter.ALL)
                .withFilter(AchievementFilter.Filter.UNLOCKED).focusedStop());
    }

    @Test
    public void actionsPage_oneStopPerActionNoneWithoutActions() {
        GameActionsPage page = new GameActionsPage("Turing Complete",
                Arrays.asList(GameActionsSheet.Item.HIDE, GameActionsSheet.Item.DETAILS),
                Arrays.asList("Hide", "View details"), false);
        PageFocus stops = page.stops();
        assertEquals(0, page.entryStop());
        assertEquals(1, stops.move(0, PageFocus.Nav.DOWN));
        assertEquals(PageFocus.EXIT_DOWN, stops.move(1, PageFocus.Nav.DOWN));
        assertEquals(GameActionsSheet.Item.DETAILS, page.itemAt(1));
        assertNull(page.itemAt(2));
        assertEquals(page, page.withFocus(1).withFocus(CompanionPage.NO_STOP));
        assertNotEquals(page, new GameActionsPage("Turing Complete",
                Arrays.asList(GameActionsSheet.Item.HIDE, GameActionsSheet.Item.DETAILS),
                Arrays.asList("Hide", "View details"), true));

        GameActionsPage empty = new GameActionsPage("Turing Complete",
                Collections.<GameActionsSheet.Item>emptyList(), Collections.<String>emptyList(),
                false);
        assertEquals(PageFocus.STAY, empty.entryStop());
        assertEquals(PageFocus.STAY, empty.stops().first());
    }
}
