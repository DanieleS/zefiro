package com.limelight.pcs;

/**
 * How many PC tiles the PCs grid lays across the width it is given.
 *
 * <pre>
 *   width (dp, the grid's own, padding excluded)    columns
 *   below 600                                       2        a phone in portrait, a split screen
 *   600 and up                                      one per 200dp, kept within 3..5
 *                                                   (4 on the Thor's top screen, beside the rail)
 * </pre>
 *
 * The Thor's top screen leaves the grid about 856dp once the rail and the gutters are out, so a
 * tile claims 200dp: at 220 it got three across there, wider than a name and a status line need,
 * where the mock draws four.
 *
 * A tile holds a name, a status line and a button, not art, so it needs a minimum width rather
 * than a shape: below about 160dp the name and "Playing · …" are cut to nothing, and above five
 * across a row of a few PCs reads as a strip rather than as tiles.
 *
 * <p>The width is the grid's, never the screen's, as with the games grid's
 * {@link com.limelight.library.GridSpanRule}: the rail takes part of a wide window, and a
 * multi-window split takes more.
 *
 * Pure.
 */
public final class PcTileSpanRule {

    /** Below this grid width, two tiles across. */
    static final int COMPACT_MAX_WIDTH_DP = 600;
    /** The width each tile claims in a wider grid. */
    static final int TILE_WIDTH_DP = 200;

    private PcTileSpanRule() {
    }

    /** The column count for a grid this many dp wide; at least 2. */
    public static int spans(int widthDp) {
        if (widthDp < COMPACT_MAX_WIDTH_DP) {
            return 2;
        }
        return Math.max(3, Math.min(5, widthDp / TILE_WIDTH_DP));
    }
}
