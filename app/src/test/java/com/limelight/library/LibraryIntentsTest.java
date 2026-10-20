package com.limelight.library;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Pins the literal values. Pinned launcher shortcuts, exported launcher files and the user's
 * preference files were all written under these strings, so a change here must fail a test rather
 * than silently orphan them.
 */
public class LibraryIntentsTest {

    @Test
    public void hostExtrasKeepTheirValues() {
        assertEquals("Name", LibraryIntents.EXTRA_NAME);
        assertEquals("UUID", LibraryIntents.EXTRA_UUID);
        assertEquals("NewPair", LibraryIntents.EXTRA_NEW_PAIR);
        assertEquals("ShowHiddenApps", LibraryIntents.EXTRA_SHOW_HIDDEN);
    }

    @Test
    public void pairingExtrasKeepTheirValues() {
        assertEquals("hostname", LibraryIntents.EXTRA_PAIR_HOSTNAME);
        assertEquals("port", LibraryIntents.EXTRA_PAIR_PORT);
        assertEquals("pin", LibraryIntents.EXTRA_PAIR_PIN);
        assertEquals("passphrase", LibraryIntents.EXTRA_PAIR_PASSPHRASE);
    }

    @Test
    public void preferenceNamesKeepTheirValues() {
        assertEquals("HiddenApps", LibraryIntents.PREFS_HIDDEN_APPS);
        assertEquals("LibrarySortOrder", LibraryIntents.PREFS_SORT);
        assertEquals("sortOrder", LibraryIntents.KEY_SORT);
        assertEquals("last_computer_uuid", LibraryIntents.PREF_LAST_HOST);
    }
}
