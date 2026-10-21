package com.limelight.settings;

import android.view.View;
import android.widget.TextView;

import androidx.preference.ListPreference;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.R;
import com.limelight.TestLogSuppressor;
import com.limelight.preferences.StreamPreferencesFragment;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** A row shows its value on the right and, once it leaves its default, the changed dot. */
@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class SettingsAdapterTest {

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    private static View rowFor(StreamPreferencesFragment fragment, String key) {
        RecyclerView list = fragment.getListView();
        SettingsAdapter adapter = (SettingsAdapter) list.getAdapter();
        assertNotNull(adapter);
        int position = adapter.getPreferenceAdapterPosition(key);
        assertTrue(key + " should be in the list", position >= 0);
        list.scrollToPosition(position);
        list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        list.layout(0, 0, 1080, 1920);
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(position);
        assertNotNull(key + " should be bound", holder);
        return holder.itemView;
    }

    @Test
    public void aListRowShowsItsEntryAndMarksAChange() {
        StreamPreferencesFragment fragment = SettingsListFixture.open();
        ListPreference pacing = fragment.findPreference("frame_pacing");
        assertNotNull(pacing);

        View row = rowFor(fragment, "frame_pacing");
        TextView value = row.findViewById(R.id.settingValue);
        assertEquals(View.VISIBLE, value.getVisibility());
        assertEquals(pacing.getEntry().toString(), value.getText().toString());
        assertEquals(View.GONE, row.findViewById(R.id.settingChangedDot).getVisibility());

        assertTrue(SettingsEditor.cycle(pacing, 1));
        SettingsListFixture.idle();
        row = rowFor(fragment, "frame_pacing");
        assertEquals(pacing.getEntry().toString(),
                ((TextView) row.findViewById(R.id.settingValue)).getText().toString());
        assertEquals(View.VISIBLE, row.findViewById(R.id.settingChangedDot).getVisibility());
    }

    @Test
    public void aSwitchRowShowsNoValueAndAWebRowItsIcon() {
        StreamPreferencesFragment fragment = SettingsListFixture.open();

        View toggle = rowFor(fragment, "checkbox_adaptive_bitrate");
        assertEquals(View.GONE, toggle.findViewById(R.id.settingValue).getVisibility());

        View web = rowFor(fragment, "option_follow_update");
        assertEquals(View.VISIBLE, web.findViewById(R.id.settingExternal).getVisibility());
    }

    @Test
    public void sectionHeaderCountsItsRows() {
        StreamPreferencesFragment fragment = SettingsListFixture.open();
        SectionCategory audio = fragment.findPreference("section_audio");
        assertNotNull(audio);

        View header = rowFor(fragment, "section_audio");
        assertEquals(String.valueOf(audio.getVisibleCount()),
                ((TextView) header.findViewById(R.id.settingsSectionCount)).getText().toString());
    }
}
