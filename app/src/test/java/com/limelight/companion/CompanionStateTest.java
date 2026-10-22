package com.limelight.companion;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import com.limelight.binding.video.PerfStats;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The content and owner regions, pinned before the single-activity refactor starts driving them
 * from fragments instead of activities; and the browsing payload (a {@link CompanionState.Browsing}
 * and the hosts row) the main screen feeds while it is up.
 */
public class CompanionStateTest {

    private CompanionState state;
    private int changes;
    private int ownerChanges;

    @Before
    public void setUp() throws Exception {
        // The production object is a process-wide singleton; each test gets its own so no state
        // leaks between them.
        Constructor<CompanionState> ctor = CompanionState.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        state = ctor.newInstance();
        state.setListener(() -> changes++);
        state.setOwnerListener(() -> ownerChanges++);
    }

    @Test
    public void startsIdleWithThePanelUp() {
        assertEquals(CompanionState.Content.IDLE, state.getContent());
        assertEquals(CompanionState.Owner.PANEL, state.getOwner());
        assertTrue(state.isPanelOwner());
    }

    @Test
    public void browsingIsEnteredBySpotlightingAGameAndLeftByClearingIt() {
        state.setBrowsing(game("Hades", 12, false));
        assertEquals(CompanionState.Content.BROWSING, state.getContent());
        assertEquals("Hades", state.getBrowsingTitle());
        assertTrue(changes > 0);

        state.clearBrowsing();
        assertEquals(CompanionState.Content.IDLE, state.getContent());
        assertNull(state.getBrowsingTitle());
    }

    @Test
    public void streamingIsEnteredAndLeft() {
        state.enterStreaming();
        assertEquals(CompanionState.Content.STREAMING, state.getContent());

        state.setTelemetryView("<html></html>");
        state.leaveStreaming();
        assertEquals(CompanionState.Content.IDLE, state.getContent());
        // The ended game's view must not outlive it.
        assertNull(state.getTelemetryView());
        assertNull(state.getStats());
    }

    @Test
    public void aLaunchOverAStreamNotYetLeftEndsThatStream() {
        // The next game's stream screen opens before the last one is destroyed: its late
        // leaveStreaming() finds LAUNCHING, so the launch itself must end the old stream.
        state.enterStreaming();
        state.setTelemetryView("<html></html>");
        state.enterLaunching(new CompanionState.Launch("Hades", "Taverna", 1000));
        state.leaveStreaming();
        assertEquals(CompanionState.Content.LAUNCHING, state.getContent());
        assertNull(state.getTelemetryView());
        assertNull(state.getStats());
    }

    @Test
    public void thePanelsStreamChoicesEndWithTheStream() {
        state.enterStreaming();
        state.setTelemetryView("<html>view</html>");
        state.setStreamSession(new CompanionState.StreamSession("Hades II", "Taverna", "HEVC",
                1000L));
        state.setStatsOverTelemetry(true);
        state.markTelemetryViewCrashed("<html>view</html>");
        assertTrue(state.isStatsOverTelemetry());
        // A crashed view is not drawn again this stream.
        assertNull(state.getUsableTelemetryView());

        state.leaveStreaming();
        assertFalse(state.isStatsOverTelemetry());
        assertNull(state.getStreamSession());

        // The next game starts on its own view, and the one that crashed gets another chance.
        state.enterStreaming();
        state.setTelemetryView("<html>view</html>");
        assertFalse(state.isStatsOverTelemetry());
        assertEquals("<html>view</html>", state.getUsableTelemetryView());
    }

    @Test
    public void statsOverTheViewOutliveAPanelRedraw() {
        state.enterStreaming();
        state.setTelemetryView("<html></html>");
        int before = changes;
        state.setStatsOverTelemetry(true);
        assertEquals(before + 1, changes);
        // Asking again for what is already shown redraws nothing.
        state.setStatsOverTelemetry(true);
        assertEquals(before + 1, changes);
        // Only the end of the stream undoes it, not leaving a state the content is not in.
        state.leaveBrowsing();
        assertTrue(state.isStatsOverTelemetry());
    }

    @Test
    public void onlyTheViewThatCrashedIsSetAside() {
        state.enterStreaming();
        state.setTelemetryView("<html>a</html>");
        state.markTelemetryViewCrashed("<html>a</html>");
        assertNull(state.getUsableTelemetryView());
        state.setTelemetryView("<html>b</html>");
        assertEquals("<html>b</html>", state.getUsableTelemetryView());
    }

