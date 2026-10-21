package com.limelight.companion;

/**
 * How many tiles across the stream's stats on the companion panel are laid out, for however many
 * are shown. Host and Device come and go with what the host and the device report, and a fixed
 * three across left an empty cell at the end of the grid, which read as a tile failing to load.
 *
 * <pre>
 *   9  3 x 3      8  4 x 2      7  4 + 3      6  3 x 2
 * </pre>
 *
 * Pure, so {@code StatsGridTest} pins it without a device.
 */
final class StatsGrid {

    /** The mock's width, and the most a panel row reads comfortably. */
    private static final int MAX_COLUMNS = 4;
    private static final int MIN_COLUMNS = 3;

    private StatsGrid() {
    }

    /** The tiles in each row for {@code count} tiles: the narrowest grid with no gap, if any. */
    static int columns(int count) {
        if (count <= MIN_COLUMNS) {
            return Math.max(1, count);
        }
        for (int columns = MIN_COLUMNS; columns <= MAX_COLUMNS; columns++) {
            if (count % columns == 0) {
                return columns;
            }
        }
        // No full grid: the widest, so the short row at the end is the smallest gap.
        return MAX_COLUMNS;
    }
}
