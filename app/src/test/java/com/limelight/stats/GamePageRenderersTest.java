package com.limelight.stats;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.R;
import com.limelight.companion.CompanionPage;
import com.limelight.library.GameActionsSheet;
import com.limelight.nvstream.http.GameAchievements;
import com.limelight.ui.SegmentedControlView;

import org.json.JSONArray;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.limelight.nvstream.http.TestGameStats.achievements;
import static com.limelight.nvstream.http.TestGameStats.json;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The game page's two panel renderers: the d-pad's stop is drawn activated and nothing takes
 * focus, and a tap names the stop the page numbers it by.
 */
@Config(sdk = {33}, qualifiers = "w640dp-h480dp")
@RunWith(RobolectricTestRunner.class)
public class GamePageRenderersTest {
    private static final long NOW = 1_791_460_800_000L;

    private static final GameAchievements DATA = achievements(json(
            "total", 3, "unlocked", 1,
            "items", new JSONArray(Arrays.asList(
                    json("id", "u1", "name", "One", "unlocked", true),
                    json("id", "l1", "name", "Two", "unlocked", false, "hidden", true),
                    json("id", "l2", "name", "Three", "unlocked", false)))));

    private Context context;
    private FrameLayout parent;
    private final List<Integer> taps = new ArrayList<>();

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.setTheme(R.style.AppTheme);
        parent = new FrameLayout(context);
    }

    private static void layout(View view) {
        view.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 640, 480);
    }

    private View bind(View view, CompanionPage page) {
        page.renderer().bind(view, page, taps::add);
        layout(view);
        return view;
    }

    @Test
    public void achievements_ringTheStopAndTapByStop() {
        GameAchievementsPage page = GameAchievementsPage.list("Sea of Stars", DATA,
                AchievementFilter.Filter.ALL, Collections.<String>emptySet(), null, NOW);
        View view = page.renderer().create(LayoutInflater.from(context), parent);
        bind(view, page);
        bind(view, page.withFocus(GameAchievementsPage.rowStop(1)));

        RecyclerView list = view.findViewById(R.id.achievementsBlockList);
        View first = list.findViewHolderForAdapterPosition(0).itemView;
        View masked = list.findViewHolderForAdapterPosition(1).itemView;
        assertTrue(masked.isActivated());
        assertFalse(first.isActivated());
        assertFalse("nothing on the panel takes focus", masked.isFocusable());

        SegmentedControlView filter = view.findViewById(R.id.achievementsBlockFilter);
        bind(view, page.withFocus(GameAchievementsPage.filterStop(AchievementFilter.Filter.LOCKED)));
        assertTrue(filter.getSegmentView(2).isActivated());
        assertFalse(filter.getSegmentView(2).isFocusable());
        assertFalse(list.findViewHolderForAdapterPosition(1).itemView.isActivated());

        // A segment by its filter; a masked row by its position; an unmasked row does nothing.
        filter.getSegmentView(1).performClick();
        list.findViewHolderForAdapterPosition(1).itemView.performClick();
        list.findViewHolderForAdapterPosition(0).itemView.performClick();
        assertEquals(Arrays.asList(GameAchievementsPage.filterStop(AchievementFilter.Filter.UNLOCKED),
                GameAchievementsPage.rowStop(1)), taps);
    }

    @Test
    public void achievementsStatus_isOneRowThatRetriesWhenFailed() {
        GameAchievementsPage failed = GameAchievementsPage.failed("Hades");
        View view = failed.renderer().create(LayoutInflater.from(context), parent);
        bind(view, failed.withFocus(GameAchievementsPage.STOP_STATUS));

        View status = view.findViewById(R.id.companionAchievementsStatus);
        assertEquals(View.VISIBLE, status.getVisibility());
        assertEquals(View.GONE, view.findViewById(R.id.companionAchievementsBlock).getVisibility());
        assertTrue(status.isActivated());
        status.performClick();
        assertEquals(Collections.singletonList(GameAchievementsPage.STOP_STATUS), taps);

        // Loading: the same row, nothing to retry.
        bind(view, GameAchievementsPage.loading("Hades"));
        assertFalse(status.isActivated());
        status.performClick();
        assertEquals(1, taps.size());
    }

    @Test
    public void actions_bigRowsRingedByStop_tapByIndex() {
        GameActionsPage page = new GameActionsPage("Turing Complete",
                Arrays.asList(GameActionsSheet.Item.HIDE, GameActionsSheet.Item.PIN_SHORTCUT,
                        GameActionsSheet.Item.DETAILS),
                Arrays.asList("Hide", "Create shortcut", "View details"), true);
        View view = page.renderer().create(LayoutInflater.from(context), parent);
        bind(view, page.withFocus(2));

        LinearLayout rows = view.findViewById(R.id.companionActionsRows);
        assertEquals(3, rows.getChildCount());
        assertTrue(rows.getChildAt(2).isActivated());
        assertFalse(rows.getChildAt(0).isActivated());
        assertTrue(rows.getChildAt(0).getHeight()
                >= context.getResources().getDimensionPixelSize(R.dimen.action_size));
        // Hide is checked while the game is hidden.
        assertEquals(View.VISIBLE,
                rows.getChildAt(0).findViewById(R.id.companionActionCheck).getVisibility());
        assertEquals(View.GONE,
                rows.getChildAt(1).findViewById(R.id.companionActionCheck).getVisibility());

        // A focus move keeps the rows; it only moves the ring.
        View kept = rows.getChildAt(1);
        bind(view, page.withFocus(1));
        assertTrue(kept == rows.getChildAt(1));
        assertTrue(kept.isActivated());

        rows.getChildAt(0).performClick();
        assertEquals(Collections.singletonList(0), taps);
    }
}
