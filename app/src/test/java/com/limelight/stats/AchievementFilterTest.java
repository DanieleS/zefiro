package com.limelight.stats;

import com.limelight.nvstream.http.Achievement;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.limelight.nvstream.http.TestGameStats.achievement;
import static com.limelight.nvstream.http.TestGameStats.json;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A game's achievements list: what each filter keeps and counts, which rows stay masked until
 * revealed, NEW by the toast's twelve hours, and the rarity bands.
 */
// Robolectric for org.json, which the plain unit-test android.jar only stubs.
@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class AchievementFilterTest {
    // 2026-10-08T12:00:00Z
    private static final long NOW = 1_791_460_800_000L;

    private static Achievement unlocked(String id, String at) {
        return achievement(json("id", id, "name", "A " + id, "unlocked", true,
                "unlocked_at", at == null ? JSONObject.NULL : at));
    }

    private static Achievement locked(String id, boolean hidden, Double percent) {
        return achievement(json("id", id, "name", "A " + id, "unlocked", false, "hidden", hidden,
                "percent", percent == null ? JSONObject.NULL : percent));
    }

    private static final List<Achievement> LIST = Arrays.asList(
            unlocked("u1", "2026-10-08T11:35:00Z"),
            unlocked("u2", "2026-10-02T10:00:00Z"),
            locked("l1", false, 5.2),
            locked("l2", true, 3.1),
            locked("l3", false, 34.0));

    @Test
    public void filters_keepTheHostsOrder() {
        assertEquals(LIST, AchievementFilter.apply(LIST, AchievementFilter.Filter.ALL));
        assertEquals(Arrays.asList(LIST.get(0), LIST.get(1)),
                AchievementFilter.apply(LIST, AchievementFilter.Filter.UNLOCKED));
        assertEquals(Arrays.asList(LIST.get(2), LIST.get(3), LIST.get(4)),
                AchievementFilter.apply(LIST, AchievementFilter.Filter.LOCKED));
        assertTrue(AchievementFilter.apply(null, AchievementFilter.Filter.ALL).isEmpty());
    }

    @Test
    public void counts_perFilter() {
        assertArrayEquals(new int[]{5, 2, 3}, AchievementFilter.counts(LIST));
        assertArrayEquals(new int[]{0, 0, 0}, AchievementFilter.counts(Collections.<Achievement>emptyList()));
    }

    @Test
    public void filterAt_segmentIndex() {
        assertEquals(AchievementFilter.Filter.LOCKED, AchievementFilter.Filter.at(2));
        assertEquals(AchievementFilter.Filter.ALL, AchievementFilter.Filter.at(-1));
        assertEquals(AchievementFilter.Filter.ALL, AchievementFilter.Filter.at(7));
    }

    @Test
    public void hiddenAndLocked_isMaskedUntilRevealed() {
        Achievement hidden = LIST.get(3);
        Set<String> revealed = new HashSet<>();
        assertTrue(AchievementFilter.isMasked(hidden, revealed));

        revealed.add(hidden.getId());
        assertFalse(AchievementFilter.isMasked(hidden, revealed));
        // A visible one is never masked.
        assertFalse(AchievementFilter.isMasked(LIST.get(2), new HashSet<String>()));
    }

    @Test
    public void hiddenButUnlocked_isNotMasked() {
        Achievement a = achievement(json("id", "h", "unlocked", true, "hidden", true,
                "unlocked_at", "2026-10-01T10:00:00Z"));
        assertFalse(AchievementFilter.isMasked(a, new HashSet<String>()));
    }

    @Test
    public void new_withinTwelveHours() {
        // 11h59m before NOW, then 12h01m before.
        assertTrue(AchievementFilter.isNew(unlocked("a", "2026-10-08T00:01:00Z"), NOW));
        assertFalse(AchievementFilter.isNew(unlocked("b", "2026-10-07T23:59:00Z"), NOW));
        // Undated, or locked: never news.
        assertFalse(AchievementFilter.isNew(unlocked("c", null), NOW));
        assertFalse(AchievementFilter.isNew(locked("d", false, null), NOW));
    }

    @Test
    public void rarityBands() {
        assertEquals(StatsFormat.Rarity.ULTRA_RARE, AchievementFilter.rarity(locked("a", false, 1.6)));
        assertEquals(StatsFormat.Rarity.RARE, AchievementFilter.rarity(locked("b", false, 8.7)));
        assertEquals(StatsFormat.Rarity.UNCOMMON, AchievementFilter.rarity(locked("c", false, 24.3)));
        assertNull(AchievementFilter.rarity(locked("d", false, 41.0)));
        assertNull(AchievementFilter.rarity(locked("e", false, null)));
    }
}
