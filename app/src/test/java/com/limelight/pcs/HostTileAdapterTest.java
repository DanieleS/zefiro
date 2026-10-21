package com.limelight.pcs;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;

import com.google.android.material.button.MaterialButton;
import com.limelight.R;
import com.limelight.computers.HostEntry;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager.PairState;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The tile's action by host state, the diff that decides whether a poll redraws a tile, and the
 * Add PC tile that always closes the grid.
 */
@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class HostTileAdapterTest {

    private static ComputerDetails details(String uuid, ComputerDetails.State state, PairState pairState) {
        ComputerDetails details = new ComputerDetails();
        details.uuid = uuid;
        details.name = "Taverna";
        details.state = state;
        details.pairState = pairState;
        return details;
    }

    private static ComputerDetails details(ComputerDetails.State state, PairState pairState) {
        return details("uuid-1", state, pairState);
    }

    private static HostEntry entry(ComputerDetails details) {
        return new HostEntry(details);
    }

    private static final HostTileAdapter.Listener NO_OP = new HostTileAdapter.Listener() {
        @Override
        public void onCardClicked(@NonNull HostEntry host) {
        }

        @Override
        public void onCardLongPressed(@NonNull HostEntry host) {
        }

        @Override
        public void onActionClicked(@NonNull HostEntry host, int action) {
        }

        @Override
        public void onMoreClicked(@NonNull HostEntry host, @NonNull View anchor) {
        }

        @Override
        public void onCardFocused(@NonNull HostEntry host) {
        }

        @Override
        public void onAddClicked() {
        }

        @Override
        public void onAddFocusChanged(boolean hasFocus) {
        }
    };

    // The tiles are Material buttons on the app's theme, as MainActivity inflates them.
    private static Context themed() {
        return new ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.AppTheme);
    }

    private static HostTileAdapter adapter() {
        return new HostTileAdapter(new HostTileBinder(themed()), NO_OP);
    }

    // submitList diffs a changed list off the main thread and lands it on the main thread.
    private static void submit(HostTileAdapter adapter, HostEntry... hosts) {
        final boolean[] committed = {false};
        adapter.submitList(Arrays.asList(hosts), () -> committed[0] = true);
        long deadline = System.currentTimeMillis() + 5000;
        while (!committed[0] && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if (!committed[0]) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        assertTrue("the list was committed", committed[0]);
    }

    @Test
    public void actionFor_followsTheHostState() {
        ComputerDetails running = details(ComputerDetails.State.ONLINE, PairState.PAIRED);
        running.runningGameId = 7;
        assertEquals(HostTileBinder.ACTION_RESUME, HostTileBinder.actionFor(entry(running)));

        assertEquals(HostTileBinder.ACTION_PAIR, HostTileBinder.actionFor(
                entry(details(ComputerDetails.State.ONLINE, PairState.NOT_PAIRED))));

        ComputerDetails asleep = details(ComputerDetails.State.OFFLINE, PairState.PAIRED);
        asleep.macAddress = "00:11:22:33:44:55";
        assertEquals(HostTileBinder.ACTION_WAKE, HostTileBinder.actionFor(entry(asleep)));

        // Without a MAC there is nothing to wake, and a host being checked is left alone.
        assertEquals(HostTileBinder.ACTION_NONE, HostTileBinder.actionFor(
                entry(details(ComputerDetails.State.OFFLINE, PairState.PAIRED))));
        ComputerDetails checking = details(ComputerDetails.State.UNKNOWN, PairState.PAIRED);
        checking.macAddress = "00:11:22:33:44:55";
        assertEquals(HostTileBinder.ACTION_NONE, HostTileBinder.actionFor(entry(checking)));

        assertEquals(HostTileBinder.ACTION_NONE, HostTileBinder.actionFor(
                entry(details(ComputerDetails.State.ONLINE, PairState.PAIRED))));
    }

    @Test
    public void sameOnTile_ignoresWhatTheTileDoesNotShow() {
        ComputerDetails a = details(ComputerDetails.State.ONLINE, PairState.PAIRED);
        a.activeAddress = new ComputerDetails.AddressTuple("10.0.0.2", 47989);
        a.runningGameId = 3;
        ComputerDetails b = new ComputerDetails(a);
        b.httpsPort = 47990;
        b.nvidiaServer = true;
        // The address is on the bottom screen and in the sheet, not on the tile.
        b.activeAddress = new ComputerDetails.AddressTuple("10.0.0.3", 47989);

        HostEntry withHero = new HostEntry(b, null,
                Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888));
        assertTrue(HostTileAdapter.sameOnTile(entry(a), withHero));
        // Nor is the game count.
        assertTrue(HostTileAdapter.sameOnTile(entry(a), new HostEntry(a, null, null, 12)));
    }

    @Test
    public void sameOnTile_seesEveryChangeTheTileShows() {
        ComputerDetails base = details(ComputerDetails.State.ONLINE, PairState.PAIRED);

        ComputerDetails renamed = new ComputerDetails(base);
        renamed.name = "Studio";
        assertFalse(HostTileAdapter.sameOnTile(entry(base), entry(renamed)));

        ComputerDetails offline = new ComputerDetails(base);
        offline.state = ComputerDetails.State.OFFLINE;
        assertFalse(HostTileAdapter.sameOnTile(entry(base), entry(offline)));

        ComputerDetails unpaired = new ComputerDetails(base);
        unpaired.pairState = PairState.NOT_PAIRED;
        assertFalse(HostTileAdapter.sameOnTile(entry(base), entry(unpaired)));

        ComputerDetails playing = new ComputerDetails(base);
        playing.runningGameId = 3;
        assertFalse(HostTileAdapter.sameOnTile(entry(base), entry(playing)));

        ComputerDetails wakeable = new ComputerDetails(base);
        wakeable.macAddress = "00:11:22:33:44:55";
        assertFalse(HostTileAdapter.sameOnTile(entry(base), entry(wakeable)));

        HostEntry named = new HostEntry(new ComputerDetails(playing), "Hades", null);
        assertFalse(HostTileAdapter.sameOnTile(entry(playing), named));
    }

    @Test
    public void theAddTile_isAlwaysLastWithItsFixedId() {
        HostTileAdapter adapter = adapter();
        // Before any host: the Add PC tile alone.
        assertEquals(1, adapter.getItemCount());
        assertEquals(0, adapter.hostCount());
        assertEquals(HostTileAdapter.ADD_TILE_ID, adapter.getItemId(0));
        assertEquals(HostTileAdapter.TYPE_ADD, adapter.getItemViewType(0));

        HostEntry first = entry(details("uuid-a", ComputerDetails.State.ONLINE, PairState.PAIRED));
        HostEntry second = entry(details("uuid-b", ComputerDetails.State.OFFLINE, PairState.PAIRED));
        submit(adapter, first, second);

        assertEquals(3, adapter.getItemCount());
        assertEquals(2, adapter.hostCount());
        assertEquals(2, adapter.hostCount());
        assertEquals(HostTileAdapter.ADD_TILE_ID, adapter.getItemId(2));
        assertEquals(HostTileAdapter.TYPE_ADD, adapter.getItemViewType(2));
        assertEquals(HostTileAdapter.TYPE_HOST, adapter.getItemViewType(0));
        assertTrue(adapter.getItemId(0) != HostTileAdapter.ADD_TILE_ID);
        assertTrue(adapter.getItemId(0) != adapter.getItemId(1));

        // A host's id follows the host, not the position.
        long idOfA = adapter.getItemId(0);
        submit(adapter, second, first);
        assertEquals(idOfA, adapter.getItemId(1));
        assertEquals(HostTileAdapter.ADD_TILE_ID, adapter.getItemId(2));

        submit(adapter);
        assertEquals(1, adapter.getItemCount());
        assertEquals(HostTileAdapter.ADD_TILE_ID, adapter.getItemId(0));
    }

    @Test
    public void entryAt_isNullForTheAddTile() {
        HostTileAdapter adapter = adapter();
        HostEntry only = entry(details(ComputerDetails.State.ONLINE, PairState.PAIRED));
        submit(adapter, only);

        assertSame(only, adapter.entryAt(0));
        assertNull(adapter.entryAt(adapter.hostCount()));
        assertNull(adapter.entryAt(-1));
        assertNull(adapter.entryAt(5));
        assertEquals(0, adapter.positionOf("uuid-1"));
        assertEquals(RecyclerView.NO_POSITION, adapter.positionOf("uuid-gone"));
        assertEquals(RecyclerView.NO_POSITION, adapter.positionOf(null));
    }

    @Test
    public void aPlayingTile_saysTheGameOrThatItIsInOne_andOffersResume() {
        HostTileAdapter adapter = adapter();
        ComputerDetails playing = details(ComputerDetails.State.ONLINE, PairState.PAIRED);
        playing.runningGameId = 7;
        submit(adapter, new HostEntry(playing, "Sea of Stars", null),
                entry(details("uuid-2", ComputerDetails.State.ONLINE, PairState.PAIRED)));

        Context context = themed();
        FrameLayout parent = new FrameLayout(context);
        RecyclerView.ViewHolder named = adapter.createViewHolder(parent, HostTileAdapter.TYPE_HOST);
        adapter.bindViewHolder(named, 0);
        assertEquals("Playing · Sea of Stars",
                ((TextView) named.itemView.findViewById(R.id.pcTileStatus)).getText().toString());
        MaterialButton action = named.itemView.findViewById(R.id.pcTileAction);
        assertEquals(View.VISIBLE, action.getVisibility());
        assertEquals(context.getString(R.string.pcview_action_resume), action.getText().toString());

        // Online and idle: no button, A opens the Library.
        RecyclerView.ViewHolder idle = adapter.createViewHolder(parent, HostTileAdapter.TYPE_HOST);
        adapter.bindViewHolder(idle, 1);
        assertEquals(View.GONE, idle.itemView.findViewById(R.id.pcTileAction).getVisibility());

        // The name is not known yet: the tile says only that the host is in a game.
        submit(adapter, entry(playing));
        adapter.bindViewHolder(named, 0);
        assertEquals(context.getString(R.string.pcview_status_running_unnamed),
                ((TextView) named.itemView.findViewById(R.id.pcTileStatus)).getText().toString());
    }

    /** Records the entries the tile's buttons hand out. */
    private static final class Taps implements HostTileAdapter.Listener {
        final List<HostEntry> actions = new ArrayList<>();
        final List<HostEntry> more = new ArrayList<>();

        @Override
        public void onCardClicked(@NonNull HostEntry host) {
        }

        @Override
        public void onCardLongPressed(@NonNull HostEntry host) {
        }

        @Override
        public void onActionClicked(@NonNull HostEntry host, int action) {
            actions.add(host);
        }

        @Override
        public void onMoreClicked(@NonNull HostEntry host, @NonNull View anchor) {
            more.add(host);
        }

        @Override
        public void onCardFocused(@NonNull HostEntry host) {
        }

        @Override
        public void onAddClicked() {
        }

        @Override
        public void onAddFocusChanged(boolean hasFocus) {
        }
    }

    @Test
    public void theButtons_actOnTheCurrentEntry_whenAPollDoesNotRedrawTheTile() {
        Context context = themed();
        Taps taps = new Taps();
        HostTileAdapter adapter = new HostTileAdapter(new HostTileBinder(context), taps);
        RecyclerView grid = new RecyclerView(context);
        grid.setLayoutManager(new LinearLayoutManager(context));
        grid.setAdapter(adapter);

        ComputerDetails first = details(ComputerDetails.State.ONLINE, PairState.NOT_PAIRED);
        first.activeAddress = new ComputerDetails.AddressTuple("192.168.1.20", 47989);
        submit(adapter, new HostEntry(first, null, null, HostEntry.UNKNOWN_GAME_COUNT));
        layOut(grid);
        View tile = grid.findViewHolderForAdapterPosition(0).itemView;

        // Same tile, new address and games count: nothing is redrawn.
        ComputerDetails moved = details(ComputerDetails.State.ONLINE, PairState.NOT_PAIRED);
        moved.activeAddress = new ComputerDetails.AddressTuple("203.0.113.7", 47989);
        HostEntry current = new HostEntry(moved, null, null, 16);
        assertTrue(HostTileAdapter.sameOnTile(adapter.entryAt(0), current));
        submit(adapter, current);
        layOut(grid);
        assertSame(tile, grid.findViewHolderForAdapterPosition(0).itemView);

        tile.findViewById(R.id.pcTileMore).performClick();
        tile.findViewById(R.id.pcTileAction).performClick();
        assertEquals(1, taps.more.size());
        assertSame(current, taps.more.get(0));
        assertEquals(1, taps.actions.size());
        assertSame(current, taps.actions.get(0));
    }

    private static void layOut(RecyclerView grid) {
        grid.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY));
        grid.layout(0, 0, 1000, 2000);
    }
}
