package com.limelight.settings;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.TwoStatePreference;

import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;
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
            return textValue(preference, ((EditTextPreference) preference).getText());
        }
        if (preference instanceof SeekBarPreference) {
            return ((SeekBarPreference) preference).getDisplayValue();
        }
        // TwoStatePreference: the switch shows. Anything else is an action.
        return null;
    }

    /**
     * Some value of this preference, written as its row would show it: the default a reset
     * would put back, or the value the active profile sets in its place. A switch's is On or
     * Off here, since there is no switch to show it. Null when the preference shows no value.
     */
    @Nullable
    public static CharSequence displayFor(@NonNull Preference preference, @Nullable Object value) {
        if (value == null) {
            return null;
        }
        if (preference instanceof ListPreference) {
            ListPreference list = (ListPreference) preference;
            int index = list.findIndexOfValue(String.valueOf(value));
            CharSequence[] entries = list.getEntries();
            return index >= 0 && entries != null && index < entries.length
                    ? entries[index] : String.valueOf(value);
        }
        if (preference instanceof TwoStatePreference && value instanceof Boolean) {
            return onOff(preference, (Boolean) value);
        }
        if (preference instanceof SeekBarPreference && value instanceof Number) {
            return ((SeekBarPreference) preference).formatValue(((Number) value).intValue());
        }
        if (preference instanceof EditTextPreference) {
            return textValue(preference, String.valueOf(value));
        }
        return null;
    }

    /**
     * A typed value as its row shows it: with its unit where the setting has one ("20 Mbps",
     * "59.94 Hz"), as a slider's value is; "Not set" for none.
     */
    @NonNull
    private static CharSequence textValue(@NonNull Preference preference, @Nullable String text) {
        Context context = preference.getContext();
        if (TextUtils.isEmpty(text)) {
            return context.getString(R.string.settings_value_not_set);
        }
        int unit = unitOf(preference.getKey());
        return unit != 0 ? context.getString(R.string.settings_value_with_unit, text,
                context.getString(unit)) : text;
    }

    // The unit a typed setting is entered in, as its dialog asks for it; 0 for none.
    @StringRes
    private static int unitOf(@Nullable String key) {
        if (PreferenceConfiguration.CUSTOM_BITRATE_PREF_STRING.equals(key)) {
            return R.string.suffix_seekbar_bitrate_mbps;
        }
        if (PreferenceConfiguration.CUSTOM_REFRESH_RATE_PREF_STRING.equals(key)) {
            return R.string.settings_unit_hz;
        }
        return 0;
    }

    /** A switch's state in words, for where the switch itself is not drawn (the pinned card). */
    @NonNull
    public static CharSequence onOff(@NonNull Preference preference, boolean on) {
        return preference.getContext().getString(on ? R.string.settings_value_on
                : R.string.settings_value_off);
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
