package com.limelight.settings;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The settings search on its own: synonyms, folding, every token required, the ranking, and the
 * Changed filter. Entries are written by hand, as {@link SettingsIndex} would copy them from the
 * live list.
 */
public class SettingsSearchTest {

    private static final Set<String> NONE = Collections.emptySet();

    private static SettingsSearch.Entry entry(String key, String title, String summary,
                                              String sectionId, String section, String subgroup,
                                              int order) {
        return new SettingsSearch.Entry(key, title, summary, sectionId, section, subgroup,
                SettingsSearch.Kind.TOGGLE, false, order);
    }

    /** A slice of the real list, in screen order. */
    private static SettingsSearch library() {
        List<SettingsSearch.Entry> entries = new ArrayList<>();
        int i = 0;
        entries.add(entry("list_resolution", "Video resolution",
                "Increase to improve image clarity.", "video", "Video",
                "Resolution and frame rate", i++));
        entries.add(entry("frame_pacing", "Video frame pacing",
                "Specify how to balance video latency and smoothness", "video", "Video",
                "Frame pacing", i++));
        entries.add(entry("seekbar_deadzone", "Stick deadzone",
                "Small stick movements are ignored", "controllers", "Controllers", "Gamepad", i++));
        entries.add(entry("checkbox_enable_rumble", "Enable rumble", "Pass the game's rumble on",
                "controllers", "Controllers", "Rumble", i++));
        entries.add(entry("checkbox_vibrate_fallback", "Emulate rumble with vibration",
                "When the gamepad has none", "controllers", "Controllers", "Rumble", i++));
        entries.add(entry("checkbox_vibrate_osc", "Enable vibration",
                "Vibrate when touching the on-screen controls", "touch", "Touch & keyboard",
                "On-screen controls", i++));
        entries.add(entry("checkbox_trackpad_drag_drop_vibration",
                "Vibrate on trackpad drag-and-drop", "A tick when a drag starts", "touch",
                "Touch & keyboard", "Trackpad", i++));
        return new SettingsSearch(entries);
    }

    private static List<String> keys(SettingsSearch.Result result) {
        List<String> keys = new ArrayList<>();
        for (SettingsSearch.Entry e : result.entries()) {
            keys.add(e.key);
        }
        return keys;
    }

    @Test
    public void rumble_findsTheVibrationSettingsThroughSynonyms() {
        SettingsSearch.Result result = library().search("rumble", SettingsSearch.Filter.ALL, NONE);

        assertEquals(Arrays.asList("checkbox_enable_rumble", "checkbox_vibrate_fallback",
                "checkbox_vibrate_osc", "checkbox_trackpad_drag_drop_vibration"), keys(result));
        // Found by their own words, the controller ones need no synonym; the touch ones do.
        assertTrue(result.usedSynonyms.contains("vibration"));
        assertTrue(result.usedSynonyms.contains("vibrate"));
        assertEquals(4, result.total);
        assertEquals(Integer.valueOf(2), result.sectionCounts.get("controllers"));
        assertEquals(Integer.valueOf(2), result.sectionCounts.get("touch"));
    }

    @Test
    public void aDirectMatch_reportsNoSynonym() {
        SettingsSearch.Result result = library().search("enable rumble",
                SettingsSearch.Filter.ALL, NONE);

        assertTrue(keys(result).contains("checkbox_enable_rumble"));
        assertEquals("checkbox_enable_rumble", keys(result).get(0));
    }

    @Test
    public void accentsAndCase_fold() {
        assertEquals("video fps", SettingsSearch.fold("  VIDÉO--fps! "));

        SettingsSearch.Result result = library().search("RÉSOLUTION", SettingsSearch.Filter.ALL, NONE);

        assertEquals(Collections.singletonList("list_resolution"), keys(result));
    }

    @Test
    public void everyToken_mustMatch() {
        assertEquals(Collections.singletonList("frame_pacing"),
                keys(library().search("video pacing", SettingsSearch.Filter.ALL, NONE)));
        assertTrue(keys(library().search("rumble zzz", SettingsSearch.Filter.ALL, NONE)).isEmpty());
    }

