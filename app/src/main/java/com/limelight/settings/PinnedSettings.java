package com.limelight.settings;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.limelight.preferences.PreferenceConfiguration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * What the pinned card at the top of Settings holds: the essentials every stream depends on
 * (resolution, frame rate, bitrate, codec, HDR), always, then the settings the user pinned, in
 * the order they were pinned.
 *
 * <h3>Storage</h3>
 *
 * The user's pins live in their own preferences file, {@value #FILE}, under {@value #KEY_PINNED},
 * never in the default preferences: profiles and the settings export read those, and a pin is
 * about this screen, not about how a game streams. The keys are stored as one comma-joined
 * string rather than a string set, because a set does not keep the order.
 *
 * <p>At most {@value #MAX_USER_PINS} user pins. A pinned key this device does not have (a vibration
 * setting on a device without a vibrator, say) stays stored but is not shown, so the pin comes
 * back if the same settings are restored on a device that has it.
 *
 * Main thread only.
 */
public final class PinnedSettings {

    static final String FILE = "settings_ui";
    static final String KEY_PINNED = "pinned_keys";
    public static final int MAX_USER_PINS = 8;

    /** Always on the card, in this order, and never unpinned. */
    public static final List<String> ESSENTIALS = Collections.unmodifiableList(Arrays.asList(
            PreferenceConfiguration.RESOLUTION_PREF_STRING,
            PreferenceConfiguration.FPS_PREF_STRING,
            PreferenceConfiguration.BITRATE_PREF_STRING,
            "video_format",
            "checkbox_enable_hdr"));

    private final SharedPreferences store;

    public PinnedSettings(@NonNull Context context) {
        this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE));
    }

    PinnedSettings(@NonNull SharedPreferences store) {
        this.store = store;
    }

    /** The user's pins, oldest first, whether or not this device has them. */
    @NonNull
    public List<String> userPins() {
        return parse(store.getString(KEY_PINNED, null));
    }

    public static boolean isEssential(@Nullable String key) {
        return key != null && ESSENTIALS.contains(key);
    }

    public boolean isPinned(@Nullable String key) {
        return key != null && userPins().contains(key);
    }

    public boolean isFull() {
        return userPins().size() >= MAX_USER_PINS;
    }

    /** Pins a setting at the end of the card. False for an essential, a pin already there, or a full card. */
    public boolean pin(@NonNull String key) {
        List<String> pins = userPins();
        if (isEssential(key) || pins.contains(key) || pins.size() >= MAX_USER_PINS) {
            return false;
        }
        pins.add(key);
        store.edit().putString(KEY_PINNED, join(pins)).apply();
        return true;
    }

    /** Takes a user pin off the card; false when it was not on it. */
    public boolean unpin(@NonNull String key) {
        List<String> pins = userPins();
        if (!pins.remove(key)) {
            return false;
        }
        store.edit().putString(KEY_PINNED, join(pins)).apply();
        return true;
    }

    /**
     * The card's keys in order, essentials first, keeping only those this device has
     * ({@code present}: the keys of the live settings list).
     */
    @NonNull
    public List<String> shown(@NonNull Collection<String> present) {
        List<String> out = new ArrayList<>();
        for (String key : ESSENTIALS) {
            if (present.contains(key)) {
                out.add(key);
            }
        }
        for (String key : userPins()) {
            if (present.contains(key) && !out.contains(key)) {
                out.add(key);
            }
        }
        return out;
    }

    @NonNull
    static List<String> parse(@Nullable String stored) {
        List<String> out = new ArrayList<>();
        if (stored == null || stored.isEmpty()) {
            return out;
        }
        for (String key : stored.split(",")) {
            String k = key.trim();
            if (!k.isEmpty() && !out.contains(k)) {
                out.add(k);
            }
        }
        return out;
    }

    @NonNull
    static String join(@NonNull List<String> keys) {
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(key);
        }
        return out.toString();
    }
}