    @Test
    public void anUnchangedStreamSessionRedrawsNothing() {
        state.setStreamSession(new CompanionState.StreamSession("Hades II", "Taverna", null,
                1000L));
        int before = changes;
        state.setStreamSession(new CompanionState.StreamSession("Hades II", "Taverna", null,
                1000L));
        assertEquals(before, changes);
    }

    @Test
    public void browsingIsRefusedWhileStreaming() {
        state.enterStreaming();
        state.setBrowsing(game("Late push", 3, false));
        assertEquals(CompanionState.Content.STREAMING, state.getContent());

        // Leaving the library must not end the stream's surface either.
        state.clearBrowsing();
        assertEquals(CompanionState.Content.STREAMING, state.getContent());
    }

    @Test
    public void leavingAStateYouAreNotInIsANoOp() {
        state.setBrowsing(game("Hades", 12, false));
        state.leaveStreaming();
        assertEquals(CompanionState.Content.BROWSING, state.getContent());

        state.clearBrowsing();
        state.enterStreaming();
        state.leaveBrowsing();
        assertEquals(CompanionState.Content.STREAMING, state.getContent());
    }

    @Test
    public void ownerMovesIndependentlyOfContent() {
        state.enterStreaming();

        state.setOwner(CompanionState.Owner.COMPANION_APP);
        assertFalse(state.isPanelOwner());
        assertEquals(CompanionState.Content.STREAMING, state.getContent());
        assertEquals(1, ownerChanges);

        state.setOwner(CompanionState.Owner.PANEL);
        assertTrue(state.isPanelOwner());
        assertEquals(CompanionState.Content.STREAMING, state.getContent());
        assertEquals(2, ownerChanges);
    }

    @Test
    public void settingTheSameOwnerDoesNotNotify() {
        state.setOwner(CompanionState.Owner.PANEL);
        assertEquals(0, ownerChanges);

        state.setOwner(CompanionState.Owner.OFF);
        state.setOwner(CompanionState.Owner.OFF);
        assertEquals(1, ownerChanges);
    }

    @Test
    public void anOutgoingSurfaceCannotClearItsReplacement() {
        CompanionState.Listener outgoing = () -> { };
        int[] incomingCalls = {0};
        CompanionState.Listener incoming = () -> incomingCalls[0]++;

        state.setListener(outgoing);
        state.setListener(incoming);
        state.clearListener(outgoing);

        state.enterStreaming();
        assertEquals(1, incomingCalls[0]);
    }

    // --- Browsing and the hosts row ---

    // --- Launching ---

    @Test
    public void aLaunchIsShownUntilTheStreamTakesOver() {
        CompanionState.Launch launch = new CompanionState.Launch("Hades", "Taverna", 1000);
        state.enterLaunching(launch);
        assertEquals(CompanionState.Content.LAUNCHING, state.getContent());
        assertSame(launch, state.getLaunch());

        state.enterStreaming();
        assertEquals(CompanionState.Content.STREAMING, state.getContent());
        assertNull(state.getLaunch());
        // The scene fading out afterwards does not take the stream away.
        state.leaveLaunching(1000);
        assertEquals(CompanionState.Content.STREAMING, state.getContent());
    }

    @Test
    public void aLaunchGivenUpGoesBackToWhatWasBrowsedMeanwhile() {
        state.setBrowsing(game("Hades", 12, false));
        state.enterLaunching(new CompanionState.Launch("Hades", "Taverna", 1000));
        assertEquals(CompanionState.Content.LAUNCHING, state.getContent());

        // The main screen pushes again before it stops: held, not drawn.
        state.setBrowsing(game("Celeste", 13, false));
        assertEquals(CompanionState.Content.LAUNCHING, state.getContent());

        state.leaveLaunching(1000);
        assertEquals(CompanionState.Content.BROWSING, state.getContent());
        assertEquals("Celeste", state.getBrowsingTitle());
        assertNull(state.getLaunch());
    }

    @Test
    public void aLaunchGivenUpWithNothingBrowsedGoesIdle() {
        state.enterLaunching(new CompanionState.Launch("Hades", "Taverna", 1000));
        state.leaveLaunching(1000);
        assertEquals(CompanionState.Content.IDLE, state.getContent());
    }

