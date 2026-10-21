package com.limelight.library;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Which games the Search section lists for what has been typed: those whose name contains it,
 * ignoring case, in the library's own order.
 *
 * <p>A substring rather than a prefix, since a game is as often remembered by a later word
 * ("Wake" for Alan Wake 2) as by its first. Nothing typed, or only spaces, matches every game,
 * so the section opens on the whole library and narrows as the user types. Hidden games are left
 * out unless the user asked to see them, as in See all.
 *
 * Pure: no Android, safe on any thread.
 */
public final class SearchFilter {

    private SearchFilter() {
    }

    /**
     * @param apps        the library's games, in the order to keep
     * @param query       what was typed; null is nothing
     * @param showHidden  whether hidden games are listed too
     * @return the matching games; a new list, or {@code apps} itself when every game matches
     */
    @NonNull
    public static List<LibraryApp> filter(@Nullable List<LibraryApp> apps, @Nullable String query,
                                          boolean showHidden) {
        if (apps == null || apps.isEmpty()) {
            return Collections.emptyList();
        }
        String needle = normalise(query);
        if (needle.isEmpty() && showHidden) {
            return apps;
        }
        List<LibraryApp> out = new ArrayList<>(apps.size());
        for (LibraryApp app : apps) {
            if (app.hidden && !showHidden) {
                continue;
            }
            if (needle.isEmpty() || normalise(app.app.getAppName()).contains(needle)) {
                out.add(app);
            }
        }
        return out;
    }

    /** Whether nothing that narrows the list has been typed. */
    public static boolean isBlank(@Nullable String query) {
        return normalise(query).isEmpty();
    }

    // Locale.ROOT: under a Turkish locale "I" would lower to a dotless ı and miss "i".
    private static String normalise(@Nullable String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
