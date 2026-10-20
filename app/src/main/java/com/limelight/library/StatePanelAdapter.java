package com.limelight.library;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.R;

import java.util.List;

/**
 * The {@link HostStatePanel} as a row of the Library home: present while the Library has no
 * games to shelve (no host, connecting, offline, not paired, or nothing visible), gone otherwise.
 *
 * <p>See all lays the panel over its grid; the home lists it instead, under the header and the
 * hero, because both can be there at once: a host whose games are all hidden may well be
 * running one of them, and the hero must stay reachable over "No games". Main thread only.
 */
final class StatePanelAdapter extends RecyclerView.Adapter<StatePanelAdapter.Holder> {

    private static final Object PAYLOAD_STATE = new Object();

    private final HostStatePanel.Listener listener;

    private HostStatePanel.Mode mode = HostStatePanel.Mode.NONE;
    @Nullable
    private String hostName;
    private boolean allHidden;
    @Nullable
    private Holder bound;

    StatePanelAdapter(@NonNull HostStatePanel.Listener listener) {
        this.listener = listener;
    }

    /** Shows a mode, or removes the row for {@link HostStatePanel.Mode#NONE}. */
    void show(@NonNull HostStatePanel.Mode mode, @Nullable String hostName, boolean allHidden) {
        boolean was = this.mode != HostStatePanel.Mode.NONE;
        boolean is = mode != HostStatePanel.Mode.NONE;
        boolean same = mode == this.mode && allHidden == this.allHidden
                && (hostName == null ? this.hostName == null : hostName.equals(this.hostName));
        this.mode = mode;
        this.hostName = hostName;
        this.allHidden = allHidden;
        if (was && !is) {
            notifyItemRemoved(0);
        } else if (!was && is) {
            notifyItemInserted(0);
        } else if (is && !same) {
            notifyItemChanged(0, PAYLOAD_STATE);
        }
    }

    @NonNull
    HostStatePanel.Mode mode() {
        return mode;
    }

    /** The panel's way out (Wake, Pair, …) while it is laid out; null otherwise. */
    @Nullable
    View focusTarget() {
        if (bound == null || !bound.itemView.isShown()) {
            return null;
        }
        return bound.panel.focusTarget();
    }

    /** Whether the view is one of the panel's buttons. */
    boolean contains(@Nullable View view) {
        return bound != null && bound.panel.contains(view);
    }

    @Override
    public int getItemCount() {
        return mode == HostStatePanel.Mode.NONE ? 0 : 1;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_host_state_panel, parent, false);
        return new Holder(view, new HostStatePanel(view.findViewById(R.id.hostStatePanel), listener));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        bound = holder;
        holder.panel.show(mode, hostName, allHidden);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position, @NonNull List<Object> payloads) {
        onBindViewHolder(holder, position);
    }

    @Override
    public void onViewRecycled(@NonNull Holder holder) {
        if (bound == holder) {
            bound = null;
        }
        super.onViewRecycled(holder);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final HostStatePanel panel;

        Holder(@NonNull View view, @NonNull HostStatePanel panel) {
            super(view);
            this.panel = panel;
        }
    }
}
