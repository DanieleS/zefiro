package com.limelight.pcs;

import android.graphics.Bitmap;

import com.limelight.computers.HostEntry;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager.PairState;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The card's action by host state, and the diff that decides whether a poll redraws a card.
 */
@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class HostCardAdapterTest {

    private static ComputerDetails details(ComputerDetails.State state, PairState pairState) {
        ComputerDetails details = new ComputerDetails();
        details.uuid = "uuid-1";
        details.name = "Taverna";
        details.state = state;
        details.pairState = pairState;
        return details;
    }

    private static HostEntry entry(ComputerDetails details) {
        return new HostEntry(details);
    }

    @Test
    public void actionFor_followsTheHostState() {
        ComputerDetails running = details(ComputerDetails.State.ONLINE, PairState.PAIRED);
        running.runningGameId = 7;
        assertEquals(HostCardBinder.ACTION_RESUME, HostCardAdapter.actionFor(entry(running)));

        assertEquals(HostCardBinder.ACTION_PAIR, HostCardAdapter.actionFor(
                entry(details(ComputerDetails.State.ONLINE, PairState.NOT_PAIRED))));

        ComputerDetails asleep = details(ComputerDetails.State.OFFLINE, PairState.PAIRED);
        asleep.macAddress = "00:11:22:33:44:55";
        assertEquals(HostCardBinder.ACTION_WAKE, HostCardAdapter.actionFor(entry(asleep)));

        // Without a MAC there is nothing to wake, and a host being checked is left alone.
        assertEquals(HostCardBinder.ACTION_NONE, HostCardAdapter.actionFor(
                entry(details(ComputerDetails.State.OFFLINE, PairState.PAIRED))));
        ComputerDetails checking = details(ComputerDetails.State.UNKNOWN, PairState.PAIRED);
        checking.macAddress = "00:11:22:33:44:55";
        assertEquals(HostCardBinder.ACTION_NONE, HostCardAdapter.actionFor(entry(checking)));

        assertEquals(HostCardBinder.ACTION_NONE, HostCardAdapter.actionFor(
                entry(details(ComputerDetails.State.ONLINE, PairState.PAIRED))));
    }

    @Test
    public void sameOnCard_ignoresWhatTheCardDoesNotShow() {
        ComputerDetails a = details(ComputerDetails.State.ONLINE, PairState.PAIRED);
        ComputerDetails b = new ComputerDetails(a);
        b.httpsPort = 47990;
        b.nvidiaServer = true;

        assertTrue(HostCardAdapter.sameOnCard(entry(a), entry(b)));
    }

    @Test
    public void sameOnCard_seesEveryChangeTheCardShows() {
        ComputerDetails base = details(ComputerDetails.State.ONLINE, PairState.PAIRED);
        base.activeAddress = new ComputerDetails.AddressTuple("10.0.0.2", 47989);

        ComputerDetails offline = new ComputerDetails(base);
        offline.state = ComputerDetails.State.OFFLINE;
        assertFalse(HostCardAdapter.sameOnCard(entry(base), entry(offline)));

        ComputerDetails moved = new ComputerDetails(base);
        moved.activeAddress = new ComputerDetails.AddressTuple("10.0.0.3", 47989);
        assertFalse(HostCardAdapter.sameOnCard(entry(base), entry(moved)));

        ComputerDetails playing = new ComputerDetails(base);
        playing.runningGameId = 3;
        assertFalse(HostCardAdapter.sameOnCard(entry(base), entry(playing)));

        HostEntry named = new HostEntry(new ComputerDetails(base), "Hades", null);
        assertFalse(HostCardAdapter.sameOnCard(entry(base), named));

        HostEntry withHero = new HostEntry(new ComputerDetails(base), null, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888));
        assertFalse(HostCardAdapter.sameOnCard(entry(base), withHero));
    }
}
