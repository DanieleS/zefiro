package com.limelight.main;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where the d-pad goes among a companion page's stops: the virtual focus's map.
 *
 * <p>The panel never takes key focus, so Android's focus search cannot move through what it
 * draws. The page's owner describes its stops instead, as rows in reading order (a filter row of
 * three segments, then one row per list item), and this answers each press with the next stop.
 * Pure: no views, so the rules are tested off the device.
 *
 * <h3>Rules</h3>
 *
 * <pre>
 *   UP / DOWN      the row above or below, in the same column, clamped to that row's width
 *   LEFT / RIGHT   the neighbour within the row; at the row's end, {@link #STAY}
 *   off the top    {@link #EXIT_UP}, off the bottom {@link #EXIT_DOWN}: the owner decides
 *                  (back up to the main screen, or nowhere)
 *   disabled       skipped: sideways to the next enabled one, vertically to the nearest enabled
 *                  column of the row, or past a row with none
 * </pre>
 *
 * Stop ids are the page's own, never negative, and appear once; the results below are negative
 * so they can never be mistaken for one. Immutable.
 */
public final class PageFocus {

    /**
     * A d-pad direction. Not the stream's {@code ControlsSurface.Nav}, though it reads like a
     * subset of it: that one also carries activate, back and close, which a page leaves to its
     * owner, and its grid ({@code ControlsGrid}) moves by other rules (nothing exits, nothing is
     * disabled, a row lands in proportion rather than by column). One shared type would have both
     * navigators reject half of each other's values.
     */
    public enum Nav { UP, DOWN, LEFT, RIGHT }

    /** Off the first row, upwards. */
    public static final int EXIT_UP = -2;
    /** Off the last row, downwards. */
    public static final int EXIT_DOWN = -3;
    /** Nowhere to go: the focus stays where it is. */
    public static final int STAY = -4;

    private final List<int[]> rows;
    private final int[] disabled;

    /** Every stop enabled. */
    public PageFocus(@NonNull List<int[]> rows) {
        this(rows, new int[0]);
    }

    /**
     * @param rows     the stops, row by row, in reading order; empty rows are dropped
     * @param disabled stops drawn but not reachable (a "next" at the latest period)
     */
    public PageFocus(@NonNull List<int[]> rows, @NonNull int... disabled) {
        List<int[]> copy = new ArrayList<>(rows.size());
        for (int[] row : rows) {
            if (row != null && row.length > 0) {
                copy.add(row.clone());
            }
        }
        this.rows = Collections.unmodifiableList(copy);
        this.disabled = disabled.clone();
    }

    /** Whether {@code stop} is on the page and can take the focus. */
    public boolean isEnabledStop(int stop) {
        return find(stop) != null && !isDisabled(stop);
    }

    /** The first enabled stop in reading order, or {@link #STAY} when there is none. */
    public int first() {
        for (int[] row : rows) {
            for (int stop : row) {
                if (!isDisabled(stop)) {
                    return stop;
                }
            }
        }
        return STAY;
    }

    /**
     * Where {@code dir} goes from {@code stop}.
     *
     * @return the next stop, or {@link #EXIT_UP}, {@link #EXIT_DOWN} or {@link #STAY}. From a
     * stop that is not on the page (the page changed under it), the first enabled stop.
     */
    public int move(int stop, @NonNull Nav dir) {
        int[] at = find(stop);
        if (at == null) {
            return first();
        }
        int row = at[0];
        int column = at[1];
        switch (dir) {
            case LEFT:
                return sideways(rows.get(row), column, -1);
            case RIGHT:
                return sideways(rows.get(row), column, 1);
            case UP:
                return vertical(row, column, -1, EXIT_UP);
            case DOWN:
            default:
                return vertical(row, column, 1, EXIT_DOWN);
        }
    }

    private int sideways(int[] row, int column, int step) {
        for (int c = column + step; c >= 0 && c < row.length; c += step) {
            if (!isDisabled(row[c])) {
                return row[c];
            }
        }
        return STAY;
    }

    private int vertical(int row, int column, int step, int exit) {
        for (int r = row + step; r >= 0 && r < rows.size(); r += step) {
            int stop = nearestEnabled(rows.get(r), Math.min(column, rows.get(r).length - 1));
            if (stop != STAY) {
                return stop;
            }
        }
        return exit;
    }

    // The enabled stop closest to the column, the left one first on a tie: reading order.
    private int nearestEnabled(int[] row, int column) {
        for (int distance = 0; distance < row.length; distance++) {
            int left = column - distance;
            if (left >= 0 && !isDisabled(row[left])) {
                return row[left];
            }
            int right = column + distance;
            if (distance > 0 && right < row.length && !isDisabled(row[right])) {
                return row[right];
            }
        }
        return STAY;
    }

    // {row, column} of the stop's first appearance, or null.
    private int[] find(int stop) {
        if (stop < 0) {
            return null;
        }
        for (int r = 0; r < rows.size(); r++) {
            int[] row = rows.get(r);
            for (int c = 0; c < row.length; c++) {
                if (row[c] == stop) {
                    return new int[]{r, c};
                }
            }
        }
        return null;
    }

    private boolean isDisabled(int stop) {
        for (int d : disabled) {
            if (d == stop) {
                return true;
            }
        }
        return false;
    }
}
