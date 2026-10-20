package com.limelight.library;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The See all grid's columns by width: three on a portrait phone, five in a narrow window, six
 * or seven on the Thor and anything wider, never eight.
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
        assertEquals(GridSpanRule.spans(1100), GridSpanRule.SEE_ALL.spanCount(1100));
    }
}
