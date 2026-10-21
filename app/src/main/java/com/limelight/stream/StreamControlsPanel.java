package com.limelight.stream;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.DisplayCutout;
import android.view.InputDevice;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.content.ContextCompat;

import com.limelight.R;
import com.limelight.binding.video.PerfStats;
import com.limelight.stats.StatsFormat;
import com.limelight.stats.StatsText;

/**
 * The phone's in-stream controls: a panel on the right edge of the stream, over a scrim, after
 * the approved Stream mock. It is a {@link ControlsSurface}: the router (GameMenu) opens the
 * {@link StreamActions} tree on it, and each page is drawn here in turn.
 *
 * <h3>Why a view and not a Dialog</h3>
 *
 * The centred sheet it replaces was a Dialog, so it took the window focus from the game while up,
 * and every action that pressed keys at the PC had to wait for the focus to come back. The panel
 * is a view inside the game's own window: the game keeps the focus, and nothing has to wait. The
 * price is that the panel gets no keys of its own, since the stream's handlers see them first;
 * {@link ControlsInputRouter} hands them over as {@link Nav}, and {@link #onNav} moves focus by
 * hand inside the {@code FocusTrapLayout}, which keeps it from wandering back to the stream.
 *
 * <h3>Pages</h3>
 *
 * <pre>
 *   Main           header · 4 tiles · list rows · Disconnect | Quit game
 *   any other      header · ‹ page title · its rows (or why there are none)
 * </pre>
 *
 * Every page, the Mouse mode and the empty Server commands included, is drawn here: nothing
 * opens a window of its own over the stream.
 *
 * <p>The mock's header also has a small box-art thumbnail beside the title. It is left out on
 * purpose for now: the header is text only, and the thumbnail is a separate follow-up. The mock's
 * muted trailing value on some rows (Bitrate "80 Mbps · adaptive") is not a column of its own
 * either: a row ends in a chevron or a check, and the value, where one matters, is in the label
 * ("Bitrate: 80 Mbps (adaptive)", from {@link StreamActions}).
 *
 * <h3>Size</h3>
 *
 * {@code min(360dp, 0.85 x the window's width)}, taken from the configuration on every change:
 * the game handles its own rotations, so no resource qualifier would ever re-resolve.
 *
 * <p>Main thread only, like any view.
 */
public class StreamControlsPanel extends FrameLayout implements ControlsSurface {

    /** The share of the window's width the panel may take on a narrow window. */
    private static final float MAX_WIDTH_FRACTION = 0.85f;
    /** Numbers older than this are not shown on opening: they would describe another moment. */
    private static final long STATS_FRESH_MS = 2500;
    private static final long SLIDE_MS = 180;

    private final View scrim;
    private final ViewGroup sheet;
    private final TextView title;
    private final TextView subtitle;
    private final TextView statsLine;
    private final ScrollView scroll;
    private final LinearLayout page;
    private final LinearLayout danger;
    private final LayoutInflater inflater;
    private final int sheetPadding;

    @Nullable
    private Pages pages;

    @Nullable
    private String gameName;
    @Nullable
    private String hostName;
    private long streamStartUptimeMs;
    @Nullable
    private PerfStats stats;
    private long statsUptimeMs;
    // The display cutout's safe insets, kept from the last window insets, added to the padding.
    private int cutoutRight;
    private int cutoutTop;
    private int cutoutBottom;
    // Moves the header's "N min" on at each new minute of the session while the panel is open.
    // The once-a-second perf updates would redraw it too, but only while they arrive: a stalled
    // stream, or a host that sends none, would leave the length frozen at the opening minute.
    private final Runnable sessionTick = this::onSessionTick;

    public StreamControlsPanel(Context context) {
        this(context, null);
    }

    public StreamControlsPanel(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        inflater = LayoutInflater.from(context);
        inflater.inflate(R.layout.view_stream_controls_panel, this, true);

        scrim = findViewById(R.id.controlsScrim);
        sheet = findViewById(R.id.controlsSheet);
        title = findViewById(R.id.controlsTitle);
        subtitle = findViewById(R.id.controlsSubtitle);
        statsLine = findViewById(R.id.controlsStats);
        scroll = findViewById(R.id.controlsScroll);
        page = findViewById(R.id.controlsPage);
        danger = findViewById(R.id.controlsDanger);
        sheetPadding = getResources().getDimensionPixelSize(R.dimen.controls_panel_padding);

        // A tap on the game around the panel closes it, as a tap outside any sheet does.
        scrim.setOnClickListener(v -> close());
        setVisibility(GONE);
        applyWidth(getResources().getConfiguration());
    }

