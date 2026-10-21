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
     * Below this height a wide window is a short one: a phone in landscape (about 340dp once its
     * cutout is out), not the Thor (about 450dp). It has no room for a page title over the
     * shelves, and the landscape mocks draw none.
     */
    public static final int SHORT_MAX_HEIGHT_DP = 400;

    /**
     * The rail column's height with every item in it: 12dp above the rail and the 8dp the rail
     * keeps above its first item, its four destinations and the Settings button at 56dp each,
     * and the 48dp profile avatar with its 12dp margin. A phone in landscape has it to spare, so the avatar is at the rail's foot
     * there as on the Thor; only a window shorter than this leaves it to Settings.
     */
    public static final int RAIL_WITH_AVATAR_MIN_HEIGHT_DP = 360;

    /**
     * @param screenWidthDp {@code Configuration.screenWidthDp}; an undefined width (0) counts as
     *                      compact, which keeps the system bars and so never hides anything.
     */
    public static WindowClass from(int screenWidthDp) {
        return screenWidthDp >= WIDE_MIN_WIDTH_DP ? WIDE : COMPACT;
    }

    /**
     * Whether a window is a wide and short one (see {@link #SHORT_MAX_HEIGHT_DP}).
     *
     * @param screenHeightDp {@code Configuration.screenHeightDp}; an undefined height (0) is not
     *                       short, which keeps everything shown
     */
    public static boolean isShortWide(int screenWidthDp, int screenHeightDp) {
        return from(screenWidthDp) == WIDE && screenHeightDp > 0
                && screenHeightDp < SHORT_MAX_HEIGHT_DP;
    }

    /**
     * Whether the rail has no room for the profile avatar under Settings (see
     * {@link #RAIL_WITH_AVATAR_MIN_HEIGHT_DP}).
     *
     * @param screenHeightDp {@code Configuration.screenHeightDp}; an undefined height (0) has room
     */
    public static boolean railTooShortForAvatar(int screenHeightDp) {
        return screenHeightDp > 0 && screenHeightDp < RAIL_WITH_AVATAR_MIN_HEIGHT_DP;
    }
}
