package com.limelight.pcs;

import com.limelight.R;
import com.limelight.computers.HostEntry;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.ComputerDetails.AddressTuple;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager.PairState;
import com.limelight.pcs.HostStatus.Connection;
import com.limelight.pcs.HostStatus.Kind;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * A PC tile's state, and the facts about how the host is reached: only what a poll reports,
 * never a guess.
 */
public class HostStatusTest {

    private static final AddressTuple LOCAL = new AddressTuple("192.168.1.20", NvHTTP.DEFAULT_HTTP_PORT);
    private static final AddressTuple MANUAL = new AddressTuple("pc.example.org", NvHTTP.DEFAULT_HTTP_PORT);
    private static final AddressTuple IPV6 = new AddressTuple("fe80::1", NvHTTP.DEFAULT_HTTP_PORT);
    private static final AddressTuple REMOTE = new AddressTuple("203.0.113.7", 48010);

    private static ComputerDetails host(ComputerDetails.State state, PairState pairState) {
        ComputerDetails details = new ComputerDetails();
        details.uuid = "uuid-1";
        details.name = "Taverna";
        details.state = state;
        details.pairState = pairState;
        return details;
    }

    @Test
    public void kind_coversTheFiveStates() {
        ComputerDetails playing = host(ComputerDetails.State.ONLINE, PairState.PAIRED);
        playing.runningGameId = 7;
        assertEquals(Kind.PLAYING, HostStatus.kind(playing));
        assertEquals(Kind.ONLINE, HostStatus.kind(host(ComputerDetails.State.ONLINE, PairState.PAIRED)));
        assertEquals(Kind.UNPAIRED, HostStatus.kind(host(ComputerDetails.State.ONLINE, PairState.NOT_PAIRED)));
        assertEquals(Kind.CHECKING, HostStatus.kind(host(ComputerDetails.State.UNKNOWN, PairState.PAIRED)));
        assertEquals(Kind.OFFLINE, HostStatus.kind(host(ComputerDetails.State.OFFLINE, PairState.PAIRED)));
    }

    @Test
    public void kind_anUnpairedHostInAGameIsStillUnpaired() {
        // The game is someone else's session; this client cannot resume it.
        ComputerDetails details = host(ComputerDetails.State.ONLINE, PairState.NOT_PAIRED);
        details.runningGameId = 7;
        assertEquals(Kind.UNPAIRED, HostStatus.kind(details));
    }

    @Test
    public void kind_dotColours() {
        assertEquals(R.color.signal, Kind.PLAYING.color);
        assertEquals(R.color.status_online, Kind.ONLINE.color);
        assertEquals(R.color.content_faint, Kind.CHECKING.color);
        assertEquals(R.color.content_faint, Kind.OFFLINE.color);
        // Only a host that answers but is not ours is drawn as a ring.
        for (Kind kind : Kind.values()) {
            assertEquals(kind.name(), kind == Kind.UNPAIRED, kind.hollow);
        }
    }

    @Test
    public void textRes_namesTheGameOnlyWhenItIsKnown() {
        ComputerDetails playing = host(ComputerDetails.State.ONLINE, PairState.PAIRED);
        playing.runningGameId = 7;

        assertEquals(R.string.pcview_status_running,
                HostStatus.textRes(new HostEntry(playing, "Sea of Stars", null)));
        assertEquals(R.string.pcview_status_running_unnamed,
                HostStatus.textRes(new HostEntry(playing)));
        assertEquals(R.string.pcview_status_offline, HostStatus.textRes(
                new HostEntry(host(ComputerDetails.State.OFFLINE, PairState.PAIRED))));
    }

    @Test
    public void connection_namesTheAddressThatAnswered() {
        ComputerDetails details = host(ComputerDetails.State.ONLINE, PairState.PAIRED);
        details.localAddress = LOCAL;
        details.manualAddress = MANUAL;
        details.ipv6Address = IPV6;
        details.remoteAddress = REMOTE;

        details.activeAddress = LOCAL;
        assertEquals(Connection.LOCAL, HostStatus.connection(details));
        details.activeAddress = MANUAL;
        assertEquals(Connection.MANUAL, HostStatus.connection(details));
        details.activeAddress = IPV6;
        assertEquals(Connection.IPV6, HostStatus.connection(details));
        details.activeAddress = REMOTE;
        assertEquals(Connection.REMOTE, HostStatus.connection(details));
    }