    /** Wire the panel to the router. Until then it never opens. */
    public void bind(@NonNull Callbacks callbacks) {
        pages = new Pages(callbacks);
    }

    /** The game and the PC the header names; either may be null and is then left out. */
    public void setSession(@Nullable String gameName, @Nullable String hostName) {
        this.gameName = gameName;
        this.hostName = hostName;
        renderHeader();
    }

    /** When the stream started (uptime), for the header's session length; 0 while unknown. */
    public void setStreamStart(long uptimeMs) {
        streamStartUptimeMs = uptimeMs;
        renderHeader();
        if (isOpen()) {
            scheduleSessionTick();
        }
    }

    /** A new second of numbers. Kept while closed, so a panel opened now shows them at once. */
    public void bindStats(@NonNull PerfStats stats) {
        this.stats = stats;
        statsUptimeMs = SystemClock.uptimeMillis();
        if (isOpen()) {
            renderHeader();
        }
    }

    // --- ControlsSurface -------------------------------------------------------------------

    @Override
    public void open(@NonNull StreamAction tree) {
        if (pages != null) {
            pages.open(tree);
        }
    }

    @Override
    public void openAt(@NonNull StreamAction tree, @Nullable StreamAction.Id page) {
        if (pages != null) {
            pages.openAt(tree, page);
        }
    }

    @Nullable
    @Override
    public StreamAction shownPage() {
        return pages != null ? pages.shownPage() : null;
    }

    @Override
    public void back() {
        if (pages != null) {
            pages.back();
        }
    }

    @Override
    public void close() {
        if (pages != null) {
            pages.close();
        } else {
            hideNow();
        }
    }

    @Override
    public boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    @Override
    public boolean onNav(@NonNull Nav nav) {
        if (!isOpen()) {
            return false;
        }
        View focused = sheet.findFocus();
        switch (nav) {
            case UP:
                moveFocus(focused, View.FOCUS_UP);
                break;
            case DOWN:
                moveFocus(focused, View.FOCUS_DOWN);
                break;
            case LEFT:
                moveFocus(focused, View.FOCUS_LEFT);
                break;
            case RIGHT:
                moveFocus(focused, View.FOCUS_RIGHT);
                break;
            case ACTIVATE:
                if (focused != null) {
                    focused.performClick();
                } else {
                    focusFirst(true);
                }
                break;
            case BACK:
                back();
                break;
            case CLOSE:
                close();
                break;
        }
        return true;
    }

    /**
     * A mouse's wheel and other pointer motion stop here while the panel is up. The scrim takes
     * taps and hover only, so without this a scroll the panel's own list did not use would fall
     * through to the stream view behind and scroll the game. Its children see the event first,
     * so the list still scrolls under the pointer.
     */
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (isOpen() && (event.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0) {
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    // --- Layout ----------------------------------------------------------------------------

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // A rotation with the panel open: same page, new width.
        applyWidth(newConfig);
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        // The stream draws under a cutout; the panel's rows must not.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            DisplayCutout cutout = insets.getDisplayCutout();
            cutoutRight = cutout != null ? cutout.getSafeInsetRight() : 0;
            cutoutTop = cutout != null ? cutout.getSafeInsetTop() : 0;
            cutoutBottom = cutout != null ? cutout.getSafeInsetBottom() : 0;
            sheet.setPadding(sheetPadding, dp(12) + cutoutTop, sheetPadding + cutoutRight,
                    dp(12) + cutoutBottom);
        }
        return super.onApplyWindowInsets(insets);
    }

    private void applyWidth(Configuration config) {
        int max = getResources().getDimensionPixelSize(R.dimen.controls_panel_max_width);
        int width = max;
        if (config.screenWidthDp > 0) {
            width = Math.min(max, Math.round(dp(config.screenWidthDp) * MAX_WIDTH_FRACTION));
        }
        ViewGroup.LayoutParams lp = sheet.getLayoutParams();
        if (lp.width != width) {
            lp.width = width;
            sheet.setLayoutParams(lp);
        }
    }

    // --- Drawing ---------------------------------------------------------------------------

