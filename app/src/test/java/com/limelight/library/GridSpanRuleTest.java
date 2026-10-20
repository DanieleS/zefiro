package com.limelight.library;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The See all grid's columns by width: three on a portrait phone, five in a narrow window, six
 * or seven on the Thor and anything wider, never eight; a short window one more, up to seven.
 */
public class GridSpanRuleTest {

    @Test
    public void aPortraitPhoneGetsThree() {
        assertEquals(3, GridSpanRule.spans(380));
    }

    @Test
    public void aNarrowWindowGetsFive() {
        assertEquals(5, GridSpanRule.spans(650));
    }

    @Test
    public void justPastTheNarrowBucketGetsSix() {
        assertEquals(6, GridSpanRule.spans(770));
        assertEquals(6, GridSpanRule.spans(880));
    }

    @Test
    public void wideWindowsStopAtSeven() {
        assertEquals(7, GridSpanRule.spans(1100));
        assertEquals(7, GridSpanRule.spans(1280));
    }

    @Test
    public void theBucketEdgesBelongToTheWiderBucket() {
        assertEquals(3, GridSpanRule.spans(599));
        assertEquals(5, GridSpanRule.spans(600));
        assertEquals(5, GridSpanRule.spans(719));
        assertEquals(6, GridSpanRule.spans(720));
    }

    @Test
    public void theSeeAllRuleIsTheBucketRule() {
        assertEquals(GridSpanRule.spans(1100, 500), GridSpanRule.SEE_ALL.spanCount(1100, 500));
    }

    @Test
    public void aLandscapePhoneGetsSevenWhereTheThorKeepsSix() {
        // A phone in landscape, under its header: about 800 by 340.
        assertEquals(7, GridSpanRule.spans(800, 340));
        // The Thor: about 860 by 460.
        assertEquals(6, GridSpanRule.spans(860, 460));
    }

    @Test
    public void aShortWindowNeverGoesPastSeven() {
        assertEquals(7, GridSpanRule.spans(1280, 400));
    }

    @Test
    public void anUnknownHeightOrANarrowGridAddsNothing() {
        assertEquals(6, GridSpanRule.spans(800, 0));
        assertEquals(3, GridSpanRule.spans(500, 200));
        assertEquals(5, GridSpanRule.spans(650, 300));
    }

    @Test
    public void aShelfShowsOneMoreTileThanTheGridHasColumns() {
        assertEquals(4, GridSpanRule.shelfTiles(380));
        assertEquals(6, GridSpanRule.shelfTiles(650));
        assertEquals(7, GridSpanRule.shelfTiles(880));
        assertEquals(8, GridSpanRule.shelfTiles(1280));
        assertEquals(GridSpanRule.shelfTiles(1100), GridSpanRule.SHELF.spanCount(1100, 300));
    }
}
