package com.limelight.library;

import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.computers.HostLibrary;
import com.limelight.grid.assets.ArtState;
import com.limelight.grid.assets.CachedAppAssetLoader;
import com.limelight.ui.GameTileView;

import java.util.List;
import java.util.Objects;

/**
 * The See all grid, and each shelf of the Library home: one {@link GameTileView} per
 * {@link LibraryApp}, its cover loaded from the selected host's {@link HostLibrary}.
 *
 * <p>The library publishes a new snapshot whenever anything about it changes, and the host is
 * polled every 1.5 s. The old grid answered each change with {@code notifyDataSetChanged()},
 * which rebinds every tile, reloads every cover and, with the change animation, swaps the
 * focused tile's view for a new one, so the d-pad lost its place whenever a game started or
 * stopped. Here {@link DiffUtil} keeps every unchanged tile as it is, and a change that is only
 * a flag goes through a payload:
 *
 * <pre>
 *   running or hidden flipped ──▶ PAYLOAD_FLAGS: badge and dimming only; the cover stays
 *   renamed (same id)          ──▶ full bind of the same tile; its cover stays, it is the same game
 *   covers changed on the host ──▶ PAYLOAD_ART ({@link #refreshArt}): the cover is asked again
 *   another game               ──▶ full bind: the old request is cancelled, the new one made
 * </pre>
 *
 * <h3>Stable ids</h3>
 *
 * The app id, which is what the host keys its games by. Two hosts can use the same ids for
 * different games, so a host switch is not diffed at all: {@link #setLibrary} with another
 * library clears the grid first, and the new host's games are all new tiles.
 *
 * <h3>Covers</h3>
 *
 * Each tile holds at most one request. It is cancelled when the tile is bound to another game,
 * and left to finish when the tile is bound to the same game again while it is still on its way.
 * A cover comes in the size bucket of the tile's width (see {@code CachedAppAssetLoader}), so a
 * 3-column phone grid and a 7-column TV grid each decode what they draw. A shelf decides its
 * tiles' width itself rather than by columns, and says so by being a {@link FixedTileWidth}.
 *
 * Main thread only.
 */
public class GameGridAdapter extends ListAdapter<LibraryApp, GameGridAdapter.Holder> {

    /** What the user does to a tile. */
    public interface Listener {
        void onTileClicked(@NonNull LibraryApp app);

        void onTileLongPressed(@NonNull LibraryApp app);

        /** A tile took focus, for the section's focus memory. */
        void onTileFocused(@NonNull LibraryApp app);
    }

    /**
     * A list that gives every tile one width of its own choosing (a shelf), rather than a grid's
     * column. The tiles are made at that width, and their covers asked for at it.
     */
    public interface FixedTileWidth {
        /** The width of every tile, in px; 0 while the list has not been measured. */
        int tileWidthPx();
    }

    /** Only the running and hidden flags changed: rebind them, keep the cover. */
    static final Object PAYLOAD_FLAGS = new Object();
    /** The host's covers changed: ask for this tile's again. */
    static final Object PAYLOAD_ART = new Object();

    private static final int FALLBACK_TILE_WIDTH_PX = 300;

    private static final DiffUtil.ItemCallback<LibraryApp> DIFF = new DiffUtil.ItemCallback<LibraryApp>() {
        @Override
        public boolean areItemsTheSame(@NonNull LibraryApp a, @NonNull LibraryApp b) {
            return a.sameApp(b);
        }

        @Override
        public boolean areContentsTheSame(@NonNull LibraryApp a, @NonNull LibraryApp b) {
            return a.equals(b);
        }

        @Nullable
        @Override
        public Object getChangePayload(@NonNull LibraryApp a, @NonNull LibraryApp b) {
            return sameExceptFlags(a, b) ? PAYLOAD_FLAGS : null;
        }
    };

    private final Listener listener;
    // The loader of whichever host the grid shows; null while none is open.
    @Nullable
    private HostLibrary library;
    @Nullable
    private RecyclerView recyclerView;

    public GameGridAdapter(@NonNull Listener listener) {
        super(DIFF);
        this.listener = listener;
        setHasStableIds(true);
    }

    /** Whether two entries for one game differ only by the flags a payload rebinds. */
    static boolean sameExceptFlags(@NonNull LibraryApp a, @NonNull LibraryApp b) {
        return a.appId() == b.appId()
                && a.app.isHdrSupported() == b.app.isHdrSupported()
                && Objects.equals(a.app.getAppName(), b.app.getAppName())
                && Objects.equals(a.app.getAppUUID(), b.app.getAppUUID());
    }

    /**
     * The library whose games are shown and whose covers are loaded. Another library is another
     * host: the grid is cleared at once rather than diffed, since ids are only unique per host.
     *
     * @return whether the library changed, and with it the list was cleared
     */
    public boolean setLibrary(@Nullable HostLibrary library) {
        if (library == this.library) {
            return false;
        }
        this.library = library;
        submitList(null);
        return true;
    }

