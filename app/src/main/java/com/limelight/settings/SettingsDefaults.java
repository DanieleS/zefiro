package com.limelight.settings;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.SeekBarPreference;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Every setting's default, so a row can mark a value that differs from it and a reset can put it
 * back.
 *
 * <p>androidx keeps a preference's default to itself. The defaults are recovered the way the
 * preferences themselves apply them: {@code R.xml.preferences} is inflated against a throwaway
 * {@link PreferenceManager} whose {@link PreferenceDataStore} records each value a preference
 * writes while setting its initial value, and with nothing stored that value is the default.
 * That is the data-store mechanism {@code EditProfileActivity} already uses, so nothing touches
 * the real settings. {@link SeekBarPreference} never stores its default, so it is asked for it.
 *
 * <p>Two defaults are not constants and are special-cased. The bitrate's follows the resolution
 * and frame rate, as the resolution and FPS listeners reset it. The metered bitrate's is 0,
 * which {@link PreferenceConfiguration} reads as "a quarter of the bitrate" and writes back
 * whenever it finds nothing stored.
 */
public final class SettingsDefaults {

    private static final String METERED_BITRATE_PREF_STRING = "seekbar_metered_bitrate_kbps";

    private final Map<String, Object> defaults;

    SettingsDefaults(@NonNull Map<String, Object> defaults) {
        this.defaults = Collections.unmodifiableMap(new HashMap<>(defaults));
    }

    /** Inflates the settings off the real storage. Main thread; not cheap: once per list, not per row. */
    @SuppressLint("RestrictedApi") // a standalone manager is the only way to inflate off-screen
    @NonNull
    public static SettingsDefaults read(@NonNull Context context) {
        PreferenceManager manager = new PreferenceManager(context);
        RecordingDataStore recorder = new RecordingDataStore();
        manager.setPreferenceDataStore(recorder);
        PreferenceScreen screen = manager.inflateFromResource(context, R.xml.preferences, null);

        Map<String, Object> values = new HashMap<>(recorder.values);
        addSeekBarDefaults(screen, values);
        values.put(METERED_BITRATE_PREF_STRING, 0);
        return new SettingsDefaults(values);
    }

    private static void addSeekBarDefaults(PreferenceGroup group, Map<String, Object> values) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference preference = group.getPreference(i);
            if (preference instanceof PreferenceGroup) {
                addSeekBarDefaults((PreferenceGroup) preference, values);
            } else if (preference instanceof SeekBarPreference && preference.getKey() != null) {
                values.put(preference.getKey(), ((SeekBarPreference) preference).getDefaultProgress());
            }
        }
    }

    /** Every recorded default, by key: String, Boolean or Integer as the preference stores it. */
    @NonNull
    public Map<String, Object> asMap() {
        return defaults;
    }

    /**
     * The key's default as things stand: for the bitrate, the one the current resolution and
     * frame rate in {@code prefs} give. Null when the key has none (an action, an unset list).
     */
    @Nullable
    public Object defaultFor(@NonNull String key, @Nullable SharedPreferences prefs) {
        if (PreferenceConfiguration.BITRATE_PREF_STRING.equals(key)) {
            return defaultBitrate(prefs);
        }
        return defaults.get(key);
    }

    /** True when {@code value} is not the key's default. A key without a default never is. */
    public boolean isChanged(@NonNull String key, @Nullable Object value,
                             @Nullable SharedPreferences prefs) {
        Object def = defaultFor(key, prefs);
        if (def == null || value == null) {
            return false;
        }
        return !def.equals(value);
    }

    /** {@link #isChanged(String, Object, SharedPreferences)} for a live preference's value. */
    public boolean isChanged(@NonNull Preference preference, @Nullable SharedPreferences prefs) {
        String key = preference.getKey();
        return key != null && isChanged(key, SettingValues.current(preference), prefs);
    }

    @Nullable
    private static Integer defaultBitrate(@Nullable SharedPreferences prefs) {
        String res = PreferenceConfiguration.DEFAULT_RESOLUTION;
        String fps = PreferenceConfiguration.DEFAULT_FPS;
        if (prefs != null) {
            res = prefs.getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, res);
            fps = prefs.getString(PreferenceConfiguration.FPS_PREF_STRING, fps);
        }
        try {
            return PreferenceConfiguration.getDefaultBitrate(res, fps);
        } catch (RuntimeException e) {
            // A malformed stored value; with no default to compare, the row shows no dot.
            return null;
        }
    }

    /**
     * Answers every read with the caller's default (or what was written since) and keeps every
     * write, so a preference attaching to it writes exactly its default.
     */
    private static final class RecordingDataStore extends PreferenceDataStore {
        final Map<String, Object> values = new HashMap<>();

        @Override
        public void putString(String key, @Nullable String value) {
            values.put(key, value);
        }

        @Override
        public void putStringSet(String key, @Nullable Set<String> value) {
            values.put(key, value);
        }

        @Override
        public void putInt(String key, int value) {
            values.put(key, value);
        }

        @Override
        public void putLong(String key, long value) {
            values.put(key, value);
        }

        @Override
        public void putFloat(String key, float value) {
            values.put(key, value);
        }

        @Override
        public void putBoolean(String key, boolean value) {
            values.put(key, value);
        }

        @Nullable
        @Override
        public String getString(String key, @Nullable String defValue) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : defValue;
        }

        @SuppressWarnings("unchecked")
        @Nullable
        @Override
        public Set<String> getStringSet(String key, @Nullable Set<String> defValues) {
            Object value = values.get(key);
            return value instanceof Set ? (Set<String>) value : defValues;
        }

        @Override
        public int getInt(String key, int defValue) {
            Object value = values.get(key);
            return value instanceof Integer ? (Integer) value : defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            Object value = values.get(key);
            return value instanceof Long ? (Long) value : defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            Object value = values.get(key);
            return value instanceof Float ? (Float) value : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : defValue;
        }
    }
}
