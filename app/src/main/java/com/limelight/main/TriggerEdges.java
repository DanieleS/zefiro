package com.limelight.main;

/**
 * Turns a gamepad's analog triggers into L2 and R2 presses.
 *
 * <p>Many pads report L2 and R2 only as axes ({@code AXIS_LTRIGGER}/{@code AXIS_RTRIGGER}, or
 * {@code AXIS_BRAKE}/{@code AXIS_GAS}) and never send {@code KEYCODE_BUTTON_L2/R2}, so a screen
 * that binds the key alone would do nothing on them. Others send both, the key and the axis, for
 * one pull. This tracks each trigger across both sources and reports one edge per press,
 * whichever source moved first; the other is swallowed.
 *
 * <p>The axis has hysteresis: pressed above {@value #PRESS}, released below {@value #RELEASE}.
 * A trigger resting near one threshold (a worn spring, a finger on it) would otherwise press and
 * release at every sample.
 *
 * <p>Pure, one per input device. Main thread only.
 */
public final class TriggerEdges {

    /** An axis value above this presses the trigger. */
    public static final float PRESS = 0.6f;
    /** And below this releases it. */
    public static final float RELEASE = 0.3f;

    /** Edges, as flags of {@link #onAxes}. */
    public static final int L2_DOWN = 1;
    public static final int L2_UP = 1 << 1;
    public static final int R2_DOWN = 1 << 2;
    public static final int R2_UP = 1 << 3;

    private final Trigger left = new Trigger();
    private final Trigger right = new Trigger();

    /**
     * A sample of both triggers' axes, from 0 to 1.
     *
     * @return the edges it makes, as flags; 0 for none
     */
    public int onAxes(float leftValue, float rightValue) {
        return left.onAxis(leftValue, L2_DOWN, L2_UP) | right.onAxis(rightValue, R2_DOWN, R2_UP);
    }

    /**
     * A real {@code KEYCODE_BUTTON_L2/R2} going down or up.
     *
     * @return whether it is an edge; false when the axis already reported this press (or its
     * release), so the key must be swallowed
     */
    public boolean onKey(boolean rightTrigger, boolean down) {
        return (rightTrigger ? right : left).onKey(down);
    }

    /** One trigger: pressed while either source holds it. */
    private static final class Trigger {
        private boolean axis;
        private boolean key;

        int onAxis(float value, int downFlag, int upFlag) {
            boolean was = axis || key;
            if (!axis && value > PRESS) {
                axis = true;
            } else if (axis && value < RELEASE) {
                axis = false;
            }
            boolean now = axis || key;
            return was == now ? 0 : now ? downFlag : upFlag;
        }

        boolean onKey(boolean down) {
            boolean was = axis || key;
            key = down;
            return was != (axis || key);
        }
    }
}
