package com.limelight.computers;

import com.limelight.nvstream.http.ComputerDetails;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The service starts polling on the 0→1 edge and tears down on 1→0, and on nothing else; these
 * pin those edges and that every listener hears every update.
 */
public class PollingListenersTest {

    private static final class Recorder implements ComputerManagerListener {
        final List<ComputerDetails> heard = new ArrayList<>();

        @Override
        public void notifyComputerUpdated(ComputerDetails details) {
            heard.add(details);
        }
    }

    @Test
    public void addReportsOnlyTheFirstListener() {
        PollingListeners set = new PollingListeners();
        assertTrue(set.add(new Recorder()));
        assertFalse(set.add(new Recorder()));
        assertEquals(2, set.size());
    }

    @Test
    public void removeReportsOnlyTheLastListener() {
        PollingListeners set = new PollingListeners();
        Recorder a = new Recorder();
        Recorder b = new Recorder();
        set.add(a);
        set.add(b);

        assertFalse(set.remove(a));
        assertTrue(set.remove(b));
        assertTrue(set.isEmpty());
    }

    @Test
    public void addingTheSameListenerTwiceIsOneListener() {
        PollingListeners set = new PollingListeners();
        Recorder a = new Recorder();
        assertTrue(set.add(a));
        assertFalse(set.add(a));
        assertEquals(1, set.size());

        set.notifyComputerUpdated(new ComputerDetails());
        assertEquals(1, a.heard.size());

        // One registration, so one removal empties the set.
        assertTrue(set.remove(a));
    }

    @Test
    public void removingAStrangerChangesNothing() {
        PollingListeners set = new PollingListeners();
        Recorder a = new Recorder();
        set.add(a);

        assertFalse(set.remove(new Recorder()));
        assertFalse(set.remove(null));
        assertTrue(set.contains(a));
        // An empty set has no 1→0 edge left to report.
        assertTrue(set.remove(a));
        assertFalse(set.remove(a));
    }

    @Test
    public void everyListenerHearsEveryUpdate() {
        PollingListeners set = new PollingListeners();
        Recorder a = new Recorder();
        Recorder b = new Recorder();
        set.add(a);
        set.add(b);

        ComputerDetails details = new ComputerDetails();
        set.notifyComputerUpdated(details);

        assertEquals(1, a.heard.size());
        assertEquals(1, b.heard.size());
        assertTrue(a.heard.get(0) == details && b.heard.get(0) == details);
    }

    @Test
    public void aListenerMayLeaveFromInsideItsCallback() {
        final PollingListeners set = new PollingListeners();
        final Recorder after = new Recorder();
        ComputerManagerListener leaver = new ComputerManagerListener() {
            @Override
            public void notifyComputerUpdated(ComputerDetails details) {
                set.remove(this);
            }
        };
        set.add(leaver);
        set.add(after);

        set.notifyComputerUpdated(new ComputerDetails());

        assertFalse(set.contains(leaver));
        // The walk was over a snapshot, so the one after it still heard this update.
        assertEquals(1, after.heard.size());
    }

    @Test
    public void clearReportsWhetherAnyoneWasThere() {
        PollingListeners set = new PollingListeners();
        assertFalse(set.clear());
        set.add(new Recorder());
        assertTrue(set.clear());
        assertTrue(set.isEmpty());
        // After a clear, the next listener is a first listener again.
        assertTrue(set.add(new Recorder()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullIsNotAListener() {
        new PollingListeners().add(null);
    }
}
