package com.limelight.library;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.limelight.R;
import com.limelight.companion.CompanionState;
import com.limelight.computers.HostLibrary;
import com.limelight.grid.assets.ArtState;
import com.limelight.grid.assets.CachedAppAssetLoader;
import com.limelight.main.WindowClass;
import com.limelight.nvstream.http.AppMetadata;
import com.limelight.nvstream.http.NvApp;
import com.limelight.ui.GameTileView;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The hero over the Library home's shelves: the game the selected host is running, with Resume
 * and Quit game. One row while the host runs something, none otherwise.
 *
 * <p>Its tag says where the game runs ("Running on Taverna"), and a meta line under the title
 * says what the game is (year, genres, score) when the host has Playnite metadata for it; it is
 * left out without, never stood in for. The mock's time in the session is not shown: no host
 * says when a game was started.
 *
 * <h3>Size</h3>
 *
 * The card is as tall as its cover, and the cover is sized so the card takes at most a third of
 * the window's height ({@link #coverWidthDp}): on the Thor the shelves are what the screen is
 * for, and a hero half its height pushed them off it. The column beside the cover is at least as
 * tall as the cover and may be taller: a title on two lines, or a meta line, on a phone's small
 * cover makes the card grow rather than cut the buttons off. The backdrop fills the card without sizing
 * it; before, a tall background art made the card as tall as the picture.
 *
 * <h3>What counts as running</h3>
 *
 * Whatever the library's snapshot says the host runs ({@code Snapshot.running}), and nothing
 * else: a hidden game (hiding takes a game off the shelves, not out of reach while it runs), a
 * special such as the virtual display, or a stand-in for a game the host never listed. The hero
 * never consults the shelves, which leave all of those out. {@link #heroFor} is that rule.
 *
 * <h3>The card's ground</h3>
 *
 * <pre>
 *   the game's background art, cached ──▶ shown at once
 *   the host has one (Playnite metadata) ──▶ fetched, and laid over what shows meanwhile
 *   the game's cover ──▶ stretched behind the scrim until something better comes, or for good
 *   neither ──▶ the plain card (hero_card_bg), which is the placeholder
 * </pre>
 *
 * <h3>Focus</h3>
 *
 * The card is not focusable; Resume and Quit game are the stops, Resume first. Main thread only.
 */
final class HeroAdapter extends RecyclerView.Adapter<HeroAdapter.Holder> {

    interface Listener {
        void onResume(@NonNull LibraryApp game);

        void onQuit(@NonNull LibraryApp game);
    }

    /** The host's covers changed: ask for the hero's again. */
    private static final Object PAYLOAD_ART = new Object();

    // A phone in portrait has room for a smaller cover beside the buttons, and none for the
    // play glyph on Resume: both buttons must fit next to the cover on a 360dp-wide screen.
    private static final int COMPACT_COVER_WIDTH_DP = 84;
    // The narrowest a wide window's cover gets to keep the card within a third of the window.
    private static final int MIN_COVER_WIDTH_DP = 72;
    // The card's share of the window's height, at most.
    private static final int HEIGHT_SHARE = 3;

    private final Listener listener;

    @Nullable
    private LibraryApp game;
    @Nullable
    private HostLibrary library;
    private boolean hasBackground;
    // The tag's PC and the meta line, as last submitted; null when not known.
    @Nullable
    private String hostName;
    @Nullable
    private String meta;
    @Nullable
    private Holder bound;

    HeroAdapter(@NonNull Listener listener) {
        this.listener = listener;
    }

    /**
     * The game the hero offers for a snapshot: its running app, flagged as running and, when it
     * is one of the library's games, as hidden or not; null when the host runs nothing.
     */
    @Nullable
    static LibraryApp heroFor(@Nullable HostLibrary.Snapshot snapshot) {
        if (snapshot == null || snapshot.running == null) {
            return null;
        }
        return heroFor(snapshot.running, snapshot.apps);
    }

    @NonNull
    @VisibleForTesting
    static LibraryApp heroFor(@NonNull NvApp running, @NonNull List<LibraryApp> apps) {
        for (LibraryApp app : apps) {
            if (app.appId() == running.getAppId()) {
                // The library's own entry, so the actions sheet offers to show it again if hidden.
                return app.running ? app : new LibraryApp(app.app, true, app.hidden);
            }
        }
        return new LibraryApp(running, true, false);
    }

    /**
     * Shows a game, or removes the row for null. The same game again (a poll) changes nothing,
     * unless the metadata now says it has background art (metadata is fetched apart from the
     * applist, and may land after the hero is up), which asks for that art; another game, or
     * another host's library, is a fresh bind.
     *
     * @param metadata the host's Playnite metadata, to know whether the game has background art
     */
    void submit(@Nullable LibraryApp game, @Nullable HostLibrary library,
                @Nullable Map<String, AppMetadata> metadata) {
        submit(game, library, metadata, null);
    }

    /**
     * As {@link #submit(LibraryApp, HostLibrary, Map)}, naming the PC the game runs on in the
     * tag; a change of name or of meta line redraws the card in place.
     *
     * @param hostName the selected PC's name; null for the plain "Continue" tag
     */
    void submit(@Nullable LibraryApp game, @Nullable HostLibrary library,
                @Nullable Map<String, AppMetadata> metadata, @Nullable String hostName) {
        boolean had = this.game != null;
        String nextMeta = game != null ? metaFor(game.app, metadata) : null;
        boolean same = had && game != null && library == this.library
                && game.appId() == this.game.appId()
                && equal(game.app.getAppName(), this.game.app.getAppName())
                && game.hidden == this.game.hidden
                && equal(hostName, this.hostName) && equal(nextMeta, this.meta);
        boolean background = game != null && hasBackground(game.app, metadata);
        boolean backgroundCame = background && !this.hasBackground;
        this.game = game;
        this.library = library;
        this.hasBackground = background;
        this.hostName = hostName;
        this.meta = nextMeta;
        if (had && game == null) {
            notifyItemRemoved(0);
        } else if (!had && game != null) {
            notifyItemInserted(0);
        } else if (game != null && !same) {
            notifyItemChanged(0);
        } else if (backgroundCame) {
            notifyItemChanged(0, PAYLOAD_ART);
        }
    }

    /**
     * Asks for the hero's art again: the library's covers or stand-ins changed, and a
     * background another screen loaded may be in the library's cache by now.
     */
    void refreshArt() {
        if (game != null) {
            notifyItemChanged(0, PAYLOAD_ART);
        }
    }

    /** The game on show; null when there is none. */
    @Nullable
    LibraryApp game() {
        return game;
    }

    /** Resume, or Quit game, while the hero is laid out; null otherwise. */
    @Nullable
    View button(boolean quit) {
        if (bound == null || !bound.itemView.isShown()) {
            return null;
        }
        return quit ? bound.quit : bound.resume;
    }

    /** Whether the view is in the hero. */
    boolean contains(@Nullable View view) {
        return view != null && bound != null && (view == bound.resume || view == bound.quit);
    }

    boolean isQuit(@Nullable View view) {
        return view != null && bound != null && view == bound.quit;
    }

    @Override
    public int getItemCount() {
        return game != null ? 1 : 0;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_continue_hero, parent, false);
        final Holder holder = new Holder(view);
        // Clip the backdrop to the card's rounded outline.
        holder.card.setClipToOutline(true);
        // Each press reads the game on show at the time of the press.
        holder.resume.setOnClickListener(v -> {
            if (game != null) {
                listener.onResume(game);
            }
        });
        holder.quit.setOnClickListener(v -> {
            if (game != null) {
                listener.onQuit(game);
            }
        });
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        bound = holder;
        LibraryApp shown = game;
        if (shown == null) {
            return;
        }
        applyWindow(holder);
        boolean sameGame = holder.appId == shown.appId() && holder.library == library;
        holder.appId = shown.appId();
        holder.library = library;
        holder.title.setText(shown.app.getAppName());
        Context context = holder.itemView.getContext();
        holder.tag.setText(hostName != null
                ? context.getString(R.string.library_running_on, hostName)
                : context.getString(R.string.library_continue));
        boolean hasMeta = meta != null && !meta.isEmpty();
        holder.meta.setText(hasMeta ? meta : null);
        holder.meta.setVisibility(hasMeta ? View.VISIBLE : View.GONE);
        // With a meta line the title keeps to one line, so the card stays as tall as its cover.
        holder.title.setMaxLines(hasMeta ? 1 : 2);
        holder.resume.setContentDescription(context.getString(R.string.library_resume) + ", "
                + shown.app.getAppName());
        if (!sameGame) {
            cancel(holder);
            holder.coverBitmap = null;
            holder.backdropFromBackground = false;
            holder.backgroundRequested = false;
            holder.cover.bind(asCover(shown), ArtState.LOADING);
            showBackdrop(holder, null, false);
        }
        requestArt(holder, shown, !sameGame);
        requestBackground(holder, shown);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position, @NonNull List<Object> payloads) {
        if (payloads.isEmpty() || game == null) {
            onBindViewHolder(holder, position);
            return;
        }
        bound = holder;
        if (payloads.contains(PAYLOAD_ART)) {
            requestArt(holder, game, true);
            requestBackground(holder, game);
        }
    }

    @Override
    public void onViewRecycled(@NonNull Holder holder) {
        cancel(holder);
        holder.appId = Integer.MIN_VALUE;
        holder.library = null;
        holder.backgroundRequested = false;
        if (bound == holder) {
            bound = null;
        }
        super.onViewRecycled(holder);
    }

    // The cover tile shows the game, not its state: the hero already says it is running, and a
    // hidden game is not dimmed here, since it is the one thing on screen to go back to.
    private static LibraryApp asCover(LibraryApp game) {
        return new LibraryApp(game.app, false, false);
    }

    private void requestArt(final Holder holder, final LibraryApp shown, boolean force) {
        if (!force && holder.request != null && holder.request.isPending()) {
            return;
        }
        cancel(holder);
        HostLibrary source = library;
        if (source == null || source.isClosed()) {
            return;
        }
        final int appId = shown.appId();
        int width = holder.cover.getLayoutParams().width;
        holder.request = source.loadArt(shown.app, width > 0 ? width : holder.coverWidthPx,
                (bitmap, state) -> {
                    if (holder.appId != appId) {
                        return;
                    }
                    holder.cover.bind(asCover(shown), state);
                    holder.cover.setArt(state == ArtState.ART ? bitmap : null);
                    holder.coverBitmap = state == ArtState.ART ? bitmap : null;
                    if (!holder.backdropFromBackground) {
                        showBackdrop(holder, holder.coverBitmap, false);
                    }
                });
    }

    // Called on every bind and art refresh, so it fetches at most once per game per holder:
    // HostLibrary does not merge two fetches of the same background.
    private void requestBackground(final Holder holder, LibraryApp shown) {
        final HostLibrary source = library;
        if (source == null || source.isClosed()) {
            return;
        }
        final int appId = shown.appId();
        Bitmap cached = source.cachedBackground(appId);
        if (cached != null) {
            showBackdrop(holder, cached, true);
            return;
        }
        if (!hasBackground || holder.backdropFromBackground || holder.backgroundRequested) {
            return;
        }
        holder.backgroundRequested = true;
        source.loadBackground(appId, (id, background) -> {
            // The library too, not the id alone: two hosts' running apps can share an id (each
            // Sunshine's Desktop entry), and the fetch cannot be cancelled across a switch.
            if (holder.appId == id && holder.appId == appId && holder.library == source) {
                showBackdrop(holder, background, true);
            }
        });
    }

    private static void showBackdrop(Holder holder, @Nullable Bitmap bitmap, boolean isBackground) {
        holder.backdropFromBackground = isBackground && bitmap != null;
        holder.backdrop.setImageBitmap(bitmap);
        int visibility = bitmap != null ? View.VISIBLE : View.GONE;
        holder.backdrop.setVisibility(visibility);
        holder.scrim.setVisibility(visibility);
    }

    private static void cancel(Holder holder) {
        if (holder.request != null) {
            holder.request.cancel();
            holder.request = null;
        }
    }

    /**
     * The cover's width, in dp, for a window: the compact one's on a phone in portrait, otherwise
     * the full one, narrowed when the card it makes (a 3:4 cover and the card's padding above and
     * below it) would take more than a third of the window's height. Never under
     * {@link #MIN_COVER_WIDTH_DP}: the title and the buttons beside it need that much height.
     *
     * @param windowHeightDp the window's height; 0 when unknown, which keeps the full width
     */
    static int coverWidthDp(boolean compact, int windowHeightDp, int fullWidthDp, int paddingDp) {
        if (compact) {
            return COMPACT_COVER_WIDTH_DP;
        }
        if (windowHeightDp <= 0) {
            return fullWidthDp;
        }
        int coverHeight = windowHeightDp / HEIGHT_SHARE - 2 * paddingDp;
        int width = coverHeight * 3 / 4;
        return Math.max(MIN_COVER_WIDTH_DP, Math.min(fullWidthDp, width));
    }

    // The cover and the play glyph by the window; see coverWidthDp and COMPACT_COVER_WIDTH_DP.
    private static void applyWindow(Holder holder) {
        Context context = holder.itemView.getContext();
        Resources res = context.getResources();
        Configuration config = res.getConfiguration();
        boolean compact = WindowClass.from(config.screenWidthDp) == WindowClass.COMPACT;
        float density = res.getDisplayMetrics().density;
        int widthDp = coverWidthDp(compact, config.screenHeightDp,
                Math.round(res.getDimension(R.dimen.hero_cover_width) / density),
                Math.round(res.getDimension(R.dimen.hero_padding) / density));
        int width = Math.round(widthDp * density);
        ViewGroup.LayoutParams lp = holder.cover.getLayoutParams();
        if (lp.width != width) {
            lp.width = width;
            holder.cover.setLayoutParams(lp);
        }
        holder.coverWidthPx = width;
        // The column's floor is the cover's 3:4 height, so the buttons sit at the cover's foot.
        int coverHeight = width * 4 / 3;
        if (holder.column.getMinimumHeight() != coverHeight) {
            holder.column.setMinimumHeight(coverHeight);
        }
        if (compact) {
            holder.resume.setIcon(null);
        } else if (holder.resume.getIcon() == null) {
            holder.resume.setIconResource(R.drawable.ic_resume);
        }
    }

    /** The game's meta line ("2025 · Roguelike · 88%"); null without metadata for it. */
    @Nullable
    private static String metaFor(NvApp app, @Nullable Map<String, AppMetadata> metadata) {
        String uuid = app.getAppUUID();
        if (metadata == null || uuid == null || uuid.isEmpty()) {
            return null;
        }
        String line = CompanionState.Browsing.metaLine(metadata.get(uuid.toUpperCase(Locale.ROOT)));
        return line.isEmpty() ? null : line;
    }

    private static boolean hasBackground(NvApp app, @Nullable Map<String, AppMetadata> metadata) {
        String uuid = app.getAppUUID();
        if (metadata == null || uuid == null || uuid.isEmpty()) {
            return false;
        }
        AppMetadata entry = metadata.get(uuid.toUpperCase(Locale.ROOT));
        return entry != null && entry.hasBackground();
    }

    private static boolean equal(@Nullable String a, @Nullable String b) {
        return a == null ? b == null : a.equals(b);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final View column;
        final ImageView backdrop;
        final View scrim;
        final GameTileView cover;
        final TextView tag;
        final TextView title;
        final TextView meta;
        final MaterialButton resume;
        final MaterialButton quit;

        int appId = Integer.MIN_VALUE;
        @Nullable
        HostLibrary library;
        int coverWidthPx;
        @Nullable
        CachedAppAssetLoader.Request request;
        @Nullable
        Bitmap coverBitmap;
        // Whether the backdrop is the game's own background art, which the cover never replaces.
        boolean backdropFromBackground;
        // Whether this game's background art has been fetched for, so a refresh does not fetch twice.
        boolean backgroundRequested;

        Holder(@NonNull View view) {
            super(view);
            card = view.findViewById(R.id.heroCard);
            column = view.findViewById(R.id.heroColumn);
            backdrop = view.findViewById(R.id.heroBackdrop);
            scrim = view.findViewById(R.id.heroScrim);
            cover = view.findViewById(R.id.heroCover);
            tag = view.findViewById(R.id.heroTag);
            title = view.findViewById(R.id.heroTitle);
            meta = view.findViewById(R.id.heroMeta);
            resume = view.findViewById(R.id.heroResume);
            quit = view.findViewById(R.id.heroQuit);
            // GameTileView makes itself a focusable, clickable tile; here it is only a picture.
            cover.setFocusable(false);
            cover.setClickable(false);
        }
    }
}