    @Test
    public void aSynonymPhrase_staysOneToken() {
        // "dead zone" is the synonym of deadzone as a phrase; split, "dead" would find nothing.
        assertEquals(Collections.singletonList("seekbar_deadzone"),
                keys(library().search("dead zone", SettingsSearch.Filter.ALL, NONE)));
        assertEquals(Collections.singletonList("seekbar_deadzone"),
                keys(library().search("drift", SettingsSearch.Filter.ALL, NONE)));
    }

    @Test
    public void ranking_titlePrefixThenTitleThenSynonymInTitleThenSummary() {
        // Listed worst first, so the order found can only come from the ranking.
        List<SettingsSearch.Entry> entries = Arrays.asList(
                entry("summary", "Device motion", "Uses the rumble motors", "c", "Controllers", "Motion", 0),
                entry("synonym", "Force vibration on device", "", "c", "Controllers", "Motion", 1),
                entry("contains", "Enable rumble", "", "c", "Controllers", "Motion", 2),
                entry("prefix", "Rumble strength", "", "c", "Controllers", "Motion", 3),
                entry("other", "Haptic keys", "", "t", "Touch", "Keys", 4));
        SettingsSearch.Result result = new SettingsSearch(entries)
                .search("rumble", SettingsSearch.Filter.ALL, NONE);

        assertEquals(Arrays.asList("prefix", "contains", "synonym", "summary", "other"),
                keys(result));
        // The best group first: Controllers has the title prefix, Touch only a synonym.
        assertEquals("c", result.groups.get(0).sectionId);
        assertEquals("t", result.groups.get(1).sectionId);
    }

    @Test
    public void aSectionFilter_narrowsTheRowsButNotTheCounts() {
        SettingsSearch.Result result = library().search("rumble",
                SettingsSearch.Filter.section("touch"), NONE);

        assertEquals(Arrays.asList("checkbox_vibrate_osc", "checkbox_trackpad_drag_drop_vibration"),
                keys(result));
        assertEquals(4, result.total);
        assertEquals(Integer.valueOf(2), result.sectionCounts.get("controllers"));
    }

    @Test
    public void changed_withAnEmptyQuery_listsEveryChangedSetting() {
        Set<String> changed = new HashSet<>(Arrays.asList("checkbox_vibrate_osc", "frame_pacing"));

        SettingsSearch.Result result = library().search("", SettingsSearch.Filter.CHANGED, changed);

        assertEquals(Arrays.asList("frame_pacing", "checkbox_vibrate_osc"), keys(result));
        assertTrue(library().search("  ", SettingsSearch.Filter.ALL, changed).groups.isEmpty());
    }

    @Test
    public void changed_withAQuery_keepsOnlyTheChangedMatches() {
        Set<String> changed = Collections.singleton("checkbox_vibrate_osc");

        SettingsSearch.Result result = library().search("rumble", SettingsSearch.Filter.CHANGED, changed);

        assertEquals(Collections.singletonList("checkbox_vibrate_osc"), keys(result));
    }

    @Test
    public void changedCount_countsOnlyTheChangedMatches_notEveryMatch() {
        Set<String> changed = Collections.singleton("checkbox_vibrate_osc");

        assertTrue(library().search("rumble", SettingsSearch.Filter.ALL, changed).total > 1);
        assertEquals(1, library().changedCount("rumble", changed));
        assertEquals(0, library().changedCount("rumble", Collections.<String>emptySet()));
    }

    @Test
    public void theQuerysWords_areMarkedInATitle_foldedAsTheSearchFolds() {
        List<int[]> ranges = SettingsSearch.matchRanges("Enable Rumble", "rumble");
        assertEquals(1, ranges.size());
        assertEquals(7, ranges.get(0)[0]);
        assertEquals(13, ranges.get(0)[1]);

        // Accents and case fold; each word is marked wherever it is.
        ranges = SettingsSearch.matchRanges("Vidéo bitrate on metered networks", "VIDEO metered");
        assertEquals(2, ranges.size());
        assertEquals(0, ranges.get(0)[0]);
        assertEquals(5, ranges.get(0)[1]);
        assertEquals(17, ranges.get(1)[0]);
        assertEquals(24, ranges.get(1)[1]);
    }

    @Test
    public void aSynonymsMatch_marksNothing() {
        // "rumble" finds "Enable vibration" through a synonym; the title has no "rumble" in it.
        assertTrue(SettingsSearch.matchRanges("Enable vibration", "rumble").isEmpty());
        assertTrue(SettingsSearch.matchRanges("Enable vibration", "").isEmpty());
    }
}
