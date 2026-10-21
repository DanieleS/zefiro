package com.limelight.stream;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where the d-pad goes on the Thor's controls sheet, as a neighbour table over the entries of one
 * page in the order they are drawn.
 *
 * <h3>Why a table and not focus search</h3>
 *
 * The sheet is on the companion panel, whose window never takes focus, so the platform's focus
 * search has nothing to search: the selection is moved by hand ({@code CompanionPresentation}).
 * Kept apart and pure so the moves can be checked off the device ({@code ControlsGridTest}).
 *
 * <h3>Rows</h3>
 *
 * A run of {@link StreamAction.Group#TILE} entries is one row, and so is a run of
 * {@link StreamAction.Group#DANGER} ones; every other entry (a list row, a page's way back) is a
 * row of its own.
 *
 * <pre>
 *   [Keyboard] [Gamepad] [Mouse] [Overlay]     LEFT / RIGHT along the row
 *        │ DOWN                ▲ UP from the first list row: the first tile
 *        ▼                     │
 *   [Screenshot]  ──────────────┘
 *   [Bitrate]                                  UP / DOWN one row at a time
 *   [More]
 *        │ DOWN
 *        ▼
 *   [Disconnect] [Quit game]                   LEFT / RIGHT along the row
 * </pre>
 *
 * Moving between two rows of several cells keeps the place along the row (tiles to the danger row
 * on a page without a list); into a row of several from a single cell lands on its first. Nothing
 * wraps: at an edge a move stays where it is, which on a pad reads as "that's the end".
 */
public final class ControlsGrid {

    /** The row each entry is in, by entry index. */
    private final int[] rowOf;
    /** Each row's entries, as indices, left to right. */
    private final List<int[]> rows;

    private ControlsGrid(int[] rowOf, List<int[]> rows) {
        this.rowOf = rowOf;
        this.rows = rows;
    }

    /** The table for a page whose entries, in drawing order, are in these groups. */
    @NonNull
    public static ControlsGrid of(@NonNull List<StreamAction.Group> groups) {
        int[] rowOf = new int[groups.size()];
        List<int[]> rows = new ArrayList<>();
        int i = 0;
        while (i < groups.size()) {
            StreamAction.Group group = groups.get(i);
            int end = i + 1;
            if (spansRow(group)) {
                while (end < groups.size() && groups.get(end) == group) {
                    end++;
                }
            }
            int[] row = new int[end - i];
            for (int j = i; j < end; j++) {
                row[j - i] = j;
                rowOf[j] = rows.size();
            }
            rows.add(row);
            i = end;
        }
        return new ControlsGrid(rowOf, Collections.unmodifiableList(rows));
    }

    /** Whether a run of this group shares a row: the tiles, and the session's two ends. */
    private static boolean spansRow(StreamAction.Group group) {
        return group == StreamAction.Group.TILE || group == StreamAction.Group.DANGER;
    }

    public int size() {
        return rowOf.length;
    }

    /** Where a page opens: its first entry, or -1 for a page with none. */
    public int first() {
        return rowOf.length > 0 ? 0 : -1;
    }

    /**
     * The entry a move lands on from {@code from}. {@code from} itself when there is nowhere to go
     * that way; the first entry when nothing is selected yet ({@code from} out of range), whatever
     * the direction. Only the four directions move; any other nav returns {@code from}.
     */
    public int neighbour(int from, @NonNull ControlsSurface.Nav nav) {
        if (from < 0 || from >= rowOf.length) {
            return first();
        }
        int row = rowOf[from];
        int[] cells = rows.get(row);
        int column = indexOf(cells, from);
        switch (nav) {
            case LEFT:
                return column > 0 ? cells[column - 1] : from;
            case RIGHT:
                return column < cells.length - 1 ? cells[column + 1] : from;
            case UP:
                return row > 0 ? landIn(rows.get(row - 1), cells, column) : from;
            case DOWN:
                return row < rows.size() - 1 ? landIn(rows.get(row + 1), cells, column) : from;
            default:
                return from;
        }
    }

    /**
     * The cell of {@code target} a vertical move from {@code column} of {@code source} lands on:
     * the same place along the row between two rows of several cells, the first cell otherwise.
     */
    private static int landIn(int[] target, int[] source, int column) {
        if (target.length == 1 || source.length == 1) {
            return target[0];
        }
        // In proportion, so the last of four tiles lands on the last of two danger buttons.
        int mapped = Math.round(column * (target.length - 1) / (float) (source.length - 1));
        return target[Math.max(0, Math.min(target.length - 1, mapped))];
    }

    private static int indexOf(int[] cells, int entry) {
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] == entry) {
                return i;
            }
        }
        return 0;
    }
}
