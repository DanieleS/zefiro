package com.limelight.library;

import com.limelight.nvstream.http.NvApp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rules that turn a host's app list into the library: which entries are games, what changed
 * since the last list, which game is running, and how the hidden set and the order apply.
 *
 * <p>Ported from the old library's {@code updateUiWithAppList}, {@code updateUiWithServerinfo}
 * and {@code populateAppGridWithCache}, where the same rules were interleaved with adapter calls
 * on the UI thread. Here they are pure functions over lists, so the host library can run them on
 * any thread, and a test can pin them without an Activity.
 *
 * <h3>Flow</h3>
 *
 * <pre>
 *   host list ──reduce──▶ games + specials (+ added / removed / renamed)
 *   games ──toLibraryApps(hidden, runningId, order)──▶ what a screen lists
 *   games + specials ──resolveRunning(runningId)──▶ what "Continue" offers
 * </pre>
 */
public final class LibraryModel {

    /**
     * The host's own entries that are not games: Virtual Display, Remote Monitor and Remote
     * Input. They are pulled out of the library and offered as host actions instead of covers.
     * Each is null when the host does not advertise it.
     *
     * <p>While this client holds a Remote Monitor session the host lists only its Resume and
     * Disconnect Monitor controls (see {@link #isSessionCatalogue}); those two are kept here too,
     * so the hero can offer the session as the running app and its Quit can end it.
     */
    public static final class Specials {
        public static final Specials NONE = new Specials(null, null, null);

        public final NvApp virtualDisplay;
        public final NvApp remoteMonitor;
        public final NvApp remoteInput;
        /** The host's Resume control while a Remote Monitor session is held; null otherwise. */
        public final NvApp monitorResume;
        /** The host's Disconnect Monitor control, likewise; non-null exactly while one is held. */
        public final NvApp monitorDisconnect;

        public Specials(NvApp virtualDisplay, NvApp remoteMonitor, NvApp remoteInput) {
            this(virtualDisplay, remoteMonitor, remoteInput, null, null);
        }

        public Specials(NvApp virtualDisplay, NvApp remoteMonitor, NvApp remoteInput,
                        NvApp monitorResume, NvApp monitorDisconnect) {
            this.virtualDisplay = virtualDisplay;
            this.remoteMonitor = remoteMonitor;
            this.remoteInput = remoteInput;
            this.monitorResume = monitorResume;
            this.monitorDisconnect = monitorDisconnect;
        }

        /** Whether this client holds a Remote Monitor session on the host. */
        public boolean holdsMonitorSession() {
            return monitorDisconnect != null;
        }

        /** Whether there is a remote session entry to offer; either one is enough. */
        public boolean hasRemoteSession() {
            return remoteMonitor != null || remoteInput != null;
        }

        /** The special with this id, or null. */
        NvApp byId(int appId) {
            if (virtualDisplay != null && virtualDisplay.getAppId() == appId) {
                return virtualDisplay;
            }
            if (remoteMonitor != null && remoteMonitor.getAppId() == appId) {
                return remoteMonitor;
            }
            if (remoteInput != null && remoteInput.getAppId() == appId) {
                return remoteInput;
            }
            return null;
        }
    }

    /** One list from the host, reduced against the one before it. All lists are unmodifiable. */
    public static final class Reduction {
        /** The games, in the order the host listed them. */
        public final List<NvApp> games;
        public final Specials specials;
        /** Games whose id was not in the previous list. On the first list, every game. */
        public final List<NvApp> added;
        /** Games of the previous list whose id is gone. */
        public final List<NvApp> removed;
        /** Games whose id stayed but whose name changed; the new instances. */
        public final List<NvApp> renamed;

        Reduction(List<NvApp> games, Specials specials, List<NvApp> added,
                  List<NvApp> removed, List<NvApp> renamed) {
            this.games = Collections.unmodifiableList(games);
            this.specials = specials;
            this.added = Collections.unmodifiableList(added);
            this.removed = Collections.unmodifiableList(removed);
            this.renamed = Collections.unmodifiableList(renamed);
        }

