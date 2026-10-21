package com.limelight.pcs;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.limelight.R;
import com.limelight.computers.HostEntry;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;

/**
 * Draws one host onto an {@code item_pc_tile}: the state as a badge on the icon box and as a dot
 * and a line, and the one action the host is waiting for.
 *
 * <p>The tile reads its state from {@link HostStatus}, which tells more states apart than a list
 * does (playing, not paired). It shows no art: the running game's cover and hero image belong to
 * the focused PC's details on the Thor's bottom screen, not to a tile a quarter of the width.
 * It knows nothing about lists: the adapter owns the holders and the clicks on the tile, this
 * owns what a tile shows. Its buttons are wired by the adapter too, once per holder, so that a
 * tap reads the entry the tile shows at the time of the tap, not the one it was drawn with: a
 * poll that changes nothing on the tile (the address, the games count) does not redraw it.
 *
 * <pre>
 *   kind       badge and dot      status line              action
 *   PLAYING    signal             Playing · Sea of Stars   Resume (filled)
 *   ONLINE     green              Online                   none: A opens the Library
 *   UNPAIRED   hollow ring        Not paired               Pair (tonal)
 *   OFFLINE    grey, dimmed       Offline                  Wake (tonal), with a MAC
 *   CHECKING   grey               Checking…                none
 * </pre>
 *
 * Main thread only.
 */
public final class HostTileBinder {

    public static final int ACTION_NONE = 0;
    public static final int ACTION_WAKE = 1;
    public static final int ACTION_PAIR = 2;
    public static final int ACTION_RESUME = 3;

    // An offline host's glyph, as the mock dims it: still there, plainly asleep.
    private static final float ASLEEP_GLYPH_ALPHA = 0.45f;

