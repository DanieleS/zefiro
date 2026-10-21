package com.limelight.settings;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The Settings section's layout choices, made again whenever the window or the companion panel
 * changes: the same inputs always give the same layout, so switching the Thor's lower screen off
 * and on lands back where it started.
 */
public class SettingsLayoutTest {

    @Test
    public void theHeader_scrollsOnAnUprightPhone_andStaysOnTheThor() {
        assertTrue("phone, upright", SettingsLayout.headerInList(false, false));
        assertFalse("Thor", SettingsLayout.headerInList(true, false));
        // Searching it is the search bar, fixed everywhere.
        assertFalse(SettingsLayout.headerInList(false, true));
        assertFalse(SettingsLayout.headerInList(true, true));
    }

    @Test
    public void onATurnedPhone_onlyTheCardScrolls_theFieldAndChipsStay() {
        assertFalse("phone, turned", SettingsLayout.headerInList(true, false));
        assertTrue("phone, turned", SettingsLayout.cardInList(true, true, false));
        assertFalse("Thor", SettingsLayout.cardInList(true, false, false));
        // An upright phone scrolls the whole header, card included.
        assertFalse("phone, upright", SettingsLayout.cardInList(false, false, false));
        // Searching there is no card.
        assertFalse(SettingsLayout.cardInList(true, true, true));
    }

    @Test
    public void aListRowsChoices_goToThePanelOnlyWhileItIsUp() {
        assertTrue(SettingsLayout.choicesOnPanel(true, SettingsSearch.Kind.LIST));
        assertFalse(SettingsLayout.choicesOnPanel(false, SettingsSearch.Kind.LIST));
        // A slider's or a text's own dialog stays: the panel cannot type a value.
        assertFalse(SettingsLayout.choicesOnPanel(true, SettingsSearch.Kind.SEEK));
        assertFalse(SettingsLayout.choicesOnPanel(true, SettingsSearch.Kind.TEXT));
    }

    @Test
    public void descriptions_areShort_andTheFocusedRowsIsWholeWithoutAPanel() {
        assertEquals(1, SettingsLayout.summaryLines(true, false, true));
        assertEquals(1, SettingsLayout.summaryLines(true, false, false));
        assertEquals(2, SettingsLayout.summaryLines(false, false, false));
        // The panel spells the focused row out; without it, the row does.
        assertEquals(1, SettingsLayout.summaryLines(true, true, true));
        assertEquals(SettingsLayout.ALL_LINES, SettingsLayout.summaryLines(true, true, false));
        assertEquals(SettingsLayout.ALL_LINES, SettingsLayout.summaryLines(false, true, false));
    }

    @Test
    public void thePanelOffAndOnAgain_givesTheSameLayout() {
        boolean[] panel = {true, false, true};
        int[] lines = new int[panel.length];
        boolean[] onPanel = new boolean[panel.length];
        for (int i = 0; i < panel.length; i++) {
            lines[i] = SettingsLayout.summaryLines(true, true, panel[i]);
            onPanel[i] = SettingsLayout.choicesOnPanel(panel[i], SettingsSearch.Kind.LIST);
        }
        assertEquals(lines[0], lines[2]);
        assertEquals(onPanel[0], onPanel[2]);
        assertTrue(lines[1] > lines[0]);
        assertFalse(onPanel[1]);
    }
}
