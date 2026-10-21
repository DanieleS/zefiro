package com.limelight.settings;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/**
 * The settings list's first item, scrolling away with the rows: in a compact window the Settings
 * section's one header view, as the phone mock does; in a short wide one (a phone in landscape)
 * only its pinned card, while the search and the chips stay fixed above the list.
 *
 * <p>There is one header view, and it moves: into this item while it is shown, back to the
 * section's fixed slot otherwise ({@link #setContent}); so does the card. Each bind takes the
 * view from wherever it is and puts it in the holder being bound, so a holder recycled and made
 * again, or a list rebuilt after a reload, still ends up with the view and never with a copy.
 * Hidden, the item is not in the list at all, so the Thor's list starts at its first section.
 *
 * <p>Stable ids, as {@link androidx.preference.PreferenceGroupAdapter}'s are, so the two can sit
 * in one {@link androidx.recyclerview.widget.ConcatAdapter} with isolated stable ids.
 * Main thread only.
 */
final class SettingsHeaderAdapter extends RecyclerView.Adapter<SettingsHeaderAdapter.Holder> {

    // The view the item holds: the header, its card, or null while the item is not shown.
    @Nullable
    private View content;

    SettingsHeaderAdapter() {
        setHasStableIds(true);
    }

    /**
     * Puts a view in the list as its first item, or with null takes the item out (the section
     * then hangs the header in its slot).
     */
    void setContent(@Nullable View next) {
        if (content == next) {
            return;
        }
        View previous = content;
        content = next;
        if (previous == null) {
            notifyItemInserted(0);
        } else if (next == null) {
            notifyItemRemoved(0);
        } else {
            notifyItemChanged(0);
        }
    }

    boolean isShown() {
        return content != null;
    }

    @Override
    public int getItemCount() {
        return content != null ? 1 : 0;
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
        View view = content;
        if (view == null) {
            return;
        }
        ViewParent parent = view.getParent();
        if (parent == frame) {
            return;
        }
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(view);
        }
        // The view this holder held before (the header, now the card is the item) has moved on.
        frame.removeAllViews();
        frame.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    static final class Holder extends RecyclerView.ViewHolder {
        Holder(@NonNull View itemView) {
            super(itemView);
        }
    }
}
