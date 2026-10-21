package com.limelight.settings;

import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.preference.ListPreference;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.R;
import com.limelight.TestLogSuppressor;
import com.limelight.main.Section;
import com.limelight.preferences.StreamPreferencesFragment;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The Settings section around the list: where its header hangs, search and its way back, and
 * Left and Right changing a row in place.
 */
@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class SettingsSectionTest {

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    private static KeyEvent down(int code) {
        return new KeyEvent(KeyEvent.ACTION_DOWN, code);
    }

    private static View header(SettingsSectionFragment section) {
        return section.requireView().findViewById(R.id.settingsHeader);
    }

    private static StreamPreferencesFragment list(SettingsSectionFragment section) {
        return (StreamPreferencesFragment) section.getChildFragmentManager()
                .findFragmentByTag(SettingsSectionFragment.TAG_LIST);
    }

    private static View layOutRow(StreamPreferencesFragment list, String key) {
        RecyclerView view = list.getListView();
        SettingsAdapter adapter = list.getSettingsAdapter();
        assertNotNull(adapter);
        int position = view.getAdapter().getItemCount() - adapter.getItemCount()
                + adapter.getPreferenceAdapterPosition(key);
        view.scrollToPosition(position);
        view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 1080, 1920);
        RecyclerView.ViewHolder holder = view.findViewHolderForAdapterPosition(position);
        assertNotNull(key + " should be bound", holder);
        return holder.itemView;
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp-port")
    public void aPhone_scrollsTheHeaderWithTheList_andSearchHangsItAbove() {
        SettingsSectionFragment section = SettingsListFixture.section();
        // Laid out, so the list has bound its first item, the header.
        layOutRow(list(section), "list_resolution");
        View slot = section.requireView().findViewById(R.id.settingsHeaderSlot);
        assertNotSame("At rest the header is the list's first item", slot, header(section).getParent());

        assertTrue(section.onSectionKey(down(KeyEvent.KEYCODE_BUTTON_Y)));
        SettingsListFixture.idle();

        assertSame(slot, header(section).getParent());
        assertTrue(section.hasBackWork());
        assertEquals(View.VISIBLE, section.requireView().findViewById(R.id.settingsResults).getVisibility());
        assertEquals(View.GONE, section.requireView().findViewById(R.id.settingsList).getVisibility());

        section.onBack();
        SettingsListFixture.idle();

        assertFalse(section.hasBackWork());
        layOutRow(list(section), "list_resolution");
        assertNotSame(slot, header(section).getParent());
        assertEquals(View.VISIBLE, section.requireView().findViewById(R.id.settingsList).getVisibility());
    }

    @Test
    @Config(qualifiers = "w900dp-h500dp-land")
    public void aWideWindow_keepsTheHeaderAboveTheList() {
        SettingsSectionFragment section = SettingsListFixture.section();

        assertSame(section.requireView().findViewById(R.id.settingsHeaderSlot),
                header(section).getParent());
    }

    @Test
    public void searchingRumble_findsTheVibrationSettings_andSaysWhy() {
        SettingsSectionFragment section = SettingsListFixture.section();
        section.onSectionKey(down(KeyEvent.KEYCODE_BUTTON_Y));
        EditText field = section.requireView().findViewById(R.id.settingsSearchField);

        field.setText("rumble");
        SettingsListFixture.idle();

        RecyclerView results = section.requireView().findViewById(R.id.settingsResults);
        assertTrue(results.getAdapter().getItemCount() > 0);
        SettingsResultsAdapter adapter = (SettingsResultsAdapter) results.getAdapter();
        assertTrue(adapter.positionOf("checkbox_enable_rumble") != RecyclerView.NO_POSITION);
        TextView also = section.requireView().findViewById(R.id.settingsAlsoMatches);
        assertEquals(View.VISIBLE, also.getVisibility());
        assertTrue(also.getText().toString().contains("vibrat"));
    }

    @Test
    public void changed_withNothingChanged_saysEverythingIsAtItsDefault() {
        SettingsSectionFragment section = SettingsListFixture.section();

        section.onChangedChosen();
        SettingsListFixture.idle();

        RecyclerView results = section.requireView().findViewById(R.id.settingsResults);
        results.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        results.layout(0, 0, 1080, 1920);
        assertEquals(1, results.getAdapter().getItemCount());
        TextView note = (TextView) results.findViewHolderForAdapterPosition(0).itemView;
        assertEquals(section.getString(R.string.settings_nothing_changed), note.getText().toString());
    }

    @Test
    public void rightOnAListRow_takesTheNextChoice_andLeftAtTheFirstGoesOn() {
        SettingsSectionFragment section = SettingsListFixture.section();
        StreamPreferencesFragment list = list(section);
        ListPreference pacing = list.findPreference("frame_pacing");
        assertNotNull(pacing);
        pacing.setValue(pacing.getEntryValues()[0].toString());
        SettingsListFixture.idle();

        View row = layOutRow(list, "frame_pacing");
        row.setFocusableInTouchMode(true);
        assertTrue(row.requestFocus());

        // At the first choice, Left has nowhere to go here: it goes on, to the rail.
        assertFalse(section.onSectionKey(down(KeyEvent.KEYCODE_DPAD_LEFT)));
        assertTrue(section.onSectionKey(down(KeyEvent.KEYCODE_DPAD_RIGHT)));
        assertEquals(pacing.getEntryValues()[1].toString(), pacing.getValue());
    }

    @Test
    public void theSection_isTheSettingsTab() {
        SettingsSectionFragment section = SettingsListFixture.section();
        Fragment shown = section.requireActivity().getSupportFragmentManager()
                .findFragmentByTag(Section.SETTINGS.tag());
        assertSame(section, shown);
        assertTrue(section.isVisible());
    }
}
