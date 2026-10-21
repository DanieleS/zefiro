package com.limelight.library;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.R;
import com.limelight.computers.HostLibrary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * One shelf of the Library home, as a row of the home's list: a label, and a
 * {@link ShelfRecyclerView} of game tiles. One row while the shelf has games, none otherwise.
 *
 * <h3>The label's link</h3>
 *
 * A shelf may lead somewhere, through a link at the end of its label row, in the signal, the
 * same words on both so they read as the same thing: "See all · N" on All games, which also ends
 * its row with a "See all" tile for the d-pad, and "See all" on Recently played, touch only. The
 * home takes both links away on a compact window ({@link #setLinkShown}), where the grid's last
 * cell is the one way there. The label itself can carry a detail after it ("All games ·
 * Taverna"), which the home sets on a wide window.
 *
 * <h3>Two lists</h3>
 *
 * The row is the outer list's; the tiles are the shelf's own list, a {@link GameGridAdapter}
 * (the same tiles, covers and payloads as See all) followed, on All games, by the "See all · N"
 * tile. The inner adapter belongs to this object, not to the row's view: a poll diffs into it
 * whether or not the row is on screen, and the row only ever attaches it. So a new list never
 * rebinds the row, and the shelf keeps its scroll position and its focus memory through polls.
 *
 * <pre>
 *   outer row (this)     label · link
 *                        └─ ShelfRecyclerView ── ConcatAdapter ── GameGridAdapter  (games)
 *                                                              └─ SeeAllTileAdapter (All games)
 * </pre>
 *
 * <h3>One pool</h3>
 *
 * Every shelf shares the home's {@link RecyclerView.RecycledViewPool}, so a tile scrolled off one
 * shelf is reused by the next. That needs view types that mean the same thing in every shelf, so
 * the inner adapters' types are not isolated: a game tile is the grid adapter's type 0, and the
 * "See all" tile is its layout's id. Stable ids are shared too: the game's app id, which is
 * what the shelf's focus memory keys on, and an id no app id can take for the "See all" tile.
 *
 * <h3>Shape</h3>
 *
 * The count is said once per row: a row, whose tile is scrolled to its end, keeps the count in
 * its link; a grid (a phone in portrait) says it in its last cell and has no link over it.
 *
 * <p>The home decides each shelf's shape from the window ({@link #setShape}): a row, with its tiles'
 * height capped or not, or a grid of a few rows. A grid shows only as many games as fill its
 * cells, the "See all" tile taking the last; a row shows them all.
 *
 * <h3>Rows off screen</h3>
 *
 * A row scrolled off is cached or pooled by the outer list with the tiles still attached, which
 * is what lets it come back as it was; but it is no longer {@code bound}. So the rows that hold
 * the tiles are tracked apart ({@code holding}): a host switch resets every one of them, a row
 * that takes the tiles takes them from any other first (so the tiles live in one list, the one
 * their width is measured on), and {@link #release} detaches them all.
 *
 * Main thread only.
 */
final class ShelfAdapter extends RecyclerView.Adapter<ShelfAdapter.Holder> {

    /** The "See all" tile's stable id: outside the int range, so no app id can take it. */
    static final long SEE_ALL_ID = Long.MIN_VALUE;

    private static final Object PAYLOAD_LABEL = new Object();

    @StringRes
    private final int label;
    private final RecyclerView.RecycledViewPool pool;
    @Nullable
    private final Runnable onSeeAll;
    // Whether the link is "See all · N", with the trailing tile; otherwise it is "See all".
    private final boolean counted;
    // Whether the label row shows the link at all; see setLinkShown.
    private boolean linkShown = true;
    @Nullable
    private String labelDetail;
    private final GameGridAdapter tiles;
    @Nullable
    private final SeeAllTileAdapter seeAllTile;
    private final ConcatAdapter content;

    private boolean shown;
    private int seeAllCount;
    private List<LibraryApp> games = Collections.emptyList();
    private int gridColumns;
    private int gridRows;
    private int maxTileHeightPx;
    @Nullable
    private Holder bound;
    // Every row whose shelf has `content` attached, on screen or not. Weak, so a row the outer
    // pool drops is not kept alive here.
    private final Set<Holder> holding = Collections.newSetFromMap(new WeakHashMap<Holder, Boolean>());

    /**
     * @param onSeeAll what the header link (and the trailing tile) do; null for a shelf that
     *                 leads nowhere, which then has neither
     * @param counted  true for a "See all · N" link and a trailing "See all" tile, false for a
     *                 plain "See all" link and no tile
     */
    ShelfAdapter(@StringRes int label, @NonNull RecyclerView.RecycledViewPool pool,
                 @NonNull GameGridAdapter.Listener tileListener, @Nullable Runnable onSeeAll,
                 boolean counted) {
        this.label = label;
        this.pool = pool;
        this.onSeeAll = onSeeAll;
        this.counted = counted;
        this.tiles = new GameGridAdapter(tileListener);
        ConcatAdapter.Config config = new ConcatAdapter.Config.Builder()
                .setIsolateViewTypes(false)
                .setStableIdMode(ConcatAdapter.Config.StableIdMode.SHARED_STABLE_IDS)
                .build();
        if (onSeeAll != null && counted) {
            seeAllTile = new SeeAllTileAdapter(onSeeAll);
            content = new ConcatAdapter(config, tiles, seeAllTile);
        } else {
            seeAllTile = null;
            content = new ConcatAdapter(config, Collections.singletonList(tiles));
        }
    }

    /** See {@link GameGridAdapter#setLibrary}; true when the shelf was cleared for another host. */
    boolean setLibrary(@Nullable HostLibrary library) {
        return tiles.setLibrary(library);
    }

    /**
     * The shelf's games. An empty list removes the row; the label and the count change in place.
     *
     * @param seeAllCount how many games "See all" leads to; ignored on a shelf that leads nowhere
     */
    void submit(@NonNull List<LibraryApp> games, int seeAllCount) {
        this.games = games;
        boolean show = !games.isEmpty();
        boolean countChanged = seeAllCount != this.seeAllCount;
        this.seeAllCount = seeAllCount;
        submitTiles();
        if (show != shown) {
            shown = show;
            if (show) {
                notifyItemInserted(0);
            } else {
                notifyItemRemoved(0);
            }
        } else if (show && countChanged && onSeeAll != null && counted) {
            notifyItemChanged(0, PAYLOAD_LABEL);
        }
    }

    /** A detail after the label ("All games · Taverna"); null for the label alone. */
    void setLabelDetail(@Nullable String detail) {
        if (detail != null && detail.isEmpty()) {
            detail = null;
        }
        if (detail == null ? labelDetail == null : detail.equals(labelDetail)) {
            return;
        }
        labelDetail = detail;
        if (shown) {
            notifyItemChanged(0, PAYLOAD_LABEL);
        }
    }

    /**
     * Whether the label row offers the link. Off on a compact window, where All games' grid ends
     * in the "See all · N" cell: one way to See all on a phone screen, not one per shelf on top.
     */
    void setLinkShown(boolean shown) {
        if (shown == linkShown) {
            return;
        }
        linkShown = shown;
        for (Holder holder : holding) {
            bindLink(holder);
        }
    }

    /** Whether the shelf's row is in the list. */
    boolean isShown() {
        return shown;
    }

    /**
     * The shelf's shape: a grid of {@code columns} by {@code rows} when columns is above 0,
     * otherwise a row whose tiles are at most {@code maxTileHeightPx} tall (0 for no cap).
     */
    void setShape(int columns, int rows, int maxTileHeightPx) {
        boolean cellsChanged = columns != gridColumns || (columns > 0 && rows != gridRows);
        gridColumns = columns;
        gridRows = rows;
        this.maxTileHeightPx = maxTileHeightPx;
        for (Holder holder : holding) {
            applyShape(holder);
        }
        if (cellsChanged) {
            submitTiles();
        }
    }

    private void applyShape(Holder holder) {
        holder.shelf.setGridColumns(gridColumns);
        holder.shelf.setMaxTileHeight(gridColumns > 0 ? 0 : maxTileHeightPx);
        bindLink(holder);
    }

    // "See all · N" on All games, "See all" on Recently played, and none over a grid, whose last
    // cell says it, or while the home has taken the links away.
    private void bindLink(Holder holder) {
        if (onSeeAll == null) {
            return;
        }
        boolean shown = linkShown && !(gridColumns > 0 && seeAllTile != null);
        holder.link.setVisibility(shown ? View.VISIBLE : View.GONE);
        if (!shown) {
            return;
        }
        Context context = holder.itemView.getContext();
        holder.link.setText(counted
                ? context.getString(R.string.library_see_all_count, seeAllCount)
                : context.getString(R.string.library_see_all));
    }

    // The games that fit the shape, then the "See all" tile once they are in: added in the same
    // pass it would be laid out before them, as the row's first tile, and open it scrolled.
    private void submitTiles() {
        List<LibraryApp> shownGames = games;
        if (gridColumns > 0 && seeAllTile != null) {
            int cells = gridColumns * gridRows - 1;
            if (shownGames.size() > cells) {
                shownGames = new ArrayList<>(shownGames.subList(0, Math.max(0, cells)));
            }
        }
        final boolean show = !games.isEmpty();
        final int count = seeAllCount;
        tiles.submitList(shownGames, () -> {
            if (seeAllTile != null) {
                seeAllTile.set(show, count);
            }
        });
    }

    void refreshArt() {
        tiles.refreshArt();
    }

    /** The shelf's row of tiles while it is laid out; null otherwise. */
    @Nullable
    ShelfRecyclerView shelf() {
        return bound != null && bound.itemView.isShown() ? bound.shelf : null;
    }

    /** Whether the view is one of this shelf's tiles. */
    boolean contains(@Nullable View view) {
        ShelfRecyclerView shelf = bound != null ? bound.shelf : null;
        if (view == null || shelf == null) {
            return false;
        }
        for (Object p = view.getParent(); p instanceof View; p = ((View) p).getParent()) {
            if (p == shelf) {
                return true;
            }
        }
        return false;
    }

    /** The game whose tile is this view (or holds it); null for the "See all" tile or none. */
    @Nullable
    LibraryApp appFor(@Nullable View view) {
        ShelfRecyclerView shelf = bound != null ? bound.shelf : null;
        if (view == null || shelf == null || !contains(view)) {
            return null;
        }
        RecyclerView.ViewHolder holder = shelf.findContainingViewHolder(view);
        return holder instanceof GameGridAdapter.Holder ? ((GameGridAdapter.Holder) holder).app : null;
    }

    /**
     * Forgets the shelf's focus and scroll: another host. Reaches the rows off screen too, which
     * would otherwise come back with the old host's place.
     */
    void reset() {
        for (Holder holder : holding) {
            holder.shelf.reset();
        }
    }

    /** Detaches the tiles from every row, so they and their covers go with the home's views. */
    void release() {
        for (Holder holder : new ArrayList<>(holding)) {
            holder.shelf.setAdapter(null);
        }
        holding.clear();
        bound = null;
    }

    @Override
    public int getItemCount() {
        return shown ? 1 : 0;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_shelf, parent, false);
        Holder holder = new Holder(view);
        holder.shelf.setRecycledViewPool(pool);
        if (onSeeAll != null) {
            // Shown or not with the shape (bindLink).
            holder.link.setOnClickListener(v -> onSeeAll.run());
        }
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        bound = holder;
        if (holder.shelf.getAdapter() != content) {
            // Off the other row first: GameGridAdapter measures its tiles on the list it was
            // attached to last, and forgets it on any detach.
            for (Holder other : new ArrayList<>(holding)) {
                other.shelf.setAdapter(null);
            }
            holding.clear();
            holder.shelf.setAdapter(content);
            holding.add(holder);
        }
        applyShape(holder);
        Context context = holder.itemView.getContext();
        holder.label.setText(labelDetail != null
                ? context.getString(R.string.library_shelf_label_detail,
                        context.getString(label), labelDetail)
                : context.getString(label));
        // The link was bound with the shape, above.
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position, @NonNull List<Object> payloads) {
        onBindViewHolder(holder, position);
    }

    @Override
    public void onViewRecycled(@NonNull Holder holder) {
        // The tiles stay attached: the row comes back with its scroll and focus memory intact.
        if (bound == holder) {
            bound = null;
        }
        super.onViewRecycled(holder);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView label;
        final TextView link;
        final ShelfRecyclerView shelf;

        Holder(@NonNull View view) {
            super(view);
            label = view.findViewById(R.id.shelfLabel);
            link = view.findViewById(R.id.shelfLink);
            shelf = view.findViewById(R.id.shelfList);
        }
    }

    /**
     * The tile that ends the All games shelf and opens See all: the d-pad's way there, since
     * the header's link is touch only. Present while the shelf has games.
     */
    static final class SeeAllTileAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final Runnable onSeeAll;
        private boolean shown;
        private int count;

        SeeAllTileAdapter(@NonNull Runnable onSeeAll) {
            this.onSeeAll = onSeeAll;
            setHasStableIds(true);
        }

        void set(boolean show, int count) {
            boolean countChanged = count != this.count;
            this.count = count;
            if (show != shown) {
                shown = show;
                if (show) {
                    notifyItemInserted(0);
                } else {
                    notifyItemRemoved(0);
                }
            } else if (show && countChanged) {
                notifyItemChanged(0);
            }
        }

        @Override
        public int getItemCount() {
            return shown ? 1 : 0;
        }

        @Override
        public long getItemId(int position) {
            return SEE_ALL_ID;
        }

        @Override
        public int getItemViewType(int position) {
            // Not isolated (see the class comment): a type no game tile shares.
            return R.layout.item_shelf_see_all;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_shelf_see_all, parent, false);
            view.setOnClickListener(v -> onSeeAll.run());
            return new RecyclerView.ViewHolder(view) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            String text = holder.itemView.getContext().getString(R.string.library_see_all_count, count);
            ((TextView) holder.itemView.findViewById(R.id.shelfSeeAllLabel)).setText(text);
            holder.itemView.setContentDescription(text);
        }
    }
}
