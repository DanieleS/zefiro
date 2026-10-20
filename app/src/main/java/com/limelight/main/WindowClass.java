package com.limelight.main;

/**
 * How much room the main screen has, as the one decision the shell makes from it: a rail beside
 * the content, or a bar beneath it.
 *
 * <p>It comes from the window's width, never from the device. A phone in landscape and the Thor
 * get the rail; a phone in portrait gets the bottom bar; a tablet in split screen gets whichever
 * its half of the screen allows. The activity handles rotation and resizing itself, so a
 * {@code layout-w600dp} qualifier would never be re-applied; this is computed again in
 * {@code onConfigurationChanged} instead.
 */
public enum WindowClass {
    /** Narrower than {@link #WIDE_MIN_WIDTH_DP}: the bottom bar, system bars kept. */
    COMPACT,
    /** At least {@link #WIDE_MIN_WIDTH_DP} wide: the rail, immersive. */
    WIDE;

    /** Material's own breakpoint between a compact and a medium window. */
    public static final int WIDE_MIN_WIDTH_DP = 600;

    /**
     * @param screenWidthDp {@code Configuration.screenWidthDp}; an undefined width (0) counts as
     *                      compact, which keeps the system bars and so never hides anything.
     */
    public static WindowClass from(int screenWidthDp) {
        return screenWidthDp >= WIDE_MIN_WIDTH_DP ? WIDE : COMPACT;
    }
}
