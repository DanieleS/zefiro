package com.limelight.settings;

import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroupAdapter;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceViewHolder;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.GamepadHintsView;

/**
 * androidx's preference adapter, plus the two things a Zefiro settings row shows that a stock
 * one does not: its value on the right ({@link SettingValues}) and a dot when that value is not
 * the default ({@link SettingsDefaults}), both drawn by {@link SettingRowBinder} as a search
 * result's are. It also hands a long-press on a row to whoever asked
 * ({@link #setRowLongPressListener}): the Settings section's reset and pin menu.
 *
 * <p>A preference redraws its own row when it changes through itself, but some values are
 * written behind it: a resolution or frame rate change resets the bitrate straight into
 * storage. The fragment forwards every stored change to {@link #refreshKey(String)}, which
 * rebinds that row; a resolution or frame rate change also rebinds the bitrate, whose default
 * follows them. Rows inflated from another layout (a category header, the stock check box) have
 * none of these views and are left as they are.
 *
 * <p>A row's description is kept short, as the mocks' compact rows are ({@link SettingsLayout}):
 * one line in a wide window, two in a compact one, and all of it on the focused row while no
 * companion panel spells it out. The host says which ({@link #setRowLines}); the lines follow
 * focus on the rows themselves, without a rebind. So does a focused list row's "A Choices"
 * where the panel is not there to offer them, and the host asks for it.
 */
public class SettingsAdapter extends PreferenceGroupAdapter {

    private final SettingsDefaults defaults;
    @Nullable
    private final SharedPreferences prefs;
    @Nullable
    private RecyclerView recyclerView;

    // What a row's description lines follow (SettingsLayout.summaryLines). A profile editor's
    // list is never told: two lines, all of them on the focused row.
    private boolean wideRows;
    private boolean panelShowing;
    private boolean choiceHints;
    private final View.OnFocusChangeListener linesFollowFocus = (row, hasFocus) -> applyLines(row);

    /**
     * @param prefs where the current resolution and frame rate are read for the bitrate's
     *              default: the stored settings, or a profile editor's in-memory copy
     */
    public SettingsAdapter(@NonNull PreferenceScreen screen, @NonNull SettingsDefaults defaults,
                           @Nullable SharedPreferences prefs) {
        super(screen);
        this.defaults = defaults;
        this.prefs = prefs;
    }

    @NonNull
    public SettingsDefaults getDefaults() {
        return defaults;
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

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder, int position) {
        super.onBindViewHolder(holder, position);
        Preference preference = getItem(position);
        bindExtras(holder, preference);
        // Whether A on this row opens a list of choices, for its hint.
        holder.itemView.setTag(R.id.settingChoicesHint, preference != null
                && SettingsIndex.kindOf(preference) == SettingsSearch.Kind.LIST);
        if (holder.findViewById(android.R.id.summary) != null) {
            holder.itemView.setOnFocusChangeListener(linesFollowFocus);
            applyLines(holder.itemView);
        }
    }

    /**
     * How long the rows' descriptions are: a wide window's rows get one line, a compact one's
     * two; the focused row gets all of it unless the panel shows it. Applied to the rows on
     * screen at once, so the panel coming or going reshapes the focused row in place.
     *
     * @param choiceHints whether a focused list row says "A Choices" while the panel is not
     *                    up; shown only on a screen driven by the pad ({@link GamepadHintsView})
     */
    public void setRowLines(boolean wide, boolean panelShowing, boolean choiceHints) {
        if (wideRows == wide && this.panelShowing == panelShowing
                && this.choiceHints == choiceHints) {
            return;
        }
        wideRows = wide;
        this.panelShowing = panelShowing;
        this.choiceHints = choiceHints;
        RecyclerView list = recyclerView;
        if (list != null) {
            for (int i = 0; i < list.getChildCount(); i++) {
                applyLines(list.getChildAt(i));
            }
        }
    }

    // A row RecyclerView keeps in its cache comes back without a rebind, so it takes the current
    // line count here rather than keeping the one it had when it left the screen.
    @Override
    public void onViewAttachedToWindow(@NonNull PreferenceViewHolder holder) {
        super.onViewAttachedToWindow(holder);
        applyLines(holder.itemView);
    }

    private void applyLines(View row) {
        View summary = row.findViewById(android.R.id.summary);
        if (!(summary instanceof TextView) || !(row instanceof ViewGroup)) {
            return;
        }
        int lines = SettingsLayout.summaryLines(wideRows, row.hasFocus(), panelShowing);
        TextView text = (TextView) summary;
        if (text.getMaxLines() != lines) {
            text.setMaxLines(lines);
        }
        View hint = row.findViewById(R.id.settingChoicesHint);
        if (hint instanceof GamepadHintsView) {
            boolean shown = choiceHints && !panelShowing && row.hasFocus()
                    && Boolean.TRUE.equals(row.getTag(R.id.settingChoicesHint));
            ((GamepadHintsView) hint).setHints(shown
                    ? new GamepadHintsView.Hint[] {new GamepadHintsView.Hint(
                            row.getContext().getString(R.string.settings_choices_choices), "A")}
                    : new GamepadHintsView.Hint[0]);
        }
    }

    private void bindExtras(PreferenceViewHolder holder, @Nullable Preference preference) {
        TextView value = (TextView) holder.findViewById(R.id.settingValue);
        View dot = holder.findViewById(R.id.settingChangedDot);
        View external = holder.findViewById(R.id.settingExternal);
        if (preference == null || value == null || dot == null || external == null) {
            return;
        }
        SettingRowBinder.bindExtras(value, dot, external, preference, defaults, prefs);

        // Bound on every bind: androidx rebinds the row view, and a recycled one may have been
        // another preference's. Only a keyed row has a menu; a header or a stray has none.
        final RowLongPressListener listener = longPressListener;
        if (listener != null && preference.getKey() != null) {
            holder.itemView.setOnLongClickListener(v -> listener.onRowLongPressed(preference, v));
        } else {
            holder.itemView.setOnLongClickListener(null);
            holder.itemView.setLongClickable(false);
        }
    }

    /** A long-press on a row: the Settings section's reset and pin menu. */
    public interface RowLongPressListener {
        /** @return true when the press was taken */
        boolean onRowLongPressed(@NonNull Preference preference, @NonNull View row);
    }

    @Nullable
    private RowLongPressListener longPressListener;

    /** Where a long-press on a row goes; null for none (a profile editor's list). Main thread. */
    public void setRowLongPressListener(@Nullable RowLongPressListener listener) {
        this.longPressListener = listener;
        notifyDataSetChanged();
    }

    /** Rebinds the row for {@code key}, and the bitrate when its default may have moved. */
    public void refreshKey(@NonNull String key) {
        refreshRow(key);
        if (PreferenceConfiguration.RESOLUTION_PREF_STRING.equals(key)
                || PreferenceConfiguration.FPS_PREF_STRING.equals(key)) {
            refreshRow(PreferenceConfiguration.BITRATE_PREF_STRING);
        }
    }

    private void refreshRow(String key) {
        int position = getPreferenceAdapterPosition(key);
        if (position == RecyclerView.NO_POSITION) {
            return;
        }
        RecyclerView list = recyclerView;
        if (list != null && list.isComputingLayout()) {
            // A write made while the list lays out (a row's bind storing a value) rebinds after.
            list.post(() -> notifyItemChanged(position));
        } else {
            notifyItemChanged(position);
        }
    }
}