    @Test
    public void anUpdateMovesTheLaunchOnAndRedraws() {
        CompanionState.Launch launch = new CompanionState.Launch("Hades", "Taverna", 1000);
        state.enterLaunching(launch);
        int before = changes;

        CompanionState.Launch next = launch.withStep(1, "Reaching your PC…")
                .withStream("1920×1080 · 60 fps", "Auto · HDR · 40 Mbps", "Virtual display");
        state.updateLaunch(next);
        assertSame(next, state.getLaunch());
        assertEquals(1, state.getLaunch().step);
        assertEquals("Virtual display", state.getLaunch().display);
        assertTrue(changes > before);

        // The same again draws nothing.
        int after = changes;
        state.updateLaunch(launch.withStep(1, "Reaching your PC…")
                .withStream("1920×1080 · 60 fps", "Auto · HDR · 40 Mbps", "Virtual display"));
        assertEquals(after, changes);
    }

    @Test
    public void anotherLaunchesUpdatesAndEndAreIgnored() {
        state.enterLaunching(new CompanionState.Launch("Celeste", "Taverna", 2000));
        // A screen torn down late, after the next one opened, speaks for its own launch only.
        state.updateLaunch(new CompanionState.Launch("Hades", "Taverna", 1000).withStep(3, "x"));
        assertEquals("Celeste", state.getLaunch().game);
        state.leaveLaunching(1000);
        assertEquals(CompanionState.Content.LAUNCHING, state.getContent());
    }

    @Test
    public void anUpdateAfterTheLaunchEndedDoesNotBringItBack() {
        CompanionState.Launch launch = new CompanionState.Launch("Hades", "Taverna", 1000);
        state.enterLaunching(launch);
        state.leaveLaunching(1000);
        state.updateLaunch(launch.withStep(2, "Preparing video and controls…"));
        assertEquals(CompanionState.Content.IDLE, state.getContent());
        assertNull(state.getLaunch());
    }

    @Test
    public void aLaunchsStepStaysInRange() {
        CompanionState.Launch launch = new CompanionState.Launch("Hades", null, 1000);
        assertEquals(0, launch.withStep(-3, null).step);
        assertEquals(CompanionState.Launch.STEP_COUNT, launch.withStep(9, null).step);
    }

    private static CompanionState.Browsing game(String title, int appId, boolean running) {
        return new CompanionState.Browsing(CompanionState.Browsing.Kind.GAME, "HOST-A", appId,
                title, "2023  ·  Action", "<p>About it</p>", null, running);
    }

    private static List<CompanionState.HostChip> twoHosts(String selected) {
        return Arrays.asList(
                new CompanionState.HostChip("HOST-A", "Taverna", true, "HOST-A".equals(selected)),
                new CompanionState.HostChip("HOST-B", "Desk", false, "HOST-B".equals(selected)));
    }

    @Test
    public void aBrowsingEntersBrowsingAndIsKeptAsGiven() {
        CompanionState.Browsing hades = game("Hades", 12, false);
        state.setBrowsing(hades);

        assertEquals(CompanionState.Content.BROWSING, state.getContent());
        assertSame(hades, state.getBrowsing());
        assertEquals("Hades", state.getBrowsingTitle());
        assertTrue(hades.hasGame());
    }

    @Test
    public void anEqualBrowsingDoesNotRedraw() {
        state.setBrowsing(game("Hades", 12, false));
        int before = changes;

        // Re-resolved on a poll that changed nothing: the same answer, a new instance.
        state.setBrowsing(game("Hades", 12, false));
        assertEquals(before, changes);

        // The game started running: Play becomes Resume, so the panel must redraw.
        state.setBrowsing(game("Hades", 12, true));
        assertEquals(before + 1, changes);
        assertTrue(state.getBrowsing().running);
    }

    @Test
    public void anEqualBrowsingStillReturnsFromIdle() {
        CompanionState.Browsing hades = game("Hades", 12, false);
        state.setBrowsing(hades);
        state.leaveBrowsing();
        assertEquals(CompanionState.Content.IDLE, state.getContent());

        // Back from somewhere that left the surface idle, the same game is pushed again.
        state.setBrowsing(game("Hades", 12, false));
        assertEquals(CompanionState.Content.BROWSING, state.getContent());
    }

