package com.limelight.main;

import com.limelight.main.PageFocus.Nav;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The virtual focus's map: a filter row of three segments over a list, as a game's achievements
 * are laid out on the panel.
 */
public class PageFocusTest {

    // [0 1 2]
    // [100]
    // [101]
    // [20 21]
    private static final PageFocus ACHIEVEMENTS = new PageFocus(Arrays.asList(
            new int[]{0, 1, 2}, new int[]{100}, new int[]{101}, new int[]{20, 21}));

    @Test
    public void downKeepsTheColumnAndClampsIt() {
        // From the third segment, the single-column row below.
        assertEquals(100, ACHIEVEMENTS.move(2, Nav.DOWN));
        assertEquals(101, ACHIEVEMENTS.move(100, Nav.DOWN));
        // Column 0 stays column 0 on a wider row.
        assertEquals(20, ACHIEVEMENTS.move(101, Nav.DOWN));
    }

    @Test
    public void upKeepsTheColumnWhereTheRowAboveHasIt() {
        PageFocus grid = new PageFocus(Arrays.asList(new int[]{10, 11, 12}, new int[]{20, 21, 22}));
        assertEquals(11, grid.move(21, Nav.UP));
        assertEquals(22, grid.move(12, Nav.DOWN));
        // Narrower above: clamped to its last column.
        PageFocus narrow = new PageFocus(Arrays.asList(new int[]{10}, new int[]{20, 21, 22}));
        assertEquals(10, narrow.move(22, Nav.UP));
    }

    @Test
    public void offTheFirstRowExitsUpAndOffTheLastExitsDown() {
        assertEquals(PageFocus.EXIT_UP, ACHIEVEMENTS.move(1, Nav.UP));
        assertEquals(PageFocus.EXIT_DOWN, ACHIEVEMENTS.move(21, Nav.DOWN));
    }

    @Test
    public void sidewaysStaysWithinTheRowAndStaysPutAtItsEnds() {
        assertEquals(1, ACHIEVEMENTS.move(0, Nav.RIGHT));
        assertEquals(0, ACHIEVEMENTS.move(1, Nav.LEFT));
        assertEquals(PageFocus.STAY, ACHIEVEMENTS.move(0, Nav.LEFT));
        assertEquals(PageFocus.STAY, ACHIEVEMENTS.move(2, Nav.RIGHT));
        assertEquals(PageFocus.STAY, ACHIEVEMENTS.move(100, Nav.RIGHT));
    }

    @Test
    public void disabledStopsAreSkipped() {
        // "Next" disabled at the latest period; the middle segment disabled too.
        PageFocus remote = new PageFocus(Arrays.asList(
                new int[]{10, 11, 12}, new int[]{20, 21}), 11, 21);
        assertEquals(12, remote.move(10, Nav.RIGHT));
        assertEquals(10, remote.move(12, Nav.LEFT));
        assertEquals(PageFocus.STAY, remote.move(20, Nav.RIGHT));
        // Down from the right-hand segment: its column is disabled below, so the nearest one.
        assertEquals(20, remote.move(12, Nav.DOWN));
        assertFalse(remote.isEnabledStop(21));
        assertTrue(remote.isEnabledStop(20));

        // A row with nothing enabled is passed over.
        PageFocus gap = new PageFocus(Arrays.asList(
                new int[]{1}, new int[]{2, 3}, new int[]{4}), 2, 3);
        assertEquals(4, gap.move(1, Nav.DOWN));
        assertEquals(1, gap.move(4, Nav.UP));
        assertEquals(PageFocus.EXIT_UP, new PageFocus(Arrays.asList(
                new int[]{2}, new int[]{4}), 2).move(4, Nav.UP));
    }

    @Test
    public void aStopNoLongerOnThePageMovesToTheFirst() {
        assertEquals(0, ACHIEVEMENTS.move(105, Nav.DOWN));
        assertEquals(0, ACHIEVEMENTS.first());
        assertEquals(1, new PageFocus(Collections.singletonList(new int[]{0, 1}), 0).first());
        assertEquals(PageFocus.STAY, new PageFocus(Collections.<int[]>emptyList()).first());
    }

    @Test
    public void theRowsAreCopied() {
        int[] row = {0, 1};
        PageFocus focus = new PageFocus(Collections.singletonList(row));
        row[1] = 9;
        assertEquals(1, focus.move(0, Nav.RIGHT));
    }
}
