package com.limelight.stats;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.limelight.nvstream.http.Achievement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The rules behind a game's achievements list, kept apart from the views so they can be tested
 * on their own: which rows a filter keeps, how many each filter would keep, which rows stay
 * masked, which carry NEW, and which rarity band earns a label.
 *
 * <ul>
 *   <li><b>Filters</b> keep the host's order (unlocked first, newest first, then the rest, the
 *       most common first); they only leave rows out.</li>
 *   <li><b>Masked</b>: a hidden achievement that is still locked shows only that it exists, until
 *       the user reveals it. The game did not want it spoiled; once unlocked there is nothing
 *       left to spoil.</li>
 *   <li><b>NEW</b>: unlocked within {@link AchievementFreshness#FRESH_MS}, the toast's own rule, so
 *       what was announced is what is marked.</li>
 *   <li><b>Rarity</b>: SuccessStory's bands ({@link StatsFormat#rarity}); a common one gets no
 *       label.</li>
 * </ul>
 *
 * Pure: no Android, no clock of its own.
 */
public final class AchievementFilter {

    /** The three choices above the list, in the order the segmented control shows them. */
    public enum Filter {
        ALL,
        UNLOCKED,
        LOCKED;

        /** The filter at a segment's index; ALL for anything out of range. */
        @NonNull
        public static Filter at(int index) {
            Filter[] all = values();
            return index >= 0 && index < all.length ? all[index] : ALL;
        }
    }

    private AchievementFilter() {
    }

    /** Whether a filter keeps an achievement. */
    public static boolean keeps(@NonNull Filter filter, @NonNull Achievement a) {
        switch (filter) {
            case UNLOCKED:
                return a.isUnlocked();
            case LOCKED:
                return !a.isUnlocked();
            case ALL:
            default:
                return true;
        }
    }

    /** The rows a filter keeps, in the host's order. Unmodifiable. */
    @NonNull
    public static List<Achievement> apply(@Nullable List<Achievement> items, @NonNull Filter filter) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }
        List<Achievement> kept = new ArrayList<>(items.size());
        for (Achievement a : items) {
            if (keeps(filter, a)) {
                kept.add(a);
            }
        }
        return Collections.unmodifiableList(kept);
    }

    /**
     * How many rows each filter keeps, indexed by {@link Filter#ordinal()}: the numbers on the
     * segments ("All 41", "Unlocked 18", "Locked 23"). Counted from the list itself, so a
     * segment's number is always the length of the list it shows.
     */
    @NonNull
    public static int[] counts(@Nullable List<Achievement> items) {
        int[] counts = new int[Filter.values().length];
        if (items == null) {
            return counts;
        }
        for (Achievement a : items) {
            for (Filter f : Filter.values()) {
                if (keeps(f, a)) {
                    counts[f.ordinal()]++;
                }
            }
        }
        return counts;
    }

    /** Hidden, still locked, and not revealed by the user: shown only as "Hidden achievement". */
    public static boolean isMasked(@NonNull Achievement a, @NonNull Set<String> revealed) {
        return a.isHidden() && !a.isUnlocked() && !revealed.contains(a.getId());
    }

    /** Whether the row carries NEW. */
    public static boolean isNew(@NonNull Achievement a, long nowMillis) {
        return AchievementFreshness.isFresh(a, nowMillis);
    }

    /** The band to label the row with, or null for a common one or an unknown share. */
    @Nullable
    public static StatsFormat.Rarity rarity(@NonNull Achievement a) {
        return StatsFormat.rarity(a.getPercent());
    }
}