    @Test
    public void aBrowsingIsRefusedWhileStreaming() {
        state.enterStreaming();
        state.setBrowsing(game("Late push", 3, false));
        assertEquals(CompanionState.Content.STREAMING, state.getContent());

        state.setHosts(twoHosts("HOST-A"));
        state.clearBrowsing();
        assertEquals(CompanionState.Content.STREAMING, state.getContent());
    }

    @Test
    public void theKindsAndWhichOfThemHaveAGame() {
        CompanionState.Browsing idle = CompanionState.Browsing.idleHosts();
        assertEquals(CompanionState.Browsing.Kind.IDLE_HOSTS, idle.kind);
        assertNull(idle.title);
        assertFalse(idle.hasGame());

        CompanionState.Browsing idleHost = new CompanionState.Browsing(
                CompanionState.Browsing.Kind.HOST, "HOST-A", CompanionState.Browsing.NO_APP,
                "Taverna", "Online", null, null, false);
        assertFalse(idleHost.hasGame());

        // A host running a game offers Resume and Quit for it.
        CompanionState.Browsing busyHost = new CompanionState.Browsing(
                CompanionState.Browsing.Kind.HOST, "HOST-A", 7, "Taverna", "Playing · Hades",
                null, null, true);
        assertTrue(busyHost.hasGame());

        // A game with no host to start it on is not one the panel can act on.
        CompanionState.Browsing orphan = new CompanionState.Browsing(
                CompanionState.Browsing.Kind.GAME, null, 7, "Hades", null, null, null, false);
        assertFalse(orphan.hasGame());
    }

    @Test
    public void browsingsAreEqualWhenTheyWouldDrawTheSame() {
        assertEquals(game("Hades", 12, false), game("Hades", 12, false));
        assertEquals(game("Hades", 12, false).hashCode(), game("Hades", 12, false).hashCode());
        assertNotEquals(game("Hades", 12, false), game("Hades", 13, false));
        assertNotEquals(game("Hades", 12, false), game("Hades II", 12, false));
        assertNotEquals(game("Hades", 12, false), CompanionState.Browsing.idleHosts());
        // No backdrop to swap in is no change at all.
        CompanionState.Browsing hades = game("Hades", 12, false);
        assertSame(hades, hades.withBackdrop(null));
    }

    @Test
    public void hostsAreKeptAsAnUnmodifiableCopy() {
        List<CompanionState.HostChip> given = new ArrayList<>(twoHosts("HOST-A"));
        state.setHosts(given);
        given.clear();

        List<CompanionState.HostChip> kept = state.getHosts();
        assertEquals(2, kept.size());
        assertEquals("Taverna", kept.get(0).name);
        assertTrue(kept.get(0).selected);
        assertFalse(kept.get(1).online);
        try {
            kept.clear();
            fail("the hosts row must not be changed from outside");
        } catch (UnsupportedOperationException expected) {
            // As it should be.
        }
    }

    @Test
    public void anEqualHostsRowDoesNotRedraw() {
        state.setHosts(twoHosts("HOST-A"));
        int before = changes;

        // Every poll of every host publishes the list again; most of the time nothing moved.
        state.setHosts(twoHosts("HOST-A"));
        assertEquals(before, changes);

        // The selection moved: the row must redraw.
        state.setHosts(twoHosts("HOST-B"));
        assertEquals(before + 1, changes);
        assertTrue(state.getHosts().get(1).selected);
    }

    @Test
    public void noHostsIsAnEmptyRowNeverNull() {
        assertTrue(state.getHosts().isEmpty());
        state.setHosts(twoHosts("HOST-A"));
        state.setHosts(null);
        assertTrue(state.getHosts().isEmpty());
        state.setHosts(Collections.<CompanionState.HostChip>emptyList());
        assertTrue(state.getHosts().isEmpty());
    }

    @Test
    public void clearingBrowsingTakesTheHostsRowWithIt() {
        state.setBrowsing(game("Hades", 12, false));
        state.setHosts(twoHosts("HOST-A"));

        state.clearBrowsing();
        assertEquals(CompanionState.Content.IDLE, state.getContent());
        assertNull(state.getBrowsing());
        assertNull(state.getBrowsingTitle());
        assertTrue(state.getHosts().isEmpty());

        // Clearing what is already clear tells nobody.
        int before = changes;
        state.clearBrowsing();
        assertEquals(before, changes);
    }

    @Test
    public void aNullBrowsingClears() {
        state.setBrowsing(game("Hades", 12, false));
        state.setBrowsing((CompanionState.Browsing) null);
        assertEquals(CompanionState.Content.IDLE, state.getContent());
        assertNull(state.getBrowsing());
    }

