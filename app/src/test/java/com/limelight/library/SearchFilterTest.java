package com.limelight.library;

import com.limelight.nvstream.http.NvApp;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** The Search section's filter: case-insensitive substring, library order, hidden games. */
public class SearchFilterTest {

    private static LibraryApp app(String name, int id, boolean hidden) {
        return new LibraryApp(new NvApp(name, "uuid-" + id, id, false), false, hidden);
    }

    private static List<String> names(List<LibraryApp> apps) {
        List<String> names = new ArrayList<>();
        for (LibraryApp app : apps) {
            names.add(app.app.getAppName());
        }
        return names;
    }

    private final List<LibraryApp> library = Arrays.asList(
            app("Alan Wake 2", 1, false),
            app("Hades", 2, false),
            app("Hollow Knight", 3, true),
            app("Wakfu", 4, false),
            app("Desktop", 5, false));

    @Test
    public void matchesASubstringAnywhereInTheName_ignoringCase() {
        assertEquals(Arrays.asList("Alan Wake 2", "Wakfu"),
                names(SearchFilter.filter(library, "WAK", false)));
        assertEquals(Collections.singletonList("Alan Wake 2"),
                names(SearchFilter.filter(library, "e 2", false)));
    }

    @Test
    public void keepsTheLibrarysOrder() {
        assertEquals(Arrays.asList("Hades", "Desktop"),
                names(SearchFilter.filter(library, "es", false)));
        assertEquals(Arrays.asList("Alan Wake 2", "Hades", "Wakfu"),
                names(SearchFilter.filter(library, "a", false)));
    }

    @Test
    public void blankQuery_listsEveryVisibleGame() {
        assertEquals(Arrays.asList("Alan Wake 2", "Hades", "Wakfu", "Desktop"),
                names(SearchFilter.filter(library, null, false)));
        assertEquals(4, SearchFilter.filter(library, "   ", false).size());
        assertTrue(SearchFilter.isBlank("  "));
        assertTrue(SearchFilter.isBlank(null));
        assertFalse(SearchFilter.isBlank(" h "));
    }

    @Test
    public void surroundingSpaces_areIgnored() {
        assertEquals(Collections.singletonList("Hades"),
                names(SearchFilter.filter(library, "  hades ", false)));
    }

    @Test
    public void hiddenGames_onlyWhenAskedFor() {
        assertTrue(SearchFilter.filter(library, "hollow", false).isEmpty());
        assertEquals(Collections.singletonList("Hollow Knight"),
                names(SearchFilter.filter(library, "hollow", true)));
        // Nothing to narrow and nothing to leave out: the library's own list.
        assertSame(library, SearchFilter.filter(library, "", true));
    }

    @Test
    public void noMatch_orNoGames_isEmpty() {
        assertTrue(SearchFilter.filter(library, "zelda", true).isEmpty());
        assertTrue(SearchFilter.filter(null, "a", false).isEmpty());
        assertTrue(SearchFilter.filter(Collections.<LibraryApp>emptyList(), "", true).isEmpty());
    }

    @Test
    public void matchingDoesNotDependOnTheDefaultLocale() {
        Locale before = Locale.getDefault();
        Locale.setDefault(new Locale("tr", "TR"));
        try {
            List<LibraryApp> games = Collections.singletonList(app("INSIDE", 9, false));
            assertEquals(1, SearchFilter.filter(games, "inside", false).size());
        } finally {
            Locale.setDefault(before);
        }
    }
}
