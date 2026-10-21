package com.limelight.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.limelight.R;
import com.limelight.grid.assets.ArtState;
import com.limelight.library.LibraryApp;

/**
 * One game in the library: its cover, or its title when it has none, with a RUNNING badge and
 * the dimming of a hidden game. The shelves, the hero and the full grid all draw a game with
 * this, so a game looks the same wherever it appears.
 *
 * <h3>The title is never hidden while there is no cover</h3>
 *
 * The old grid hid the title while a cover loaded and showed it only once the host had failed
 * to provide one. On a slow host that left a screen of blank cards for seconds, and a host that
 * served its own stand-in image left a screen of identical pictures with no names at all. Here
 * the title card's visibility follows the {@link ArtState} and nothing else:
 *
 * <pre>
 *   LOADING ──▶ title card, dimmed: provisional, but already says which game this is
 *   NO_ART  ──▶ title card, full strength: this is how the game looks, for good
 *   ART     ──▶ the cover, fading in when it replaces a LOADING card
 * </pre>
 *
 * Whether a cover is real or the host's stand-in is decided before it gets here (by the loader,
 * see {@code NoArtDetector}); this view only draws the state it is given.
 *
 * <h3>Use</h3>
 *
 * The view is passive: an adapter binds it with {@link #bind} and hands it the cover with
 * {@link #setArt}. It holds no request and loads nothing, so recycling it needs nothing but a
 * new bind. Main thread only, as views are.
 *
 * <p>A cover belongs to the game bound when it was given. For that game, bind and setArt come
 * in either order; a new game is bound first. The tile remembers whose cover it holds, so a
 * recycled tile bound to another game in ART never shows the previous game's cover while this
 * game's is on its way: it shows the dimmed title card until setArt brings the right one.
 *
 * <p>Given an exact width and a free height, it takes the 3:4 shape of box art; given both, it
 * fills what it is given.
 */
public class GameTileView extends FrameLayout {

    // A hidden game is still shown when the user asks to see hidden games, dimmed.
    private static final float HIDDEN_ALPHA = 0.40f;
    private static final float VISIBLE_ALPHA = 1.0f;

    // Box art is 3:4, portrait.
    private static final int ASPECT_WIDTH = 3;
    private static final int ASPECT_HEIGHT = 4;

    private static final long ART_FADE_MS = 150;

    private static final int NO_APP = Integer.MIN_VALUE;

    private final View content;
    private final ImageView art;
    private final TextView title;
    private final View runningBadge;

    private final int titleColor;
    private final int titleLoadingColor;

    private int boundAppId = NO_APP;
    // The game whose cover the art layer holds; NO_APP when it holds none.
    private int artAppId = NO_APP;
    private ArtState artState = ArtState.LOADING;

    public GameTileView(Context context) {
        this(context, null);
    }

    public GameTileView(Context context, AttributeSet attrs) {
        super(context, attrs);
        inflate(context, R.layout.view_game_tile, this);
        content = findViewById(R.id.tileContent);
        art = findViewById(R.id.tileArt);
        title = findViewById(R.id.tileTitle);
        runningBadge = findViewById(R.id.tileRunningBadge);

        titleColor = ContextCompat.getColor(context, R.color.content_primary);
        titleLoadingColor = ContextCompat.getColor(context, R.color.content_muted);

        // A tile is something to pick, with the d-pad as much as by touch; the ring is drawn
        // over the art, on the tile's bounds, as the old grid drew it.
        setFocusable(true);
        setClickable(true);
        setForeground(ContextCompat.getDrawable(context, R.drawable.tile_focus_ring));

        applyArtState(ArtState.LOADING, false);
    }

    /**
     * Shows a game in an art state. In {@link ArtState#ART} the cover this game was given with
     * {@link #setArt} stays, so binding the same game again for a flipped running or hidden flag
     * keeps it; another game's cover is dropped. In any other state the cover is dropped, as
     * none is shown.
     */
    public void bind(LibraryApp app, ArtState state) {
        boolean sameGame = app.appId() == boundAppId;
        // The cover fades in only when it replaces this game's own loading card; a recycled
        // tile, or one bound straight from memory, shows it at once.
        boolean fadeIn = sameGame && artState == ArtState.LOADING && state == ArtState.ART;
        boundAppId = app.appId();

        String name = app.app.getAppName();
        title.setText(name);
        setContentDescription(describe(name, app));

        runningBadge.setVisibility(app.running ? View.VISIBLE : View.GONE);
        // The layers dim, not the tile: the focus ring is the tile's foreground and has to stay
        // at full strength on a hidden game too, or a d-pad user loses track of focus on it.
        content.setAlpha(app.hidden ? HIDDEN_ALPHA : VISIBLE_ALPHA);

        applyArtState(state, fadeIn);
    }

    /**
     * The cover of the game last bound, shown while the state is {@link ArtState#ART}; null
     * clears it. For that game it may come before or after the {@link #bind} that sets the
     * state; given before any bind, it belongs to no game and the first bind drops it.
     */
    public void setArt(Bitmap bitmap) {
        // Already in ART but showing the title card in its place: the cover replaces the card
        // the way it replaces a LOADING one, fading in.
        boolean replacesCard = artState == ArtState.ART && !holdsOwnArt();
        art.setImageBitmap(bitmap);
        artAppId = bitmap != null ? boundAppId : NO_APP;
        if (artState == ArtState.ART) {
            applyArtState(ArtState.ART, replacesCard);
        }
    }

    public ArtState getArtState() {
        return artState;
    }

    private boolean holdsOwnArt() {
        return artAppId != NO_APP && artAppId == boundAppId;
    }

    // What TalkBack says: the name, then what the badge and the dimming show to the eye.
    private String describe(String name, LibraryApp app) {
        StringBuilder description = new StringBuilder(name != null ? name : "");
        if (app.running) {
            description.append(", ").append(getContext().getString(R.string.tile_a11y_running));
        }
        if (app.hidden) {
            description.append(", ").append(getContext().getString(R.string.tile_a11y_hidden));
        }
        return description.toString();
    }

    private void applyArtState(ArtState state, boolean fadeIn) {
        artState = state;
        art.animate().cancel();

        if (state == ArtState.ART && holdsOwnArt()) {
            title.setVisibility(View.GONE);
            art.setVisibility(View.VISIBLE);
            if (fadeIn) {
                art.setAlpha(0f);
                art.animate().alpha(1f).setDuration(ART_FADE_MS);
            } else {
                art.setAlpha(1f);
            }
            return;
        }

        // No cover to show: release the last one rather than keep a bitmap nobody sees. That
        // includes ART with no cover of this game's yet (a recycled tile whose last cover was
        // another game's), which waits on setArt like a LOADING card, dimmed.
        art.setVisibility(View.GONE);
        art.setImageDrawable(null);
        artAppId = NO_APP;
        title.setVisibility(View.VISIBLE);
        title.setTextColor(state == ArtState.NO_ART ? titleColor : titleLoadingColor);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY
                && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = width * ASPECT_HEIGHT / ASPECT_WIDTH;
            if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.AT_MOST) {
                height = Math.min(height, MeasureSpec.getSize(heightMeasureSpec));
            }
            heightMeasureSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
