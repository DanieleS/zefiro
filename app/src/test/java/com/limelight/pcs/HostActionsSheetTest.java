package com.limelight.pcs;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.computers.HostEntry;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager.PairState;
import com.limelight.pcs.HostActionsSheet.Item;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;

/**
 * The host actions sheet offers what PcView's context menu offered, state by state, never
 * Unpair, in the PCs mock's order: what the host waits for first, Delete PC last and alone in
 * the danger tone.
 */
@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class HostActionsSheetTest {

    private static ComputerDetails host(ComputerDetails.State state, PairState pairState) {
        ComputerDetails details = new ComputerDetails();
        details.uuid = "uuid-1";
        details.name = "Taverna";
        details.state = state;
        details.pairState = pairState;
        return details;
    }

    @Test
    public void offline_offersWakeAndTheEndOfServiceFaq() {
        ComputerDetails details = host(ComputerDetails.State.OFFLINE, PairState.PAIRED);

        assertEquals(Arrays.asList(Item.WAKE, Item.GAMESTREAM_EOL,
                        Item.TEST_NETWORK, Item.VIEW_DETAILS, Item.DELETE),
                HostActionsSheet.itemsFor(details));
    }

    @Test
    public void checking_isTreatedAsOffline() {
        ComputerDetails details = host(ComputerDetails.State.UNKNOWN, PairState.NOT_PAIRED);

        assertEquals(Arrays.asList(Item.WAKE, Item.GAMESTREAM_EOL,
                        Item.TEST_NETWORK, Item.VIEW_DETAILS, Item.DELETE),
                HostActionsSheet.itemsFor(details));
    }

    @Test
    public void unpaired_offersBothPairingsAndTheConfigPage() {
        ComputerDetails details = host(ComputerDetails.State.ONLINE, PairState.NOT_PAIRED);

        assertEquals(Arrays.asList(Item.PAIR_OTP, Item.PAIR, Item.OPEN_MANAGEMENT_PAGE,
                        Item.TEST_NETWORK, Item.VIEW_DETAILS, Item.DELETE),
                HostActionsSheet.itemsFor(details));
    }

    @Test
    public void unpairedNvidiaHost_offersTheEndOfServiceFaqInsteadOfAConfigPage() {
        ComputerDetails details = host(ComputerDetails.State.ONLINE, PairState.NOT_PAIRED);
        details.nvidiaServer = true;

        assertEquals(Arrays.asList(Item.PAIR_OTP, Item.PAIR, Item.GAMESTREAM_EOL,
                        Item.TEST_NETWORK, Item.VIEW_DETAILS, Item.DELETE),
                HostActionsSheet.itemsFor(details));
    }

    @Test
    public void pairedAndIdle_offersTheLibraryAndExport() {
        ComputerDetails details = host(ComputerDetails.State.ONLINE, PairState.PAIRED);

        assertEquals(Arrays.asList(Item.FULL_APP_LIST, Item.OPEN_MANAGEMENT_PAGE,
                        Item.FRONTEND_EXPORT, Item.TEST_NETWORK, Item.VIEW_DETAILS, Item.DELETE),
                HostActionsSheet.itemsFor(details));
    }

    @Test
    public void pairedAndRunning_putsResumeAndQuitFirst() {
        ComputerDetails details = host(ComputerDetails.State.ONLINE, PairState.PAIRED);
        details.runningGameId = 42;

        assertEquals(Arrays.asList(Item.RESUME, Item.QUIT, Item.FULL_APP_LIST,
                        Item.OPEN_MANAGEMENT_PAGE, Item.FRONTEND_EXPORT,
                        Item.TEST_NETWORK, Item.VIEW_DETAILS, Item.DELETE),
                HostActionsSheet.itemsFor(details));
    }

    @Test
    public void deleteIsLastAndTheOnlyDangerRow_inEveryState() {
        List<ComputerDetails> states = new ArrayList<>();
        states.add(host(ComputerDetails.State.OFFLINE, PairState.PAIRED));
        states.add(host(ComputerDetails.State.UNKNOWN, PairState.NOT_PAIRED));
        states.add(host(ComputerDetails.State.ONLINE, PairState.NOT_PAIRED));
        states.add(host(ComputerDetails.State.ONLINE, PairState.PAIRED));
        ComputerDetails running = host(ComputerDetails.State.ONLINE, PairState.PAIRED);
        running.runningGameId = 42;
        states.add(running);

        for (ComputerDetails details : states) {
            List<Item> items = HostActionsSheet.itemsFor(details);
            assertEquals(Item.DELETE, items.get(items.size() - 1));
            for (Item item : items) {
                assertEquals(item.name(), item == Item.DELETE, item.danger);
            }
        }
    }

    @Test
    public void everyRowHasAnIcon() {
        for (Item item : Item.values()) {
            assertNotEquals(item.name(), 0, item.icon);
        }
    }

    @Test
    public void subtitle_givesTheAddressAndTheGamesWhenKnown() {
        Context context = ApplicationProvider.getApplicationContext();
        ComputerDetails details = host(ComputerDetails.State.ONLINE, PairState.PAIRED);
        details.localAddress = new ComputerDetails.AddressTuple("192.168.1.20", NvHTTP.DEFAULT_HTTP_PORT);
        details.activeAddress = details.localAddress;

        assertEquals("192.168.1.20 · 16 games", HostActionsSheet.subtitleFor(context,
                new HostEntry(details, null, null, 16)));
        assertEquals("192.168.1.20 · 1 game", HostActionsSheet.subtitleFor(context,
                new HostEntry(details, null, null, 1)));
        assertEquals("192.168.1.20", HostActionsSheet.subtitleFor(context,
                new HostEntry(details, null, null, HostEntry.UNKNOWN_GAME_COUNT)));

        ComputerDetails nowhere = host(ComputerDetails.State.OFFLINE, PairState.PAIRED);
        assertEquals("0 games", HostActionsSheet.subtitleFor(context,
                new HostEntry(nowhere, null, null, 0)));
        assertNull(HostActionsSheet.subtitleFor(context, new HostEntry(nowhere)));
    }

    @Test
    public void title_namesTheHostAndItsState() {
        Context context = ApplicationProvider.getApplicationContext();

        assertEquals("Taverna - Online", HostActionsSheet.titleFor(context,
                host(ComputerDetails.State.ONLINE, PairState.PAIRED)));
        assertEquals("Taverna - Offline", HostActionsSheet.titleFor(context,
                host(ComputerDetails.State.OFFLINE, PairState.PAIRED)));
        assertEquals("Taverna - Refreshing", HostActionsSheet.titleFor(context,
                host(ComputerDetails.State.UNKNOWN, PairState.PAIRED)));
    }

    @Test
    public void build_givesTheSheetARowPerItem() {
        Context context = ApplicationProvider.getApplicationContext();
        HostEntry entry = new HostEntry(host(ComputerDetails.State.ONLINE, PairState.PAIRED));

        assertFalse(HostActionsSheet.build(context, entry, mock(HostActions.class)).isEmpty());
    }
}
