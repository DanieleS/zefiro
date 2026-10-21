package com.limelight.binding.input;

import android.view.MotionEvent;

/**
 * The touchscreen's and the stylus's gestures under way as the in-stream controls opened. The
 * host was told to let go of every contact then, so the rest of each gesture is dropped until a
 * new one starts: the host never sees a contact move or lift that it was told is gone. Pure, so
 * the rule is tested off the device. Main thread only.
 */
public final class StaleGestures {
    private boolean fingers;
    private boolean pen;

    /** The controls opened: whatever touches the screen now, finger or stylus, is cut off. */
    public void cutOff() {
        fingers = true;
        pen = true;
    }

    /**
     * Whether a finger's event is the rest of a cut-off gesture, to be dropped. A finger coming
     * down starts a new one.
     */
    public boolean dropFinger(int actionMasked) {
        if (!fingers) {
            return false;
        }
        if (actionMasked != MotionEvent.ACTION_DOWN) {
            return true;
        }
        fingers = false;
        return false;
    }

    /**
     * Whether a stylus's or eraser's event is the rest of a cut-off gesture, to be dropped. The
     * pen starts anew when it comes down, or when it hovers: it has lifted by then, and a hover
     * is no contact the host was told about.
     */
    public boolean dropPen(int actionMasked) {
        if (!pen) {
            return false;
        }
        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
            case MotionEvent.ACTION_HOVER_EXIT:
                pen = false;
                return false;
            default:
                return true;
        }
    }
}
