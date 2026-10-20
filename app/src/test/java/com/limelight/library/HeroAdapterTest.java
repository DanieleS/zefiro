package com.limelight.library;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.R;
import com.limelight.TestLogSuppressor;
import com.limelight.computers.HostLibrary;
import com.limelight.computers.SnapshotFixtures;
import com.limelight.nvstream.http.NvApp;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The Library home's hero follows what the host runs, and nothing else: absent while it runs
 * nothing, present for a game the shelves leave out because it is hidden.
 */
@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class HeroAdapterTest {

    private static final String HOST = "host-1";

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    private static final HeroAdapter.Listener NO_OP = new HeroAdapter.Listener() {
        @Override
        public void onResume(LibraryApp game) {
        }

        @Override
        public void onQuit(LibraryApp game) {
        }
    };

    private static NvApp game(String name, int id) {
        return new NvApp(name, "uuid-" + id, id, false);
    }

    /** The hero laid out in a list, as the home lays it out. */
    private static RecyclerView layOut(HeroAdapter adapter) {
        Context context = new ContextThemeWrapper(ApplicationProvider.getApplicationContext(),
                R.style.AppTheme);
        RecyclerView list = new RecyclerView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(adapter);
        list.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY));
        list.layout(0, 0, 900, 1200);
        return list;
    }

    @Test
    public void nothingRunning_noHero() {
        NvApp hades = game("Hades II", 1);
        HostLibrary.Snapshot snapshot = SnapshotFixtures.ready(HOST,
                Arrays.asList(new LibraryApp(hades, false, false)), null);

        assertNull(HeroAdapter.heroFor(snapshot));
        HeroAdapter adapter = new HeroAdapter(NO_OP);
        adapter.submit(HeroAdapter.heroFor(snapshot), null, snapshot.metadata);

        assertEquals(0, adapter.getItemCount());
        assertEquals(0, layOut(adapter).getChildCount());
    }

    @Test
    public void noSnapshot_noHero() {
        assertNull(HeroAdapter.heroFor(null));
    }

    @Test
    public void aHiddenGameRunning_isTheHero() {
        NvApp visible = game("Alan Wake 2", 1);
        NvApp hidden = game("Hades II", 2);
        List<LibraryApp> apps = Arrays.asList(
                new LibraryApp(visible, false, false),
                new LibraryApp(hidden, true, true));
        HostLibrary.Snapshot snapshot = SnapshotFixtures.ready(HOST, apps, hidden);

        LibraryApp hero = HeroAdapter.heroFor(snapshot);
        assertNotNull(hero);
        assertEquals(2, hero.appId());
        // The library's own entry, so its actions sheet can offer to show it again.
        assertTrue(hero.hidden);
        assertTrue(hero.running);

        HeroAdapter adapter = new HeroAdapter(NO_OP);
        adapter.submit(hero, null, snapshot.metadata);
        assertEquals(1, adapter.getItemCount());

        RecyclerView list = layOut(adapter);
        assertEquals(1, list.getChildCount());
        View card = list.getChildAt(0);
        assertEquals("Hades II", ((TextView) card.findViewById(R.id.heroTitle)).getText().toString());
        // The two buttons are the stops; the card and its cover are not.
        assertTrue(card.findViewById(R.id.heroResume).isFocusable());
        assertTrue(card.findViewById(R.id.heroQuit).isFocusable());
        assertFalse(card.findViewById(R.id.heroCard).isFocusable());
        assertFalse(card.findViewById(R.id.heroCover).isFocusable());
    }

    @Test
    public void aGameTheHostNeverListed_isStillTheHero() {
        NvApp standIn = new NvApp("app", null, 77, false);
        HostLibrary.Snapshot snapshot = SnapshotFixtures.ready(HOST,
                Arrays.asList(new LibraryApp(game("Alan Wake 2", 1), false, false)), standIn);

        LibraryApp hero = HeroAdapter.heroFor(snapshot);
        assertNotNull(hero);
        assertEquals(77, hero.appId());
        assertFalse(hero.hidden);
    }

    @Test
    public void theGameStopping_removesTheHero() {
        NvApp hidden = game("Hades II", 2);
        HeroAdapter adapter = new HeroAdapter(NO_OP);
        adapter.submit(HeroAdapter.heroFor(SnapshotFixtures.ready(HOST,
                Arrays.asList(new LibraryApp(hidden, true, true)), hidden)), null, null);
        assertEquals(1, adapter.getItemCount());

        adapter.submit(HeroAdapter.heroFor(SnapshotFixtures.ready(HOST,
                Arrays.asList(new LibraryApp(hidden, false, true)), null)), null, null);

        assertEquals(0, adapter.getItemCount());
    }
}
