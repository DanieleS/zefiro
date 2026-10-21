package com.limelight.settings;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.TwoStatePreference;

import com.limelight.R;
import com.limelight.preferences.SeekBarPreference;
import com.limelight.preferences.WebLauncherPreference;

/**
 * What a settings row shows on its right, and the value it compares with its default, read from
 * the live {@link Preference} so a row always agrees with what a click on it would change.
 *
 * <p>A switch row shows its switch and a plain action row shows nothing, so both have no text
 * here; a row that opens a web page has no value either, but says so with an icon
 * ({@link #isExternal(Preference)}).
 */
public final class SettingValues {

    private SettingValues() {
    }

    /** The right-hand text: a list's entry, a text's value, a slider's value with its unit. */
    @Nullable
    public static CharSequence display(@NonNull Preference preference) {
        if (preference instanceof ListPreference) {
            return ((ListPreference) preference).getEntry();
        }
        if (preference instanceof EditTextPreference) {
            String text = ((EditTextPreference) preference).getText();
            return TextUtils.isEmpty(text)
                    ? preference.getContext().getString(R.string.settings_value_not_set) : text;
        }
        if (preference instanceof SeekBarPreference) {
            return ((SeekBarPreference) preference).getDisplayValue();
        }
        // TwoStatePreference: the switch shows. Anything else is an action.
        return null;
    }

    /** True for a row that leaves the app for a web page. */
    public static boolean isExternal(@NonNull Preference preference) {
        return preference instanceof WebLauncherPreference;
    }

    /**
     * The value in effect, typed as the preference stores it (String, Boolean or Integer), for
     * comparing with {@link SettingsDefaults}; null for a row that stores nothing.
     */
    @Nullable
    public static Object current(@NonNull Preference preference) {
        if (preference instanceof ListPreference) {
            return ((ListPreference) preference).getValue();
        }
        if (preference instanceof TwoStatePreference) {
            return ((TwoStatePreference) preference).isChecked();
        }
        if (preference instanceof EditTextPreference) {
            return ((EditTextPreference) preference).getText();
        }
        if (preference instanceof SeekBarPreference) {
            return ((SeekBarPreference) preference).getValue();
        }
        return null;
    }
}