    private void show() {
        if (isOpen()) {
            return;
        }
        if (SystemClock.uptimeMillis() - statsUptimeMs > STATS_FRESH_MS) {
            stats = null;
        }
        renderHeader();
        setVisibility(VISIBLE);
        scheduleSessionTick();

        // In from the edge, the way the swipe that opened it went.
        int width = sheet.getLayoutParams().width;
        sheet.setTranslationX(width > 0 ? width : sheet.getWidth());
        sheet.animate().translationX(0).setDuration(SLIDE_MS).start();
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(SLIDE_MS).start();
    }

    private void hideNow() {
        // At once, not animated: what closed the panel (a leaf) usually wants the screen now.
        sheet.animate().cancel();
        scrim.animate().cancel();
        removeCallbacks(sessionTick);
        sheet.setTranslationX(0);
        scrim.setAlpha(1f);
        page.removeAllViews();
        danger.removeAllViews();
        setVisibility(GONE);
    }

    private void onSessionTick() {
        if (isOpen()) {
            renderHeader();
            scheduleSessionTick();
        }
    }

    /** Wakes up just after the session's next whole minute, when the header's length changes. */
    private void scheduleSessionTick() {
        removeCallbacks(sessionTick);
        if (streamStartUptimeMs <= 0) {
            // No start yet: setStreamStart redraws the header and calls back here when it comes.
            return;
        }
        long elapsed = Math.max(0, SystemClock.uptimeMillis() - streamStartUptimeMs);
        postDelayed(sessionTick, 60000 - elapsed % 60000 + 50);
    }

    @Override
    protected void onDetachedFromWindow() {
        // The game is going away: a pending tick would only hold the view.
        removeCallbacks(sessionTick);
        super.onDetachedFromWindow();
    }

    private void renderHeader() {
        Resources res = getResources();
        title.setText(gameName);
        title.setVisibility(gameName != null ? VISIBLE : GONE);

        String session = streamStartUptimeMs > 0
                ? sessionLength(res, SystemClock.uptimeMillis() - streamStartUptimeMs) : null;
        String sub = hostName != null && session != null
                ? res.getString(R.string.stream_controls_subtitle, hostName, session)
                : hostName != null ? hostName : session;
        subtitle.setText(sub);
        subtitle.setVisibility(sub != null ? VISIBLE : GONE);

        PerfStats shown = stats;
        statsLine.setText(shown != null ? statsText(res, shown) : null);
        statsLine.setVisibility(shown != null ? VISIBLE : GONE);
    }

    private void renderPage(@NonNull StreamAction shown, boolean root) {
        show();
        page.removeAllViews();
        danger.removeAllViews();
        Resources res = getResources();

        if (!root) {
            // The way back, as the page's first row, so it is a stop for the d-pad as much as
            // a target for a thumb.
            View back = addRow(page, shown.label(res), R.drawable.ic_arrow_back, 0, null);
            ((TextView) back.findViewById(R.id.controlsRowLabel)).setTypeface(null,
                    android.graphics.Typeface.BOLD);
            back.setContentDescription(res.getString(R.string.stream_controls_back));
            back.setOnClickListener(v -> back());
        }

        LinearLayout tiles = null;
        boolean afterRow = false;
        if (shown.children != null) {
            for (StreamAction child : shown.children) {
                switch (child.group) {
                    case TILE:
                        if (tiles == null) {
                            tiles = new LinearLayout(getContext());
                            tiles.setOrientation(LinearLayout.HORIZONTAL);
                            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
                            lp.bottomMargin = dp(8);
                            page.addView(tiles, lp);
                        }
                        addTile(tiles, child);
                        break;
                    case DANGER:
                        addDanger(child);
                        break;
                    default:
                        // The mock's hairline between list rows, never after the last one.
                        if (afterRow) {
                            addDivider();
                        }
                        addEntryRow(child);
                        afterRow = true;
                        break;
                }
            }
        }

        if (shown.children == null || shown.children.isEmpty()) {
            addEmpty(shown);
        }
        danger.setVisibility(danger.getChildCount() > 0 ? VISIBLE : GONE);

        scroll.scrollTo(0, 0);
        focusFirst(false);
    }

