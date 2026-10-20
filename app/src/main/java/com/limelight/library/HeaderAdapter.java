package com.limelight.library;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.pcs.HostCardBinder;

import java.util.List;

/**
 * The Library home's first row: "Library" and the host chip, which names the selected PC behind
 * its status dot and opens the host sheet.
 *
 * <p>One row, except on a short window (a phone in landscape), where the home leaves it out
 * ({@link #setShown}): the landscape mock has no page title over its shelves, the rail says where
 * the user is, and All games names the host. X still raises the host sheet there.
 *
 * <p>The chip changes in place ({@link #bind}): the host's name and state come with every poll,
 * and a full rebind of the row would flicker the chip's focus ring under the d-pad each time.
 * Main thread only.
 */
final class HeaderAdapter extends RecyclerView.Adapter<HeaderAdapter.Holder> {

    interface Listener {
        /** The chip was pressed; the sheet drops from it. */
        void onHostChip(@NonNull View chip);
    }

    private static final Object PAYLOAD_CHIP = new Object();

    private final Listener listener;

    @Nullable
    private String hostName;
    @ColorRes
    private int dotColor = R.color.content_faint;
    @Nullable
    private String status;
    private boolean hostSelected;
    private boolean shown = true;
    @Nullable
    private Holder bound;

    HeaderAdapter(@NonNull Listener listener) {
        this.listener = listener;
    }

    /**
     * What the chip shows.
     *
     * @param hostName the selected host's name as last polled; null before it has answered once
     * @param details  that host as last polled, for its dot; null likewise
     * @param selected whether a host is selected at all; with none the chip asks for one
     */
    void bind(@Nullable String hostName, @Nullable ComputerDetails details, boolean selected,
              @NonNull Context context) {
        int color = details != null ? HostCardBinder.statusColor(details) : R.color.content_faint;
        String state = details != null ? context.getString(HostCardBinder.statusLabel(details)) : null;
        if (selected == hostSelected && color == dotColor && equal(hostName, this.hostName)
                && equal(state, status)) {
            return;
        }
        this.hostName = hostName;
        this.dotColor = color;
        this.status = state;
        this.hostSelected = selected;
        if (shown) {
            notifyItemChanged(0, PAYLOAD_CHIP);
        }
    }

    /** Puts the row in the list, or takes it out. */
    void setShown(boolean show) {
        if (show == shown) {
            return;
        }
        shown = show;
        if (show) {
            notifyItemInserted(0);
        } else {
            notifyItemRemoved(0);
        }
    }

    /** The chip, while the header is laid out; null otherwise. */
    @Nullable
    View chip() {
        return bound != null && bound.chip.isShown() ? bound.chip : null;
    }

    boolean isChip(@Nullable View view) {
        return view != null && bound != null && view == bound.chip;
    }

    @Override
    public int getItemCount() {
        return shown ? 1 : 0;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_library_header, parent, false);
        Holder holder = new Holder(view);
        holder.chip.setOnClickListener(listener::onHostChip);
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        bound = holder;
        Context context = holder.itemView.getContext();
        String name;
        if (!hostSelected) {
            name = context.getString(R.string.library_choose_pc);
        } else if (hostName != null && !hostName.isEmpty()) {
            name = hostName;
        } else {
            name = context.getString(R.string.library_this_pc);
        }
        holder.name.setText(name);
        ViewCompat.setBackgroundTintList(holder.dot,
                ColorStateList.valueOf(ContextCompat.getColor(context, dotColor)));
        holder.dot.setVisibility(hostSelected ? View.VISIBLE : View.GONE);
        holder.chip.setContentDescription(status != null
                ? context.getString(R.string.library_host_chip_a11y, name, status) : name);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position, @NonNull List<Object> payloads) {
        // The chip is all there is to change; the payload only spares the change animation.
        onBindViewHolder(holder, position);
    }

    @Override
    public void onViewRecycled(@NonNull Holder holder) {
        if (bound == holder) {
            bound = null;
        }
        super.onViewRecycled(holder);
    }

    private static boolean equal(@Nullable String a, @Nullable String b) {
        return a == null ? b == null : a.equals(b);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View chip;
        final View dot;
        final TextView name;

        Holder(@NonNull View view) {
            super(view);
            chip = view.findViewById(R.id.libraryHostChip);
            dot = view.findViewById(R.id.libraryHostChipDot);
            name = view.findViewById(R.id.libraryHostChipName);
        }
    }
}
