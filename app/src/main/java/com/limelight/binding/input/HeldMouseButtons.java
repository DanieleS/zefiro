package com.limelight.binding.input;

import android.view.MotionEvent;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.MouseButtonPacket;

/**
 * The host's mouse buttons a pointer holds down, as a set, so they can all be let go of at once:
 * when the in-stream controls open, the releases of whatever is held then go to the controls, and
 * the host would otherwise keep the buttons down. A set is a bit per button, (1 << (button - 1))
 * for each {@link MouseButtonPacket} button. Pure but for {@link #release}, so the mapping is
 * tested off the device.
 */
public final class HeldMouseButtons {

    private HeldMouseButtons() {
    }

    /** The set holding just this {@link MouseButtonPacket} button. */
    public static int of(byte button) {
        return 1 << (button - 1);
    }

    /**
     * The buttons the host was sent down for an Android button state, as the stream's mouse path
     * maps them: the stylus's primary button is the right one and its secondary the middle one.
     * Back and forward are buttons only when they are sent as such, not as keys.
     */
    public static int fromButtonState(int buttonState, boolean navButtons) {
        int held = 0;
        if ((buttonState & MotionEvent.BUTTON_PRIMARY) != 0) {
            held |= of(MouseButtonPacket.BUTTON_LEFT);
        }
        if ((buttonState & (MotionEvent.BUTTON_SECONDARY | MotionEvent.BUTTON_STYLUS_PRIMARY)) != 0) {
            held |= of(MouseButtonPacket.BUTTON_RIGHT);
        }
        if ((buttonState & (MotionEvent.BUTTON_TERTIARY | MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0) {
            held |= of(MouseButtonPacket.BUTTON_MIDDLE);
        }
        if (navButtons) {
            if ((buttonState & MotionEvent.BUTTON_BACK) != 0) {
                held |= of(MouseButtonPacket.BUTTON_X1);
            }
            if ((buttonState & MotionEvent.BUTTON_FORWARD) != 0) {
                held |= of(MouseButtonPacket.BUTTON_X2);
            }
        }
        return held;
    }

    /** Send the host a release for each button of the set, once each. */
    public static void release(NvConnection conn, int held) {
        for (byte button = MouseButtonPacket.BUTTON_LEFT; button <= MouseButtonPacket.BUTTON_X2; button++) {
            if ((held & of(button)) != 0) {
                conn.sendMouseButtonUp(button);
            }
        }
    }
}
