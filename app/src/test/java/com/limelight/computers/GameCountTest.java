package com.limelight.computers;

import com.limelight.nvstream.http.NvApp;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;

/**
 * A host's game count is what the Library would list (no specials, no hidden games), and a poll
 * that changed nothing does not parse the list again.
 */
public class GameCountTest {

    private static NvApp game(int id) {
        return new NvApp("game " + id, "uuid-" + id, id, false);
    }

    private static NvApp special(String uuid, int id) {
        return new NvApp("special " + id, uuid, id, false);
    }

    private static Set<Integer> ids(Integer... ids) {
        return new HashSet<>(Arrays.asList(ids));
    }

    /** A parser that hands out a fixed list and counts how often it was asked. */
    private static final class CountingParser implements GameCount.Parser {
        final List<NvApp> apps;
        int parses;

        CountingParser(List<NvApp> apps) {
            this.apps = apps;
        }

        @Override
        public List<NvApp> parse(String rawAppList) {
            parses++;
            return apps;
        }
    }

    @Test
    public void count_leavesTheSpecialsOut() {
        List<NvApp> list = Arrays.asList(game(1), game(2),
                special(NvApp.VIRTUAL_DISPLAY_UUID, 90),
                special(NvApp.REMOTE_MONITOR_UUID, 91),
                special(NvApp.VIBEPOLLO_REMOTE_INPUT_UUID, 92),
                special(NvApp.REMOTE_INPUT_UUID, 93));

        assertEquals(2, GameCount.count(list, null));
    }

    @Test
    public void count_leavesTheHiddenGamesOut() {
        List<NvApp> list = Arrays.asList(game(1), game(2), game(3));

        assertEquals(1, GameCount.count(list, ids(1, 3)));
        // An id hidden on the host's old list takes nothing away from this one.
        assertEquals(3, GameCount.count(list, ids(42)));
    }

    @Test
    public void count_ofAnEmptyListIsZero() {
        assertEquals(0, GameCount.count(Collections.<NvApp>emptyList(), ids()));
    }

    @Test
    public void countFor_withoutAListIsUnknown() {
        CountingParser parser = new CountingParser(Arrays.asList(game(1)));
        GameCount counts = new GameCount(parser);

        assertEquals(HostEntry.UNKNOWN_GAME_COUNT, counts.countFor("a", null, ids()));
        assertEquals(HostEntry.UNKNOWN_GAME_COUNT, counts.countFor("a", "", ids()));
        assertEquals(0, parser.parses);
    }

    @Test
    public void countFor_doesNotParseAnUnchangedListAgain() {
        CountingParser parser = new CountingParser(Arrays.asList(game(1), game(2), game(3)));
        GameCount counts = new GameCount(parser);

        assertEquals(3, counts.countFor("a", "<root>list</root>", ids()));
        // An equal string, not the same instance: a poll copies the details.
        assertEquals(3, counts.countFor("a", new String("<root>list</root>"), ids()));
        assertEquals(1, parser.parses);

        // A hide changes the answer, and so does a new list.
        assertEquals(2, counts.countFor("a", "<root>list</root>", ids(2)));
        assertEquals(2, parser.parses);
        assertEquals(2, counts.countFor("a", "<root>list</root>", ids(2)));
        assertEquals(2, parser.parses);
        assertEquals(2, counts.countFor("a", "<root>list 2</root>", ids(2)));
        assertEquals(3, parser.parses);

        // Each host keeps its own answer.
        assertEquals(3, counts.countFor("b", "<root>list</root>", ids()));
        assertEquals(4, parser.parses);
    }

    @Test
    public void countFor_aSessionCatalogueKeepsTheLastCount() {
        final List<NvApp> library = Arrays.asList(game(1), game(2), game(3));
        final List<NvApp> session = Arrays.asList(
                new NvApp("Resume", NvApp.SESSION_RESUME_UUID, 91, true),
                new NvApp("Disconnect Monitor", NvApp.DISCONNECT_MONITOR_UUID, 92, true));
        GameCount counts = new GameCount(raw -> raw.equals("library") ? library : session);

        // Held before the library was ever counted, the count is not known yet.
        assertEquals(HostEntry.UNKNOWN_GAME_COUNT, counts.countFor("b", "session", ids()));

        assertEquals(3, counts.countFor("a", "library", ids()));
        // A held Remote Monitor session lists two controls, not two games, nor none.
        assertEquals(3, counts.countFor("a", "session", ids()));
        assertEquals(3, counts.countFor("a", "library", ids()));
    }

    @Test
    public void countFor_keepsItsOwnCopyOfTheHiddenIds() {
        CountingParser parser = new CountingParser(Arrays.asList(game(1), game(2)));
        GameCount counts = new GameCount(parser);
        Set<Integer> hidden = ids();

        assertEquals(2, counts.countFor("a", "<root/>", hidden));
        // The caller's set changing under the memo must not make the memo stale.
        hidden.add(1);
        assertEquals(1, counts.countFor("a", "<root/>", hidden));
        assertEquals(2, parser.parses);
    }

    @Test
    public void countFor_aListThatDoesNotParseIsUnknownAndNotParsedAgain() {
        final List<Integer> calls = new ArrayList<>();
        GameCount counts = new GameCount(raw -> {
            calls.add(1);
            throw new IOException("truncated");
        });

        assertEquals(HostEntry.UNKNOWN_GAME_COUNT, counts.countFor("a", "<root", ids()));
        assertEquals(HostEntry.UNKNOWN_GAME_COUNT, counts.countFor("a", "<root", ids()));
        assertEquals(1, calls.size());
    }

    @Test
    public void forget_dropsTheMemo() {
        CountingParser parser = new CountingParser(Arrays.asList(game(1)));
        GameCount counts = new GameCount(parser);

        counts.countFor("a", "<root/>", ids());
        counts.forget("a");
        counts.countFor("a", "<root/>", ids());
        assertEquals(2, parser.parses);
    }
}