        /** Whether the games differ from the previous list at all. */
        public boolean changed() {
            return !added.isEmpty() || !removed.isEmpty() || !renamed.isEmpty();
        }
    }

    private LibraryModel() {
    }

    /**
     * Splits a host list into games and specials, and says what changed since {@code previous}.
     *
     * <p>Apps are matched by id, as the old library did: an id the host keeps is the same game
     * even under a new name. The games keep the host's order; the screen's sort order is applied
     * later, by {@link #toLibraryApps}, so a change of order never looks like a change of list.
     *
     * <p>The host's session controls ({@link NvApp#isSessionControl}) are never games. A list
     * of nothing but those is the host's session catalogue ({@link #isSessionCatalogue}): it says
     * a Remote Monitor session is held, not that every game is gone, so the previous games and
     * specials are kept, unchanged, with the session added to the specials.
     *
     * @param previous the games of the last list, or empty for the first one (from the cache or
     *                 the host). Never null.
     * @param hostList everything the host listed, specials included.
     */
    public static Reduction reduce(List<NvApp> previous, List<NvApp> hostList) {
        return reduce(previous, Specials.NONE, hostList);
    }

    /**
     * The same, also given the specials of the last list, which a session catalogue keeps.
     *
     * @param previousSpecials the specials of the last list; {@link Specials#NONE} for none
     */
    public static Reduction reduce(List<NvApp> previous, Specials previousSpecials,
                                   List<NvApp> hostList) {
        List<NvApp> games = new ArrayList<>();
        NvApp virtualDisplay = null;
        NvApp remoteMonitor = null;
        NvApp remoteInput = null;
        NvApp monitorResume = null;
        NvApp monitorDisconnect = null;
        for (NvApp app : hostList) {
            if (app.isVirtualDisplay()) {
                virtualDisplay = app;
            } else if (app.isRemoteMonitor()) {
                remoteMonitor = app;
            } else if (app.isRemoteInput()) {
                remoteInput = app;
            } else if (app.isSessionControl()) {
                // A control, never a cover. The two a held monitor session lists are kept.
                if (app.isSessionResume()) {
                    monitorResume = app;
                } else if (app.isDisconnectMonitor()) {
                    monitorDisconnect = app;
                }
            } else {
                games.add(app);
            }
        }

        if (isSessionCatalogue(hostList)) {
            Specials held = previousSpecials != null ? previousSpecials : Specials.NONE;
            return new Reduction(new ArrayList<>(previous),
                    new Specials(held.virtualDisplay, held.remoteMonitor, held.remoteInput,
                            monitorResume, monitorDisconnect),
                    new ArrayList<NvApp>(), new ArrayList<NvApp>(), new ArrayList<NvApp>());
        }

        Map<Integer, NvApp> before = new HashMap<>();
        for (NvApp app : previous) {
            before.put(app.getAppId(), app);
        }

        List<NvApp> added = new ArrayList<>();
        List<NvApp> renamed = new ArrayList<>();
        Set<Integer> kept = new HashSet<>();
        for (NvApp app : games) {
            NvApp old = before.get(app.getAppId());
            if (old == null) {
                added.add(app);
            } else {
                kept.add(app.getAppId());
                if (!sameName(old, app)) {
                    renamed.add(app);
                }
            }
        }

        List<NvApp> removed = new ArrayList<>();
        for (NvApp app : previous) {
            if (!kept.contains(app.getAppId())) {
                removed.add(app);
            }
        }

        Specials specials = virtualDisplay == null && remoteMonitor == null && remoteInput == null
                ? Specials.NONE
                : new Specials(virtualDisplay, remoteMonitor, remoteInput);
        return new Reduction(games, specials, added, removed, renamed);
    }

