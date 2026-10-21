package com.limelight.settings;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroupAdapter;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceViewHolder;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;

/**
 * androidx's preference adapter, plus the two things a Zefiro settings row shows that a stock
 * one does not: its value on the right ({@link SettingValues}) and a dot when that value is not
 * the default ({@link SettingsDefaults}).
 *
 * <p>A preference redraws its own row when it changes through itself, but some values are
 * written behind it: a resolution or frame rate change resets the bitrate straight into
 * storage. The fragment forwards every stored change to {@link #refreshKey(String)}, which
 * rebinds that row; a resolution or frame rate change also rebinds the bitrate, whose default
 * follows them. Rows inflated from another layout (a category header, the stock check box) have
 * none of these views and are left as they are.
 */
public class SettingsAdapter extends PreferenceGroupAdapter {

    private final SettingsDefaults defaults;
    @Nullable
    private final SharedPreferences prefs;
    @Nullable
    private RecyclerView recyclerView;

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
        bindExtras(holder, getItem(position));
    }

    private void bindExtras(PreferenceViewHolder holder, @Nullable Preference preference) {
        TextView value = (TextView) holder.findViewById(R.id.settingValue);
        View dot = holder.findViewById(R.id.settingChangedDot);
        View external = holder.findViewById(R.id.settingExternal);
        if (preference == null || value == null || dot == null || external == null) {
            return;
        }

        boolean changed = defaults.isChanged(preference, prefs);
        dot.setVisibility(changed ? View.VISIBLE : View.GONE);

        CharSequence text = SettingValues.display(preference);
        value.setText(text);
        value.setVisibility(text == null ? View.GONE : View.VISIBLE);
        // A changed value carries the signal, as in the mocks; a disabled row dims it.
        value.setTextColor(changed && preference.isEnabled()
                ? ColorStateList.valueOf(ContextCompat.getColor(value.getContext(), R.color.signal))
                : ContextCompat.getColorStateList(value.getContext(), R.color.settings_value));

        external.setVisibility(SettingValues.isExternal(preference) ? View.VISIBLE : View.GONE);
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
