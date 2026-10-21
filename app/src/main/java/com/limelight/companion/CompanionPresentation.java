package com.limelight.companion;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Html;
import android.text.Spanned;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.input.virtual_controller.keyboard.KeyBoardLayoutController;
import com.limelight.binding.video.PerfStats;
import com.limelight.nvstream.http.Achievement;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.stats.AchievementToast;
import com.limelight.stats.HostSession;
import com.limelight.stream.ControlsGrid;
import com.limelight.stream.ControlsSurface;
import com.limelight.stream.StreamAction;
import com.limelight.stream.StreamControlsPanel;
import com.limelight.stream.StreamHealth;
import com.limelight.ui.MaxHeightScrollView;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The companion surface rendered on the secondary panel.
 *
 * While a stream is running it draws the game's own telemetry view when one is installed, and the
 * stream's stats when there is none (or when the user swaps them in), over a strip of its own that
 * carries the stats button and Menu; Menu raises the in-game controls as a sheet here, so the top
 * screen shows only the game and its HUD (see the approved StreamTelemetry mock). While the
 * main screen is browsing it draws what the d-pad is on up there ({@link CompanionState.Browsing}):
 * a game's details with Play, or Resume and Quit game for the one running, or a host's state,
 * over a row of the known PCs. The rest of the time it shows nothing but the app name. A screen
 * with a lower half of its own puts it here as a {@link CompanionPage}, drawn by
 * {@link CompanionPageHost} over all of the browsing surface.
 *
 * <p>Those browsing controls are touch only. The window is never focusable (see
 * {@link #onCreate}), so the d-pad stays on the main screen, which offers the same actions; a tap
 * goes back to it through {@link CompanionDisplayManager#setBrowsingActions}.
 *
 * It never polls: {@link CompanionState} pushes, roughly once a second, and only while it is on
 * screen. Telemetry frames are the exception, and deliberately bypass that path: they arrive up to
 * twenty times a second and go straight into the view already on screen, rather than redrawing the
 * surface to deliver a number.
 */
public class CompanionPresentation extends android.app.Presentation
        implements CompanionState.Listener, TelemetryStream.Listener {

    /** Matches the side padding on the idle surface in @layout/companion_surface. */
    private static final int IDLE_PADDING_DP = 32;

    private final CompanionState state;

    private View idleView;
    private LinearLayout idleColumnView;
    private TextView idleLabelView;
    private TextView idleTitleView;
    private TextView idleMetaView;
    private TextView idleDescriptionView;
    private ScrollView idleDescriptionScrollView;
    /** The title we last drew, so a re-render for late-arriving art doesn't rewind the reader. */
    private String renderedTitle;
    /** The description HTML we last parsed, for the same reason. */
    private String renderedDescriptionHtml;
    private DescriptionImages descriptionImages;
    /** Play, or Resume and Quit game; under the meta line while a game is browsed. */
    private View actionsView;
    private TextView playLabelView;
    private View quitView;
    /** The hosts row, and the chips in it. */
    private View hostsView;
    private LinearLayout hostItemsView;
    /** The hosts the row was last built for, so a poll that changed nothing rebuilds nothing. */
    private List<CompanionState.HostChip> renderedHosts;
    /** What the browsing controls were last drawn for: the one a tap acts on. */
    private CompanionState.Browsing shownBrowsing;
    // A screen's lower half, drawn over the browsing surface when the state has one.
    private CompanionPageHost pageHost;
    private ImageView backdropView;
    private View backdropScrimView;
    /** The streaming surface: the view or the stats, over the strip. */
    private View streamView;
    private View statsView;
    /** Where the telemetry view goes. Empty unless a view is on screen. */
    private FrameLayout telemetryContainer;
    /**
     * The telemetry view, or null when none is on screen. Created only when a game has a view and
     * destroyed as soon as it is not needed: a WebView is a renderer process and a Chromium
     * instance, and the panel is re-hosted on every activity change, most of which have no view.
     */
    private WebView telemetryView;
    /** The HTML currently loaded, so a redraw does not restart a running view from scratch. */
    private String loadedTelemetryHtml;
    /** Whether the loaded view has finished loading and can be handed frames. */
    private boolean telemetryViewReady;
    /** The stream this panel is subscribed to, so a new or ended one can be told apart from it. */
    private TelemetryStream followedStream;

    /**
     * How often a view is handed the whole picture again, whether it asked or not.
     *
     * <p>A view builds its state by applying diffs, and nothing stops it from losing that state —
     * one exception in the middle of a render and it carries on drawing numbers that stopped being
     * true. There is deliberately no channel for it to ask for a resync, so the resync is simply
     * unconditional. Short enough that a broken view rights itself before anyone reads it as fact,
     * and free: the picture is already in memory here, so this costs no network at all.
     */
    private static final long TELEMETRY_RESYNC_MS = 5000;

    private final Runnable telemetryResync = new Runnable() {
        @Override
        public void run() {
            if (telemetryView == null || telemetryView.getVisibility() != View.VISIBLE) {
                return;
            }
            TelemetryStream stream = state.getTelemetryStream();
            if (stream != null) {
                JSONObject picture = stream.getPicture();
                if (picture != null) {
                    deliver("snapshot", picture);
                }
            }
            telemetryView.postDelayed(this, TELEMETRY_RESYNC_MS);
        }
    };
    // The stats: what the stream is, then the nine tiles.
    private View statsHeaderView;
    private TextView statsTitleView;
    private TextView statsCodecView;
    private TextView resolutionView;
    private TextView fpsView;
    private TextView latencyView;
    private TextView decodeTimeView;
    private TextView hostLatencyView;
    private TextView lossView;
    private TextView bandwidthView;
    private TextView droppedView;

    // The strip under the view or the stats.
    private View statButton;
    private View statDot;
    private TextView statText;
    private View sessionView;
    private View sessionDot;
    private TextView sessionText;
    private View gameButton;

    // The in-game controls sheet, when the controls are drawn here rather than over the game.
    private View menuOverlay;
    private TextView menuTitleView;
    private TextView menuStatsView;
    /** The page's title, for the header when the stream's game has no name to give it. */
    private String menuPageTitle;
    private LinearLayout menuItemsView;
    private LinearLayout menuDangerView;
    private MaxHeightScrollView menuScrollView;
    /** Every entry of the page, tiles, rows and the danger row alike, in drawing order. */
    private final List<View> menuCells = new ArrayList<>();
    private final List<Runnable> menuActions = new ArrayList<>();
    /** Where the pad goes from each of {@link #menuCells}; null while the sheet is down. */
    private ControlsGrid menuGrid;
    private int menuSelectedIndex = -1;

    // The keyboard for a text field focused on the host, created the first time one is needed.
    // It is the same on-screen PC keyboard the full-keyboard button raises over the stream, and
    // it types by touch alone: this window never takes input focus, so the system keyboard,
    // which needs a focused editor, cannot be raised here without pulling the gamepad over too.
    private KeyBoardLayoutController textKeyboard;
    // Set while we hide the keyboard ourselves, so only the user closing it is reported
    private boolean hidingTextKeyboard;

    public CompanionPresentation(Context outerContext, Display display, CompanionState state) {
        super(outerContext, display);
        this.state = state;
    }

    /** Raise or dismiss the keyboard for a text field focused on the host. */
    void setTextKeyboardVisible(boolean visible) {
        if (visible) {
            if (textKeyboard == null) {
                FrameLayout container = findViewById(R.id.companionKeyboard);
                if (container == null) {
                    return;
                }
                textKeyboard = new KeyBoardLayoutController(container, getContext(),
                        PreferenceConfiguration.readPreferences(getContext()));
                textKeyboard.setViewCallbacks(shown -> {
                    if (!shown && !hidingTextKeyboard) {
                        // Closed from its own hide key
                        CompanionDisplayManager.onTextKeyboardDismissed();
                    }
                });
                textKeyboard.refreshLayout();
            }
            textKeyboard.show();
        } else if (isTextKeyboardVisible()) {
            hidingTextKeyboard = true;
            try {
                textKeyboard.hide();
            } finally {
                hidingTextKeyboard = false;
            }
        }
    }

    boolean isTextKeyboardVisible() {
        return textKeyboard != null && textKeyboard.isKeyboardVisible();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // The panel never takes key focus: the gamepad stays with the game on the main screen,
        // which routes it to the menu drawn here while one is up. This keeps the pad from ever
        // moving onto this panel's display — where, on the way back, it would land on whatever
        // sits behind the panel rather than returning to the game.
        if (getWindow() != null) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }

        setContentView(R.layout.companion_surface);
        idleView = findViewById(R.id.companionIdle);
        idleColumnView = findViewById(R.id.companionIdleColumn);
        idleLabelView = findViewById(R.id.companionIdleLabel);
        idleTitleView = findViewById(R.id.companionIdleTitle);
        idleMetaView = findViewById(R.id.companionIdleMeta);
        idleDescriptionView = findViewById(R.id.companionIdleDescription);
        idleDescriptionScrollView = findViewById(R.id.companionIdleDescriptionScroll);
        descriptionImages = new DescriptionImages(idleDescriptionView, this::onDescriptionImageLoaded);
        actionsView = findViewById(R.id.companionActions);
        playLabelView = findViewById(R.id.companionPlayLabel);
        quitView = findViewById(R.id.companionQuit);
        hostsView = findViewById(R.id.companionHosts);
        hostItemsView = findViewById(R.id.companionHostItems);
        // Each acts on the browsing the buttons were drawn for, not whatever the state holds by
        // the time the tap lands: what the user saw is what they meant.
        findViewById(R.id.companionPlay).setOnClickListener(v -> {
            CompanionState.Browsing browsing = shownBrowsing;
            if (browsing != null && browsing.running) {
                CompanionDisplayManager.onBrowsingResume(browsing);
            } else {
                CompanionDisplayManager.onBrowsingPlay(browsing);
            }
        });
        quitView.setOnClickListener(v -> CompanionDisplayManager.onBrowsingQuit(shownBrowsing));
        backdropView = findViewById(R.id.companionBackdrop);
        backdropScrimView = findViewById(R.id.companionBackdropScrim);
        streamView = findViewById(R.id.companionStream);
        statsView = findViewById(R.id.companionStats);
        telemetryContainer = findViewById(R.id.companionTelemetryContainer);
        statsHeaderView = findViewById(R.id.companionStatsHeader);
        statsTitleView = findViewById(R.id.companionStatsTitle);
        statsCodecView = findViewById(R.id.companionStatsCodec);
        resolutionView = findViewById(R.id.companionStatResolution);
        fpsView = findViewById(R.id.companionStatFps);
        latencyView = findViewById(R.id.companionStatLatency);
        decodeTimeView = findViewById(R.id.companionStatDecode);
        hostLatencyView = findViewById(R.id.companionStatHost);
        lossView = findViewById(R.id.companionStatLoss);
        bandwidthView = findViewById(R.id.companionStatBitrate);
        droppedView = findViewById(R.id.companionStatDropped);

        statButton = findViewById(R.id.companionStatButton);
        statDot = findViewById(R.id.companionStatDot);
        statText = findViewById(R.id.companionStatText);
        sessionView = findViewById(R.id.companionSession);
        sessionDot = findViewById(R.id.companionSessionDot);
        sessionText = findViewById(R.id.companionSessionText);
        gameButton = findViewById(R.id.companionGameButton);
        // The strip under a view has room for two numbers; its button swaps all of them in for
        // the view and back, and "Game" goes back too. Kept in CompanionState, so the choice
        // survives the panel being re-hosted (a Settings round-trip) for the rest of the stream.
        statButton.setOnClickListener(v ->
                state.setStatsOverTelemetry(!state.isStatsOverTelemetry()));
        gameButton.setOnClickListener(v -> state.setStatsOverTelemetry(false));
        // The panel's own Menu raises the controls, the same as the gamepad's combo does.
        findViewById(R.id.companionMenuButton)
                .setOnClickListener(v -> CompanionDisplayManager.requestOpenMenu());

        menuOverlay = findViewById(R.id.companionMenu);
        menuTitleView = findViewById(R.id.companionMenuTitle);
        menuStatsView = findViewById(R.id.companionMenuStats);
        menuItemsView = findViewById(R.id.companionMenuItems);
        menuDangerView = findViewById(R.id.companionMenuDanger);
        menuScrollView = findViewById(R.id.companionMenuScroll);
        // The sheet's header and danger row stay in sight; only the tiles and rows scroll.
        menuScrollView.setMaxHeightPx((int) (getContext().getResources().getDisplayMetrics().heightPixels * 0.6f));
        // A tap on the scrim around the sheet closes it, as a tap outside any sheet does.
        menuOverlay.setOnClickListener(v -> hideMenu());
        pageHost = new CompanionPageHost(findViewById(R.id.companionPage),
                LayoutInflater.from(getContext()));
    }

    @Override
    protected void onStart() {
        super.onStart();

        state.setListener(this);
        render();
    }

    @Override
    protected void onStop() {
        // Only give up the listener if it is still ours: during a re-host the replacement panel
        // has already registered by the time this one stops.
        state.clearListener(this);
        if (followedStream != null) {
            followedStream.removeListener(this);
            followedStream = null;
        }
        // A dismissed panel is not coming back, so its WebView goes now rather than whenever the
        // finalizer gets to it, taking the activity context with it.
        destroyTelemetryView();
        super.onStop();
    }

    @Override
    public void onCompanionStateChanged() {
        render();
    }

    /**
     * Subscribe to whichever stream the state holds now.
     *
     * <p>Frames come straight from the stream rather than through CompanionState, because they
     * arrive far too often to redraw the surface for. Checked on every redraw rather than once on
     * the way in, because the two rarely line up: the panel is hosted when the game's activity
     * resumes, and the stream only starts once the session has connected, some seconds later.
     */
    private void followTelemetryStream() {
        TelemetryStream current = state.getTelemetryStream();
        if (current == followedStream) {
            return;
        }
        if (followedStream != null) {
            followedStream.removeListener(this);
        }
        followedStream = current;
        if (current != null) {
            current.addListener(this);
        }
    }

    private void render() {
        if (statsView == null) {
            return;
        }
        followTelemetryStream();

        // One surface per content state. The controls belong to a running game, so the sheet
        // goes as soon as the stream does.
        CompanionState.Content content = state.getContent();
        boolean streaming = content == CompanionState.Content.STREAMING;
        if (!streaming) {
            hideMenu();
        }

        // The browsing controls belong to browsing alone: a tap on Play mid-stream, or on an idle
        // panel whose main screen has gone, would act for a screen that is not there.
        CompanionState.Browsing browsing =
                content == CompanionState.Content.BROWSING ? state.getBrowsing() : null;
        // A page replaces the whole browsing surface, its buttons and hosts row included; one
        // that fails to draw leaves the spotlight in its place.
        boolean paged = showPage(
                content == CompanionState.Content.BROWSING ? state.getPage() : null);
        showBrowsingControls(paged ? null : browsing,
                browsing != null && !paged ? state.getHosts() : null);

        switch (content) {
            case BROWSING:
                if (paged) {
                    return;
                }
                if (browsing == null) {
                    showSpotlight(null, null, null, null);
                } else {
                    showSpotlight(browsing.title, browsing.meta, browsing.descriptionHtml,
                            browsing.backdrop);
                    // "Focused" names a game the d-pad is on, with its place in the grid when
                    // See all told it; a host is the fallback, unlabelled.
                    idleLabelView.setText(browsing.hasPosition()
                            ? getContext().getString(R.string.companion_focused_position,
                                    browsing.position + 1, browsing.count)
                            : getContext().getString(R.string.companion_focused));
                    idleLabelView.setVisibility(browsing.kind == CompanionState.Browsing.Kind.GAME
                            && browsing.title != null && !browsing.title.isEmpty()
                            ? View.VISIBLE : View.GONE);
                }
                return;
            case STREAMING:
                showStream();
                return;
            case IDLE:
            default:
                showSpotlight(null, null, null, null);
        }
    }

    /**
     * Draw a screen's lower half in place of the spotlight, or take the page away for null.
     *
     * @return whether a page is on screen
     */
    private boolean showPage(CompanionPage page) {
        if (page == null || !pageHost.show(page)) {
            pageHost.hide();
            return false;
        }
        hideTelemetryView();
        idleView.setVisibility(View.GONE);
        streamView.setVisibility(View.GONE);
        statsView.setVisibility(View.GONE);
        setBackdrop(null);
        descriptionImages.setAnimating(false);
        return true;
    }

    /**
     * Lock the telemetry view down to what a renderer needs and nothing else.
     *
     * <p>The HTML comes from outside this app, so the settings here are the boundary. JavaScript is
     * on because a view without it cannot render anything; everything else that would let the page
     * reach beyond its own markup is off. In particular there is no
     * {@code addJavascriptInterface}: frames go <em>in</em> via {@code evaluateJavascript} and
     * nothing comes back out, so a view has no channel to this app at all.
     */
    private void configureTelemetryView() {
        WebSettings settings = telemetryView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setDomStorageEnabled(false);
        settings.setGeolocationEnabled(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        // A view is a renderer, not a browser: it has no business navigating, and a page that tries
        // is a page doing something other than drawing telemetry.
        settings.setSupportMultipleWindows(false);
        // An opaque origin does not stop a page from loading subresources: an <img>, a <script> or
        // a no-cors fetch would still reach the network, since the app holds INTERNET. A view could
        // then send live telemetry off the device, or pull in code nobody reviewed. This turns
        // network loads off; shouldInterceptRequest below refuses whatever else gets through.
        settings.setBlockNetworkLoads(true);
        telemetryView.setBackgroundColor(Color.TRANSPARENT);
        telemetryView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }

            // The overload above is API 24+. Below that, this is the one the WebView calls, and
            // without it a view could navigate the panel anywhere.
            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                // A self-contained view only ever loads what is already inside it.
                if ("data".equals(scheme) || "blob".equals(scheme) || "about".equals(scheme)) {
                    return null;
                }
                return new WebResourceResponse("text/plain", "utf-8", 403, "Forbidden",
                        null, new ByteArrayInputStream(new byte[0]));
            }

            // The view's renderer can crash or be killed for memory. On API 26+ the default answer
            // is to kill this app with it, which would end the stream over a panel. Handled here,
            // the panel drops the view and falls back to the stats.
            @Override
            @TargetApi(Build.VERSION_CODES.O)
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                LimeLog.warning("Telemetry: the view's renderer "
                        + (detail.didCrash() ? "crashed" : "was killed to free memory"));
                if (view == telemetryView) {
                    // Not loaded again. Whatever took the renderer down would most likely do it
                    // again, so for the rest of this stream that view gives way to the stats —
                    // remembered in the state, so a re-hosted panel does not try it once more.
                    String crashed = loadedTelemetryHtml;
                    destroyTelemetryView();
                    state.markTelemetryViewCrashed(crashed);
                    render();
                } else {
                    view.destroy();
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (view != telemetryView) {
                    return;
                }
                telemetryViewReady = true;
                // The view missed every frame that arrived while it was loading, so it starts from
                // the accumulated picture rather than from the next diff — which on its own would
                // tell it almost nothing. From here the same delivery repeats on a timer, so a view
                // that later loses its state does not stay wrong.
                view.removeCallbacks(telemetryResync);
                telemetryResync.run();
            }
        });
    }

    /**
     * Show the game's own telemetry view, in place of the stats.
     *
     * <p>Loaded with a null base URL, which gives the page an opaque origin: it cannot read files
     * and cannot be reached. Network loads are blocked in {@link #configureTelemetryView}.
     * Everything it draws has to arrive through {@link #deliver}.
     */
    private void showTelemetryView(String html) {
        statsView.setVisibility(View.GONE);
        telemetryContainer.setVisibility(View.VISIBLE);
        if (telemetryView == null) {
            telemetryView = new WebView(getContext());
            configureTelemetryView();
            telemetryContainer.addView(telemetryView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }

        if (html.equals(loadedTelemetryHtml)) {
            return;
        }
        loadedTelemetryHtml = html;
        telemetryViewReady = false;
        telemetryView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    private void hideTelemetryView() {
        // Destroyed rather than left paused: a view holds a dead game's numbers, and the next game
        // is entitled to a view that has never seen them.
        destroyTelemetryView();
    }

    private void destroyTelemetryView() {
        if (telemetryContainer != null) {
            telemetryContainer.setVisibility(View.GONE);
        }
        if (telemetryView == null) {
            return;
        }
        WebView dead = telemetryView;
        telemetryView = null;
        loadedTelemetryHtml = null;
        telemetryViewReady = false;
        dead.removeCallbacks(telemetryResync);
        telemetryContainer.removeView(dead);
        dead.destroy();
    }

    // --- TelemetryStream.Listener ---

    @Override
    public void onTelemetrySnapshot(JSONObject snapshot) {
        deliver("snapshot", snapshot);
    }

    @Override
    public void onTelemetryDiff(JSONObject diff) {
        deliver("diff", diff);
    }

    @Override
    public void onTelemetryDetached() {
        deliver("detached", new JSONObject());
    }

    /**
     * Hand one frame to the view.
     *
     * <p>Dropped silently until the page has finished loading, because a call into a page that has
     * not run its script yet goes nowhere. Nothing is lost by it: {@code onPageFinished} follows up
     * with the full picture.
     */
    private void deliver(String kind, JSONObject body) {
        if (telemetryView == null || !telemetryViewReady) {
            return;
        }
        // The payload crosses as a quoted string that the page parses, rather than being
        // interpolated into the expression as raw JSON. Pasting JSON into JavaScript source is very
        // nearly correct, which is the problem: U+2028 and U+2029 are legal inside a JSON string and
        // were line terminators in JavaScript, so a game whose text contains one would turn a value
        // into a syntax error, or worse. Quoting sidesteps the whole question.
        String script = "window.scry && window.scry.onFrame && window.scry.onFrame("
                + JSONObject.quote(kind) + ", JSON.parse(" + JSONObject.quote(body.toString()) + "));";
        telemetryView.evaluateJavascript(script, null);
    }

    /**
     * The streaming surface: the game's view when it has one (unless the stats were asked for in
     * its place), the stats otherwise, and the strip under either. Never the library spotlight:
     * that game is not the one being played.
     */
    private void showStream() {
        idleView.setVisibility(View.GONE);
        // The hero backdrop belongs to the browsing surface, not the stream.
        setBackdrop(null);
        // Nor is there any point animating a description nobody can see while a game is running.
        descriptionImages.setAnimating(false);
        streamView.setVisibility(View.VISIBLE);

        String view = state.getUsableTelemetryView();
        boolean hasView = view != null;
        boolean statsOver = hasView && state.isStatsOverTelemetry();
        PerfStats stats = state.getStats();
        CompanionState.StreamSession session = state.getStreamSession();
        if (hasView && !statsOver) {
            showTelemetryView(view);
        } else {
            // Destroyed rather than hidden while the stats are up: the swap back loads the view
            // afresh, and onPageFinished hands it the whole picture again.
            hideTelemetryView();
            statsView.setVisibility(View.VISIBLE);
            bindStats(stats, session);
        }
        bindStrip(hasView, statsOver, stats, session);
        if (isMenuOpen()) {
            bindMenuHeader();
        }
    }

    /** The nine tiles, or a dash in each until the first second of numbers arrives. */
    private void bindStats(PerfStats stats, CompanionState.StreamSession session) {
        Context context = getContext();
        String title = session != null ? joinNames(session.game, session.host) : null;
        String codec = session != null ? session.codec : null;
        statsTitleView.setText(title);
        statsCodecView.setText(codec);
        statsHeaderView.setVisibility(title != null || codec != null ? View.VISIBLE : View.GONE);

        String none = context.getString(R.string.companion_value_unavailable);
        if (stats == null) {
            for (TextView value : new TextView[]{resolutionView, fpsView, latencyView,
                    decodeTimeView, hostLatencyView, lossView, bandwidthView, droppedView}) {
                value.setText(none);
            }
            return;
        }
        resolutionView.setText(context.getString(R.string.companion_resolution, stats.width, stats.height));
        fpsView.setText(context.getString(R.string.companion_value_fps, stats.totalFps));
        latencyView.setText(context.getString(R.string.companion_value_ms_whole, stats.networkLatencyMs));
        decodeTimeView.setText(context.getString(R.string.companion_value_ms, stats.decodeTimeMs));
        // Not every host reports it; a dash says so without moving the other tiles.
        hostLatencyView.setText(stats.hasHostProcessingLatency
                ? context.getString(R.string.companion_value_ms, stats.avgHostProcessingLatencyMs)
                : none);
        // A window with no frames has 0/0 for its loss, which is no number at all.
        lossView.setText(Float.isNaN(stats.networkDropsPercent) ? none
                : context.getString(R.string.companion_value_percent, stats.networkDropsPercent));
        bandwidthView.setText(formatBandwidth(stats));
        // Frames that arrived in the last second but never reached the screen.
        droppedView.setText(String.valueOf(
                Math.max(0, Math.round(stats.receivedFps - stats.renderedFps))));
    }

    /**
     * The strip. Under a view: the stats button, lit while the stats are in the view's place, and
     * "Game" while they are. Under the stats of a game with no view there is nothing to swap, so
     * the button gives way to the session's length. Menu is drawn by the layout, always there.
     */
    private void bindStrip(boolean hasView, boolean statsOver, PerfStats stats,
                           CompanionState.StreamSession session) {
        Context context = getContext();
        ColorStateList health = ColorStateList.valueOf(
                ContextCompat.getColor(context, healthColor(stats, session)));
        statButton.setVisibility(hasView ? View.VISIBLE : View.GONE);
        sessionView.setVisibility(hasView ? View.GONE : View.VISIBLE);
        gameButton.setVisibility(statsOver ? View.VISIBLE : View.GONE);

        if (hasView) {
            statButton.setActivated(statsOver);
            ViewCompat.setBackgroundTintList(statDot, health);
            statText.setText(stats != null
                    ? context.getString(R.string.companion_strip_stats,
                            Math.round(stats.totalFps), stats.networkLatencyMs)
                    : context.getString(R.string.companion_value_unavailable));
            // Said by what a tap will do, since the button is a toggle with no words of its own.
            statButton.setContentDescription(context.getString(statsOver
                    ? R.string.companion_strip_show_view : R.string.companion_strip_show_stats));
        } else {
            ViewCompat.setBackgroundTintList(sessionDot, health);
            // Moves on with each second of numbers, which keep coming for as long as the panel
            // can show them (Game measures for it whenever the panel exists).
            sessionText.setText(session != null && session.startUptimeMs > 0
                    ? StreamControlsPanel.sessionLength(context.getResources(),
                            SystemClock.uptimeMillis() - session.startUptimeMs)
                    : null);
        }
    }

    /** The HUD's health dot, judged the same way; faint until there is anything to judge. */
    private static int healthColor(PerfStats stats, CompanionState.StreamSession session) {
        if (stats == null) {
            return R.color.content_faint;
        }
        // The host's "poor connection" reports reach the top screen's HUD only, so this dot reads
        // the numbers alone; they show the same trouble a moment later.
        switch (StreamHealth.classify(stats, session != null ? session.targetFps : 0, false)) {
            case BAD:
                return R.color.health_bad;
            case WARN:
                return R.color.health_warn;
            case GOOD:
            default:
                return R.color.health_good;
        }
    }

    /** "Hades II · Taverna", or whichever of the two is known; null for neither. */
    private String joinNames(String game, String host) {
        if (game != null && host != null) {
            return getContext().getString(R.string.companion_stats_title, game, host);
        }
        return game != null ? game : host;
    }

    /**
     * The non-stats surface: a spotlight on the given game or host, or a bare panel when passed
     * nothing. Callers hand it the content to draw rather than a mode to interpret, so "nothing
     * to show" and "show this game" are the same code path with different arguments.
     */
    private void showSpotlight(String title, String metaLine, String html, Bitmap backdrop) {
        hideTelemetryView();
        if (idleTitleView == null) {
            return;
        }

        streamView.setVisibility(View.GONE);
        statsView.setVisibility(View.GONE);
        idleView.setVisibility(View.VISIBLE);
        setBackdrop(backdrop);

        // A new game starts its description from the top; a re-render of the same one (the hero
        // art landing, say) leaves the reader where they were.
        boolean spotlightChanged = !TextUtils.equals(title, renderedTitle);
        renderedTitle = title;

        idleLabelView.setVisibility(View.GONE);
        if (title == null || title.isEmpty()) {
            // Nothing to show: the app name alone, in the middle of the panel.
            idleColumnView.setGravity(Gravity.CENTER);
            idleTitleView.setText(R.string.app_label);
            idleMetaView.setVisibility(View.GONE);
            idleDescriptionScrollView.setVisibility(View.GONE);
            return;
        }
        // A game or a host reads from the top left, its actions at the foot of the column.
        idleColumnView.setGravity(Gravity.TOP | Gravity.START);

        idleTitleView.setText(title);

        if (metaLine == null || metaLine.isEmpty()) {
            idleMetaView.setVisibility(View.GONE);
        } else {
            idleMetaView.setText(metaLine);
            idleMetaView.setVisibility(View.VISIBLE);
        }

        // Re-parsing the HTML on every push would be wasted work, and re-setting the text would
        // fight the reader's scroll position, so only touch it when the description itself changed.
        if (!TextUtils.equals(html, renderedDescriptionHtml)) {
            renderedDescriptionHtml = html;
            // The images belong to the description being replaced: let them go with it.
            descriptionImages.reset();
            idleDescriptionView.setText(toRichText(html));
        } else {
            // Coming back from a stream, the images are still the right ones: play them again.
            descriptionImages.setAnimating(true);
        }
        // From the text drawn, not only when it changes: the bare panel hid it in between.
        // Without one, the actions follow the title and meta line straight on rather than
        // sitting at the foot of an empty column, which read as a panel still loading.
        boolean hasDescription = idleDescriptionView.getText().length() > 0;
        idleDescriptionScrollView.setVisibility(hasDescription ? View.VISIBLE : View.GONE);
        if (spotlightChanged) {
            idleDescriptionScrollView.scrollTo(0, 0);
        }
    }

    // Show or hide the hero backdrop and its legibility scrim together.
    private void setBackdrop(android.graphics.Bitmap background) {
        if (backdropView == null) {
            return;
        }
        if (background != null) {
            backdropView.setImageBitmap(background);
            backdropView.setVisibility(View.VISIBLE);
            backdropScrimView.setVisibility(View.VISIBLE);
        } else {
            backdropView.setImageDrawable(null);
            backdropView.setVisibility(View.GONE);
            backdropScrimView.setVisibility(View.GONE);
        }
    }

    /** The name of the PC with this uuid in the hosts row; null when it is not there. */
    private static String hostName(List<CompanionState.HostChip> hosts, String uuid) {
        if (hosts == null || uuid == null) {
            return null;
        }
        for (CompanionState.HostChip host : hosts) {
            if (uuid.equals(host.uuid)) {
                return host.name;
            }
        }
        return null;
    }

    /**
     * Play, or Resume and Quit game, for a browsed game; and the hosts row under any browsing.
     * Both are left out when no screen has registered to act on them: a button that does
     * nothing when pressed is worse than no button.
     *
     * @param browsing what is browsed, or null to take the controls away
     * @param hosts    the row's PCs; null or empty for no row
     */
    private void showBrowsingControls(CompanionState.Browsing browsing,
                                      List<CompanionState.HostChip> hosts) {
        if (actionsView == null) {
            return;
        }
        boolean actionable = browsing != null && CompanionDisplayManager.hasBrowsingActions();
        shownBrowsing = actionable ? browsing : null;

        if (actionable && browsing.hasGame()) {
            String hostName = hostName(hosts, browsing.hostUuid);
            if (browsing.running) {
                playLabelView.setText(R.string.library_resume);
            } else if (hostName != null) {
                playLabelView.setText(getContext().getString(R.string.companion_play_on, hostName));
            } else {
                playLabelView.setText(R.string.companion_play);
            }
            quitView.setVisibility(browsing.running ? View.VISIBLE : View.GONE);
            actionsView.setVisibility(View.VISIBLE);
        } else {
            actionsView.setVisibility(View.GONE);
        }

        if (!actionable || hosts == null || hosts.isEmpty()) {
            hostsView.setVisibility(View.GONE);
            return;
        }
        hostsView.setVisibility(View.VISIBLE);
        if (hosts.equals(renderedHosts)) {
            return;
        }
        renderedHosts = hosts;
        hostItemsView.removeAllViews();
        Context context = getContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        for (final CompanionState.HostChip host : hosts) {
            View chip = inflater.inflate(R.layout.item_companion_host, hostItemsView, false);
            String name = host.name != null ? host.name : context.getString(R.string.library_this_pc);
            ((TextView) chip.findViewById(R.id.companionHostName)).setText(name);
            // The PC card's dot, in the PC card's colours: a host looks the same wherever it is.
            ViewCompat.setBackgroundTintList(chip.findViewById(R.id.companionHostDot),
                    ColorStateList.valueOf(ContextCompat.getColor(context,
                            host.online ? R.color.status_online : R.color.content_faint)));
            chip.setSelected(host.selected);
            String status = context.getString(host.online
                    ? R.string.pcview_status_online : R.string.pcview_status_offline);
            chip.setContentDescription(context.getString(host.selected
                    ? R.string.companion_host_selected_a11y : R.string.companion_host_a11y,
                    name, status));
            chip.setOnClickListener(v -> CompanionDisplayManager.onBrowsingSelectHost(host.uuid));
            hostItemsView.addView(chip);
        }
        // Last, "+": add a PC, as the PCs section's button does.
        View add = inflater.inflate(R.layout.item_companion_host, hostItemsView, false);
        add.findViewById(R.id.companionHostDot).setVisibility(View.GONE);
        TextView plus = add.findViewById(R.id.companionHostName);
        plus.setText(R.string.companion_add_host_glyph);
        plus.setPadding(0, 0, 0, 0);
        add.setPaddingRelative(add.getPaddingEnd(), 0, add.getPaddingEnd(), 0);
        add.setContentDescription(context.getString(R.string.pcs_add_pc));
        add.setOnClickListener(v -> CompanionDisplayManager.onBrowsingAddHost());
        hostItemsView.addView(add);
    }

    // An image has landed: draw the description again, and put the reader back where they were —
    // an image arriving is no reason to lose their place.
    private void onDescriptionImageLoaded() {
        if (idleDescriptionView == null || renderedDescriptionHtml == null) {
            return;
        }
        int scrollY = idleDescriptionScrollView.getScrollY();
        idleDescriptionView.setText(toRichText(renderedDescriptionHtml));
        idleDescriptionScrollView.post(() -> idleDescriptionScrollView.scrollTo(0, scrollY));
    }

    // Playnite stores descriptions as HTML. Parsed, not stripped: the bold, the italics, the
    // paragraph breaks and the screenshots the author put there are all worth having.
    @SuppressWarnings("deprecation")
    private CharSequence toRichText(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }

        // Images are fitted to the column the description is actually drawn in. Before the first
        // layout there is no width to ask for, so fall back to the panel minus the surface's own
        // padding — an underestimate, which overflows nothing.
        DisplayMetrics panel = getContext().getResources().getDisplayMetrics();
        int column = idleDescriptionView.getWidth()
                - idleDescriptionView.getPaddingLeft() - idleDescriptionView.getPaddingRight();
        if (column <= 0) {
            column = panel.widthPixels - Math.round(2 * IDLE_PADDING_DP * panel.density);
        }
        // Half the panel is as much as one screenshot may take: enough to be worth looking at,
        // little enough that the text around it stays in sight.
        descriptionImages.setBounds(column, panel.heightPixels / 2);

        Spanned parsed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                ? Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY, descriptionImages, null)
                : Html.fromHtml(html, descriptionImages, null);
        SpannableStringBuilder text = new SpannableStringBuilder(parsed);

        // An image that hasn't arrived (or never will) draws as nothing, but the block it sits in
        // is still there. Squeeze every run of whitespace spanning more than one line back down to
        // a single blank line — a paragraph break, no more — so no chasm is left behind.
        int i = text.length();
        while (i > 0) {
            if (!Character.isWhitespace(text.charAt(i - 1))) {
                i--;
                continue;
            }
            int runEnd = i;
            int newlines = 0;
            while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) {
                if (text.charAt(i - 1) == '\n') {
                    newlines++;
                }
                i--;
            }
            if (newlines >= 2 && runEnd - i > 2) {
                text.replace(i, runEnd, "\n\n");
            }
        }

        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        int start = 0;
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        return text.subSequence(start, end);
    }

    private String formatBandwidth(PerfStats stats) {
        Context context = getContext();
        if (!stats.hasBandwidth()) {
            return context.getString(R.string.companion_value_unavailable);
        }
        if (stats.bandwidthKbps >= 1000) {
            return context.getString(R.string.companion_value_mbps, stats.bandwidthKbps / 1024f);
        }
        return context.getString(R.string.companion_value_kbps, stats.bandwidthKbps);
    }

    // --- The in-game controls, drawn on this panel as a sheet ---
    //
    // The panel takes no input focus of its own: the window is not focusable, so the gamepad
    // stays with the game on the main screen. The Game activity, which keeps receiving the pad,
    // routes it here while the sheet is up — moving the selection along the page's ControlsGrid
    // and activating an entry through the methods below. The entry under the pad is marked
    // selected rather than focused (there is no real focus to give it), and wears the same ring.

    /** The unlock toast, over whatever the panel shows, at its bottom centre. */
    public void showAchievementToast(List<Achievement> fresh, HostSession session) {
        View content = findViewById(android.R.id.content);
        if (content instanceof ViewGroup) {
            AchievementToast.show((ViewGroup) content, fresh, session);
        }
    }

    public boolean isMenuOpen() {
        return menuOverlay != null && menuOverlay.getVisibility() == View.VISIBLE;
    }

    /**
     * Draw one page of the controls as the sheet and select its first entry. Replaces any page
     * already up. The entries come in the tree's order: the tiles become one row, each list entry
     * a row of its own, and the danger entries the row under the scroll.
     *
     * @param title the page's own name, for the header when the stream's game has none
     */
    public void showMenu(String title, List<CompanionMenuItem> items) {
        if (menuItemsView == null) {
            return;
        }
        menuPageTitle = title;

        menuItemsView.removeAllViews();
        menuDangerView.removeAllViews();
        menuCells.clear();
        menuActions.clear();

        List<StreamAction.Group> groups = new ArrayList<>(items.size());
        LayoutInflater inflater = LayoutInflater.from(getContext());
        LinearLayout tileRow = null;
        boolean afterRow = false;
        for (CompanionMenuItem item : items) {
            View cell;
            switch (item.group) {
                case TILE:
                    if (tileRow == null) {
                        tileRow = new LinearLayout(getContext());
                        tileRow.setOrientation(LinearLayout.HORIZONTAL);
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        lp.bottomMargin = dp(8);
                        menuItemsView.addView(tileRow, lp);
                    }
                    cell = addMenuTile(inflater, tileRow, item);
                    afterRow = false;
                    break;
                case DANGER:
                    cell = addMenuDanger(item);
                    break;
                default:
                    // The tiles after a row start a row of their own, as ControlsGrid counts them.
                    tileRow = null;
                    // The mock's hairline between list rows, never after the last one.
                    if (afterRow) {
                        addMenuDivider();
                    }
                    cell = addMenuRow(inflater, item);
                    afterRow = true;
                    break;
            }
            // Touch only, like everything on the panel; the pad's selection is drawn by hand.
            cell.setFocusable(false);
            final int index = menuActions.size();
            cell.setOnClickListener(v -> activateAt(index));
            menuCells.add(cell);
            menuActions.add(item.action);
            groups.add(item.group);
        }
        menuDangerView.setVisibility(menuDangerView.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        menuGrid = ControlsGrid.of(groups);

        bindMenuHeader();
        menuOverlay.setVisibility(View.VISIBLE);
        menuScrollView.scrollTo(0, 0);
        menuSelectedIndex = -1;
        int first = menuGrid.first();
        if (first >= 0) {
            setSelected(first);
        }
    }

    /** The header: the game, and the stream's numbers as the side panel writes them. */
    private void bindMenuHeader() {
        CompanionState.StreamSession session = state.getStreamSession();
        String title = session != null && session.game != null ? session.game : menuPageTitle;
        menuTitleView.setText(title);
        menuTitleView.setVisibility(title == null || title.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        PerfStats stats = state.getStats();
        menuStatsView.setText(stats != null
                ? StreamControlsPanel.statsText(getContext().getResources(), stats) : null);
        menuStatsView.setVisibility(stats != null ? View.VISIBLE : View.GONE);
    }

    private View addMenuTile(LayoutInflater inflater, LinearLayout row, CompanionMenuItem item) {
        View tile = inflater.inflate(R.layout.item_controls_tile, row, false);
        tile.setBackgroundResource(R.drawable.companion_sheet_tile_bg);
        ImageView icon = tile.findViewById(R.id.controlsTileIcon);
        if (item.iconRes != 0) {
            icon.setImageResource(item.iconRes);
        } else {
            icon.setVisibility(View.GONE);
        }
        ((TextView) tile.findViewById(R.id.controlsTileLabel)).setText(item.label);
        // On is activated, as on the side panel; the pad's selection is the separate selected.
        tile.setActivated(Boolean.TRUE.equals(item.checked));
        tile.setContentDescription(item.label);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) tile.getLayoutParams();
        if (row.getChildCount() > 0) {
            lp.leftMargin = getContext().getResources().getDimensionPixelSize(R.dimen.controls_tile_gap);
        }
        row.addView(tile, lp);
        return tile;
    }

    private View addMenuRow(LayoutInflater inflater, CompanionMenuItem item) {
        View row = inflater.inflate(R.layout.item_controls_row, menuItemsView, false);
        row.setBackgroundResource(R.drawable.companion_sheet_row_bg);
        ((TextView) row.findViewById(R.id.controlsRowLabel)).setText(item.label);
        if (item.iconRes != 0) {
            ImageView icon = row.findViewById(R.id.controlsRowIcon);
            icon.setImageResource(item.iconRes);
            icon.setVisibility(View.VISIBLE);
        }
        // A chevron on a row that opens a page, a check on a choice in use: the side panel's rows.
        int trailing = 0;
        int tint = 0;
        if (item.opensPage) {
            trailing = R.drawable.ic_chevron_right;
            tint = R.color.content_muted;
        } else if (Boolean.TRUE.equals(item.checked)) {
            trailing = R.drawable.ic_check;
            tint = R.color.signal;
        }
        if (trailing != 0) {
            ImageView end = row.findViewById(R.id.controlsRowTrailing);
            end.setImageResource(trailing);
            end.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(getContext(), tint)));
            end.setVisibility(View.VISIBLE);
        }
        menuItemsView.addView(row);
        return row;
    }

    private void addMenuDivider() {
        View rule = new View(getContext());
        rule.setBackgroundColor(ContextCompat.getColor(getContext(), R.color.divider));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.bottomMargin = dp(2);
        menuItemsView.addView(rule, lp);
    }

    /** Disconnect, and Quit game in the danger hue: the mock's two buttons, side by side. */
    private View addMenuDanger(CompanionMenuItem item) {
        TextView button = new TextView(getContext());
        button.setText(item.label);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setSingleLine(true);
        button.setTextColor(ContextCompat.getColor(getContext(),
                item.warn ? R.color.danger : R.color.content_primary));
        button.setBackgroundResource(item.warn
                ? R.drawable.companion_sheet_danger_bg : R.drawable.companion_sheet_tile_bg);
        button.setClickable(true);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                getContext().getResources().getDimensionPixelSize(R.dimen.menu_row_min_height), 1f);
        if (menuDangerView.getChildCount() > 0) {
            lp.leftMargin = getContext().getResources().getDimensionPixelSize(R.dimen.controls_tile_gap);
        }
        menuDangerView.addView(button, lp);
        return button;
    }

    public void hideMenu() {
        if (menuOverlay == null || menuOverlay.getVisibility() != View.VISIBLE) {
            return;
        }
        menuOverlay.setVisibility(View.GONE);
        menuItemsView.removeAllViews();
        menuDangerView.removeAllViews();
        menuCells.clear();
        menuActions.clear();
        menuGrid = null;
        menuSelectedIndex = -1;
        // Let the game know it is back to steering the pad itself.
        CompanionDisplayManager.onMenuClosed();
    }

    /** Move the selection one step along the page's grid; at an edge it stays put. */
    public void moveSelection(ControlsSurface.Nav nav) {
        if (menuGrid == null || menuCells.isEmpty()) {
            return;
        }
        int next = menuGrid.neighbour(menuSelectedIndex, nav);
        if (next >= 0 && next != menuSelectedIndex) {
            setSelected(next);
        }
    }

    public void activateSelection() {
        activateAt(menuSelectedIndex);
    }

    private void activateAt(int index) {
        if (index < 0 || index >= menuActions.size()) {
            return;
        }
        // Take the sheet down before running, so a re-opening action (a page) is not undone
        // by this same dismissal, and nothing the action opens lands under a sheet still up.
        Runnable action = menuActions.get(index);
        hideMenu();
        if (action != null) {
            action.run();
        }
    }

    private void setSelected(int index) {
        if (menuSelectedIndex >= 0 && menuSelectedIndex < menuCells.size()) {
            menuCells.get(menuSelectedIndex).setSelected(false);
        }
        menuSelectedIndex = index;
        View cell = menuCells.get(index);
        cell.setSelected(true);
        // Keep the selected entry in view once the panel has been laid out.
        cell.post(() -> cell.requestRectangleOnScreen(
                new android.graphics.Rect(0, 0, cell.getWidth(), cell.getHeight()), false));
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value,
                getContext().getResources().getDisplayMetrics()));
    }
}