    /**
     * Whether a host list is the host's session catalogue: only session controls, Disconnect
     * Monitor among them. The host answers so while this client holds a Remote Monitor session,
     * and it is not the host's library: it is neither cached nor counted, and it never replaces
     * the games already known.
     */
    public static boolean isSessionCatalogue(List<NvApp> hostList) {
        boolean disconnect = false;
        for (NvApp app : hostList) {
            if (!app.isSessionControl()) {
                return false;
            }
            disconnect |= app.isDisconnectMonitor();
        }
        return disconnect;
    }

    /**
     * The app the host is running, or null when it is idle.
     *
     * <p>Looked for among all games first, hidden ones included: hiding a game takes it off the
     * shelves, not out of the host, and a hidden game that is running must still be resumable.
     * Then among the specials, since a remote session or virtual display is something to come
     * back to too. Failing both (a list we have not seen yet, or a game the host does not list),
     * a stand-in with the id alone: resuming only needs the id.
     *
     * <p>A Remote Monitor session held by this client is running too, though the host reports
     * no game for it: the Remote Monitor entry (launching it again resumes the session), or the
     * host's Resume control when that entry was never seen.
     *
     * @param runningName the name the host or the cached list gave the game, used only for the
     *                    stand-in; null when there is none.
     */
    public static NvApp resolveRunning(List<NvApp> games, Specials specials, int runningId,
                                       String runningName) {
        if (runningId == 0) {
            if (specials != null && specials.holdsMonitorSession()) {
                return specials.remoteMonitor != null ? specials.remoteMonitor : specials.monitorResume;
            }
            return null;
        }
        for (NvApp app : games) {
            if (app.getAppId() == runningId) {
                return app;
            }
        }
        NvApp special = specials == null ? null : specials.byId(runningId);
        if (special != null) {
            return special;
        }
        // "app" is the name the stream and the PC list already use for a game the host never
        // named, so the stand-in reads the same everywhere.
        return new NvApp(runningName != null ? runningName : "app", null, runningId, false);
    }

    /**
     * The games as a screen lists them: every game, hidden ones included and flagged (a screen
     * that does not show hidden games filters on {@link LibraryApp#hidden}), with the running
     * flag set, in the given order.
     */
    public static List<LibraryApp> toLibraryApps(List<NvApp> games, Set<Integer> hiddenIds,
                                                 int runningId, final Comparator<NvApp> order) {
        List<NvApp> sorted = new ArrayList<>(games);
        if (order != null) {
            Collections.sort(sorted, order);
        }
        List<LibraryApp> apps = new ArrayList<>(sorted.size());
        for (NvApp app : sorted) {
            apps.add(new LibraryApp(app,
                    runningId != 0 && app.getAppId() == runningId,
                    hiddenIds != null && hiddenIds.contains(app.getAppId())));
        }
        return Collections.unmodifiableList(apps);
    }

    /**
     * The hidden ids as stored in the {@code HiddenApps} preference: decimal strings, one set per
     * host. An entry that does not parse is skipped rather than failing the whole set.
     */
    public static Set<Integer> parseHiddenIds(Set<String> stored) {
        Set<Integer> ids = new HashSet<>();
        if (stored == null) {
            return ids;
        }
        for (String value : stored) {
            try {
                ids.add(Integer.parseInt(value));
            } catch (NumberFormatException e) {
                // Not ours to fix; it simply hides nothing.
            }
        }
        return ids;
    }

    /** The inverse of {@link #parseHiddenIds}, for writing the preference back. */
    public static Set<String> formatHiddenIds(Set<Integer> ids) {
        Set<String> stored = new HashSet<>();
        for (Integer id : ids) {
            stored.add(id.toString());
        }
        return stored;
    }

    private static boolean sameName(NvApp a, NvApp b) {
        return a.getAppName() == null ? b.getAppName() == null : a.getAppName().equals(b.getAppName());
    }
}
