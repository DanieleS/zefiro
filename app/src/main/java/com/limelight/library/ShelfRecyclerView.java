package com.limelight.library;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.limelight.R;
import com.limelight.grid.GridSpacingItemDecoration;

import java.util.ArrayList;

/**
 * One shelf of the Library home: a horizontal row of tiles that remembers, on its own, which
 * tile the d-pad left it on.
 *
 * <p>Between shelves, Android's focus search picks whichever tile of the next row sits nearest
 * the one being left. Down from the fifth Recently played tile lands on the fifth All games
 * tile, and up again lands on the fifth Recently played, not on the tile the user had been on
 * before: each row forgets. A launcher's rows are expected to remember, so this one does:
 *
 * <pre>
 *   the shelf holds focus      every tile is a candidate, as usual
 *   the shelf does not         only the tile it last held is offered to the search
 *                              (addFocusables), so focus coming in from any side lands there
 * </pre>
 *
 * The memory is by stable id (the game's app id, or the "See all" tile's), not by position, so
 * a poll that reorders the row does not move it. A tile that is gone, or scrolled off, is not
 * offered, and the search falls back to the nearest tile.
 *
 * <h3>Edges</h3>
 *
 * <pre>
 *   RIGHT on the last tile    stays put: nothing beyond the row's end is "to the right" of it,
 *                             only a tile of another shelf that happens to sit further right
 *   LEFT on the first tile    the search climbs to the main screen's focus gate as it does for
 *                             any section, which hands it to the navigation rail
 * </pre>
 *
 * <h3>Tiles</h3>
 *
 * A shelf shows {@link GridSpanRule#shelfTiles} tiles across its own width, all one size, so the
 * adapter is told that width ({@link GameGridAdapter.FixedTileWidth}) and every tile, "See all"
 * included, is laid out at it, in box art's 3:4. The width is the shelf's, measured, never the
 * screen's. In a wide window the home may cap the tiles' height ({@link #setMaxTileHeight}), so
 * its shelves fit the window's height too; the row then shows more of them across.
 *
 * <h3>As a grid</h3>
 *
 * On a phone in portrait one row of tiles leaves most of the screen empty, so the home can lay a
 * shelf out as a few rows of a fixed number of columns instead ({@link #setGridColumns}). The
 * grid does not scroll on its own: it is as tall as its tiles, and the home's list scrolls it.
 * The focus memory and the edges above hold for it as they do for a row.
 *
 * <h3>Opening at the start</h3>
 *
 * A row opens at its first tile. Games inserted ahead of the tiles already laid out (the "See
 * all" tile is there before the games' diff lands, or a poll puts a game first) would otherwise
 * open it scrolled: the layout keeps the first tile it had in place and lays the new ones out
 * to its left, off screen. A row that was at its start, and that the d-pad is not in, stays
 * there. Its place is kept otherwise, which is what it comes back to from See all.
 *
 * Main thread only, as views are.
 */
public class ShelfRecyclerView extends RecyclerView implements GameGridAdapter.FixedTileWidth {

    private final int gapPx;
    private final AdapterDataObserver startKeeper = new AdapterDataObserver() {
        @Override
        public void onItemRangeInserted(int positionStart, int itemCount) {
            if (positionStart == 0 && !hasFocus() && isAtStart()) {
                scrollToPosition(0);
            }
        }
    };

    private LinearLayoutManager layoutManager;
    @Nullable
    private Adapter<?> observed;
    private int tileWidthPx;
    private int maxTileHeightPx;
    private int gridColumns;
    private long rememberedId = NO_ID;

    public ShelfRecyclerView(@NonNull Context context) {
        this(context, null);
    }

