package com.limelight.stream;

import android.view.MotionEvent;

/**
 * The swipe in from the right edge that opens the phone's side panel, anywhere along the edge.
 * The {@link EdgeHandleView} is 120dp of it, where it takes the touch outright; the rest of the
 * edge belongs to the stream, so a touch there is watched from the window (Game's
 * dispatchTouchEvent) and only taken once it has become a swipe towards the middle.
 *
 * <h3>Taps still reach the game</h3>
 *
 * Every event goes on to the stream until the swipe is certain. Then the caller ends the touch
 * for the stream with a cancel, as a parent taking over a gesture does, and opens the panel; the
 * rest of that gesture is swallowed. A tap, a drag along the edge, or a second finger leaves the
 * touch with the game.
 *
 * <p>Pure apart from the action constants, so {@code EdgeSwipeTest} pins it without a device.
 * Main thread only.
 */
public final class EdgeSwipe {

    /** What the caller does with an event. */
    public enum Verdict {
        /** Hand it to the stream as usual. */
        PASS,
        /** The swipe is certain: cancel the touch for the stream, then open the panel. */
        CLAIM,
        /** Part of a gesture already claimed: nobody else sees it. */
        SWALLOW
    }

    private final float edgeWidthPx;
    private final float swipeDistancePx;

    private boolean tracking;
    private boolean claimed;
    private float downX;
    private float downY;

    /**
     * @param edgeWidthPx     how far in from the right edge a touch may start and still count
     * @param swipeDistancePx how far towards the middle it must travel to be a swipe
     */
    public EdgeSwipe(float edgeWidthPx, float swipeDistancePx) {
        this.edgeWidthPx = edgeWidthPx;
        this.swipeDistancePx = swipeDistancePx;
    }

    /**
     * One event of the window's touch stream, in window coordinates.
     *
     * @param width the window's width
     * @param armed read on a down only: whether a swipe may open the panel now (the edge is
     *              the panel's way in, the panel is closed, and the touch is not on the handle,
     *              which deals with its own)
     */
    public Verdict onTouch(int action, float x, float y, int width, boolean armed) {
        if (claimed) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                claimed = false;
            }
            return Verdict.SWALLOW;
        }
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                tracking = armed && width > 0 && x >= width - edgeWidthPx;
                downX = x;
                downY = y;
                return Verdict.PASS;
            case MotionEvent.ACTION_MOVE:
                if (tracking) {
                    float inwards = downX - x;
                    // Mostly sideways: a drag down the edge is the game's.
                    if (inwards >= swipeDistancePx && inwards > Math.abs(y - downY)) {
                        tracking = false;
                        claimed = true;
                        return Verdict.CLAIM;
                    }
                }
                return Verdict.PASS;
            case MotionEvent.ACTION_POINTER_DOWN:
                // Two fingers are a game's gesture (a pinch, a right click), never the panel's.
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                tracking = false;
                return Verdict.PASS;
            default:
                return Verdict.PASS;
        }
    }
}
