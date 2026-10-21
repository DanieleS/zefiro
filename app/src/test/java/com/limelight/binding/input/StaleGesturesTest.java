package com.limelight.binding.input;

import android.view.MotionEvent;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A finger's or a stylus's gesture cut off by the in-stream controls opening: the host let go of
 * it, so the rest of it never reaches the host, and the next one does.
 */
public class StaleGesturesTest {

    @Test
    public void nothingIsDropped_beforeTheControlsCutAnythingOff() {
        StaleGestures stale = new StaleGestures();

        assertFalse(stale.dropFinger(MotionEvent.ACTION_MOVE));
        assertFalse(stale.dropPen(MotionEvent.ACTION_MOVE));
        assertFalse(stale.dropPen(MotionEvent.ACTION_UP));
    }

    @Test
    public void aCutOffFinger_isDroppedTillOneComesDown() {
        StaleGestures stale = new StaleGestures();
        stale.cutOff();

        assertTrue(stale.dropFinger(MotionEvent.ACTION_MOVE));
        assertTrue(stale.dropFinger(MotionEvent.ACTION_POINTER_UP));
        assertTrue(stale.dropFinger(MotionEvent.ACTION_UP));
        assertFalse(stale.dropFinger(MotionEvent.ACTION_DOWN));
        assertFalse(stale.dropFinger(MotionEvent.ACTION_MOVE));
        assertFalse(stale.dropFinger(MotionEvent.ACTION_UP));
    }

    @Test
    public void aCutOffStylus_isDroppedTillItComesDownAgain() {
        StaleGestures stale = new StaleGestures();
        stale.cutOff();

        assertTrue(stale.dropPen(MotionEvent.ACTION_MOVE));
        assertTrue(stale.dropPen(MotionEvent.ACTION_BUTTON_PRESS));
        assertTrue(stale.dropPen(MotionEvent.ACTION_UP));
        assertFalse(stale.dropPen(MotionEvent.ACTION_DOWN));
        assertFalse(stale.dropPen(MotionEvent.ACTION_MOVE));
        assertFalse(stale.dropPen(MotionEvent.ACTION_UP));
    }

    @Test
    public void aCutOffStylus_startsAnewWhenItHovers() {
        StaleGestures stale = new StaleGestures();
        stale.cutOff();

        assertFalse(stale.dropPen(MotionEvent.ACTION_HOVER_MOVE));
        assertFalse(stale.dropPen(MotionEvent.ACTION_MOVE));
    }

    @Test
    public void theFingerAndTheStylus_startAnewApart() {
        StaleGestures stale = new StaleGestures();
        stale.cutOff();

        assertFalse(stale.dropFinger(MotionEvent.ACTION_DOWN));
        assertTrue(stale.dropPen(MotionEvent.ACTION_MOVE));

        stale.cutOff();
        assertFalse(stale.dropPen(MotionEvent.ACTION_DOWN));
        assertTrue(stale.dropFinger(MotionEvent.ACTION_MOVE));
    }
}