    @Test
    public void aHostChipNeedsAUuid() {
        try {
            new CompanionState.HostChip(null, "Taverna", true, false);
            fail("a chip without a uuid could never be selected");
        } catch (IllegalArgumentException expected) {
            // As it should be.
        }
        assertEquals(new CompanionState.HostChip("HOST-A", "Taverna", true, false),
                new CompanionState.HostChip("HOST-A", "Taverna", true, false));
    }

    @Test
    public void browsingKeepsAPositionOnlyWithinItsList() {
        CompanionState.Browsing placed = new CompanionState.Browsing(
                CompanionState.Browsing.Kind.GAME, "HOST-A", 7, "Celeste", null, null, null,
                false, 3, 12);
        assertTrue(placed.hasPosition());
        assertEquals(3, placed.position);
        assertEquals(12, placed.count);
        // The same game at another place is another thing to draw.
        assertNotEquals(placed, new CompanionState.Browsing(CompanionState.Browsing.Kind.GAME,
                "HOST-A", 7, "Celeste", null, null, null, false, 4, 12));

        CompanionState.Browsing outside = new CompanionState.Browsing(
                CompanionState.Browsing.Kind.GAME, "HOST-A", 7, "Celeste", null, null, null,
                false, 12, 12);
        assertFalse(outside.hasPosition());
        assertEquals(0, outside.count);
        assertFalse(new CompanionState.Browsing(CompanionState.Browsing.Kind.GAME, "HOST-A", 7,
                "Celeste", null, null, null, false).hasPosition());
    }

    // --- Pages ---

    /** A page of rows, standing in for a screen's lower half. */
    private static final class RowsPage extends CompanionPage {
        private static final CompanionPageRenderer RENDERER = new CompanionPageRenderer() {
            @NonNull
            @Override
            public View create(@NonNull LayoutInflater inflater, @NonNull ViewGroup parent) {
                throw new UnsupportedOperationException("not drawn here");
            }

            @Override
            public void bind(@NonNull View view, @NonNull CompanionPage page,
                             @NonNull PageTaps taps) {
                throw new UnsupportedOperationException("not drawn here");
            }
        };

        final List<String> rows;

        RowsPage(List<String> rows, int focusedStop) {
            super(focusedStop);
            this.rows = rows;
        }

        @NonNull
        @Override
        public CompanionPageRenderer renderer() {
            return RENDERER;
        }

        @NonNull
        @Override
        public CompanionPage withFocus(int stop) {
            return new RowsPage(rows, stop);
        }

        @Override
        protected boolean sameContent(@NonNull CompanionPage other) {
            return sameList(rows, ((RowsPage) other).rows);
        }

        @Override
        protected int contentHash() {
            return Objects.hashCode(rows);
        }
    }

    private static RowsPage rows(String... rows) {
        return new RowsPage(Arrays.asList(rows), CompanionPage.NO_STOP);
    }

    @Test
    public void anEqualPageReplacesTheOneHeldWithoutARedraw() {
        state.setBrowsing(game("Hades", 12, false));
        RowsPage first = rows("Escaped Tartarus", "Pact of Punishment");
        state.setPage(first);
        assertSame(first, state.getPage());
        int before = changes;

        // Another list, the same rows: the same page to draw.
        RowsPage again = rows("Escaped Tartarus", "Pact of Punishment");
        state.setPage(again);
        assertEquals(before, changes);
        assertSame(again, state.getPage());

        // The d-pad moving onto a row is a new page, and is drawn.
        state.setPage(again.withFocus(1));
        assertEquals(before + 1, changes);
        assertEquals(1, state.getPage().focusedStop());
    }

    @Test
    public void pagesAreEqualByContentAndFocus() {
        List<String> shared = Arrays.asList("A", "B");
        assertEquals(new RowsPage(shared, 0), new RowsPage(shared, 0));
        assertEquals(rows("A", "B"), rows("A", "B"));
        assertEquals(rows("A", "B").hashCode(), rows("A", "B").hashCode());
        assertNotEquals(rows("A", "B"), rows("A", "C"));
        assertNotEquals(rows("A", "B"), rows("A", "B").withFocus(0));
        // Any negative stop is "no stop".
        assertEquals(rows("A").withFocus(-7), rows("A"));
    }

