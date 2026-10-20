package com.limelight.library;

/**
 * The extra keys and preference names that cross screen boundaries, in one place that outlives
 * the screens that used to own them.
 *
 * <p>These used to live on the old library and PC list activities. Both are gone, but the
 * values they defined were not theirs to take with them: pinned launcher shortcuts
 * and exported {@code .art} files already carry the extra keys, and the preference files hold the
 * user's hidden games, sort order and last host. Every value here is therefore a contract with
 * data that already exists outside the app.
 *
 * <h3>Invariant</h3>
 *
 * A value never changes. Renaming a constant is fine; changing its string silently orphans every
 * shortcut and preference written under the old one. {@code LibraryIntentsTest} pins the literals
 * so such a change fails loudly rather than in the field.
 */
public final class LibraryIntents {

    // --- Intent extras: which host to open, and how ---

    /** The host's display name, shown before the host itself has been reached. */
    public static final String EXTRA_NAME = "Name";

    /** The host's UUID; the key every shortcut and the library use to find the host. */
    public static final String EXTRA_UUID = "UUID";

    /** Set when the host was paired a moment ago, so its first library opening can offer a shortcut. */
    public static final String EXTRA_NEW_PAIR = "NewPair";

    /** Open the library with hidden games shown, so they can be unhidden. */
    public static final String EXTRA_SHOW_HIDDEN = "ShowHiddenApps";

    // --- Intent extras: pairing handed over from a deep link (AddComputerManually) ---

    public static final String EXTRA_PAIR_HOSTNAME = "hostname";

    /** An int; the host's HTTP port. */
    public static final String EXTRA_PAIR_PORT = "port";

    public static final String EXTRA_PAIR_PIN = "pin";

    public static final String EXTRA_PAIR_PASSPHRASE = "passphrase";

    // --- Preferences ---

    /** Preference file of hidden games: one string set per host UUID. */
    public static final String PREFS_HIDDEN_APPS = "HiddenApps";

    // The chosen order is one preference for the whole app, not one per PC: it is how the user
    // likes to browse, not a property of any host.

    /** Preference file holding the library sort order. */
    public static final String PREFS_SORT = "LibrarySortOrder";

    /** Key of the sort order in {@link #PREFS_SORT}; the value is an {@code AppSortOrder} key. */
    public static final String KEY_SORT = "sortOrder";

    /** Key in the default shared preferences: the host whose library was opened last. */
    public static final String PREF_LAST_HOST = "last_computer_uuid";

    private LibraryIntents() {
    }
}
