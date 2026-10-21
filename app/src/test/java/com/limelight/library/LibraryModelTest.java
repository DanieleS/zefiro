package com.limelight.library;

import com.limelight.grid.AppSortOrder;
import com.limelight.nvstream.http.NvApp;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class LibraryModelTest {

    private static NvApp game(String name, int id) {
        return new NvApp(name, "uuid-" + id, id, false);
    }

    private static NvApp special(String uuid, int id) {
        return new NvApp("special " + id, uuid, id, false);
    }

    private static List<Integer> ids(List<NvApp> apps) {
        List<Integer> ids = new ArrayList<>();
        for (NvApp app : apps) {
            ids.add(app.getAppId());
        }
        return ids;
    }

    private static List<String> names(List<LibraryApp> apps) {
        List<String> names = new ArrayList<>();
        for (LibraryApp app : apps) {
            names.add(app.app.getAppName());
        }
        return names;
    }

    @Test
    public void firstListAddsEveryGame() {
        LibraryModel.Reduction r = LibraryModel.reduce(Collections.<NvApp>emptyList(),
                Arrays.asList(game("A", 1), game("B", 2)));

        assertEquals(Arrays.asList(1, 2), ids(r.games));
        assertEquals(Arrays.asList(1, 2), ids(r.added));
        assertTrue(r.removed.isEmpty());
        assertTrue(r.renamed.isEmpty());
        assertTrue(r.changed());
    }

    @Test
    public void addRenameAndRemoveAreMatchedById() {
        List<NvApp> before = Arrays.asList(game("Keep", 1), game("Old name", 2), game("Gone", 3));
        List<NvApp> after = Arrays.asList(game("Keep", 1), game("New name", 2), game("Fresh", 4));

        LibraryModel.Reduction r = LibraryModel.reduce(before, after);

        assertEquals(Arrays.asList(1, 2, 4), ids(r.games));
        assertEquals(Collections.singletonList(4), ids(r.added));
        assertEquals(Collections.singletonList(3), ids(r.removed));
        assertEquals(Collections.singletonList(2), ids(r.renamed));
        assertEquals("New name", r.renamed.get(0).getAppName());
    }

    @Test
    public void anUnchangedListChangesNothing() {
        List<NvApp> list = Arrays.asList(game("A", 1), game("B", 2));
        LibraryModel.Reduction r = LibraryModel.reduce(list, Arrays.asList(game("A", 1), game("B", 2)));
        assertFalse(r.changed());
    }

    @Test
    public void specialsArePulledOutOfTheGames() {
        NvApp vdisplay = special(NvApp.VIRTUAL_DISPLAY_UUID, 10);
        NvApp monitor = special(NvApp.REMOTE_MONITOR_UUID, 11);
        // Lower case on purpose: the host's casing is not ours to rely on.
        NvApp input = special(NvApp.VIBEPOLLO_REMOTE_INPUT_UUID.toLowerCase(), 12);

        LibraryModel.Reduction r = LibraryModel.reduce(Collections.<NvApp>emptyList(),
                Arrays.asList(game("A", 1), vdisplay, monitor, input));

        assertEquals(Collections.singletonList(1), ids(r.games));
        assertSame(vdisplay, r.specials.virtualDisplay);
        assertSame(monitor, r.specials.remoteMonitor);
        assertSame(input, r.specials.remoteInput);
        assertTrue(r.specials.hasRemoteSession());
    }

    @Test
    public void noSpecialsIsTheSharedEmptyValue() {
        LibraryModel.Reduction r = LibraryModel.reduce(Collections.<NvApp>emptyList(),
                Collections.singletonList(game("A", 1)));
        assertSame(LibraryModel.Specials.NONE, r.specials);
        assertFalse(r.specials.hasRemoteSession());
    }

    // --- The host's session controls ---

    private static NvApp control(String uuid, int id, String name) {
        return new NvApp(name, uuid, id, true);
    }

    @Test
    public void sessionControlsAreNeverGames() {
        // A second client while another plays: the library, plus the host's controls.
        LibraryModel.Reduction r = LibraryModel.reduce(Collections.<NvApp>emptyList(),
                Arrays.asList(control(NvApp.RUNNING_GAME_COPY_UUID, 90, "     A"),
                        control(NvApp.SESSION_RESUME_UUID, 91, "  Resume"),
                        control(NvApp.TERMINATE_UUID.toLowerCase(), 92, " Terminate"),
                        game("A", 1), game("B", 2)));

        assertEquals(Arrays.asList(1, 2), ids(r.games));
        assertFalse(r.specials.holdsMonitorSession());
    }

    @Test
    public void aSessionCatalogueKeepsTheGamesAndSpecials() {
        NvApp monitor = special(NvApp.REMOTE_MONITOR_UUID, 11);
        LibraryModel.Reduction before = LibraryModel.reduce(Collections.<NvApp>emptyList(),
                Arrays.asList(game("A", 1), game("B", 2), monitor));
        NvApp resume = control(NvApp.SESSION_RESUME_UUID, 91, "Resume");
        NvApp disconnect = control(NvApp.DISCONNECT_MONITOR_UUID, 92, "Disconnect Monitor");

        List<NvApp> catalogue = Arrays.asList(resume, disconnect);
        assertTrue(LibraryModel.isSessionCatalogue(catalogue));
        LibraryModel.Reduction r = LibraryModel.reduce(before.games, before.specials, catalogue);

        assertEquals(Arrays.asList(1, 2), ids(r.games));
        assertFalse(r.changed());
        assertSame(monitor, r.specials.remoteMonitor);
        assertSame(resume, r.specials.monitorResume);
        assertSame(disconnect, r.specials.monitorDisconnect);
        // The session runs no game, yet it is what the hero offers: the Remote Monitor entry.
        assertSame(monitor, LibraryModel.resolveRunning(r.games, r.specials, 0, null));

        // The library comes back once the session ends, and the session with it is gone.
        LibraryModel.Reduction after = LibraryModel.reduce(r.games, r.specials,
                Arrays.asList(game("A", 1), game("B", 2), monitor));
        assertFalse(after.specials.holdsMonitorSession());
        assertNull(LibraryModel.resolveRunning(after.games, after.specials, 0, null));
    }

    @Test
    public void aSessionWithNoMonitorEntryResumesThroughTheControl() {
        NvApp resume = control(NvApp.SESSION_RESUME_UUID, 91, "Resume");
        LibraryModel.Reduction r = LibraryModel.reduce(Collections.<NvApp>emptyList(),
                LibraryModel.Specials.NONE,
                Arrays.asList(resume, control(NvApp.DISCONNECT_MONITOR_UUID, 92, "Disconnect Monitor")));

        assertTrue(r.games.isEmpty());
        assertSame(resume, LibraryModel.resolveRunning(r.games, r.specials, 0, null));
    }

    @Test
    public void onlyControlsWithDisconnectMonitorAreASessionCatalogue() {
        assertFalse(LibraryModel.isSessionCatalogue(Collections.<NvApp>emptyList()));
        assertFalse(LibraryModel.isSessionCatalogue(Arrays.asList(
                control(NvApp.SESSION_RESUME_UUID, 91, "Resume"),
                control(NvApp.DISCONNECT_MONITOR_UUID, 92, "Disconnect Monitor"),
                game("A", 1))));
        assertFalse(LibraryModel.isSessionCatalogue(Collections.singletonList(
                control(NvApp.TERMINATE_UUID, 94, "Terminate"))));
    }

    @Test
    public void nothingRunningResolvesToNull() {
        assertNull(LibraryModel.resolveRunning(Collections.singletonList(game("A", 1)),
                LibraryModel.Specials.NONE, 0, "A"));
    }

    @Test
    public void runningGameIsFoundEvenWhenHidden() {
        NvApp hidden = game("Hidden", 7);
        List<NvApp> games = Arrays.asList(game("A", 1), hidden);
        // The hidden set plays no part: a hidden game that runs is still the one to resume.
        assertSame(hidden, LibraryModel.resolveRunning(games, LibraryModel.Specials.NONE, 7, null));

        List<LibraryApp> apps = LibraryModel.toLibraryApps(games, Collections.singleton(7), 7, null);
        assertTrue(apps.get(1).running);
        assertTrue(apps.get(1).hidden);
    }

    @Test
    public void runningSpecialIsFoundAfterTheGames() {
        NvApp vdisplay = special(NvApp.VIRTUAL_DISPLAY_UUID, 10);
        LibraryModel.Specials specials = new LibraryModel.Specials(vdisplay, null, null);
        assertSame(vdisplay, LibraryModel.resolveRunning(
                Collections.singletonList(game("A", 1)), specials, 10, null));
    }

    @Test
    public void anUnlistedGameGetsAStandIn() {
        NvApp standIn = LibraryModel.resolveRunning(Collections.<NvApp>emptyList(),
                LibraryModel.Specials.NONE, 42, "Named by host");
        assertEquals(42, standIn.getAppId());
        assertEquals("Named by host", standIn.getAppName());
        assertNull(standIn.getAppUUID());

        NvApp unnamed = LibraryModel.resolveRunning(Collections.<NvApp>emptyList(), null, 43, null);
        assertEquals(43, unnamed.getAppId());
        assertEquals("app", unnamed.getAppName());
    }

    @Test
    public void libraryAppsCarryFlagsInTheGivenOrder() {
        List<NvApp> games = Arrays.asList(game("charlie", 3), game("Alpha", 1), game("bravo", 2));
        Set<Integer> hidden = new HashSet<>(Collections.singletonList(2));

        List<LibraryApp> apps = LibraryModel.toLibraryApps(games, hidden, 3,
                AppSortOrder.NAME.comparator(null, null));

        assertEquals(Arrays.asList("Alpha", "bravo", "charlie"), names(apps));
        assertFalse(apps.get(0).running || apps.get(0).hidden);
        assertTrue(apps.get(1).hidden);
        assertTrue(apps.get(2).running);
    }

    @Test
    public void libraryAppEqualityFollowsWhatATileShows() {
        LibraryApp a = new LibraryApp(game("A", 1), false, false);
        assertEquals(a, new LibraryApp(game("A", 1), false, false));
        assertEquals(a.hashCode(), new LibraryApp(game("A", 1), false, false).hashCode());

        assertFalse(a.equals(new LibraryApp(game("A", 1), true, false)));
        assertFalse(a.equals(new LibraryApp(game("A", 1), false, true)));
        assertFalse(a.equals(new LibraryApp(game("Renamed", 1), false, false)));
        // Different state, same game: what a list keeps its stable id on.
        assertTrue(a.sameApp(new LibraryApp(game("Renamed", 1), true, true)));
    }

    @Test
    public void hiddenIdsRoundTripAndSkipJunk() {
        Set<String> stored = new HashSet<>(Arrays.asList("3", "17", "not-a-number"));
        Set<Integer> ids = LibraryModel.parseHiddenIds(stored);
        assertEquals(new HashSet<>(Arrays.asList(3, 17)), ids);
        assertEquals(new HashSet<>(Arrays.asList("3", "17")), LibraryModel.formatHiddenIds(ids));
        assertTrue(LibraryModel.parseHiddenIds(null).isEmpty());
    }
}
