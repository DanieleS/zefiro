package com.limelight.settings;

import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.lifecycle.MutableLiveData;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.TwoStatePreference;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.R;
import com.limelight.TestLogSuppressor;
import com.limelight.companion.CompanionDisplayManager;
import com.limelight.companion.CompanionPage;
import com.limelight.main.CompanionFeed;
import com.limelight.main.MainActivity;
import com.limelight.main.Section;
import com.limelight.preferences.StreamPreferencesFragment;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Settings with the companion panel showing: the focused setting is spelled out on the panel,
 * follows Left and Right on top, and a tap on the panel changes it through the preference; the
 * header shows the essentials, and leaving the section takes the page off.
 */
@Config(sdk = {33}, qualifiers = "w900dp-h500dp-land",
        shadows = {com.limelight.shadows.ShadowMoonBridge.class,
                com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class SettingsPanelTest {

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void panelOn() throws Exception {
        setPanelShowing(true);
    }

    @After
    public void panelOff() throws Exception {
        setPanelShowing(false);
    }

    // The panel is a Presentation on a second display, which a test has none of: the value its
    // attach and detach would set is set directly.
    @SuppressWarnings("unchecked")
    private static void setPanelShowing(boolean showing) throws Exception {
        Field field = CompanionDisplayManager.class.getDeclaredField("panelShowing");
        field.setAccessible(true);
        ((MutableLiveData<Boolean>) field.get(null)).setValue(showing);
    }

    // What the feed would draw on the panel now.
    private static CompanionPage panelPage(SettingsSectionFragment section) throws Exception {
        Field feedField = MainActivity.class.getDeclaredField("companionFeed");
        feedField.setAccessible(true);
        Object feed = feedField.get(section.requireActivity());
        Field pageField = CompanionFeed.class.getDeclaredField("page");
        pageField.setAccessible(true);
        return (CompanionPage) pageField.get(feed);
    }

    private static StreamPreferencesFragment list(SettingsSectionFragment section) {
        return (StreamPreferencesFragment) section.getChildFragmentManager()
                .findFragmentByTag(SettingsSectionFragment.TAG_LIST);
    }

    private static void focusRow(StreamPreferencesFragment list, String key) {
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
        holder.itemView.setFocusableInTouchMode(true);
        assertTrue(holder.itemView.requestFocus());
        SettingsListFixture.idle();
    }

    private static SettingsChoicesPage choicesOn(SettingsSectionFragment section, String key)
            throws Exception {
        CompanionPage page = panelPage(section);
        assertTrue("The panel shows a setting: " + page, page instanceof SettingsChoicesPage);
        SettingsChoicesPage choices = (SettingsChoicesPage) page;
        assertEquals(key, choices.key);
        return choices;
    }

    @Test
    public void aFocusedListRow_isSpelledOutBelow_andFollowsLeftRightAndTaps() throws Exception {
        SettingsSectionFragment section = SettingsListFixture.section();
        StreamPreferencesFragment list = list(section);
        ListPreference pacing = list.findPreference("frame_pacing");
        assertNotNull(pacing);
        pacing.setValue("latency");
        SettingsListFixture.idle();

        focusRow(list, "frame_pacing");
        SettingsChoicesPage page = choicesOn(section, "frame_pacing");
        assertEquals(6, page.choices.size());
        assertEquals(pacing.findIndexOfValue("latency"), page.selectedIndex);

        // Right on top: the next mode, and the panel moves its selection with it.
        assertTrue(section.onSectionKey(new KeyEvent(KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_DPAD_RIGHT)));
        SettingsListFixture.idle();
        assertEquals(pacing.findIndexOfValue(pacing.getValue()),
                choicesOn(section, "frame_pacing").selectedIndex);

        // A tap on "Balanced" on the panel sets it through the preference.
        int balanced = pacing.findIndexOfValue("balanced");
        section.onPageTap(balanced);
        SettingsListFixture.idle();
        assertEquals("balanced", pacing.getValue());
        assertEquals(balanced, choicesOn(section, "frame_pacing").selectedIndex);
    }

    @Test
    public void theSwitchOnThePanel_togglesIt_andItsDependentsFollow() throws Exception {
        SettingsSectionFragment section = SettingsListFixture.section();
        StreamPreferencesFragment list = list(section);
        TwoStatePreference rumble = list.findPreference("checkbox_enable_rumble");
        Preference fallback = list.findPreference("checkbox_vibrate_fallback");
        assertNotNull(rumble);
        assertNotNull(fallback);
        rumble.setChecked(false);
        SettingsListFixture.idle();

        focusRow(list, "checkbox_enable_rumble");
        SettingsChoicesPage page = choicesOn(section, "checkbox_enable_rumble");
        assertEquals(SettingsSearch.Kind.TOGGLE, page.kind);
        assertFalse(page.checked);

        section.onPageTap(SettingsChoicesPage.STOP_SWITCH);
        SettingsListFixture.idle();

        assertTrue(rumble.isChecked());
        assertTrue(fallback.isEnabled());
        assertTrue(choicesOn(section, "checkbox_enable_rumble").checked);
    }

    @Test
    public void aDisabledResult_isShownDimmed_andIgnoresTaps() throws Exception {
        SettingsSectionFragment section = SettingsListFixture.section();
        StreamPreferencesFragment list = list(section);
        TwoStatePreference rumble = list.findPreference("checkbox_enable_rumble");
        TwoStatePreference fallback = list.findPreference("checkbox_vibrate_fallback");
        assertNotNull(rumble);
        assertNotNull(fallback);
        rumble.setChecked(false);
        fallback.setChecked(false);
        SettingsListFixture.idle();

        // A disabled row in the list never takes focus; among the results it does, dimmed.
        section.onSectionKey(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_Y));
        EditText field = section.requireView().findViewById(R.id.settingsSearchField);
        field.setText("rumble");
        SettingsListFixture.idle();
        RecyclerView results = section.requireView().findViewById(R.id.settingsResults);
        SettingsResultsAdapter adapter = (SettingsResultsAdapter) results.getAdapter();
        assertNotNull(adapter);
        int position = adapter.positionOf("checkbox_vibrate_fallback");
        assertTrue(position != RecyclerView.NO_POSITION);
        results.scrollToPosition(position);
        results.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        results.layout(0, 0, 1080, 1920);
        RecyclerView.ViewHolder holder = results.findViewHolderForAdapterPosition(position);
        assertNotNull(holder);
        holder.itemView.setFocusableInTouchMode(true);
        assertTrue(holder.itemView.requestFocus());
        SettingsListFixture.idle();

        SettingsChoicesPage page = choicesOn(section, "checkbox_vibrate_fallback");
        assertFalse(page.enabled);
        assertNotNull(page.needs);

        section.onPageTap(SettingsChoicesPage.STOP_SWITCH);
        SettingsListFixture.idle();
        assertFalse(fallback.isChecked());
    }

    @Test
    public void theHeader_showsTheEssentials_andLeavingTakesThePageOff() throws Exception {
        SettingsSectionFragment section = SettingsListFixture.section();
        View search = section.requireView().findViewById(R.id.settingsSearchRest);
        search.setFocusableInTouchMode(true);
        assertTrue(search.requestFocus());
        SettingsListFixture.idle();

        CompanionPage page = panelPage(section);
        assertTrue("The header shows the essentials: " + page, page instanceof SettingsPinnedPage);
        // The device may lack some (HDR); those it has, in the card's order.
        SettingsPinnedPage pinned = (SettingsPinnedPage) page;
        assertFalse(pinned.rows.isEmpty());
        assertEquals(PinnedSettings.ESSENTIALS.get(0), pinned.rows.get(0).key);

        ((MainActivity) section.requireActivity()).showSection(Section.LIBRARY);
        SettingsListFixture.idle();
        assertNull("Another section owns the panel", panelPage(section));
    }

    @Test
    public void withoutThePanel_nothingIsPublished() throws Exception {
        setPanelShowing(false);
        SettingsSectionFragment section = SettingsListFixture.section();
        focusRow(list(section), "frame_pacing");
        assertNull(panelPage(section));

        setPanelShowing(true);
        SettingsListFixture.idle();
        choicesOn(section, "frame_pacing");

        setPanelShowing(false);
        SettingsListFixture.idle();
        assertNull(panelPage(section));
    }

    // The dialog a list row's click opened on top, or null.
    private static Fragment dialogOf(StreamPreferencesFragment list) {
        return list.getParentFragmentManager()
                .findFragmentByTag("androidx.preference.PreferenceFragment.DIALOG");
    }

    @Test
    public void aListRowsClick_bindsThePanel_andOpensItsDialogOnlyWhileThePanelIsOff()
            throws Exception {
        SettingsSectionFragment section = SettingsListFixture.section();
        StreamPreferencesFragment list = list(section);
        ListPreference pacing = list.findPreference("frame_pacing");
        ListPreference fps = list.findPreference("list_fps");
        assertNotNull(pacing);
        assertNotNull(fps);
        focusRow(list, "frame_pacing");

        // The panel up: A on another list row spells it out below, and nothing covers the top.
        fps.performClick();
        SettingsListFixture.idle();
        choicesOn(section, "list_fps");
        assertNull("No dialog on top while the panel offers the choices", dialogOf(list));

        // The lower screen switched off while Settings is up: the same click opens the dialog.
        setPanelShowing(false);
        SettingsListFixture.idle();
        pacing.performClick();
        SettingsListFixture.idle();
        Fragment dialog = dialogOf(list);
        assertTrue("The choices open on top: " + dialog, dialog instanceof SettingsListDialog);
        ((SettingsListDialog) dialog).dismissNow();
        SettingsListFixture.idle();

        // And on again: back to the panel, the section never rebuilt.
        setPanelShowing(true);
        SettingsListFixture.idle();
        pacing.performClick();
        SettingsListFixture.idle();
        choicesOn(section, "frame_pacing");
        assertNull(dialogOf(list));
        assertSame(list, list(section));
    }

    @Test
    public void focusLeavingTheRows_putsTheRestingCardBack() throws Exception {
        SettingsSectionFragment section = SettingsListFixture.section();
        focusRow(list(section), "frame_pacing");
        choicesOn(section, "frame_pacing");

        View rail = section.requireActivity().findViewById(R.id.navRailSettings);
        rail.setFocusableInTouchMode(true);
        assertTrue(rail.requestFocus());
        SettingsListFixture.idle();

        assertTrue("The rail shows the essentials: " + panelPage(section),
                panelPage(section) instanceof SettingsPinnedPage);
    }

    @Test
    public void searching_theFieldSpellsOutTheFirstResult() throws Exception {
        SettingsSectionFragment section = SettingsListFixture.section();
        section.onSectionKey(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_Y));
        EditText field = section.requireView().findViewById(R.id.settingsSearchField);
        field.setFocusableInTouchMode(true);
        assertTrue(field.requestFocus());
        field.setText("frame pacing");
        SettingsListFixture.idle();

        choicesOn(section, "frame_pacing");
    }

    @Test
    public void theFocusedRowsDescription_isWholeOnlyWhileThePanelIsOff() throws Exception {
        SettingsSectionFragment section = SettingsListFixture.section();
        StreamPreferencesFragment list = list(section);
        focusRow(list, "frame_pacing");
        TextView summary = focusedSummary(list);
        assertEquals("The panel shows it in full", 1, summary.getMaxLines());

        setPanelShowing(false);
        SettingsListFixture.idle();
        assertEquals(SettingsLayout.ALL_LINES, focusedSummary(list).getMaxLines());

        setPanelShowing(true);
        SettingsListFixture.idle();
        assertEquals(1, focusedSummary(list).getMaxLines());
        // The row kept its focus through both switches.
        assertTrue(list.getListView().hasFocus());
    }

    private static TextView focusedSummary(StreamPreferencesFragment list) {
        View row = list.getListView().getFocusedChild();
        assertNotNull("A row has focus", row);
        TextView summary = row.findViewById(android.R.id.summary);
        assertNotNull(summary);
        return summary;
    }
}
