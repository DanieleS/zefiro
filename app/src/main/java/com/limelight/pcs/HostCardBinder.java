package com.limelight.pcs;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.limelight.R;
import com.limelight.computers.HostEntry;
import com.limelight.grid.assets.CachedAppAssetLoader;
import com.limelight.grid.assets.DiskAssetLoader;
import com.limelight.grid.assets.ScaledBitmap;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.PairingManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Draws one host onto a {@code pc_card_item}: the state as a dot and a line, the game it is
 * running, that game's art, and the one action the host is waiting for.
 *
 * <p>It is what used to be the body of the old PC list's adapter's {@code onBindViewHolder},
 * taken out so the old list and the PCs section could draw a host the same way while both
 * existed. It knows nothing about lists: an adapter owns the holders and the clicks on the card,
 * this owns what a card shows.
 *
 * <h3>Art</h3>
 *
 * When the host is in a game the card borrows that game's art: its cover in the leading slot and
 * its hero image bled across the card behind the text. The cover is only ever read from the disk
 * cache the library fills, and only off the main thread; a host whose library has never been
 * opened shows the host glyph. The hero image arrives on the {@link HostEntry} itself, fetched by
 * whoever publishes the entries; until it does, or for a game that has none, the cover stands in.
 *
 * <pre>
 *   bind ──▶ cover cached? ──yes──▶ apply now
 *                 │no
 *                 ▼
 *          cover executor: read the disk cache ──▶ main handler: cache it, apply if the card
 *                                                   still shows that host and game (coverKey)
 * </pre>
 *
 * Main thread only, except the disk read, which runs on the binder's own executor. Call
 * {@link #release()} when the list it draws goes away.
 */
public final class HostCardBinder {

    /** A tap on the card's action, which is a different thing from a tap on the card. */
    public interface OnActionClickListener {
        void onClick(HostEntry host, int action);
    }

    public static final int ACTION_NONE = 0;
    public static final int ACTION_WAKE = 1;
    public static final int ACTION_PAIR = 2;
    public static final int ACTION_RESUME = 3;

    /**
     * The colour of a host's status dot: the signal only for a host that is online and paired,
     * the one state in which it can be played on. The Library's host chip and host sheet draw
     * their dots with it too, so a host looks the same wherever it is listed.
     */
    @ColorRes
    public static int statusColor(@NonNull ComputerDetails details) {
        if (details.state != ComputerDetails.State.ONLINE) {
            return R.color.content_faint;
        }
        return details.pairState == PairingManager.PairState.PAIRED
                ? R.color.signal : R.color.content_muted;
    }

    /** What the dot means, in words: checking, offline, not paired or online. */
    @StringRes
    public static int statusLabel(@NonNull ComputerDetails details) {
        if (details.state == ComputerDetails.State.UNKNOWN) {
            return R.string.pcview_status_checking;
        }
        if (details.state != ComputerDetails.State.ONLINE) {
            return R.string.pcview_status_offline;
        }
        return details.pairState == PairingManager.PairState.PAIRED
                ? R.string.pcview_status_online : R.string.pcview_status_unpaired;
    }

    /** The views of one card, looked up once per holder. */
    public static final class CardViews {
        final View card;
        final TextView name;
        final TextView status;
        final TextView running;
        final View dot;
        final ImageView glyph;
        final ImageView cover;
        final ImageView ambient;
        final View ambientScrim;
        final ProgressBar spinner;
        final MaterialButton action;

        /** The host and game this card's art was last asked for; a late read for another is dropped. */
        String coverKey;

        public CardViews(@NonNull View card) {
            this.card = card;
            name = card.findViewById(R.id.pc_card_name);
            status = card.findViewById(R.id.pc_card_status);
            running = card.findViewById(R.id.pc_card_running);
            dot = card.findViewById(R.id.pc_card_dot);
            glyph = card.findViewById(R.id.pc_card_glyph);
            cover = card.findViewById(R.id.pc_card_cover);
            ambient = card.findViewById(R.id.pc_card_ambient);
            ambientScrim = card.findViewById(R.id.pc_card_ambient_scrim);
            spinner = card.findViewById(R.id.pc_card_spinner);
            action = card.findViewById(R.id.pc_card_action);

            // The art bleeds to the card's edge, so the card has to clip to its own corners.
            card.setClipToOutline(true);
            card.setFocusable(true);
            card.setClickable(true);
            card.setLongClickable(true);
        }
    }

    private final Context context;
    private final DiskAssetLoader assetLoader;
    private final ExecutorService coverExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // A handful of hosts, one cover each. This is not the library.
    private final LruCache<String, Bitmap> coverCache = new LruCache<>(8);

    public HostCardBinder(@NonNull Context context) {
        this.context = context;
        this.assetLoader = new DiskAssetLoader(context);
    }

    /**
     * The one thing this host is waiting for. Everything else lives in the host's actions.
     *
     * <p>The gamepad's X button asks for this too, so that it does what the card's button says.
     */
    public static int actionFor(@NonNull HostEntry host) {
        final ComputerDetails details = host.details;
        final boolean online = details.state == ComputerDetails.State.ONLINE;
        final boolean paired = details.pairState == PairingManager.PairState.PAIRED;
        final boolean checking = details.state == ComputerDetails.State.UNKNOWN;

        if (online && paired && details.runningGameId != 0) {
            return ACTION_RESUME;
        }
        if (online && !paired) {
            return ACTION_PAIR;
        }
        if (!online && !checking && details.macAddress != null) {
            return ACTION_WAKE;
        }
        return ACTION_NONE;
    }

    /**
     * Draws the host onto the card.
     *
     * @param gamepadPresent with a gamepad the card holds focus in touch mode, as the library's
     *                       tiles do, and its action wears the letter of the button that fires it,
     *                       because a d-pad cannot reach a button inside the focused card.
     *                       Touch-only devices get neither: no ring stranded by a tap, and no
     *                       letter for a button the user does not have.
     * @param actionListener the card's action button; null leaves the button inert
     */
    public void bind(@NonNull CardViews views, @NonNull final HostEntry host, boolean gamepadPresent,
                     @Nullable final OnActionClickListener actionListener) {
        final ComputerDetails details = host.details;

        views.name.setText(details.name);

        final boolean online = details.state == ComputerDetails.State.ONLINE;
        final boolean paired = details.pairState == PairingManager.PairState.PAIRED;
        final boolean checking = details.state == ComputerDetails.State.UNKNOWN;
        final boolean inGame = online && paired && details.runningGameId != 0;

        // The state, as a dot and a line: online and paired is the only state worth the signal.
        final int dotColor = statusColor(details);
        final StringBuilder status = new StringBuilder(context.getString(statusLabel(details)));

        if (online && details.activeAddress != null) {
            status.append(" · ").append(details.activeAddress.address);
        }

        views.dot.setBackgroundTintList(
                ColorStateList.valueOf(ContextCompat.getColor(context, dotColor)));
        views.status.setText(status);

        views.spinner.setVisibility(checking ? View.VISIBLE : View.GONE);
        views.glyph.setVisibility(checking || inGame ? View.GONE : View.VISIBLE);
        views.glyph.setAlpha(online ? 1.0f : 0.4f);

        if (inGame) {
            String running = host.runningAppName;
            views.running.setText(running != null
                    ? context.getString(R.string.pcview_status_running, running)
                    : context.getString(R.string.pcview_status_running_unnamed));
            views.running.setVisibility(View.VISIBLE);
            bindArt(views, host);
        }
        else {
            views.running.setVisibility(View.GONE);
            clearArt(views);
        }

        final int action = actionFor(host);
        if (action == ACTION_NONE) {
            views.action.setVisibility(View.GONE);
            views.action.setOnClickListener(null);
        }
        else {
            views.action.setVisibility(View.VISIBLE);
            views.action.setText(actionLabel(action));

            // With a gamepad, the button names the button: X on the focused card does this.
            views.action.setIcon(gamepadPresent
                    ? ContextCompat.getDrawable(context, R.drawable.ic_gamepad_x)
                    : null);

            views.action.setOnClickListener(v -> {
                if (actionListener != null) {
                    actionListener.onClick(host, action);
                }
            });
        }

        views.card.setFocusableInTouchMode(gamepadPresent);
    }

    /** Stops the cover reads; covers asked for before this are dropped. Main thread. */
    public void release() {
        coverExecutor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        coverCache.evictAll();
    }

    private static int actionLabel(int action) {
        switch (action) {
            case ACTION_PAIR:
                return R.string.pcview_action_pair;
            case ACTION_WAKE:
                return R.string.pcview_action_wake;
            case ACTION_RESUME:
            default:
                return R.string.pcview_action_resume;
        }
    }

    private static void clearArt(CardViews views) {
        views.coverKey = null;
        views.cover.setVisibility(View.GONE);
        views.ambient.setVisibility(View.GONE);
        views.ambientScrim.setVisibility(View.GONE);
    }

    private void bindArt(final CardViews views, final HostEntry host) {
        final ComputerDetails details = host.details;
        final String key = details.uuid + ":" + details.runningGameId;
        views.coverKey = key;

        final Bitmap cover = coverCache.get(key);
        applyArt(views, cover, host.runningBackground);

        if (cover != null) {
            return;
        }

        final int appId = details.runningGameId;
        try {
            coverExecutor.execute(() -> {
                ScaledBitmap loaded = assetLoader.loadBitmapFromCache(
                        new CachedAppAssetLoader.LoaderTuple(details, new NvApp(null, null, appId, false)), 1);
                if (loaded == null || loaded.bitmap == null) {
                    return;
                }

                final Bitmap bitmap = loaded.bitmap;
                mainHandler.post(() -> {
                    coverCache.put(key, bitmap);

                    // The card may have been recycled onto another host while we were reading.
                    if (key.equals(views.coverKey)) {
                        applyArt(views, bitmap, host.runningBackground);
                    }
                });
            });
        } catch (RejectedExecutionException e) {
            // Released: the list is going away, and the glyph it shows now is as good as any.
        }
    }

    private static void applyArt(CardViews views, Bitmap cover, Bitmap background) {
        if (cover != null) {
            views.cover.setImageBitmap(cover);
            views.cover.setVisibility(View.VISIBLE);
            views.glyph.setVisibility(View.GONE);
        }
        else {
            // A game we have no cover for still gets the host glyph rather than an empty slot.
            views.cover.setVisibility(View.GONE);
            views.glyph.setVisibility(View.VISIBLE);
        }

        Bitmap ambient = background != null ? background : cover;
        if (ambient != null) {
            views.ambient.setImageBitmap(ambient);
            views.ambient.setVisibility(View.VISIBLE);
            views.ambientScrim.setVisibility(View.VISIBLE);
        }
        else {
            views.ambient.setVisibility(View.GONE);
            views.ambientScrim.setVisibility(View.GONE);
        }
    }
}
