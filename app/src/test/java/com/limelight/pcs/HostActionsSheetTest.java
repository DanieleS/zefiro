package com.limelight.pcs;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.computers.HostEntry;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager.PairState;
import com.limelight.pcs.HostActionsSheet.Item;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.mock;

/**
 * The host actions sheet offers what PcView's context menu offered, state by state, in the same
 * order, and never Unpair.
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
                        Item.TEST_NETWORK, Item.DELETE, Item.VIEW_DETAILS),
                HostActionsSheet.itemsFor(details));
    }

    @Test
    public void checking_isTreatedAsOffline() {
        ComputerDetails details = host(ComputerDetails.State.UNKNOWN, PairState.NOT_PAIRED);

        assertEquals(Arrays.asList(Item.WAKE, Item.GAMESTREAM_EOL,
                        Item.TEST_NETWORK, Item.DELETE, Item.VIEW_DETAILS),
                HostActionsSheet.itemsFor(details));
    }

    @Test
    public void unpaired_offersBothPairingsAndTheConfigPage() {
        ComputerDetails details = host(ComputerDetails.State.ONLINE, PairState.NOT_PAIRED);

        assertEquals(Arrays.asList(Item.PAIR_OTP, Item.PAIR, Item.OPEN_MANAGEMENT_PAGE,
                        Item.TEST_NETWORK, Item.DELETE, Item.VIEW_DETAILS),
                HostActionsSheet.itemsFor(details));
    }

    @Test
    public void unpairedNvidiaHost_offersTheEndOfServiceFaqInsteadOfAConfigPage() {
        ComputerDetails details = host(ComputerDetails.State.ONLINE, PairState.NOT_PAIRED);
        details.nvidiaServer = true;

        assertEquals(Arrays.asList(Item.PAIR_OTP, Item.PAIR, Item.GAMESTREAM_EOL,
                        Item.TEST_NETWORK, Item.DELETE, Item.VIEW_DETAILS),
                HostActionsSheet.itemsFor(details));
    }

    @Test
    public void pairedAndIdle_offersTheLibraryAndExport() {
        ComputerDetails details = host(ComputerDetails.State.ONLINE, PairState.PAIRED);

        assertEquals(Arrays.asList(Item.OPEN_MANAGEMENT_PAGE, Item.FULL_APP_LIST,
                        Item.FRONTEND_EXPORT, Item.TEST_NETWORK, Item.DELETE, Item.VIEW_DETAILS),
                HostActionsSheet.itemsFor(details));
    }

    @Test
    public void pairedAndRunning_putsResumeAndQuitFirst() {
        ComputerDetails details = host(ComputerDetails.State.ONLINE, PairState.PAIRED);
        details.runningGameId = 42;

        assertEquals(Arrays.asList(Item.RESUME, Item.QUIT, Item.OPEN_MANAGEMENT_PAGE,
                        Item.FULL_APP_LIST, Item.FRONTEND_EXPORT,
                        Item.TEST_NETWORK, Item.DELETE, Item.VIEW_DETAILS),
                HostActionsSheet.itemsFor(details));
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
