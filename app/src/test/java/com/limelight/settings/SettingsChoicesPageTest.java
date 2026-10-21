package com.limelight.settings;

import android.content.SharedPreferences;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.TwoStatePreference;

import com.limelight.TestLogSuppressor;
import com.limelight.companion.CompanionPage;
import com.limelight.companion.CompanionState;
import com.limelight.preferences.SeekBarPreference;
import com.limelight.preferences.StreamPreferencesFragment;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The companion panel's page for a setting is read from the live preference: a list's choices
 * with their descriptions and the one in effect, a switch with its default, a disabled setting
 * saying what it waits for, and equal values for an unchanged setting.
 */
@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class SettingsChoicesPageTest {

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    private static SettingsChoicesPage pageOf(StreamPreferencesFragment fragment, String key) {
        Preference p = fragment.findPreference(key);
        assertNotNull(key + " should be in the list", p);
        SharedPreferences prefs = fragment.getPreferenceManager().getSharedPreferences();
        return SettingsChoicesPage.of(p, fragment.getSettingsAdapter().getDefaults(), prefs);
    }

    @Test
    public void framePacingListsItsSixModesWithDescriptions_andTheOneInEffect() {
        StreamPreferencesFragment fragment = SettingsListFixture.open();
        ListPreference pacing = fragment.findPreference("frame_pacing");
        assertNotNull(pacing);
        pacing.setValue("balanced");

        SettingsChoicesPage page = pageOf(fragment, "frame_pacing");

        assertEquals(SettingsSearch.Kind.LIST, page.kind);
        assertEquals(6, page.choices.size());
        assertEquals(pacing.findIndexOfValue("balanced"), page.selectedIndex);
        assertEquals("balanced", page.choices.get(page.selectedIndex).value);
        for (SettingsChoicesPage.Choice choice : page.choices) {
            assertNotNull("Every pacing mode is described: " + choice, choice.description);
        }
        assertEquals("Waits for the screen refresh. Smoother, a little slower.",
                page.choices.get(pacing.findIndexOfValue("balanced")).description);
        // The default entry says so itself, after its description.
        assertEquals("Shows each frame as soon as it arrives. Default.",
                page.choices.get(pacing.findIndexOfValue("latency")).description);
        assertTrue(page.sectionLabel.endsWith("Choices"));
        assertTrue(page.enabled);
        assertEquals(CompanionPage.NO_STOP, page.focusedStop());
    }

    @Test
    public void aListWithoutDescriptionsShowsLabelsOnly() {
        StreamPreferencesFragment fragment = SettingsListFixture.open();

        SettingsChoicesPage page = pageOf(fragment, "list_video_fec");

        assertFalse(page.choices.isEmpty());
        for (SettingsChoicesPage.Choice choice : page.choices) {
            // Only the default entry carries a line, and it is the default's alone.
            assertTrue(choice.description == null || "Default".equals(choice.description));
        }
    }

    @Test
    public void rumbleIsAToggleWithItsDefault() {
        StreamPreferencesFragment fragment = SettingsListFixture.open();
        TwoStatePreference rumble = fragment.findPreference("checkbox_enable_rumble");
        assertNotNull(rumble);
        rumble.setChecked(false);

        SettingsChoicesPage page = pageOf(fragment, "checkbox_enable_rumble");

        assertEquals(SettingsSearch.Kind.TOGGLE, page.kind);
        assertFalse(page.checked);
        assertEquals("Off", page.valueText);
        assertEquals("Default: On", page.defaultText);
        assertTrue(page.changed);
        assertTrue(page.choices.isEmpty());
        assertEquals(-1, page.selectedIndex);
    }

    @Test
    public void aSliderHasItsRangeAndEnds() {
        StreamPreferencesFragment fragment = SettingsListFixture.open();
        SeekBarPreference deadzone = fragment.findPreference("seekbar_deadzone");
        assertNotNull(deadzone);
        deadzone.commitValue(deadzone.getMinValue());

        SettingsChoicesPage page = pageOf(fragment, "seekbar_deadzone");

        assertEquals(SettingsSearch.Kind.SEEK, page.kind);
        assertNotNull(page.rangeText);
        assertNotNull(page.defaultText);
        assertFalse("At the low end, − cannot move", page.canDecrease);
        assertTrue(page.canIncrease);
    }

    @Test
    public void aDisabledSettingYieldsADisabledPage_namingWhatItNeeds() {
        StreamPreferencesFragment fragment = SettingsListFixture.open();
        TwoStatePreference rumble = fragment.findPreference("checkbox_enable_rumble");
        assertNotNull(rumble);
        rumble.setChecked(false);
        Preference fallback = fragment.findPreference("checkbox_vibrate_fallback");
        assertNotNull(fallback);
        assertFalse("Its dependency is off", fallback.isEnabled());

        SettingsChoicesPage page = pageOf(fragment, "checkbox_vibrate_fallback");

        assertFalse(page.enabled);
        assertEquals("Needs " + rumble.getTitle(), page.needs);

        rumble.setChecked(true);
        SettingsChoicesPage enabled = pageOf(fragment, "checkbox_vibrate_fallback");
        assertTrue(enabled.enabled);
        assertNull(enabled.needs);
        assertNotEquals(page, enabled);
    }

    @Test
    public void equalPagesAreDroppedByThePanelState() {
        StreamPreferencesFragment fragment = SettingsListFixture.open();
        ListPreference pacing = fragment.findPreference("frame_pacing");
        assertNotNull(pacing);
        pacing.setValue("latency");

        SettingsChoicesPage first = pageOf(fragment, "frame_pacing");
        SettingsChoicesPage again = pageOf(fragment, "frame_pacing");
        assertEquals(first, again);
        assertEquals(first.hashCode(), again.hashCode());
        assertSame(first, first.withFocus(3));

        CompanionState state = CompanionState.getInstance();
        final int[] redraws = {0};
        CompanionState.Listener counter = () -> redraws[0]++;
        state.setListener(counter);
        try {
            state.setPage(first);
            int afterFirst = redraws[0];
            state.setPage(again);
            assertEquals("An equal page is not drawn again", afterFirst, redraws[0]);

            pacing.setValue("smoothness");
            SettingsChoicesPage moved = pageOf(fragment, "frame_pacing");
            assertNotEquals(first, moved);
            state.setPage(moved);
            assertEquals(afterFirst + 1, redraws[0]);
        } finally {
            state.clearListener(counter);
            state.setPage(null);
        }
    }
}
