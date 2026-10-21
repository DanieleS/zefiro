package com.limelight.utils;

import android.content.Context;
import android.content.SharedPreferences;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;

/**
 * When each game was last launched, as seen by this device.
 *
 * <p>This is deliberately local. The host knows when a game was really last played — including
 * sessions played at the PC itself — but it does not report that, so "last played" here means
 * "last launched from this device". Entries are kept per computer, since the same game on two
 * hosts is two library entries.
 */
public class AppLaunchHistory {
    private static final String PREF_FILENAME = "AppLaunchHistory";

    private final SharedPreferences prefs;
    private final String computerUuid;

    public AppLaunchHistory(Context context, String computerUuid) {
        this.prefs = context.getSharedPreferences(PREF_FILENAME, Context.MODE_PRIVATE);
        this.computerUuid = computerUuid == null ? "" : computerUuid;
    }

    /** Milliseconds since the epoch, or 0 when this device has never launched the game. */
    public long getLastPlayed(NvApp app) {
        return prefs.getLong(keyFor(computerUuid, app), 0);
    }

    /**
     * Calls {@code onChange} on the main thread whenever a launch on this computer is recorded,
     * by whoever records it. Launches are recorded by a static call from the launch path, which
     * knows nothing about the screens that sort by this history, so they listen here instead of
     * being told. Preferences hold their listeners weakly: keep the returned handle for as long
     * as you watch, and pass it to {@link #unwatch} when done.
     */
    public SharedPreferences.OnSharedPreferenceChangeListener watch(final Runnable onChange) {
        final String prefix = computerUuid + "|";
        SharedPreferences.OnSharedPreferenceChangeListener listener = (changed, key) -> {
            // A null key is the whole file cleared (API 30+). The prefix is matched ignoring case
            // because UUIDs reach here from more than one source, not all upper-cased alike.
            if (key == null || key.regionMatches(true, 0, prefix, 0, prefix.length())) {
                onChange.run();
            }
        };
        prefs.registerOnSharedPreferenceChangeListener(listener);
        return listener;
    }

    /** Stops a {@link #watch}; null is a no-op. */
    public void unwatch(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        if (listener != null) {
            prefs.unregisterOnSharedPreferenceChangeListener(listener);
        }
    }

    /** Notes that a game is being launched now. */
    public static void recordLaunch(Context context, ComputerDetails computer, NvApp app) {
        if (computer == null || app == null) {
            return;
        }
        context.getSharedPreferences(PREF_FILENAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(keyFor(computer.uuid, app), System.currentTimeMillis())
                .apply();
    }

    // The app's UUID is the stable identity where the host reports one; the numeric ID is the
    // fallback for hosts that don't, and can be reassigned when the host's app list changes.
    private static String keyFor(String computerUuid, NvApp app) {
        String appUuid = app.getAppUUID();
        String appKey = appUuid == null || appUuid.isEmpty()
                ? Integer.toString(app.getAppId())
                : appUuid.toUpperCase();
        return (computerUuid == null ? "" : computerUuid) + "|" + appKey;
    }
}
