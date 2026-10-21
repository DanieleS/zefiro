package com.limelight.library;

import com.limelight.grid.AppSortOrder;
import com.limelight.nvstream.http.AppMetadata;
import com.limelight.nvstream.http.NvApp;
import com.limelight.utils.AppLaunchHistory;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class LibraryShelvesTest {

    private final Map<String, AppMetadata> metadata = new HashMap<>();
    private final AppLaunchHistory history = mock(AppLaunchHistory.class);

    private static LibraryApp app(String name, int id, boolean hidden) {
        return new LibraryApp(new NvApp(name, "uuid-" + id, id, false), false, hidden);
    }

    private void hostPlayed(LibraryApp app, long epochMillis) {
        AppMetadata meta = mock(AppMetadata.class);
        when(meta.getLastPlayedEpochMillis()).thenReturn(epochMillis);
        metadata.put(app.app.getAppUUID().toUpperCase(), meta);
    }

    private void locallyPlayed(LibraryApp app, long epochMillis) {
        when(history.getLastPlayed(app.app)).thenReturn(epochMillis);
    }

    private static List<String> names(List<LibraryApp> apps) {
        List<String> names = new ArrayList<>();
        for (LibraryApp app : apps) {
            names.add(app.app.getAppName());
        }
        return names;
    }

    @Test
    public void recentHoldsOnlyPlayedGamesMostRecentFirst() {
        LibraryApp never = app("Never", 1, false);
        LibraryApp old = app("Old", 2, false);
        LibraryApp newest = app("Newest", 3, false);
        hostPlayed(old, 1_000L);
        locallyPlayed(newest, 5_000L);

        assertEquals(Arrays.asList("Newest", "Old"),
                names(LibraryShelves.recent(Arrays.asList(never, old, newest), metadata, history)));
    }

    @Test
    public void recentLeavesOutHiddenGames() {
        LibraryApp hidden = app("Hidden", 1, true);
        LibraryApp shown = app("Shown", 2, false);
        hostPlayed(hidden, 9_000L);
        hostPlayed(shown, 1_000L);

        assertEquals(Collections.singletonList("Shown"),
                names(LibraryShelves.recent(Arrays.asList(hidden, shown), metadata, history)));
    }

    @Test
    public void recentIsEmptyWhenNothingWasPlayed() {
        assertTrue(LibraryShelves.recent(Arrays.asList(app("A", 1, false), app("B", 2, false)),
                metadata, null).isEmpty());
    }

    @Test
    public void recentIsCappedAtAShelf() {
        List<LibraryApp> apps = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            LibraryApp app = app("Game " + i, i, false);
            locallyPlayed(app, i * 1_000L);
            apps.add(app);
        }

        List<LibraryApp> shelf = LibraryShelves.recent(apps, metadata, history);

        assertEquals(LibraryShelves.SHELF_SIZE, shelf.size());
        // The cap keeps the most recent, not the first listed.
        assertEquals("Game 20", shelf.get(0).app.getAppName());
        assertEquals("Game 9", shelf.get(LibraryShelves.SHELF_SIZE - 1).app.getAppName());
    }

    @Test
    public void previewIsTheHeadOfTheVisibleGamesInOrder() {
        List<LibraryApp> apps = Arrays.asList(app("delta", 4, false), app("Alpha", 1, false),
                app("Charlie", 3, true), app("bravo", 2, false));

        assertEquals(Arrays.asList("Alpha", "bravo"),
                names(LibraryShelves.preview(apps, AppSortOrder.NAME.comparator(null, null), 2)));
        assertEquals(Arrays.asList("Alpha", "bravo", "delta"),
                names(LibraryShelves.preview(apps, AppSortOrder.NAME.comparator(null, null))));
        assertEquals(3, LibraryShelves.visibleCount(apps));
    }

    @Test
    public void previewWithoutAnOrderKeepsTheListsOwn() {
        List<LibraryApp> apps = Arrays.asList(app("Zulu", 26, false), app("Alpha", 1, false));
        assertEquals(Arrays.asList("Zulu", "Alpha"), names(LibraryShelves.preview(apps, null)));
    }

    @Test
    public void previewIsCappedAtAShelfByDefault() {
        List<LibraryApp> apps = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            apps.add(app(String.format("Game %02d", i), i + 1, false));
        }
        assertEquals(LibraryShelves.SHELF_SIZE,
                LibraryShelves.preview(apps, AppSortOrder.NAME.comparator(null, null)).size());
    }

    @Test
    public void seeAllOpensOnRecent_untilAnOrderIsPicked_whenThereIsAHistory() {
        List<LibraryApp> played = Collections.singletonList(app("Hades II", 1, false));
        List<LibraryApp> none = Collections.emptyList();

        assertEquals(AppSortOrder.RECENT, LibraryShelves.seeAllDefault(false, played));
        // Nothing played yet: the library's own order, as there is nothing to lead with.
        assertEquals(null, LibraryShelves.seeAllDefault(false, none));
        // An order picked by hand, host order included, is the user's: it stands.
        assertEquals(null, LibraryShelves.seeAllDefault(true, played));
    }

    @Test
    public void recentShelfIsLeftOutWhileItWouldBeMostlyEmpty() {
        List<LibraryApp> three = Arrays.asList(app("A", 1, false), app("B", 2, false),
                app("C", 3, false));
        List<LibraryApp> four = new ArrayList<>(three);
        four.add(app("D", 4, false));

        assertTrue(LibraryShelves.recentShelf(three, 16).isEmpty());
        assertEquals(four, LibraryShelves.recentShelf(four, 16));
        // A small library played through is a full shelf, not a mostly empty one.
        assertEquals(three, LibraryShelves.recentShelf(three, 3));
        assertTrue(LibraryShelves.recentShelf(Collections.<LibraryApp>emptyList(), 0).isEmpty());
    }
}
