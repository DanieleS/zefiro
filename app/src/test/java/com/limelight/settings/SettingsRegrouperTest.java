package com.limelight.settings;

import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import androidx.preference.TwoStatePreference;

import com.limelight.TestLogSuppressor;
import com.limelight.preferences.StreamPreferencesFragment;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The stream settings list as the Settings section shows it: one list in the catalogue's
 * sections, every device removal already applied, and the dependencies still working after the
 * move, including the ones that now cross sections.
 */
@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class SettingsRegrouperTest {

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Test
    public void sectionsFollowTheCatalogueAndNoXmlCategoryRemains() {
        PreferenceScreen screen = SettingsListFixture.open().getPreferenceScreen();

        List<String> shown = new ArrayList<>();
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference child = screen.getPreference(i);
            assertTrue("Only sections at the top: " + child.getKey(),
                    child instanceof SectionCategory);
            assertTrue(child.getKey().startsWith(SectionCategory.KEY_PREFIX));
            shown.add(child.getKey().substring(SectionCategory.KEY_PREFIX.length()));
        }

        List<String> expected = new ArrayList<>();
        for (SettingsCatalog.Section section : SettingsCatalog.get().sections()) {
            if (shown.contains(section.id)) {
                expected.add(section.id);
            }
        }
        assertEquals("Sections in catalogue order, none unknown", expected, shown);
        assertEquals("video", shown.get(0));
        assertFalse("Nothing should land in Other", shown.contains("other"));
    }

    @Test
    public void rowsSitInTheirSectionInCatalogueOrder() {
        PreferenceScreen screen = SettingsListFixture.open().getPreferenceScreen();

        // Moved out of the XML's UI category into Video's frame pacing.
        Preference vsync = screen.findPreference("checkbox_vsync_target_present");
        assertNotNull(vsync);
        assertEquals("section_video", vsync.getParent().getKey());

        SectionCategory video = screen.findPreference("section_video");
        assertNotNull(video);
        assertEquals("list_resolution", video.getPreference(0).getKey());
        assertEquals("list_fps", video.getPreference(1).getKey());
        assertEquals("seekbar_bitrate_kbps", video.getPreference(2).getKey());
        assertEquals(video.getPreferenceCount(), video.getVisibleCount());
    }

    @Test
    public void adaptiveBitrateModeFollowsItsSwitch() {
        StreamPreferencesFragment fragment = SettingsListFixture.open();
        TwoStatePreference adaptive = fragment.findPreference("checkbox_adaptive_bitrate");
        Preference mode = fragment.findPreference("list_adaptive_bitrate_mode");
        assertNotNull(adaptive);
        assertNotNull(mode);

        adaptive.setChecked(true);
        assertTrue(mode.isEnabled());
        adaptive.setChecked(false);
        assertFalse(mode.isEnabled());
    }

    @Test
    public void rememberMouseModeFollowsMouseEmulationAcrossSections() {
        StreamPreferencesFragment fragment = SettingsListFixture.open();
        TwoStatePreference emulation = fragment.findPreference("checkbox_mouse_emulation");
        Preference remember = fragment.findPreference("checkbox_remember_mouse_mode");
        assertNotNull(emulation);
        assertNotNull(remember);
        assertNotEquals("The two now live in different sections",
                emulation.getParent().getKey(), remember.getParent().getKey());

        emulation.setChecked(false);
        assertFalse(remember.isEnabled());
        emulation.setChecked(true);
        assertTrue(remember.isEnabled());
    }
}