    @Test
    public void connection_localWinsWhenTheManualAddressIsTheSame() {
        ComputerDetails details = host(ComputerDetails.State.ONLINE, PairState.PAIRED);
        details.localAddress = LOCAL;
        details.manualAddress = new AddressTuple(LOCAL.address, LOCAL.port);
        details.activeAddress = new AddressTuple(LOCAL.address, LOCAL.port);

        assertEquals(Connection.LOCAL, HostStatus.connection(details));
    }

    @Test
    public void connection_isNoneWhileOfflineOrAtAnUnlistedAddress() {
        ComputerDetails offline = host(ComputerDetails.State.OFFLINE, PairState.PAIRED);
        offline.localAddress = LOCAL;
        offline.activeAddress = LOCAL;
        assertEquals(Connection.NONE, HostStatus.connection(offline));

        ComputerDetails checking = host(ComputerDetails.State.UNKNOWN, PairState.PAIRED);
        checking.localAddress = LOCAL;
        checking.activeAddress = LOCAL;
        assertEquals(Connection.NONE, HostStatus.connection(checking));

        ComputerDetails elsewhere = host(ComputerDetails.State.ONLINE, PairState.PAIRED);
        elsewhere.localAddress = LOCAL;
        elsewhere.activeAddress = REMOTE;
        assertEquals(Connection.NONE, HostStatus.connection(elsewhere));

        ComputerDetails unreached = host(ComputerDetails.State.ONLINE, PairState.PAIRED);
        assertEquals(Connection.NONE, HostStatus.connection(unreached));
        assertEquals(0, Connection.NONE.label);
    }

    @Test
    public void address_fallsBackThroughTheKnownAddressesInOrder() {
        ComputerDetails details = host(ComputerDetails.State.OFFLINE, PairState.PAIRED);
        assertNull(HostStatus.address(details));

        details.remoteAddress = REMOTE;
        assertSame(REMOTE, HostStatus.address(details));
        details.ipv6Address = IPV6;
        assertSame(IPV6, HostStatus.address(details));
        details.manualAddress = MANUAL;
        assertSame(MANUAL, HostStatus.address(details));
        details.localAddress = LOCAL;
        assertSame(LOCAL, HostStatus.address(details));

        AddressTuple active = new AddressTuple("10.0.0.5", NvHTTP.DEFAULT_HTTP_PORT);
        details.activeAddress = active;
        assertSame(active, HostStatus.address(details));
    }

    @Test
    public void addressText_showsThePortOnlyWhenItIsNotTheDefault() {
        assertEquals("192.168.1.20", HostStatus.addressText(LOCAL));
        assertEquals("203.0.113.7:48010", HostStatus.addressText(REMOTE));
        assertEquals("fe80::1", HostStatus.addressText(IPV6));
        assertEquals("[fe80::1]:48010", HostStatus.addressText(new AddressTuple("fe80::1", 48010)));
    }

    @Test
    public void primary_isTheCardAction() {
        ComputerDetails unpaired = host(ComputerDetails.State.ONLINE, PairState.NOT_PAIRED);
        assertEquals(HostCardBinder.ACTION_PAIR, HostStatus.primary(new HostEntry(unpaired)));

        ComputerDetails offline = host(ComputerDetails.State.OFFLINE, PairState.PAIRED);
        assertEquals(HostCardBinder.ACTION_NONE, HostStatus.primary(new HostEntry(offline)));
        offline.macAddress = "AA:BB:CC:DD:EE:FF";
        assertEquals(HostCardBinder.ACTION_WAKE, HostStatus.primary(new HostEntry(offline)));

        ComputerDetails playing = host(ComputerDetails.State.ONLINE, PairState.PAIRED);
        playing.runningGameId = 7;
        assertEquals(HostCardBinder.ACTION_RESUME, HostStatus.primary(new HostEntry(playing)));
        assertEquals(HostCardBinder.ACTION_NONE, HostStatus.primary(new HostEntry(
                host(ComputerDetails.State.ONLINE, PairState.PAIRED))));
    }
}