    public ShelfRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        gapPx = context.getResources().getDimensionPixelSize(R.dimen.see_all_tile_gap);
        installLayoutManager();
        addItemDecoration(new GridSpacingItemDecoration(gapPx));
        // Tiles take focus, never the row itself: an empty row that held focus would swallow
        // the next d-pad press.
        setFocusable(false);
        // A flipped flag rebinds a tile in place; the default change animation would swap the
        // focused tile for a new view under the d-pad.
        ItemAnimator animator = getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }
    }

    @Override
    public int tileWidthPx() {
        return tileWidthPx;
    }

    /**
     * The tallest a tile may be, in px; 0 for no cap. Only a row is capped: a grid's columns
     * already divide the width.
     */
    public void setMaxTileHeight(int px) {
        if (px != maxTileHeightPx) {
            maxTileHeightPx = px;
            requestLayout();
        }
    }

    /** Lays the shelf out as a grid of this many columns; 0 for a single row (the default). */
    public void setGridColumns(int columns) {
        if (columns != gridColumns) {
            gridColumns = columns;
            installLayoutManager();
        }
    }

    private void installLayoutManager() {
        layoutManager = gridColumns > 0
                ? new ShelfGridLayoutManager(getContext(), gridColumns)
                : new ShelfLayoutManager(getContext());
        // The shelves share one pool of tiles; a shelf scrolled off hands its tiles back to it
        // rather than keeping them for itself.
        layoutManager.setRecycleChildrenOnDetach(true);
        setLayoutManager(layoutManager);
        // A grid is as tall as its rows, and the home's list does the scrolling.
        setNestedScrollingEnabled(gridColumns == 0);
    }

    @Override
    public void setAdapter(@Nullable Adapter adapter) {
        if (observed != null) {
            observed.unregisterAdapterDataObserver(startKeeper);
        }
        super.setAdapter(adapter);
        observed = adapter;
        if (adapter != null) {
            adapter.registerAdapterDataObserver(startKeeper);
        }
    }

    // Whether the row shows its first tile at the start, as laid out before the change at hand.
    private boolean isAtStart() {
        return gridColumns > 0 || getChildCount() == 0
                || layoutManager.findFirstCompletelyVisibleItemPosition() <= 0;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        // Before super: measuring a wrap_content row lays its tiles out, and they are sized then.
        if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) {
            int usable = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
            float density = getResources().getDisplayMetrics().density;
            int across = gridColumns > 0
                    ? gridColumns : GridSpanRule.SHELF.spanCount(Math.round(usable / density), 0);
            // Each tile carries half the gap on either side (GridSpacingItemDecoration).
            int width = Math.max(1, usable / Math.max(1, across) - gapPx);
            if (gridColumns == 0 && maxTileHeightPx > 0) {
                width = Math.max(1, Math.min(width, maxTileHeightPx * 3 / 4));
                across = Math.max(across, usable / (width + gapPx));
            }
            tileWidthPx = width;
            layoutManager.setInitialPrefetchItemCount(across + 1);
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    // --- Focus memory ---

    @Override
    public void requestChildFocus(View child, View focused) {
        super.requestChildFocus(child, focused);
        long id = getChildItemId(child);
        if (id != NO_ID) {
            rememberedId = id;
        }
    }

    @Override
    public void addFocusables(ArrayList<View> views, int direction, int focusableMode) {
        if (!hasFocus()) {
            View remembered = rememberedTile();
            if (remembered != null) {
                remembered.addFocusables(views, direction, focusableMode);
                return;
            }
        }
        super.addFocusables(views, direction, focusableMode);
    }

    @Override
    public View focusSearch(View focused, int direction) {
        View found = super.focusSearch(focused, direction);
        if (direction == FOCUS_RIGHT && focused != null && isInside(focused)
                && (found == null || !isInside(found))) {
            return focused;
        }
        return found;
    }

    /** The tile the row last held, while it is laid out; null when there is none. */
    @Nullable
    public View rememberedTile() {
        if (rememberedId == NO_ID) {
            return null;
        }
        ViewHolder holder = findViewHolderForItemId(rememberedId);
        return holder != null && holder.itemView.isShown() ? holder.itemView : null;
    }

    /** Where focus enters the row: the tile it last held, or its first tile laid out. */
    @Nullable
    public View entryTile() {
        View remembered = rememberedTile();
        if (remembered != null) {
            return remembered;
        }
        return firstTile();
    }

    /** The row's first tile, when it is laid out; null otherwise. */
    @Nullable
    public View firstTile() {
        ViewHolder holder = findViewHolderForAdapterPosition(0);
        return holder != null && holder.itemView.isShown() ? holder.itemView : null;
    }

    /**
     * Moves focus to the row's last tile, scrolling it into view first. For "See all" popped:
     * the d-pad comes back to the tile that led there.
     */
    public void focusLastTile() {
        Adapter<?> adapter = getAdapter();
        if (adapter == null || adapter.getItemCount() == 0) {
            return;
        }
        final int last = adapter.getItemCount() - 1;
        ViewHolder holder = findViewHolderForAdapterPosition(last);
        if (holder != null && holder.itemView.isShown()) {
            holder.itemView.requestFocus();
            return;
        }
        scrollToPosition(last);
        post(() -> {
            ViewHolder laidOut = findViewHolderForAdapterPosition(last);
            if (laidOut != null) {
                laidOut.itemView.requestFocus();
            }
        });
    }

    /** Forgets the remembered tile and goes back to the row's start: another host's games. */
    public void reset() {
        rememberedId = NO_ID;
        scrollToPosition(0);
    }

    private boolean isInside(View view) {
        for (Object p = view.getParent(); p instanceof View; p = ((View) p).getParent()) {
            if (p == this) {
                return true;
            }
        }
        return false;
    }

    /**
     * Lays every tile out at the shelf's tile width, in 3:4. Done where RecyclerView checks a
     * view's layout params, which it does for every view it hands the layout, new, recycled or
     * scrapped alike, so a new width (a rotation) reaches the tiles already on screen too.
     */
    private void sizeTile(LayoutParams lp) {
        if (tileWidthPx > 0) {
            lp.width = tileWidthPx;
            lp.height = tileWidthPx * 4 / 3;
        }
    }

    /** The row: see {@link #sizeTile}. */
    private final class ShelfLayoutManager extends LinearLayoutManager {
        ShelfLayoutManager(Context context) {
            super(context, HORIZONTAL, false);
        }

        @Override
        public boolean checkLayoutParams(LayoutParams lp) {
            sizeTile(lp);
            return super.checkLayoutParams(lp);
        }
    }

    /** The grid: see {@link #sizeTile}. Never scrolls itself; the home's list does. */
    private final class ShelfGridLayoutManager extends GridLayoutManager {
        ShelfGridLayoutManager(Context context, int columns) {
            super(context, columns);
        }

        @Override
        public boolean checkLayoutParams(RecyclerView.LayoutParams lp) {
            sizeTile(lp);
            return super.checkLayoutParams(lp);
        }

        @Override
        public boolean canScrollVertically() {
            return false;
        }
    }
}
