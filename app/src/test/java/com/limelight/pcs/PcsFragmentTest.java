package com.limelight.pcs;

import android.app.Dialog;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import androidx.lifecycle.MutableLiveData;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.platform.app.InstrumentationRegistry;

import com.limelight.MainScreenTestEnvironment;
import com.limelight.R;
import com.limelight.TestLogSuppressor;
import com.limelight.computers.HostEntry;
import com.limelight.main.MainActivity;
import com.limelight.main.Section;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager.PairState;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The PCs grid driven by the pad: X runs the focused tile's action, Y opens its actions, and
 * focus waits on the Add PC tile until the first PC turns up.
 */
@Config(sdk = {33}, qualifiers = "w900dp-h500dp-land",
        shadows = {com.limelight.shadows.ShadowMoonBridge.class,
                com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class PcsFragmentTest {

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void prepareEnvironment() {
        MainScreenTestEnvironment.prepare();
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** The main screen on its first run, which opens on PCs; driven by a pad, not touch. */
    private static MainActivity start() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false);
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        idle();
        return activity;
    }

    // The repository's feed, as its polls would set it: no host is ever polled here.
    @SuppressWarnings("unchecked")
    private static void publish(MainActivity activity, HostEntry... hosts) {
        List<HostEntry> list = hosts.length == 0
                ? Collections.<HostEntry>emptyList() : Arrays.asList(hosts);
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

    // What has focus, asked of the views: a test's window never has focus of its own.
    private static View focus(MainActivity activity) {
        return activity.getWindow().getDecorView().findFocus();
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

    private static HostEntry host(String uuid, String name, ComputerDetails.State state,
                                  PairState pairState) {
        ComputerDetails details = new ComputerDetails();
        details.uuid = uuid;
        details.name = name;
        details.state = state;
        details.pairState = pairState;
        return new HostEntry(details);
    }

    private static void press(MainActivity activity, int code) {
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        idle();
    }

    private static void focusTile(MainActivity activity, int position) {
        RecyclerView.ViewHolder holder = grid(activity).findViewHolderForAdapterPosition(position);
        assertNotNull(holder);
        assertTrue(holder.itemView.requestFocus());
        idle();
    }

    private static int focusedViewType(MainActivity activity) {
        View focus = focus(activity);
        assertNotNull("something holds focus", focus);
        RecyclerView.ViewHolder holder = grid(activity).findContainingViewHolder(focus);
        assertNotNull("focus is on a tile, not " + focus, holder);
        return holder.getItemViewType();
    }

    @Test
    public void x_onAnUnpairedTile_pairsIt() throws Exception {
        MainActivity activity = start();
        HostActions actions = recordActions(activity);
        HostEntry unpaired = host("uuid-laptop", "Laptop", ComputerDetails.State.ONLINE,
                PairState.NOT_PAIRED);
        publish(activity, unpaired);
        focusTile(activity, 0);

        press(activity, KeyEvent.KEYCODE_BUTTON_X);

        verify(actions).runCardAction(unpaired, HostTileBinder.ACTION_PAIR);
    }

    @Test
    public void x_onATileWithNoAction_doesNothing() throws Exception {
        MainActivity activity = start();
        HostActions actions = recordActions(activity);
        publish(activity, host("uuid-desk", "Desk", ComputerDetails.State.ONLINE, PairState.PAIRED));
        focusTile(activity, 0);

        press(activity, KeyEvent.KEYCODE_BUTTON_X);

        verify(actions, never()).runCardAction(any(HostEntry.class), anyInt());
    }

    @Test
    public void y_opensTheActionsSheet() throws Exception {
        MainActivity activity = start();
        recordActions(activity);
        publish(activity, host("uuid-studio", "Studio", ComputerDetails.State.OFFLINE,
                PairState.PAIRED));
        focusTile(activity, 0);

        press(activity, KeyEvent.KEYCODE_BUTTON_Y);

        Dialog sheet = ShadowDialog.getLatestDialog();
        assertNotNull(sheet);
        assertTrue(sheet.isShowing());
        TextView title = sheet.findViewById(R.id.menuSheetTitle);
        assertEquals("Studio", title.getText().toString());
    }

    @Test
    public void withNoHosts_focusWaitsOnTheAddTile_andMovesToTheFirstHost() {
        MainActivity activity = start();
        assertEquals(HostTileAdapter.TYPE_ADD, focusedViewType(activity));

        publish(activity, host("uuid-a", "Apollo", ComputerDetails.State.ONLINE, PairState.PAIRED),
                host("uuid-b", "Studio", ComputerDetails.State.OFFLINE, PairState.PAIRED));

        assertEquals(HostTileAdapter.TYPE_HOST, focusedViewType(activity));
        RecyclerView grid = grid(activity);
        assertSame(grid.findViewHolderForAdapterPosition(0).itemView, focus(activity));
    }

    @Test
    public void theAddTile_stayingFocusedByChoice_isNotLeftForAHost() {
        MainActivity activity = start();
        publish(activity, host("uuid-a", "Apollo", ComputerDetails.State.ONLINE, PairState.PAIRED));
        assertEquals(HostTileAdapter.TYPE_HOST, focusedViewType(activity));

        // The user goes to Add PC; a poll that publishes again must not pull focus back.
        HostTileAdapter adapter = (HostTileAdapter) grid(activity).getAdapter();
        focusTile(activity, adapter.hostCount());
        publish(activity, host("uuid-a", "Apollo", ComputerDetails.State.ONLINE, PairState.PAIRED),
                host("uuid-b", "Studio", ComputerDetails.State.OFFLINE, PairState.PAIRED));

        assertEquals(HostTileAdapter.TYPE_ADD, focusedViewType(activity));
    }
}
