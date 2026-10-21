package com.limelight.main;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Analog triggers as L2 and R2 presses: hysteresis, and one edge per press from either source. */
public class TriggerEdgesTest {

    @Test
    public void aPullIsOnePressAndOneRelease() {
        TriggerEdges edges = new TriggerEdges();
        assertEquals(0, edges.onAxes(0.2f, 0f));
        assertEquals(TriggerEdges.L2_DOWN, edges.onAxes(0.7f, 0f));
        // Held, and pulled further: no more edges.
        assertEquals(0, edges.onAxes(0.9f, 0f));
        assertEquals(0, edges.onAxes(1f, 0f));
        assertEquals(TriggerEdges.L2_UP, edges.onAxes(0.1f, 0f));
        assertEquals(0, edges.onAxes(0f, 0f));
    }

    @Test
    public void betweenTheThresholdsNothingChanges() {
        TriggerEdges edges = new TriggerEdges();
        // Hovering under the press threshold never presses.
        assertEquals(0, edges.onAxes(0f, 0.59f));
        assertEquals(0, edges.onAxes(0f, 0.45f));
        assertEquals(TriggerEdges.R2_DOWN, edges.onAxes(0f, 0.61f));
        // A worn spring sagging back to the middle is still a held trigger.
        assertEquals(0, edges.onAxes(0f, 0.5f));
        assertEquals(0, edges.onAxes(0f, 0.31f));
        assertEquals(0, edges.onAxes(0f, 0.65f));
        assertEquals(TriggerEdges.R2_UP, edges.onAxes(0f, 0.29f));
    }

    @Test
    public void bothTriggersAtOnce() {
        TriggerEdges edges = new TriggerEdges();
        assertEquals(TriggerEdges.L2_DOWN | TriggerEdges.R2_DOWN, edges.onAxes(0.8f, 0.8f));
        assertEquals(TriggerEdges.R2_UP, edges.onAxes(0.8f, 0f));
    }

    @Test
    public void aPadSendingTheKeyAndTheAxisPressesOnce() {
        TriggerEdges edges = new TriggerEdges();
        // The axis first: the key that follows is the same pull.
        assertEquals(TriggerEdges.R2_DOWN, edges.onAxes(0f, 0.9f));
        assertFalse(edges.onKey(true, true));
        assertFalse(edges.onKey(true, false));
        assertEquals(TriggerEdges.R2_UP, edges.onAxes(0f, 0f));

        // The key first: the axis is swallowed instead.
        assertTrue(edges.onKey(false, true));
        assertEquals(0, edges.onAxes(0.9f, 0f));
        assertEquals(0, edges.onAxes(0f, 0f));
        assertTrue(edges.onKey(false, false));
    }

    @Test
    public void aKeyOnlyPadPressesThroughTheKey() {
        TriggerEdges edges = new TriggerEdges();
        assertTrue(edges.onKey(true, true));
        assertTrue(edges.onKey(true, false));
        assertTrue(edges.onKey(true, true));
    }
}
