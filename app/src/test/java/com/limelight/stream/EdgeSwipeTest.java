package com.limelight.stream;

import android.view.MotionEvent;

import com.limelight.stream.EdgeSwipe.Verdict;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The right-edge swipe: claimed only once it has gone far enough inwards from the edge, and
 * everything else left with the stream.
 */
public class EdgeSwipeTest {

    private static final int WIDTH = 1000;

    private final EdgeSwipe swipe = new EdgeSwipe(60, 40);

    @Test
    public void aSwipeInFromTheEdgeIsClaimedThenSwallowedToTheEnd() {
        assertEquals(Verdict.PASS, swipe.onTouch(MotionEvent.ACTION_DOWN, 980, 100, WIDTH, true));
        assertEquals(Verdict.PASS, swipe.onTouch(MotionEvent.ACTION_MOVE, 960, 102, WIDTH, true));
        assertEquals(Verdict.CLAIM, swipe.onTouch(MotionEvent.ACTION_MOVE, 930, 104, WIDTH, true));
        assertEquals(Verdict.SWALLOW, swipe.onTouch(MotionEvent.ACTION_MOVE, 800, 104, WIDTH, true));
        assertEquals(Verdict.SWALLOW, swipe.onTouch(MotionEvent.ACTION_UP, 800, 104, WIDTH, true));
        // The next touch starts over.
        assertEquals(Verdict.PASS, swipe.onTouch(MotionEvent.ACTION_DOWN, 500, 100, WIDTH, true));
        assertEquals(Verdict.PASS, swipe.onTouch(MotionEvent.ACTION_MOVE, 400, 100, WIDTH, true));
    }

    @Test
    public void aTapOnTheEdgeStaysWithTheStream() {
        assertEquals(Verdict.PASS, swipe.onTouch(MotionEvent.ACTION_DOWN, 990, 700, WIDTH, true));
        assertEquals(Verdict.PASS, swipe.onTouch(MotionEvent.ACTION_UP, 990, 700, WIDTH, true));
    }

    @Test
    public void aTouchAwayFromTheEdgeIsNeverClaimed() {
        swipe.onTouch(MotionEvent.ACTION_DOWN, 900, 100, WIDTH, true);
        assertEquals(Verdict.PASS, swipe.onTouch(MotionEvent.ACTION_MOVE, 500, 100, WIDTH, true));
    }

    @Test
    public void aDragAlongTheEdgeIsTheGames() {
        swipe.onTouch(MotionEvent.ACTION_DOWN, 980, 100, WIDTH, true);
        assertEquals(Verdict.PASS, swipe.onTouch(MotionEvent.ACTION_MOVE, 930, 300, WIDTH, true));
    }

    @Test
    public void nothingIsClaimedWhileDisarmed() {
        swipe.onTouch(MotionEvent.ACTION_DOWN, 980, 100, WIDTH, false);
        assertEquals(Verdict.PASS, swipe.onTouch(MotionEvent.ACTION_MOVE, 800, 100, WIDTH, false));
    }

    @Test
    public void aSecondFingerGivesTheGestureToTheGame() {
        swipe.onTouch(MotionEvent.ACTION_DOWN, 980, 100, WIDTH, true);
        swipe.onTouch(MotionEvent.ACTION_POINTER_DOWN, 500, 300, WIDTH, true);
        assertEquals(Verdict.PASS, swipe.onTouch(MotionEvent.ACTION_MOVE, 800, 100, WIDTH, true));
    }
}
