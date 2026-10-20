package com.limelight.companion;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The content and owner regions, pinned before the single-activity refactor starts driving them
 * from fragments instead of activities.
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
        state.setBrowsing("Hades", null);
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
    public void browsingIsRefusedWhileStreaming() {
        state.enterStreaming();
        state.setBrowsing("Late push", null);
        assertEquals(CompanionState.Content.STREAMING, state.getContent());

        // Leaving the library must not end the stream's surface either.
        state.clearBrowsing();
        assertEquals(CompanionState.Content.STREAMING, state.getContent());
    }

    @Test
    public void leavingAStateYouAreNotInIsANoOp() {
        state.setBrowsing("Hades", null);
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
}
