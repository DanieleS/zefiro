package com.limelight.settings;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * The settings list's first item in a compact window: the Settings section's one header view,
 * so it scrolls away with the rows as the phone mock does.
 *
 * <p>There is one header view, and it moves: into this item while it is shown, back to the
 * section's fixed slot otherwise ({@link #setShown}). Each bind takes the view from wherever it
 * is and puts it in the holder being bound, so a holder recycled and made again, or a list
 * rebuilt after a reload, still ends up with the header and never with a copy. Hidden, the item
 * is not in the list at all, so a wide window's list starts at its first section.
 *
 * <p>Stable ids, as {@link androidx.preference.PreferenceGroupAdapter}'s are, so the two can sit
 * in one {@link androidx.recyclerview.widget.ConcatAdapter} with isolated stable ids.
 * Main thread only.
 */
final class SettingsHeaderAdapter extends RecyclerView.Adapter<SettingsHeaderAdapter.Holder> {

    private final View header;
    private boolean shown;

    SettingsHeaderAdapter(@NonNull View header) {
        this.header = header;
        setHasStableIds(true);
    }

    /** Puts the header in the list, or takes it out (the section then hangs it in its slot). */
    void setShown(boolean shown) {
        if (this.shown == shown) {
            return;
        }
        this.shown = shown;
        if (shown) {
            notifyItemInserted(0);
        } else {
            notifyItemRemoved(0);
        }
    }

    boolean isShown() {
        return shown;
    }

    @Override
    public int getItemCount() {
        return shown ? 1 : 0;
    }

    @Override
    public long getItemId(int position) {
        return 0;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        FrameLayout frame = new FrameLayout(parent.getContext());
        frame.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return new Holder(frame);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        FrameLayout frame = (FrameLayout) holder.itemView;
        ViewParent parent = header.getParent();
        if (parent == frame) {
            return;
        }
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(header);
        }
        frame.addView(header, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    static final class Holder extends RecyclerView.ViewHolder {
        Holder(@NonNull View itemView) {
            super(itemView);
        }
    }
}
