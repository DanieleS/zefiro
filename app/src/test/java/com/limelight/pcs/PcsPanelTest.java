package com.limelight.pcs;

import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;

import androidx.lifecycle.MutableLiveData;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.platform.app.InstrumentationRegistry;

import com.limelight.MainScreenTestEnvironment;
import com.limelight.R;
import com.limelight.TestLogSuppressor;
import com.limelight.companion.CompanionDisplayManager;
import com.limelight.companion.CompanionPage;
import com.limelight.computers.HostEntry;
import com.limelight.main.CompanionFeed;
import com.limelight.main.MainActivity;
import com.limelight.main.Section;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager.PairState;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The PCs grid with the companion panel showing: the focused PC's details are a page there, Y
 * takes the d-pad down to it and B brings it back to the tile, and a tap on the page acts on the
 * PC it shows, only while that page is the one on the panel.
 */
@Config(sdk = {33}, qualifiers = "w900dp-h500dp-land",
        shadows = {com.limelight.shadows.ShadowMoonBridge.class,
                com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class PcsPanelTest {

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void prepareEnvironment() throws Exception {
        MainScreenTestEnvironment.prepare();
        setPanelShowing(true);
    }

    // The activity a test started, destroyed after it: an activity left running keeps its
    // executors' threads, and with them everything it holds, for the rest of the test JVM.
    private ActivityController<MainActivity> controller;

    @After
    public void tearDown() throws Exception {
        if (controller != null) {
            controller.pause().stop().destroy();
            controller = null;
        }
        setPanelShowing(false);
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    // The panel is a Presentation on a second display, which a test has none of: the value its
    // attach and detach would set is set directly.
    @SuppressWarnings("unchecked")
    private static void setPanelShowing(boolean showing) throws Exception {
        Field field = CompanionDisplayManager.class.getDeclaredField("panelShowing");
        field.setAccessible(true);
        ((MutableLiveData<Boolean>) field.get(null)).setValue(showing);
    }

    private static CompanionFeed feed(MainActivity activity) throws Exception {
        Field field = MainActivity.class.getDeclaredField("companionFeed");
        field.setAccessible(true);
        return (CompanionFeed) field.get(activity);
    }

    // What the feed would draw on the panel now.
    private static CompanionPage panelPage(MainActivity activity) throws Exception {
        Field field = CompanionFeed.class.getDeclaredField("page");
        field.setAccessible(true);
        return (CompanionPage) field.get(feed(activity));
    }

    private static PcDetailsPage pcPage(MainActivity activity) throws Exception {
        CompanionPage page = panelPage(activity);
        assertTrue("the panel shows a PC, not " + page, page instanceof PcDetailsPage);
        return (PcDetailsPage) page;
    }

    /** The main screen on its first run, which opens on PCs; driven by a pad, not touch. */
    private MainActivity start() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false);
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        idle();
        return controller.get();
    }

    // The repository's feed, as its polls would set it: no host is ever polled here.
    @SuppressWarnings("unchecked")
    private static void publish(MainActivity activity, HostEntry... hosts) {
        List<HostEntry> list = Arrays.asList(hosts);
        ((MutableLiveData<List<HostEntry>>) activity.hostRepository().getHosts()).setValue(list);
        // The grid diffs a changed list off the main thread; wait for it to land.
        HostTileAdapter adapter = (HostTileAdapter) grid(activity).getAdapter();
        long deadline = System.currentTimeMillis() + 5000;
        do {
            idle();
        } while (adapter.hostCount() != list.size() && System.currentTimeMillis() < deadline
                && pause());
        assertEquals(list.size(), adapter.hostCount());
        idle();
    }

    private static boolean pause() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return true;
    }

    // The activity's own HostActions, swapped for one that only records.
    private static HostActions recordActions(MainActivity activity) throws Exception {
        HostActions actions = mock(HostActions.class);
        Field field = MainActivity.class.getDeclaredField("hostActions");
        field.setAccessible(true);
        field.set(activity, actions);
        return actions;
    }

    private static PcsFragment pcs(MainActivity activity) {
        return (PcsFragment) activity.getSupportFragmentManager()
                .findFragmentByTag(Section.PCS.tag());
    }

    private static RecyclerView grid(MainActivity activity) {
        return pcs(activity).requireView().findViewById(R.id.pcsList);
    }

    private static HostEntry host(String uuid, String name) {
        ComputerDetails details = new ComputerDetails();
        details.uuid = uuid;
        details.name = name;
        details.state = ComputerDetails.State.ONLINE;
        details.pairState = PairState.PAIRED;
        return new HostEntry(details, null, null, 12);
    }

    private static View tile(MainActivity activity, int position) {
        RecyclerView.ViewHolder holder = grid(activity).findViewHolderForAdapterPosition(position);
        assertNotNull(holder);
        return holder.itemView;
    }

    private static void focusTile(MainActivity activity, int position) {
        assertTrue(tile(activity, position).requestFocus());
        idle();
    }

    private static void press(MainActivity activity, int code) {
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        idle();
    }

    // What has focus, asked of the views: a test's window never has focus of its own.
    private static View focus(MainActivity activity) {
        return activity.getWindow().getDecorView().findFocus();
    }

    @Test
    public void focusingATile_publishesItsPage_andTheAddTileClearsIt() throws Exception {
        MainActivity activity = start();
        publish(activity, host("uuid-a", "Apollo"), host("uuid-b", "Studio"));
        focusTile(activity, 0);
        assertEquals("uuid-a", pcPage(activity).uuid);

        focusTile(activity, 1);
        PcDetailsPage page = pcPage(activity);
        assertEquals("uuid-b", page.uuid);
        assertEquals("Studio", page.name);
        assertEquals(CompanionPage.NO_STOP, page.focusedStop());

        HostTileAdapter adapter = (HostTileAdapter) grid(activity).getAdapter();
        focusTile(activity, adapter.hostCount());
        assertNull("the panel goes back to its idle hosts surface", panelPage(activity));
    }

    @Test
    public void y_takesTheDpadDown_andBBringsItBackToTheTile() throws Exception {
        MainActivity activity = start();
        publish(activity, host("uuid-a", "Apollo"));
        focusTile(activity, 0);
        View tile = tile(activity, 0);

        press(activity, KeyEvent.KEYCODE_BUTTON_Y);

        PcDetailsPage page = pcPage(activity);
        assertEquals(page.entryStop(), page.focusedStop());
        View sink = focus(activity);
        assertNotNull("the main window keeps key focus", sink);
        assertFalse("no ring is left on the tile", tile.isFocused());
        assertNull("focus is on no tile", grid(activity).findContainingViewHolder(sink));
        assertNull("the sheet is not opened as well", ShadowDialog.getLatestDialog());

        // The d-pad moves on the page, not on the grid.
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT);
        assertEquals(PcDetailsPage.stopFor(page.items.get(1)), pcPage(activity).focusedStop());

        press(activity, KeyEvent.KEYCODE_BUTTON_B);
        assertEquals(CompanionPage.NO_STOP, pcPage(activity).focusedStop());
        assertSame(tile, focus(activity));
    }

    @Test
    public void aPageTapOnDelete_removesThatHost() throws Exception {
        MainActivity activity = start();
        HostActions actions = recordActions(activity);
        HostEntry apollo = host("uuid-a", "Apollo");
        publish(activity, apollo);
        focusTile(activity, 0);
        PcDetailsPage page = pcPage(activity);
        int delete = PcDetailsPage.stopFor(HostActionsSheet.Item.DELETE);

        feed(activity).onPageTap(page, delete);
        idle();

        verify(actions).remove(apollo.details);
    }

    @Test
    public void aTapOnAPageNoLongerShown_isDropped() throws Exception {
        MainActivity activity = start();
        HostActions actions = recordActions(activity);
        publish(activity, host("uuid-a", "Apollo"), host("uuid-b", "Studio"));
        focusTile(activity, 0);
        PcDetailsPage stale = pcPage(activity);
        int delete = PcDetailsPage.stopFor(HostActionsSheet.Item.DELETE);

        focusTile(activity, 1);
        feed(activity).onPageTap(stale, delete);
        idle();

        verify(actions, never()).remove(any(ComputerDetails.class));
    }

    @Test
    public void aHostsUpdateWithNothingShownChanged_publishesNothingNew() throws Exception {
        MainActivity activity = start();
        publish(activity, host("uuid-a", "Apollo"));
        focusTile(activity, 0);
        CompanionPage before = panelPage(activity);
        assertNotNull(before);

        // The next poll: new entries, the same facts.
        publish(activity, host("uuid-a", "Apollo"));

        assertSame(before, panelPage(activity));
    }
}
