package com.limelight.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.R;
import com.limelight.grid.assets.ArtState;
import com.limelight.library.LibraryApp;
import com.limelight.nvstream.http.NvApp;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class GameTileViewTest {

    private GameTileView tile;
    private TextView title;
    private ImageView art;
    private View badge;
    private View content;

    @Before
    public void setUp() {
        Context context = new ContextThemeWrapper(
                ApplicationProvider.getApplicationContext(), R.style.AppTheme);
        tile = new GameTileView(context, null);
        title = tile.findViewById(R.id.tileTitle);
        art = tile.findViewById(R.id.tileArt);
        badge = tile.findViewById(R.id.tileRunningBadge);
        content = tile.findViewById(R.id.tileContent);
    }

    private static LibraryApp game(int id, boolean running, boolean hidden) {
        return new LibraryApp(new NvApp("Game " + id, "uuid-" + id, id, false), running, hidden);
    }

    @Test
    public void theTitleShowsWheneverThereIsNoCover() {
        tile.bind(game(1, false, false), ArtState.LOADING);
        assertEquals(View.VISIBLE, title.getVisibility());
        assertEquals(View.GONE, art.getVisibility());
        assertEquals("Game 1", title.getText().toString());

        tile.bind(game(1, false, false), ArtState.NO_ART);
        assertEquals(View.VISIBLE, title.getVisibility());
        assertEquals(View.GONE, art.getVisibility());

        tile.setArt(Bitmap.createBitmap(3, 4, Bitmap.Config.ARGB_8888));
        tile.bind(game(1, false, false), ArtState.ART);
        assertEquals(View.GONE, title.getVisibility());
        assertEquals(View.VISIBLE, art.getVisibility());
    }

    @Test
    public void loadingDimsTheTitleThatNoArtShowsInFull() {
        tile.bind(game(1, false, false), ArtState.LOADING);
        int loading = title.getCurrentTextColor();
        tile.bind(game(1, false, false), ArtState.NO_ART);
        int noArt = title.getCurrentTextColor();
        assertEquals(tile.getContext().getColor(R.color.content_muted), loading);
        assertEquals(tile.getContext().getColor(R.color.content_primary), noArt);
    }

    @Test
    public void aRebindInArtKeepsTheCoverAndAnyOtherStateDropsIt() {
        tile.bind(game(1, false, false), ArtState.LOADING);
        tile.setArt(Bitmap.createBitmap(3, 4, Bitmap.Config.ARGB_8888));
        tile.bind(game(1, false, false), ArtState.ART);
        // A poll flips the running flag: the same game, rebound in the state it had.
        tile.bind(game(1, true, false), tile.getArtState());
        assertNotNull(art.getDrawable());

        tile.bind(game(2, false, false), ArtState.LOADING);
        assertNull(art.getDrawable());
    }

    @Test
    public void aRecycledTileNeverShowsThePreviousGamesCover() {
        tile.bind(game(1, false, false), ArtState.LOADING);
        tile.setArt(Bitmap.createBitmap(3, 4, Bitmap.Config.ARGB_8888));
        tile.bind(game(1, false, false), ArtState.ART);
        assertEquals(View.VISIBLE, art.getVisibility());

        // Recycled for game 2, bound in ART from memory before its cover is handed over.
        tile.bind(game(2, false, false), ArtState.ART);
        assertNull(art.getDrawable());
        assertEquals(View.GONE, art.getVisibility());
        assertEquals(View.VISIBLE, title.getVisibility());
        assertEquals("Game 2", title.getText().toString());

        // Game 2's own cover then replaces the card.
        tile.setArt(Bitmap.createBitmap(3, 4, Bitmap.Config.ARGB_8888));
        assertNotNull(art.getDrawable());
        assertEquals(View.VISIBLE, art.getVisibility());
        assertEquals(View.GONE, title.getVisibility());
    }

    @Test
    public void theDescriptionSaysWhatTheBadgeAndTheDimmingShow() {
        tile.bind(game(1, false, false), ArtState.LOADING);
        assertEquals("Game 1", tile.getContentDescription().toString());

        tile.bind(game(1, true, true), ArtState.LOADING);
        assertEquals("Game 1, running, hidden", tile.getContentDescription().toString());
    }

    @Test
    public void runningShowsTheBadgeAndHiddenDimsTheLayersButNotTheFocusRing() {
        tile.bind(game(1, true, false), ArtState.LOADING);
        assertEquals(View.VISIBLE, badge.getVisibility());
        assertEquals(1.0f, content.getAlpha(), 0f);

        tile.bind(game(1, false, true), ArtState.LOADING);
        assertEquals(View.GONE, badge.getVisibility());
        assertEquals(0.40f, content.getAlpha(), 0.001f);
        // The ring is the tile's foreground: dimming the tile would dim it too.
        assertEquals(1.0f, tile.getAlpha(), 0f);
        assertNotNull(tile.getForeground());
    }

    @Test
    public void anExactWidthGivesTheBoxArtShape() {
        tile.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        assertEquals(300, tile.getMeasuredWidth());
        assertEquals(400, tile.getMeasuredHeight());
    }
}
