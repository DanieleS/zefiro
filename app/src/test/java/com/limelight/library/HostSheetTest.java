package com.limelight.library;

import com.limelight.computers.HostEntry;
import com.limelight.library.HostSheet.Kind;
import com.limelight.library.HostSheet.Row;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.PairingManager.PairState;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The host sheet's rows, state by state, in the order they are listed. */
@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class HostSheetTest {

    private static HostEntry host(String uuid, String name, ComputerDetails.State state) {
        ComputerDetails details = new ComputerDetails();
        details.uuid = uuid;
        details.name = name;
        details.state = state;
        details.pairState = PairState.PAIRED;
        return new HostEntry(details, null, null);
    }

    private static List<Kind> kinds(List<Row> rows) {
        List<Kind> kinds = new ArrayList<>();
        for (Row row : rows) {
            kinds.add(row.kind);
        }
        return kinds;
    }

    @Test
    public void onlineHostWithNothingAdvertised_listsHostsThenManage() {
        HostEntry taverna = host("a", "Taverna", ComputerDetails.State.ONLINE);
        HostEntry studio = host("b", "Studio", ComputerDetails.State.ONLINE);

        List<Row> rows = HostSheet.rowsFor(Arrays.asList(taverna, studio), "a",
                LibraryModel.Specials.NONE, false);

        assertEquals(Arrays.asList(Kind.HOST, Kind.HOST, Kind.MANAGE_PCS), kinds(rows));
        assertTrue(rows.get(0).selected);
        assertFalse(rows.get(1).selected);
    }

    @Test
    public void advertisedSpecialsAndCompanion_comeInOrder() {
        HostEntry taverna = host("a", "Taverna", ComputerDetails.State.ONLINE);
        LibraryModel.Specials specials = new LibraryModel.Specials(
                new NvApp("Virtual Display", null, 10, false),
                new NvApp("Remote Monitor", null, 11, false), null);

        List<Row> rows = HostSheet.rowsFor(Collections.singletonList(taverna), "a", specials, true);

        assertEquals(Arrays.asList(Kind.HOST, Kind.VIRTUAL_DISPLAY, Kind.REMOTE_SESSION,
                Kind.COMPANION, Kind.MANAGE_PCS), kinds(rows));
    }

    @Test
    public void offlineHosts_carryWake_theChosenOneAsItsOwnRow() {
        HostEntry taverna = host("a", "Taverna", ComputerDetails.State.OFFLINE);
        HostEntry studio = host("b", "Studio", ComputerDetails.State.OFFLINE);

        List<Row> rows = HostSheet.rowsFor(Arrays.asList(taverna, studio), "a",
                LibraryModel.Specials.NONE, false);

        assertEquals(Arrays.asList(Kind.HOST, Kind.HOST, Kind.WAKE, Kind.MANAGE_PCS), kinds(rows));
        assertFalse(rows.get(0).wake);
        assertTrue(rows.get(1).wake);
    }

    @Test
    public void noHostChosen_offersNoHostActions() {
        HostEntry taverna = host("a", "Taverna", ComputerDetails.State.OFFLINE);
        LibraryModel.Specials specials = new LibraryModel.Specials(
                new NvApp("Virtual Display", null, 10, false), null, null);

        List<Row> rows = HostSheet.rowsFor(Collections.singletonList(taverna), null, specials, false);

        assertEquals(Arrays.asList(Kind.HOST, Kind.MANAGE_PCS), kinds(rows));
        assertTrue(rows.get(0).wake);
    }
}