    /** Asks every tile for its cover again: the library's covers or stand-ins changed. */
    public void refreshArt() {
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_ART);
    }

    /** The game at an adapter position, or null when the position is stale. */
    @Nullable
    public LibraryApp appAt(int position) {
        return position >= 0 && position < getItemCount() ? getItem(position) : null;
    }

    /** The current position of a game, or {@link RecyclerView#NO_POSITION}. */
    public int positionOf(int appId) {
        List<LibraryApp> list = getCurrentList();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).appId() == appId) {
                return i;
            }
        }
        return RecyclerView.NO_POSITION;
    }

    @Override
    public long getItemId(int position) {
        return getItem(position).appId();
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        this.recyclerView = recyclerView;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        this.recyclerView = null;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        GameTileView tile = new GameTileView(parent.getContext());
        int fixed = fixedTileWidth();
        // In a grid the cell's width is the column's; in a shelf it is the shelf's choice. The
        // tile takes its 3:4 height from either.
        tile.setLayoutParams(new RecyclerView.LayoutParams(
                fixed > 0 ? fixed : ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        final Holder holder = new Holder(tile);

        // Wired once per holder; each reads the game the holder shows at the time of the event.
        // That is the game it was last bound to, not this adapter's item at its position: the
        // shelves share one pool of tiles, so a tile made here may be bound by another shelf's
        // adapter, whose positions mean nothing in this one's list.
        tile.setOnClickListener(v -> {
            LibraryApp app = holder.app;
            if (app != null) {
                listener.onTileClicked(app);
            }
        });
        tile.setOnLongClickListener(v -> {
            LibraryApp app = holder.app;
            if (app == null) {
                return false;
            }
            listener.onTileLongPressed(app);
            return true;
        });
        tile.setOnFocusChangeListener((v, hasFocus) -> {
            LibraryApp app = hasFocus ? holder.app : null;
            if (app != null) {
                listener.onTileFocused(app);
            }
        });
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        bindFully(holder, getItem(position));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position, @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) {
            bindFully(holder, getItem(position));
            return;
        }
        LibraryApp app = getItem(position);
        holder.app = app;
        // Flags first: the tile keeps its cover through a rebind in the state it is in.
        holder.tile.bind(app, holder.tile.getArtState());
        if (payloads.contains(PAYLOAD_ART)) {
            requestArt(holder, app, true);
        }
    }

    @Override
    public void onViewRecycled(@NonNull Holder holder) {
        // A recycled tile waits in the pool, bound to nothing that will come back for it.
        cancelRequest(holder);
        super.onViewRecycled(holder);
    }

    private void bindFully(Holder holder, LibraryApp app) {
        boolean sameGame = holder.app != null && holder.app.appId() == app.appId();
        holder.app = app;
        if (sameGame) {
            // A rename, or a rebind for any other reason: still this game's cover.
            holder.tile.bind(app, holder.tile.getArtState());
            requestArt(holder, app, false);
            return;
        }
        cancelRequest(holder);
        holder.tile.bind(app, ArtState.LOADING);
        requestArt(holder, app, true);
    }

    /**
     * Asks the library for the tile's cover. A request still on its way for this game is left
     * to finish unless {@code force}; a memory hit answers before this returns.
     */
    private void requestArt(final Holder holder, final LibraryApp app, boolean force) {
        if (!force && holder.request != null && holder.request.isPending()) {
            return;
        }
        cancelRequest(holder);
        HostLibrary source = library;
        if (source == null || source.isClosed()) {
            return;
        }
        final int appId = app.appId();
        holder.request = source.loadArt(app.app, tileWidthPx(holder), (bitmap, state) -> {
            // A late answer for a game this tile no longer shows is dropped.
            if (holder.app == null || holder.app.appId() != appId) {
                return;
            }
            holder.tile.bind(holder.app, state);
            holder.tile.setArt(state == ArtState.ART ? bitmap : null);
        });
    }

    private static void cancelRequest(Holder holder) {
        if (holder.request != null) {
            holder.request.cancel();
            holder.request = null;
        }
    }

    // The tile's own width once laid out; before that, the column width the grid will give it.
    // The layout manager sets its span count before it lays out (and binds) any child. Failing
    // both, the standard box art width, which is what every host serves anyway.
    private int tileWidthPx(Holder holder) {
        int fixed = fixedTileWidth();
        if (fixed > 0) {
            return fixed;
        }
        int width = holder.tile.getWidth();
        if (width <= 0 && recyclerView != null) {
            int usable = recyclerView.getWidth() - recyclerView.getPaddingLeft()
                    - recyclerView.getPaddingRight();
            RecyclerView.LayoutManager lm = recyclerView.getLayoutManager();
            int spans = lm instanceof GridLayoutManager ? ((GridLayoutManager) lm).getSpanCount() : 1;
            width = usable / Math.max(1, spans);
        }
        return width > 0 ? width : FALLBACK_TILE_WIDTH_PX;
    }

    private int fixedTileWidth() {
        return recyclerView instanceof FixedTileWidth
                ? ((FixedTileWidth) recyclerView).tileWidthPx() : 0;
    }

    public static final class Holder extends RecyclerView.ViewHolder {
        final GameTileView tile;
        @Nullable
        LibraryApp app;
        @Nullable
        CachedAppAssetLoader.Request request;

        Holder(@NonNull GameTileView tile) {
            super(tile);
            this.tile = tile;
        }
    }
}
