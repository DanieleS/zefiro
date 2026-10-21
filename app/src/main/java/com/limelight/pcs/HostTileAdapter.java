package com.limelight.pcs;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.R;
import com.limelight.computers.HostEntry;
import com.limelight.nvstream.http.ComputerDetails;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The PCs grid: one {@code item_pc_tile} per {@link HostEntry}, drawn by {@link HostTileBinder},
 * then the Add PC tile, always last.
 *
 * <p>The repository publishes a fresh list on every poll of every host, every 1.5 s each.
 * {@link DiffUtil} compares only what a tile shows, so a poll that changed nothing on screen
 * changes nothing at all, and a real change rebinds just that tile, in place (change animations
 * are off in the fragment), so the d-pad stays where it was.
 *
 * <pre>
 *   position   0 .. n-1         n
 *              host tiles       Add PC            n = the hosts in the submitted list
 * </pre>
 *
 * The Add PC tile is outside the submitted list: the differ only ever reports hosts, all of them
 * before it, and the grid works out where it has moved to on its own.
 *
 * <h3>Stable ids</h3>
 *
 * Each host's UUID is given a number the first time it is seen, and keeps it for the adapter's
 * life. A hash of the UUID could collide; a position would follow the sort, not the host. The
 * Add PC tile has {@link #ADD_TILE_ID}, which no host is ever given.
 *
 * Main thread only.
 */
public class HostTileAdapter extends ListAdapter<HostEntry, RecyclerView.ViewHolder> {

    /** What the user does to a tile. */
    public interface Listener {
        void onCardClicked(@NonNull HostEntry host);

        void onCardLongPressed(@NonNull HostEntry host);

        /** The tile's own action button; the gamepad's X goes through the section instead. */
        void onActionClicked(@NonNull HostEntry host, int action);

        /** The tile's overflow button, which the actions are anchored to where they can be. */
        void onMoreClicked(@NonNull HostEntry host, @NonNull View anchor);

        /** A host tile took focus, for the section's focus memory. */
        void onCardFocused(@NonNull HostEntry host);

        /** The Add PC tile was chosen. */
        void onAddClicked();

        /** The Add PC tile took focus, or lost it. */
        void onAddFocusChanged(boolean hasFocus);
    }

    /** The Add PC tile's stable id. Host ids count up from 0, so they never reach it. */
    public static final long ADD_TILE_ID = Long.MAX_VALUE;

    static final int TYPE_HOST = 0;
    static final int TYPE_ADD = 1;

    private static final DiffUtil.ItemCallback<HostEntry> DIFF = new DiffUtil.ItemCallback<HostEntry>() {
        @Override
        public boolean areItemsTheSame(@NonNull HostEntry a, @NonNull HostEntry b) {
            return Objects.equals(a.details.uuid, b.details.uuid);
        }

        @Override
        public boolean areContentsTheSame(@NonNull HostEntry a, @NonNull HostEntry b) {
            return sameOnTile(a, b);
        }
    };

    private final HostTileBinder binder;
    private final Listener listener;
    private final Map<String, Long> ids = new HashMap<>();
    private boolean gamepadPresent;

    public HostTileAdapter(@NonNull HostTileBinder binder, @NonNull Listener listener) {
        super(DIFF);
        this.binder = binder;
        this.listener = listener;
        setHasStableIds(true);
    }

    /**
     * Whether two entries draw the same tile: everything {@link HostTileBinder#bind} reads, and
     * nothing else. The MAC matters because the Wake action depends on it, the running game's
     * name because the status line says it. The address, the games count and the hero image are
     * not on the tile, so a host that moves address or gets its hero image does not redraw it;
     * that is why every listener reads the entry at the time of the event.
     */
    static boolean sameOnTile(@NonNull HostEntry a, @NonNull HostEntry b) {
        ComputerDetails x = a.details;
        ComputerDetails y = b.details;
        return Objects.equals(x.name, y.name)
                && x.state == y.state
                && x.pairState == y.pairState
                && x.runningGameId == y.runningGameId
                && Objects.equals(x.macAddress, y.macAddress)
                && Objects.equals(a.runningAppName, b.runningAppName);
    }

    /**
     * Tells the tiles there is a gamepad: they then hold focus in touch mode and their action
     * wears the X glyph. See {@link HostTileBinder#bind}.
     */
    @SuppressLint("NotifyDataSetChanged") // every tile changes, and the ids are stable
    public void setGamepadPresent(boolean present) {
        if (gamepadPresent != present) {
            gamepadPresent = present;
            notifyDataSetChanged();
        }
    }

    /** The hosts, then the Add PC tile. */
    @Override
    public int getItemCount() {
        return hostCount() + 1;
    }

    /** How many host tiles there are, which is also the Add PC tile's position. */
    public int hostCount() {
        return super.getItemCount();
    }

    /** The host at an adapter position, or null for the Add PC tile or a stale position. */
    @Nullable
    public HostEntry entryAt(int position) {
        return position >= 0 && position < hostCount() ? getItem(position) : null;
    }

    /** The current position of a host, or {@link RecyclerView#NO_POSITION}. */
    public int positionOf(@Nullable String uuid) {
        if (uuid == null) {
            return RecyclerView.NO_POSITION;
        }
        List<HostEntry> hosts = getCurrentList();
        for (int i = 0; i < hosts.size(); i++) {
            if (uuid.equals(hosts.get(i).details.uuid)) {
                return i;
            }
        }
        return RecyclerView.NO_POSITION;
    }

    @Override
    public int getItemViewType(int position) {
        return position < hostCount() ? TYPE_HOST : TYPE_ADD;
    }

    @Override
    public long getItemId(int position) {
        if (position >= hostCount()) {
            return ADD_TILE_ID;
        }
        String uuid = getItem(position).details.uuid;
        Long id = ids.get(uuid);
        if (id == null) {
            id = (long) ids.size();
            ids.put(uuid, id);
        }
        return id;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_ADD) {
            View tile = inflater.inflate(R.layout.item_pc_add_tile, parent, false);
            tile.setOnClickListener(v -> listener.onAddClicked());
            tile.setOnFocusChangeListener((v, hasFocus) -> listener.onAddFocusChanged(hasFocus));
            return new AddHolder(tile);
        }

        View tile = inflater.inflate(R.layout.item_pc_tile, parent, false);
        final HostHolder holder = new HostHolder(tile);

        // Wired once per holder; each reads the entry the holder shows at the time of the event.
        tile.setOnClickListener(v -> {
            HostEntry host = entryAt(holder.getBindingAdapterPosition());
            if (host != null) {
                listener.onCardClicked(host);
            }
        });
        tile.setOnLongClickListener(v -> {
            HostEntry host = entryAt(holder.getBindingAdapterPosition());
            if (host == null) {
                return false;
            }
            listener.onCardLongPressed(host);
            return true;
        });
        tile.setOnFocusChangeListener((v, hasFocus) -> {
            HostEntry host = hasFocus ? entryAt(holder.getBindingAdapterPosition()) : null;
            if (host != null) {
                listener.onCardFocused(host);
            }
        });
        // The buttons too: sameOnTile lets a new address or games count through without a rebind,
        // so an entry kept from bind time would hand the actions an old address.
        holder.views.action.setOnClickListener(v -> {
            HostEntry host = entryAt(holder.getBindingAdapterPosition());
            int action = host != null ? HostTileBinder.actionFor(host) : HostTileBinder.ACTION_NONE;
            if (action != HostTileBinder.ACTION_NONE) {
                listener.onActionClicked(host, action);
            }
        });
        holder.views.more.setOnClickListener(v -> {
            HostEntry host = entryAt(holder.getBindingAdapterPosition());
            if (host != null) {
                listener.onMoreClicked(host, v);
            }
        });
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof HostHolder) {
            binder.bind(((HostHolder) holder).views, getItem(position), gamepadPresent);
        } else {
            holder.itemView.setFocusableInTouchMode(gamepadPresent);
        }
    }

    static final class HostHolder extends RecyclerView.ViewHolder {
        final HostTileBinder.TileViews views;

        HostHolder(@NonNull View tile) {
            super(tile);
            views = new HostTileBinder.TileViews(tile);
        }
    }

    static final class AddHolder extends RecyclerView.ViewHolder {
        AddHolder(@NonNull View tile) {
            super(tile);
        }
    }
}
