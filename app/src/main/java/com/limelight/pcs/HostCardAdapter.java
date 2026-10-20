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
import java.util.Map;
import java.util.Objects;

/**
 * The PCs section's list: one {@code pc_card_item} per {@link HostEntry}, drawn by
 * {@link HostCardBinder}.
 *
 * <p>The repository publishes a fresh list on every poll of every host, every 1.5 s each. The old
 * list called {@code notifyDataSetChanged()} on each, rebinding every card and, with the change
 * animation, swapping the focused card's view for a new one mid-press. Here {@link DiffUtil}
 * compares only what a card shows, so a poll that changed nothing on screen changes nothing at
 * all, and a real change rebinds just that card, in place (change animations are off in the
 * fragment), so the d-pad stays where it was.
 *
 * <h3>Stable ids</h3>
 *
 * Each host's UUID is given a number the first time it is seen, and keeps it for the adapter's
 * life. A hash of the UUID could collide; a position would follow the sort, not the host.
 *
 * Main thread only.
 */
public class HostCardAdapter extends ListAdapter<HostEntry, HostCardAdapter.Holder> {

    /** What the user does to a card. */
    public interface Listener {
        void onCardClicked(@NonNull HostEntry host);

        void onCardLongPressed(@NonNull HostEntry host);

        /** The card's own action button; the gamepad's X goes through the section instead. */
        void onActionClicked(@NonNull HostEntry host, int action);

        /** A card took focus, for the section's focus memory. */
        void onCardFocused(@NonNull HostEntry host);
    }

    private static final DiffUtil.ItemCallback<HostEntry> DIFF = new DiffUtil.ItemCallback<HostEntry>() {
        @Override
        public boolean areItemsTheSame(@NonNull HostEntry a, @NonNull HostEntry b) {
            return Objects.equals(a.details.uuid, b.details.uuid);
        }

        @Override
        public boolean areContentsTheSame(@NonNull HostEntry a, @NonNull HostEntry b) {
            return sameOnCard(a, b);
        }
    };

    private final HostCardBinder binder;
    private final Listener listener;
    private final Map<String, Long> ids = new HashMap<>();
    private boolean gamepadPresent;

    public HostCardAdapter(@NonNull HostCardBinder binder, @NonNull Listener listener) {
        super(DIFF);
        this.binder = binder;
        this.listener = listener;
        setHasStableIds(true);
    }

    /**
     * Whether two entries draw the same card: everything {@link HostCardBinder#bind} reads, and
     * nothing else. The address and the MAC matter because the status line shows the one and the
     * Wake action depends on the other; the hero image is compared by identity, since entries
     * carry the same bitmap forward until the game changes.
     */
    static boolean sameOnCard(@NonNull HostEntry a, @NonNull HostEntry b) {
        ComputerDetails x = a.details;
        ComputerDetails y = b.details;
        return Objects.equals(x.name, y.name)
                && x.state == y.state
                && x.pairState == y.pairState
                && Objects.equals(x.activeAddress, y.activeAddress)
                && x.runningGameId == y.runningGameId
                && Objects.equals(x.macAddress, y.macAddress)
                && Objects.equals(a.runningAppName, b.runningAppName)
                && a.runningBackground == b.runningBackground;
    }

    /** The card's action for this host; see {@link HostCardBinder#actionFor}. */
    public static int actionFor(@NonNull HostEntry host) {
        return HostCardBinder.actionFor(host);
    }

    /**
     * Tells the cards there is a gamepad: they then hold focus in touch mode and their action
     * wears the X glyph. See {@link HostCardBinder#bind}.
     */
    @SuppressLint("NotifyDataSetChanged") // every card changes, and the ids are stable
    public void setGamepadPresent(boolean present) {
        if (gamepadPresent != present) {
            gamepadPresent = present;
            notifyDataSetChanged();
        }
    }

    /** The entry at an adapter position, or null when the position is stale. */
    @Nullable
    public HostEntry entryAt(int position) {
        return position >= 0 && position < getItemCount() ? getItem(position) : null;
    }

    /** The current position of a host, or {@link RecyclerView#NO_POSITION}. */
    public int positionOf(@Nullable String uuid) {
        if (uuid == null) {
            return RecyclerView.NO_POSITION;
        }
        for (int i = 0; i < getItemCount(); i++) {
            if (uuid.equals(getItem(i).details.uuid)) {
                return i;
            }
        }
        return RecyclerView.NO_POSITION;
    }

    @Override
    public long getItemId(int position) {
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
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View card = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.pc_card_item, parent, false);
        final Holder holder = new Holder(card);

        // Wired once per holder; each reads the entry the holder shows at the time of the event.
        card.setOnClickListener(v -> {
            HostEntry host = entryAt(holder.getBindingAdapterPosition());
            if (host != null) {
                listener.onCardClicked(host);
            }
        });
        card.setOnLongClickListener(v -> {
            HostEntry host = entryAt(holder.getBindingAdapterPosition());
            if (host == null) {
                return false;
            }
            listener.onCardLongPressed(host);
            return true;
        });
        card.setOnFocusChangeListener((v, hasFocus) -> {
            HostEntry host = hasFocus ? entryAt(holder.getBindingAdapterPosition()) : null;
            if (host != null) {
                listener.onCardFocused(host);
            }
        });
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        binder.bind(holder.views, getItem(position), gamepadPresent, listener::onActionClicked);
    }

    public static final class Holder extends RecyclerView.ViewHolder {
        final HostCardBinder.CardViews views;

        Holder(@NonNull View card) {
            super(card);
            views = new HostCardBinder.CardViews(card);
        }
    }
}