    @Test
    public void clearingBrowsingDropsThePage() {
        state.setBrowsing(game("Hades", 12, false));
        state.setPage(rows("Escaped Tartarus"));

        state.clearBrowsing();
        assertNull(state.getPage());
    }

    @Test
    public void leavingBrowsingDropsThePage() {
        state.setBrowsing(game("Hades", 12, false));
        state.setPage(rows("Escaped Tartarus"));

        state.leaveBrowsing();
        assertNull(state.getPage());
        assertEquals(CompanionState.Content.IDLE, state.getContent());

        // Left while not browsing at all, a page set ahead of time is dropped too, and drawn away.
        state.setPage(rows("Early"));
        int before = changes;
        state.leaveBrowsing();
        assertNull(state.getPage());
        assertEquals(before + 1, changes);
    }

    @Test
    public void thereIsNoPageWhileStreaming() {
        state.setBrowsing(game("Hades", 12, false));
        state.setPage(rows("Escaped Tartarus"));
        state.enterStreaming();
        assertNull(state.getPage());

        // Nor is one pushed now drawn: the panel is the stream's.
        int before = changes;
        state.setPage(rows("Late push"));
        assertNull(state.getPage());
        assertEquals(before, changes);
    }

    @Test
    public void aPagePushedBeforeTheStreamHasLeftIsDrawnAfterIt() {
        // The main screen starts again before the finished game's activity is destroyed, so its
        // page arrives while the content still says STREAMING.
        state.enterStreaming();
        RowsPage page = rows("Escaped Tartarus");
        state.setPage(page);
        assertNull(state.getPage());

        state.leaveStreaming();
        state.setBrowsing(game("Hades", 12, false));
        assertEquals(CompanionState.Content.BROWSING, state.getContent());
        assertSame(page, state.getPage());
    }

    @Test
    public void leavingTheStreamGoesBackToTheBrowsingPushedDuringIt() {
        // The main screen re-pushes its browsing and page on start, while the finished game's
        // activity has not been destroyed yet. No later push is owed: leaving must draw them.
        state.enterStreaming();
        state.setBrowsing(game("Hades", 12, false));
        RowsPage page = rows("Escaped Tartarus");
        state.setPage(page);
        assertEquals(CompanionState.Content.STREAMING, state.getContent());

        int before = changes;
        state.leaveStreaming();
        assertEquals(CompanionState.Content.BROWSING, state.getContent());
        assertEquals("Hades", state.getBrowsingTitle());
        assertSame(page, state.getPage());
        assertTrue(changes > before);
    }

    @Test
    public void aPageIsKeptWhileThePanelIsOff() {
        state.setBrowsing(game("Hades", 12, false));
        state.setOwner(CompanionState.Owner.OFF);
        RowsPage page = rows("Escaped Tartarus");
        state.setPage(page);

        state.setOwner(CompanionState.Owner.PANEL);
        assertSame(page, state.getPage());
    }

    @Test
    public void aHostThatReportedItsLatencyKeepsItsTileForTheWholeStream() {
        // Remembered by the state, not by a panel: the second screen can go and come back
        // mid-stream, and the new panel must cut the same grid.
        state.enterStreaming();
        state.setStreamSession(new CompanionState.StreamSession("Hades", "PC", null, 1000));
        assertFalse(state.hasSeenHostLatency());
        state.setStats(stats(true));
        state.setStats(stats(false));
        assertTrue(state.hasSeenHostLatency());

        // The codec learnt later is the same stream.
        state.setStreamSession(new CompanionState.StreamSession("Hades", "PC", "HEVC", 1000));
        assertTrue(state.hasSeenHostLatency());

        state.leaveStreaming();
        assertFalse(state.hasSeenHostLatency());
    }

    @Test
    public void aNewStreamStartsWithoutTheHostTile() {
        state.enterStreaming();
        state.setStreamSession(new CompanionState.StreamSession("Hades", "PC", null, 1000));
        state.setStats(stats(true));
        state.setStreamSession(new CompanionState.StreamSession("Hades", "PC", null, 5000));
        assertFalse(state.hasSeenHostLatency());
    }

    private static PerfStats stats(boolean hostLatency) {
        return new PerfStats(1920, 1080, "c2.test", 60f, 60f, 60f, 0f, 20000f, 8, 1, 2f,
                hostLatency, 1f, 1f, 1f);
    }
}
