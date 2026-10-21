package com.limelight.pcs;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.companion.CompanionPage;
import com.limelight.computers.HostEntry;
import com.limelight.main.PageFocus;
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
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The focused PC's page on the companion panel: the running card's buttons first while a game
 * runs, then the sheet's actions two to a row with Delete PC last, and only the facts the client
 * really has.
 */
@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class PcDetailsPageTest {

    private static final ComputerDetails.AddressTuple LAN =
            new ComputerDetails.AddressTuple("192.168.1.20", NvHTTP.DEFAULT_HTTP_PORT);

    private static Context context() {
        return ApplicationProvider.getApplicationContext();
    }

    private static ComputerDetails details(ComputerDetails.State state, PairState pairState) {
        ComputerDetails details = new ComputerDetails();
        details.uuid = "uuid-1";
        details.name = "Apollo";
        details.state = state;
        details.pairState = pairState;
        details.localAddress = LAN;
        if (state == ComputerDetails.State.ONLINE) {
            details.activeAddress = LAN;
        }
        return details;
    }

    private static PcDetailsPage page(ComputerDetails details, int gameCount) {
        return PcDetailsPage.of(context(), new HostEntry(details, null, null, gameCount), null);
    }

    private static PcDetailsPage playing(String game, Bitmap art) {
        ComputerDetails details = details(ComputerDetails.State.ONLINE, PairState.PAIRED);
        details.runningGameId = 42;
        return PcDetailsPage.of(context(), new HostEntry(details, game, null, 16), art);
    }

    // Every stop in reading order, by walking the rows the page gives PanelFocus.
    private static List<Integer> stopsInOrder(PcDetailsPage page) {
        PageFocus focus = page.stops();
        List<Integer> order = new ArrayList<>();
        int stop = focus.first();
        while (stop >= 0) {
            order.add(stop);
            int right = focus.move(stop, PageFocus.Nav.RIGHT);
            if (right >= 0) {
                stop = right;
                continue;
            }
            // Back to the row's start, then down.
            int start = stop;
            for (int left = focus.move(start, PageFocus.Nav.LEFT); left >= 0;
                    left = focus.move(start, PageFocus.Nav.LEFT)) {
                start = left;
            }
            stop = focus.move(start, PageFocus.Nav.DOWN);
        }
        return order;
    }

    @Test
    public void playing_putsResumeAndQuitFirst_thenTheActionsWithoutThem() {
        PcDetailsPage page = playing("Sea of Stars", null);

        assertTrue(page.isRunning());
        assertEquals("Sea of Stars", page.runningTitle);
        assertEquals(PcDetailsPage.STOP_RESUME, page.entryStop());
        assertEquals(PcDetailsPage.STOP_QUIT,
                page.stops().move(PcDetailsPage.STOP_RESUME, PageFocus.Nav.RIGHT));
        assertEquals(Arrays.asList(Item.FULL_APP_LIST, Item.OPEN_MANAGEMENT_PAGE,
                Item.FRONTEND_EXPORT, Item.TEST_NETWORK, Item.VIEW_DETAILS, Item.DELETE),
                page.items);
        // Down from Resume is the first row of actions.
        assertEquals(PcDetailsPage.stopFor(Item.FULL_APP_LIST),
                page.stops().move(PcDetailsPage.STOP_RESUME, PageFocus.Nav.DOWN));
        assertEquals(PageFocus.EXIT_UP,
                page.stops().move(PcDetailsPage.STOP_RESUME, PageFocus.Nav.UP));
    }

    @Test
    public void playingAGameWithoutAName_saysItIsInAGame() {
        PcDetailsPage page = playing(null, null);

        assertEquals("In a game", page.runningTitle);
        assertEquals("In a game", page.statusText);
    }

    @Test
    public void notPaired_opensOnTheTwoPairings() {
        PcDetailsPage page = page(details(ComputerDetails.State.ONLINE, PairState.NOT_PAIRED), -1);

        assertFalse(page.isRunning());
        assertEquals(PcDetailsPage.stopFor(Item.PAIR_OTP), page.entryStop());
        assertEquals(Item.PAIR_OTP, page.itemAt(page.entryStop()));
        int right = page.stops().move(page.entryStop(), PageFocus.Nav.RIGHT);
        assertEquals(PcDetailsPage.stopFor(Item.PAIR), right);
        assertEquals(Item.PAIR, page.itemAt(right));
    }

    @Test
    public void offline_opensOnWake() {
        PcDetailsPage page = page(details(ComputerDetails.State.OFFLINE, PairState.PAIRED), 3);

        assertEquals(PcDetailsPage.stopFor(Item.WAKE), page.entryStop());
        assertEquals(Item.WAKE, page.itemAt(page.entryStop()));
        assertEquals("Offline", page.statusText);
    }

    @Test
    public void deleteIsTheLastStop_inEveryState() {
        PcDetailsPage[] pages = {
                playing("Sea of Stars", null),
                page(details(ComputerDetails.State.ONLINE, PairState.PAIRED), 16),
                page(details(ComputerDetails.State.ONLINE, PairState.NOT_PAIRED), -1),
                page(details(ComputerDetails.State.OFFLINE, PairState.PAIRED), -1),
                page(details(ComputerDetails.State.UNKNOWN, PairState.PAIRED), -1),
        };
        for (PcDetailsPage page : pages) {
            List<Integer> order = stopsInOrder(page);
            int last = order.get(order.size() - 1);
            assertEquals(page.statusText, Item.DELETE, page.itemAt(last));
            // Every action is reachable, plus the running card's two buttons.
            assertEquals(page.items.size() + (page.isRunning() ? 2 : 0), order.size());
        }
    }

    @Test
    public void itemAt_coversTheRunningStops_andDropsStaleOnes() {
        PcDetailsPage running = playing("Sea of Stars", null);
        assertEquals(Item.RESUME, running.itemAt(PcDetailsPage.STOP_RESUME));
        assertEquals(Item.QUIT, running.itemAt(PcDetailsPage.STOP_QUIT));

        PcDetailsPage idle = page(details(ComputerDetails.State.ONLINE, PairState.PAIRED), 16);
        // A tap on Resume that raced the game ending, an action this PC does not offer now, and
        // stops no action has.
        assertNull(idle.itemAt(PcDetailsPage.STOP_RESUME));
        assertNull(idle.itemAt(PcDetailsPage.STOP_QUIT));
        assertNull(idle.itemAt(PcDetailsPage.stopFor(Item.WAKE)));
        assertNull(idle.itemAt(PcDetailsPage.stopFor(Item.RESUME)));
        assertNull(idle.itemAt(Item.values().length));
        assertNull(idle.itemAt(CompanionPage.NO_STOP));
    }

    @Test
    public void aStop_namesTheSameActionAfterAPollReshapesTheList() {
        PcDetailsPage online = page(details(ComputerDetails.State.ONLINE, PairState.PAIRED), 16);
        PcDetailsPage offline = page(details(ComputerDetails.State.OFFLINE, PairState.PAIRED), 16);
        int details = PcDetailsPage.stopFor(Item.VIEW_DETAILS);
        int apps = PcDetailsPage.stopFor(Item.FULL_APP_LIST);
        assertNotEquals(online.items.indexOf(Item.VIEW_DETAILS),
                offline.items.indexOf(Item.VIEW_DETAILS));

        // View details kept its stop though its place moved; nothing took View all apps' stop.
        assertEquals(Item.VIEW_DETAILS, online.itemAt(details));
        assertEquals(Item.VIEW_DETAILS, offline.itemAt(details));
        assertTrue(offline.stops().isEnabledStop(details));
        assertNull(offline.itemAt(apps));
        assertFalse(offline.stops().isEnabledStop(apps));
        // Nor does Wake's stop name anything once the PC is back.
        assertNull(online.itemAt(offline.entryStop()));
        assertFalse(online.stops().isEnabledStop(offline.entryStop()));
    }

    @Test
    public void facts_areAddressConnectionAndGames_whenOnline() {
        PcDetailsPage page = page(details(ComputerDetails.State.ONLINE, PairState.PAIRED), 16);

        assertEquals(Arrays.asList(
                new PcDetailsPage.Fact("Address", "192.168.1.20"),
                new PcDetailsPage.Fact("Connection", "Local"),
                new PcDetailsPage.Fact("Games", "16")), page.facts);
    }

    @Test
    public void facts_leaveOutWhatIsNotKnown() {
        // Offline: no connection, but the last address it was known at; no app list seen.
        PcDetailsPage offline = page(details(ComputerDetails.State.OFFLINE, PairState.PAIRED), -1);
        assertEquals(Collections.singletonList(new PcDetailsPage.Fact("Address", "192.168.1.20")),
                offline.facts);

        ComputerDetails nowhere = details(ComputerDetails.State.OFFLINE, PairState.PAIRED);
        nowhere.localAddress = null;
        assertTrue(page(nowhere, -1).facts.isEmpty());

        PcDetailsPage counted = page(details(ComputerDetails.State.OFFLINE, PairState.PAIRED), 0);
        assertEquals(new PcDetailsPage.Fact("Games", "0"), counted.facts.get(1));
    }

    @Test
    public void anIdlePc_hasNoRunningCard_andNoCover() {
        Bitmap art = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888);
        ComputerDetails details = details(ComputerDetails.State.ONLINE, PairState.PAIRED);
        PcDetailsPage page = PcDetailsPage.of(context(), new HostEntry(details, null, null, 4), art);

        assertNull(page.runningTitle);
        assertNull(page.cover);
        assertEquals(PcDetailsPage.stopFor(page.items.get(0)), page.entryStop());
    }

    @Test
    public void equality_isByContent_withTheCoverByIdentity() {
        Bitmap cover = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888);
        Bitmap samePixels = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888);

        PcDetailsPage a = playing("Sea of Stars", cover);
        PcDetailsPage b = playing("Sea of Stars", cover);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        assertNotEquals(a, playing("Sea of Stars", samePixels));
        assertNotEquals(a, playing("Hades", cover));
        assertNotEquals(page(details(ComputerDetails.State.ONLINE, PairState.PAIRED), 16),
                page(details(ComputerDetails.State.ONLINE, PairState.PAIRED), 17));
        assertNotEquals(a, a.withFocus(PcDetailsPage.STOP_QUIT));
    }

    @Test
    public void withFocus_keepsEverythingElse() {
        Bitmap cover = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888);
        PcDetailsPage page = playing("Sea of Stars", cover);

        PcDetailsPage focused = page.withFocus(2);

        assertEquals(2, focused.focusedStop());
        assertEquals(page.uuid, focused.uuid);
        assertEquals(page.name, focused.name);
        assertEquals(page.kind, focused.kind);
        assertEquals(page.statusText, focused.statusText);
        assertSame(page.facts, focused.facts);
        assertSame(page.items, focused.items);
        assertEquals(page.runningTitle, focused.runningTitle);
        assertSame(cover, focused.cover);
        assertEquals(page, focused.withFocus(CompanionPage.NO_STOP));
    }
}