    private void addTile(LinearLayout row, StreamAction action) {
        View tile = inflater.inflate(R.layout.item_controls_tile, row, false);
        ImageView icon = tile.findViewById(R.id.controlsTileIcon);
        TextView label = tile.findViewById(R.id.controlsTileLabel);
        CharSequence text = action.label(getResources());
        label.setText(text);
        if (action.iconRes != 0) {
            icon.setImageResource(action.iconRes);
        } else {
            icon.setVisibility(GONE);
        }
        tile.setActivated(Boolean.TRUE.equals(action.checked));
        tile.setContentDescription(text);
        tile.setOnClickListener(v -> pages.choose(action));

        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) tile.getLayoutParams();
        if (row.getChildCount() > 0) {
            lp.leftMargin = getResources().getDimensionPixelSize(R.dimen.controls_tile_gap);
        }
        row.addView(tile, lp);
    }

    private void addEntryRow(StreamAction action) {
        int trailing = 0;
        ColorStateList tint = null;
        if (action.isPage()) {
            trailing = R.drawable.ic_chevron_right;
            tint = ColorStateList.valueOf(ContextCompat.getColor(getContext(), R.color.content_muted));
        } else if (Boolean.TRUE.equals(action.checked)) {
            trailing = R.drawable.ic_check;
            tint = ColorStateList.valueOf(ContextCompat.getColor(getContext(), R.color.signal));
        }
        View row = addRow(page, action.label(getResources()), action.iconRes, trailing, tint);
        row.setOnClickListener(v -> pages.choose(action));
    }

    private void addDivider() {
        View rule = new View(getContext());
        rule.setBackgroundColor(ContextCompat.getColor(getContext(), R.color.divider));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.bottomMargin = dp(2);
        page.addView(rule, lp);
    }

    private View addRow(ViewGroup parent, CharSequence text, int iconRes, int trailingRes,
                        @Nullable ColorStateList trailingTint) {
        View row = inflater.inflate(R.layout.item_controls_row, parent, false);
        ((TextView) row.findViewById(R.id.controlsRowLabel)).setText(text);
        if (iconRes != 0) {
            ImageView icon = row.findViewById(R.id.controlsRowIcon);
            icon.setImageResource(iconRes);
            icon.setVisibility(VISIBLE);
        }
        if (trailingRes != 0) {
            ImageView end = row.findViewById(R.id.controlsRowTrailing);
            end.setImageResource(trailingRes);
            end.setImageTintList(trailingTint);
            end.setVisibility(VISIBLE);
        }
        parent.addView(row);
        return row;
    }

    private void addDanger(StreamAction action) {
        boolean quit = action.id == StreamAction.Id.QUIT;
        TextView button = new TextView(getContext());
        button.setText(action.label(getResources()));
        button.setGravity(android.view.Gravity.CENTER);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setSingleLine(true);
        button.setTextColor(ContextCompat.getColor(getContext(),
                quit ? R.color.danger : R.color.content_primary));
        button.setBackgroundResource(quit ? R.drawable.controls_danger_bg : R.drawable.controls_tile_bg);
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(v -> pages.choose(action));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                getResources().getDimensionPixelSize(R.dimen.menu_row_min_height), 1f);
        if (danger.getChildCount() > 0) {
            lp.leftMargin = getResources().getDimensionPixelSize(R.dimen.controls_tile_gap);
        }
        danger.addView(button, lp);
    }

    /** A page with nothing on it says why, rather than opening blank. */
    private void addEmpty(StreamAction shown) {
        if (shown.id != StreamAction.Id.SERVER_COMMANDS) {
            return;
        }
        TextView heading = new TextView(getContext());
        heading.setText(R.string.game_dialog_title_server_cmd_empty);
        heading.setTextColor(ContextCompat.getColor(getContext(), R.color.content_primary));
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        heading.setPadding(dp(12), dp(12), dp(12), dp(4));
        page.addView(heading);

        TextView message = new TextView(getContext());
        message.setText(R.string.game_dialog_message_server_cmd_empty);
        message.setTextColor(ContextCompat.getColor(getContext(), R.color.content_muted));
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        message.setPadding(dp(12), 0, dp(12), dp(12));
        page.addView(message);
    }

    // --- Focus -----------------------------------------------------------------------------

    /** A move asked for by the pad or keys (through {@link #onNav}); main thread only. */
    private void moveFocus(@Nullable View focused, int direction) {
        if (focused == null) {
            focusFirst(true);
            return;
        }
        // The trap answers the search, so the next stop is always inside the panel.
        View next = focused.focusSearch(direction);
        if (next != null && next != focused) {
            takeFocus(next, direction, true);
        }
    }

    /**
     * Land on the page's first stop. When a page is drawn ({@code fromNav} false) this is
     * refused in touch mode, which is right: a ring that appears under a finger only gets in the
     * way. When the pad asks ({@code fromNav} true) it must not be refused, see
     * {@link #takeFocus}.
     */
    private void focusFirst(boolean fromNav) {
        View first = firstFocusable(page);
        if (first == null) {
            first = firstFocusable(danger);
        }
        if (first != null) {
            takeFocus(first, View.FOCUS_DOWN, fromNav);
        }
    }

    /**
     * Focus {@code target}, leaving touch mode first when the pad asked for it. On a phone the
     * window is nearly always in touch mode (the stream was touched last), and the rows are not
     * focusable in it. The system would leave touch mode on a d-pad key, but none ever comes:
     * the game takes the hat and stick as motion, so no d-pad keys are made from them, and A,
     * B and Start are not navigation keys. Without this the panel could not be driven by a pad.
     */
    private void takeFocus(@NonNull View target, int direction, boolean fromNav) {
        if (fromNav && isInTouchMode()) {
            target.requestFocusFromTouch();
        } else {
            target.requestFocus(direction);
        }
    }

    @Nullable
    private static View firstFocusable(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != VISIBLE) {
                continue;
            }
            if (child.isFocusable()) {
                return child;
            }
            if (child instanceof ViewGroup) {
                View inner = firstFocusable((ViewGroup) child);
                if (inner != null) {
                    return inner;
                }
            }
        }
        return null;
    }

    // --- Formatting ------------------------------------------------------------------------

    /**
     * "1h 12m", or "12 min" under the hour: the Stats page's words, so a duration reads the same
     * wherever the panel shows one. Also the companion strip's, so both agree.
     */
    public static String sessionLength(Resources res, long elapsedMs) {
        return StatsFormat.durationMinutes(Math.max(0, elapsedMs) / 60000, StatsText.words(res));
    }

    /**
     * "1080p60 · 8 ms · 80 Mbps": what the stream is, how far away, how much. Also the companion
     * controls sheet's header, so the two surfaces describe a stream the same way, and the rate
     * as {@link StreamRate} writes it everywhere.
     */
    public static String statsText(Resources res, PerfStats stats) {
        String mode = modeLabel(res, stats.width, stats.height, Math.round(stats.totalFps));
        if (stats.hasBandwidth()) {
            return res.getString(R.string.stream_controls_stats, mode,
                    stats.networkLatencyMs, StreamRate.mbps(res, stats));
        }
        return res.getString(R.string.stream_controls_stats_no_bandwidth, mode,
                stats.networkLatencyMs);
    }

    /**
     * Size and rate together, as video modes are written: a line count runs into the rate
     * ("1080p60"), while a name would run into it unreadably ("4K120"), so it gets a space.
     */
    @VisibleForTesting
    static String modeLabel(Resources res, int width, int height, int fps) {
        boolean named = Math.min(width, height) >= 2160;
        return res.getString(named ? R.string.stream_controls_mode_named
                : R.string.stream_controls_mode_lines, resolutionLabel(res, width, height), fps);
    }

    /**
     * The stream's size by its short side, the way people name it: "4K" from 2160 lines up,
     * "1440p", "1080p" and so on below. The short side, so a portrait stream reads the same.
     */
    @VisibleForTesting
    static String resolutionLabel(Resources res, int width, int height) {
        int lines = Math.min(width, height);
        // Kept in step with modeLabel, which spaces the named sizes.
        if (lines >= 2160) {
            return res.getString(R.string.resolution_4k);
        }
        return res.getString(R.string.stream_controls_resolution_lines, lines);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    /** The page stack every surface shares, drawing into this panel. */
    private final class Pages extends PagedSurface {

        Pages(@NonNull Callbacks callbacks) {
            super(callbacks);
        }

        @Override
        protected void render(@NonNull StreamAction shown) {
            renderPage(shown, isOnRootPage());
        }

        @Override
        protected void dismissView() {
            hideNow();
        }

        @Override
        public boolean isOpen() {
            return StreamControlsPanel.this.isOpen();
        }

        @Override
        public boolean onNav(@NonNull Nav nav) {
            return StreamControlsPanel.this.onNav(nav);
        }

        void choose(@NonNull StreamAction action) {
            activate(action);
        }
    }
}
