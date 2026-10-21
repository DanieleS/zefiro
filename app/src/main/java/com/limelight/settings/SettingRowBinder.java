package com.limelight.settings;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;
import androidx.preference.TwoStatePreference;

import com.limelight.R;

/**
 * Draws a setting's row from its live {@link Preference}: the same value text, changed dot and
 * external-link icon in the list ({@link SettingsAdapter}) and in the search results
 * ({@link SettingsResultsAdapter}), so a setting found by search never looks different from the
 * same setting in the list.
 *
 * <p>The list's rows are androidx's: it binds their title, summary and switch, and only the
 * extras come from here. A result row is the section's own and is bound here in full, title,
 * where the setting lives, and the switch included.
 *
 * Main thread only.
 */
public final class SettingRowBinder {

    // A disabled result is drawn at Material's disabled emphasis, as a disabled list row is.
    private static final float DISABLED_ALPHA = 0.38f;

    private SettingRowBinder() {
    }

    /** The value on the right, the dot before the title, and the external-link icon. */
    public static void bindExtras(@NonNull TextView value, @NonNull View dot, @NonNull View external,
                                  @NonNull Preference preference, @NonNull SettingsDefaults defaults,
                                  @Nullable SharedPreferences prefs) {
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

    /**
     * A search result row.
     *
     * @param place where the setting lives, "Controllers › Rumble"; a disabled setting adds
     *              what it waits for, "Needs Enable rumble"
     */
    public static void bindResult(@NonNull View row, @NonNull Preference preference,
                                  @NonNull CharSequence place, @NonNull SettingsDefaults defaults,
                                  @Nullable SharedPreferences prefs) {
        TextView title = row.findViewById(R.id.settingResultTitle);
        TextView where = row.findViewById(R.id.settingResultPlace);
        TextView value = row.findViewById(R.id.settingValue);
        View dot = row.findViewById(R.id.settingChangedDot);
        View external = row.findViewById(R.id.settingExternal);
        CompoundButton toggle = row.findViewById(R.id.settingResultSwitch);

        title.setText(preference.getTitle());
        boolean enabled = preference.isEnabled();
        CharSequence needs = enabled ? null : dependencyTitle(preference);
        where.setText(needs == null ? place : row.getContext().getString(
                R.string.settings_result_needs, place, needs));

        bindExtras(value, dot, external, preference, defaults, prefs);
        if (preference instanceof TwoStatePreference) {
            toggle.setVisibility(View.VISIBLE);
            toggle.setChecked(((TwoStatePreference) preference).isChecked());
        } else {
            toggle.setVisibility(View.GONE);
        }

        // Dimmed, not disabled: the row stays a stop the d-pad can rest on and read, and a
        // click on it does nothing, as the preference itself refuses a click while disabled.
        float alpha = enabled ? 1f : DISABLED_ALPHA;
        title.setAlpha(alpha);
        value.setAlpha(alpha);
        toggle.setAlpha(alpha);
        toggle.setEnabled(enabled);
    }

    /** The title of the setting this one waits on, or null when it waits on none. */
    @Nullable
    static CharSequence dependencyTitle(@NonNull Preference preference) {
        String dependency = preference.getDependency();
        PreferenceManager manager = preference.getPreferenceManager();
        if (dependency == null || manager == null) {
            return null;
        }
        Preference other = manager.findPreference(dependency);
        return other != null ? other.getTitle() : null;
    }
}
