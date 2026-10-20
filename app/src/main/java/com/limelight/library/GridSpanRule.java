package com.limelight.library;

/**
 * How many columns a games grid has for the width it is given.
 *
 * <p>The old grid divided the width by a fixed 150dp cell, which gives a phone in portrait two
 * columns of oversized covers and a wide window more columns than a shelf of box art reads
 * well at across a room. The approved mocks ask for three on a portrait phone and six or seven
 * on the Thor and wider windows, so the count comes from width buckets instead:
 *
 * <pre>
 *   width (dp, the grid's own, padding excluded)    columns
 *   below 600                                       3        a phone in portrait, a split screen
 *   600 to 719                                      5        a small tablet, a narrow window
 *   720 and up                                      6 or 7   one per 150dp, kept within 6..7
 * </pre>
 *
 * <h3>Short windows</h3>
 *
 * A phone in landscape is as wide as the Thor but much shorter: at the Thor's six columns its
 * covers are so tall that not even two rows fit, and the screen reads zoomed in. A grid more than
 * twice as wide as it is tall gets one column more, up to seven, as the See all mock's landscape
 * frame draws it; the Thor's grid, at under twice, keeps its six. The height is the grid's own
 * too, under the header, and an unknown one (0, before the first layout) adds nothing.
 *
 * The width is the grid's, never the screen's: the rail takes part of a wide window, and a
 * multi-window split takes more. It is an interface so a layout manager can be handed the rule
 * rather than a column width; {@link #SEE_ALL} is the one the See all grid uses.
 *
 * <h3>Shelves</h3>
 *
 * A shelf on the Library home is a row of the same tiles, one more across than the grid's
 * columns at that width ({@link #shelfTiles}): four on a portrait phone and seven or eight on the
 * Thor, as the Shelves mock draws them. A row reads as a glance rather than a page, and its
 * tiles stay a little smaller than the grid's, which they lead to.
 */
public interface GridSpanRule {

    /** The See all grid's rule, {@link #spans(int, int)}. */
    GridSpanRule SEE_ALL = GridSpanRule::spans;

    /** The tiles a shelf shows across, {@link #shelfTiles}; a row has no height to fit. */
    GridSpanRule SHELF = (widthDp, heightDp) -> shelfTiles(widthDp);

    /** The column count for a grid this many dp wide and tall (0 when unknown); at least 1. */
    int spanCount(int widthDp, int heightDp);

    /** The bucket rule above. */
    static int spans(int widthDp) {
        if (widthDp < 600) {
            return 3;
        }
        if (widthDp < 720) {
            return 5;
        }
        return Math.max(6, Math.min(7, widthDp / 150));
    }

    /** The bucket rule, with one column more for a short window (see the class comment). */
    static int spans(int widthDp, int heightDp) {
        int columns = spans(widthDp);
        if (widthDp >= 720 && heightDp > 0 && widthDp > 2 * heightDp) {
            return Math.min(7, columns + 1);
        }
        return columns;
    }

    /** How many tiles a shelf this many dp wide shows across: one more than the grid's columns. */
    static int shelfTiles(int widthDp) {
        return spans(widthDp) + 1;
    }
}
