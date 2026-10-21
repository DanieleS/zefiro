package com.limelight.pcs;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.R;
import com.limelight.computers.HostEntry;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager.PairState;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The focused PC drawn on the panel: the running card only while a game runs, the actions two to
 * a row, the ring on the focused stop alone, a focus move that rebuilds nothing, and taps that
 * name their stop.
 */
@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class PcDetailsRendererTest {

    private static Context context() {
        return ApplicationProvider.getApplicationContext();
    }

    private static PcDetailsPage page(boolean playing) {
        ComputerDetails details = new ComputerDetails();
        details.uuid = "uuid-1";
        details.name = "Apollo";
        details.state = ComputerDetails.State.ONLINE;
        details.pairState = PairState.PAIRED;
        details.runningGameId = playing ? 7 : 0;
        return PcDetailsPage.of(context(), new HostEntry(details, "Hades", null, 9), null);
    }

    private static View create() {
        ViewGroup parent = new FrameLayout(context());
        return PcDetailsRenderer.INSTANCE.create(LayoutInflater.from(context()), parent);
    }

    // The action cells in stop order.
    private static List<View> cells(View view) {
        LinearLayout actions = view.findViewById(R.id.pcDetailsActions);
        List<View> cells = new ArrayList<>();
        for (int r = 0; r < actions.getChildCount(); r++) {
            ViewGroup row = (ViewGroup) actions.getChildAt(r);
            for (int c = 0; c < row.getChildCount(); c++) {
                View cell = row.getChildAt(c);
                if (cell.findViewById(R.id.pcActionLabel) != null) {
                    cells.add(cell);
                }
            }
        }
        return cells;
    }

    @Test
    public void playing_drawsTheRunningCard_andTheActionsTwoToARow() {
        View view = create();
        PcDetailsPage page = page(true);
        PcDetailsRenderer.INSTANCE.bind(view, page, stop -> { });

        assertEquals(View.VISIBLE, view.findViewById(R.id.pcDetailsRunning).getVisibility());
        assertEquals("Hades",
                ((TextView) view.findViewById(R.id.pcDetailsRunningTitle)).getText().toString());
        LinearLayout actions = view.findViewById(R.id.pcDetailsActions);
        assertEquals((page.items.size() + 1) / 2, actions.getChildCount());
        List<View> cells = cells(view);
        assertEquals(page.items.size(), cells.size());
        TextView last = cells.get(cells.size() - 1).findViewById(R.id.pcActionLabel);
        assertEquals(context().getString(R.string.pcview_menu_delete_pc), last.getText().toString());
        LinearLayout facts = view.findViewById(R.id.pcDetailsFacts);
        assertEquals(page.facts.size(), facts.getChildCount());
    }

    @Test
    public void idle_hidesTheRunningCard() {
        View view = create();
        PcDetailsRenderer.INSTANCE.bind(view, page(false), stop -> { });

        assertEquals(View.GONE, view.findViewById(R.id.pcDetailsRunning).getVisibility());
    }

    @Test
    public void theRingIsOnTheFocusedStopAlone_andAFocusMoveRebuildsNothing() {
        View view = create();
        PcDetailsPage page = page(true);
        PcDetailsRenderer.INSTANCE.bind(view, page.withFocus(PcDetailsPage.STOP_QUIT), stop -> { });
        List<View> before = cells(view);
        assertTrue(view.findViewById(R.id.pcDetailsQuit).isActivated());
        assertFalse(view.findViewById(R.id.pcDetailsResume).isActivated());

        PcDetailsRenderer.INSTANCE.bind(view,
                page.withFocus(PcDetailsPage.stopFor(page.items.get(1))), stop -> { });

        List<View> after = cells(view);
        assertSame(before.get(0), after.get(0));
        assertFalse(view.findViewById(R.id.pcDetailsQuit).isActivated());
        for (int i = 0; i < after.size(); i++) {
            assertEquals(i == 1, after.get(i).isActivated());
        }
    }

    @Test
    public void aTap_namesItsStop() {
        View view = create();
        PcDetailsPage page = page(true);
        List<Integer> taps = new ArrayList<>();
        PcDetailsRenderer.INSTANCE.bind(view, page, taps::add);

        view.findViewById(R.id.pcDetailsResume).performClick();
        cells(view).get(2).performClick();

        assertEquals(PcDetailsPage.STOP_RESUME, (int) taps.get(0));
        assertEquals(PcDetailsPage.stopFor(page.items.get(2)), (int) taps.get(1));
    }
}