    /**
     * The colour of a host's status dot: green only for a host that is online and paired, the
     * one state in which it can be played on. The Library's host chip and host sheet draw
     * their dots with it too, so a host looks the same wherever it is listed.
     *
     * <p>This is the colour of a host in a list, where "can it be played on" is the only
     * question. A PC tile tells more states apart (playing, not paired) and reads its colours
     * from {@link HostStatus} instead.
     */
    @ColorRes
    public static int statusColor(@NonNull ComputerDetails details) {
        if (details.state != ComputerDetails.State.ONLINE) {
            return R.color.content_faint;
        }
        return details.pairState == PairingManager.PairState.PAIRED
                ? R.color.status_online : R.color.content_muted;
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

    /**
     * The one thing this host is waiting for. Everything else lives in the host's actions.
     *
     * <p>The gamepad's X button asks for this too, so that it does what the tile's button says.
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

    /** An action's name, as the tile's button and the X hint say it. */
    @StringRes
    public static int actionLabel(int action) {
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

    @DrawableRes
    private static int actionIcon(int action) {
        switch (action) {
            case ACTION_PAIR:
                return R.drawable.ic_link;
            case ACTION_WAKE:
                return R.drawable.ic_power;
            case ACTION_RESUME:
            default:
                return R.drawable.ic_resume;
        }
    }

    /** The views of one tile, looked up once per holder. */
    public static final class TileViews {
        final View tile;
        final ImageView glyph;
        final View badge;
        final TextView name;
        final View dot;
        final TextView status;
        final MaterialButton action;
        final ImageButton more;

        public TileViews(@NonNull View tile) {
            this.tile = tile;
            glyph = tile.findViewById(R.id.pcTileGlyph);
            badge = tile.findViewById(R.id.pcTileBadge);
            name = tile.findViewById(R.id.pcTileName);
            dot = tile.findViewById(R.id.pcTileDot);
            status = tile.findViewById(R.id.pcTileStatus);
            action = tile.findViewById(R.id.pcTileAction);
            more = tile.findViewById(R.id.pcTileMore);
            // ImageButton sets itself focusable after reading the layout, so focusable="false"
            // there is lost; the d-pad must stop on the tile, never on the overflow.
            more.setFocusable(false);
        }
    }

    private final Context context;

    public HostTileBinder(@NonNull Context context) {
        this.context = context;
    }

    /**
     * Draws the host onto the tile.
     *
     * @param gamepadPresent with a gamepad the tile holds focus in touch mode, as the library's
     *                       tiles do, and its action wears the letter of the button that fires
     *                       it, because a d-pad cannot reach a button inside the focused tile.
     *                       Touch-only devices get neither: no ring stranded by a tap, and no
     *                       letter for a button the user does not have.
     */
    public void bind(@NonNull TileViews views, @NonNull HostEntry host, boolean gamepadPresent) {
        final ComputerDetails details = host.details;
        final HostStatus.Kind kind = HostStatus.kind(details);
        final boolean asleep = kind == HostStatus.Kind.OFFLINE;
        final boolean quiet = asleep || kind == HostStatus.Kind.CHECKING;

        views.name.setText(details.name);
        views.name.setTextColor(ContextCompat.getColor(context,
                asleep ? R.color.content_secondary : R.color.content_primary));
        views.glyph.setAlpha(asleep ? ASLEEP_GLYPH_ALPHA : 1f);

        // A string without a placeholder ignores the game's name; see HostStatus.textRes.
        views.status.setText(context.getString(HostStatus.textRes(host), host.runningAppName));
        views.status.setTextColor(ContextCompat.getColor(context,
                quiet ? R.color.content_muted : R.color.content_secondary));

        int color = ContextCompat.getColor(context, kind.color);
        views.dot.setBackgroundResource(kind.hollow ? R.drawable.pc_status_ring : R.drawable.pc_status_dot);
        views.dot.setBackgroundTintList(ColorStateList.valueOf(color));
        views.badge.setBackground(badge(color, kind.hollow));

        bindAction(views, host, gamepadPresent);

        views.more.setContentDescription(context.getString(R.string.pcs_more_for, details.name));

        views.tile.setFocusableInTouchMode(gamepadPresent);
    }

    private void bindAction(TileViews views, HostEntry host, boolean gamepadPresent) {
        int action = actionFor(host);
        if (action == ACTION_NONE) {
            views.action.setVisibility(View.GONE);
            return;
        }
        views.action.setVisibility(View.VISIBLE);
        views.action.setText(actionLabel(action));

        // Resume is the way back into a game, what the signal is spent on; Wake and Pair are tonal.
        boolean filled = action == ACTION_RESUME;
        int fill = ContextCompat.getColor(context, filled ? R.color.signal : R.color.surface_container);
        int ink = ContextCompat.getColor(context, filled ? R.color.on_signal : R.color.content_primary);
        views.action.setBackgroundTintList(ColorStateList.valueOf(fill));
        views.action.setTextColor(ink);
        views.action.setIconTint(ColorStateList.valueOf(ink));

        // With a gamepad, the button names the button: X on the focused tile does this.
        views.action.setIconResource(gamepadPresent ? R.drawable.ic_gamepad_x : actionIcon(action));
    }

    /**
     * The badge on the icon box's corner: the state's colour, cut out of the box by a ring in the
     * tile's own colour, which follows the tile's focus (the badge duplicates its state). A host
     * that is not paired gets a ring on the tile's colour, like the status line's hollow dot.
     */
    private Drawable badge(int color, boolean hollow) {
        ColorStateList tileColour = ContextCompat.getColorStateList(context, R.color.pc_tile_badge_ring);
        float density = context.getResources().getDisplayMetrics().density;
        int cutWidth = context.getResources().getDimensionPixelSize(R.dimen.pc_tile_badge_ring);

        GradientDrawable cut = new GradientDrawable();
        cut.setShape(GradientDrawable.OVAL);
        cut.setColor(tileColour);

        GradientDrawable state = new GradientDrawable();
        state.setShape(GradientDrawable.OVAL);
        if (hollow) {
            state.setColor(tileColour);
            state.setStroke(Math.max(1, Math.round(1.5f * density)), color);
        } else {
            state.setColor(color);
        }

        LayerDrawable badge = new LayerDrawable(new Drawable[]{cut, state});
        badge.setLayerInset(1, cutWidth, cutWidth, cutWidth, cutWidth);
        return badge;
    }
}
