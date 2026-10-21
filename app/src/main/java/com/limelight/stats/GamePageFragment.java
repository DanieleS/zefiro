package com.limelight.stats;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.TextViewCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.limelight.R;
import com.limelight.companion.CompanionDisplayManager;
import com.limelight.companion.CompanionPage;
import com.limelight.companion.CompanionState;
import com.limelight.computers.HostEntry;
import com.limelight.computers.HostLibrary;
import com.limelight.computers.HostRepository;
import com.limelight.grid.assets.ArtState;
import com.limelight.grid.assets.CachedAppAssetLoader;
import com.limelight.library.GameActionsSheet;
import com.limelight.library.LibraryApp;
import com.limelight.main.MainHost;
import com.limelight.main.PageFocus;
import com.limelight.main.PanelFocus;
import com.limelight.main.SectionFocus;
import com.limelight.main.SectionKeys;
import com.limelight.main.StackedSectionFragment;
import com.limelight.main.WindowClass;
import com.limelight.nvstream.http.Achievement;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.ui.GamepadHintsView;
import com.limelight.ui.TitleCardDrawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One game's page: the game above (cover, Play or Resume and Quit, its figures, its last twelve
 * weeks, what else can be done to it), every achievement below. After the approved GamePage_B
 * mock. It replaced the statistics sheet and the achievements dialog it raised, two modals
 * stacked over whatever screen asked for them.
 *
 * <h3>Where it lives</h3>
 *
 * It is pushed on the stack of the section it was opened from ({@link MainHost#openGamePage}):
 * Stats (a most-played row, an unlock, a game to pick up again), the Library or Search (the
 * actions sheet's "Stats &amp; achievements"), or the unlock toast. Back, B or the arrow pops it,
 * and the section's own focus memory returns the d-pad to the row that led here.
 *
 * <h3>Two sources</h3>
 *
 * <pre>
 *   the library's snapshot ──▶ the game as the library has it: name, running, hidden, cover,
 *                              metadata ──▶ tag, buttons, chips (redrawn on every snapshot)
 *   GamePageViewModel     ──▶ the host's answer: figures, weeks, last played, achievements
 * </pre>
 *
 * The page follows the snapshot: a game starting or stopping redraws the buttons and the tag,
 * and a snapshot of another host (the user switched PCs) pops the page, which is about a game
 * of the host that is no longer selected. A game the library does not list (a statistics row for
 * a game outside the catalogue) has no buttons and no chips, but its figures and achievements
 * still load: the host is asked by UUID.
 *
 * <h3>Window</h3>
 *
 * Wide, the cover and its buttons stand left of the title, the figures and the chips; compact
 * (a phone in portrait), the buttons and the body move under that row into the one scroll. The
 * views are the same in both, moved by {@link #applyWindow}, and the activity handles its own
 * configuration changes, so a rotation keeps focus and state.
 *
 * <h3>Keys and focus</h3>
 *
 * Focus starts on Resume or Play, else on the first chip, else on the achievements' filter (or
 * their retry row). An answer or a snapshot that hides the focused view puts the d-pad back on
 * that first stop rather than leave it to the framework, which would hand it to the rail. R2
 * drops it onto the achievements (the first row, or the filter when the list is empty), L2
 * brings it back up, Y opens the game's actions sheet. The panel's spotlight follows the page's
 * game ({@code gameFocus().focusGame}); while a page of this screen is on the panel, the page
 * is drawn instead.
 *
 * <h3>Two screens</h3>
 *
 * Whether the lower half is here or on the companion panel follows
 * {@link CompanionDisplayManager#panelShowing()}, never the device:
 *
 * <pre>
 *   inline (no panel)   the achievements block under the game, real focus; R2 and L2 move it
 *   split (panel)       the block is gone here; the panel draws a page of this screen's:
 *                         the game has achievements    GameAchievementsPage (the list)
 *                         not answered yet / failed    GameAchievementsPage (one status row)
 *                         none tracked, or nothing     GameActionsPage, and the chips leave
 *                                                      this screen for it
 *                       R2 takes the d-pad down there (PanelFocus: this window keeps the keys,
 *                       a sink holds focus, the panel rings the stop), L2 brings it back to
 *                       Resume or Play, B or up off the filter back to where it was
 * </pre>
 *
 * The panel's page is published while this screen is the one shown on its section, and taken
 * off when it is hidden (another screen pushed over it, the section left) or its view goes. Taps
 * on the panel act as A would there, without moving the d-pad between screens.
 *
 * Main thread only; every callback checks that the view still exists.
 */
public class GamePageFragment extends Fragment implements SectionKeys, SectionFocus,
        StackedSectionFragment.SectionHiddenListener, AchievementsBlockView.Listener,
        PanelFocus.Owner {

    /** The page's tag on a section's stack: one game page per stack. */
    public static final String TAG = "stack:gamePage";

    private static final int NO_APP = Integer.MIN_VALUE;

    private MainHost host;
    private GamePageArgs args;
    private GamePageViewModel model;
    private StatsText text;

    // The game as the latest snapshot of this page's host has it; null when not listed.
    @Nullable
    private LibraryApp app;
    @Nullable
    private HostLibrary.Snapshot snapshot;
    // This page's host's library while it is the open one; null otherwise.
    @Nullable
    private HostLibrary library;
    @Nullable
    private GamePageData data;
    // When the answer shown came: what NEW is judged against on the panel, fixed per answer so
    // the page published for every d-pad move is the same value but for its stop.
    private long dataAt;

    // Whether the companion panel is showing, and so has this screen's lower half (see the
    // class comment); the d-pad's place there.
    private boolean split;
    @Nullable
    private PanelFocus panelFocus;

    // The cover: which game and art version it was asked for, and whether it shows the art.
    @Nullable
    private CachedAppAssetLoader.Request coverRequest;
    private int coverAppId = NO_APP;
    private int coverArtVersion = -1;
    private boolean coverShowsArt;
    @Nullable
    private CoverLoader coverLoader;

    // The chips drawn now, so a snapshot that changes nothing of theirs keeps them, and focus.
    @Nullable
    private List<GameActionsSheet.Item> drawnChips;
    private boolean drawnHidden;
    @Nullable
    private String drawnCompanionLabel;

    @Nullable
    private Boolean compactApplied;
    private boolean startedBefore;
    @Nullable
    private View lastFocused;
    @Nullable
    private ViewTreeObserver.OnGlobalFocusChangeListener focusWatch;
    @Nullable
    private View.OnAttachStateChangeListener focusWatchAttacher;

    private View root;
    private LinearLayout column;
    private TextView origin;
    private View hero;
    private ImageView backdrop;
    private View scrim;
    private LinearLayout top;
    private LinearLayout coverColumn;
    private View coverFrame;
    private ImageView cover;
    private View badge;
    private LinearLayout buttons;
    private MaterialButton primary;
    private MaterialButton quit;
    private LinearLayout info;
    private TextView tag;
    private TextView title;
    private TextView hostLine;
    private TextView meta;
    private LinearLayout body;
    private View status;
    private View statusProgress;
    private TextView statusText;
    private View statusHint;
    private View figures;
    private TextView total;
    private TextView count;
    private TextView countLabel;
    private View averageBlock;
    private TextView average;
    private TextView averageLabel;
    private View weeks;
    private TextView thisWeek;
    private StatsBarsView bars;
    private View chipsScroll;
    private LinearLayout chips;
    private AchievementsBlockView achievements;
    private GamepadHintsView hints;

    public GamePageFragment() {
    }

    @NonNull
    public static GamePageFragment newInstance(@NonNull GamePageArgs args) {
        GamePageFragment fragment = new GamePageFragment();
        fragment.setArguments(args.toBundle());
        return fragment;
    }

    /** The game this page is about, as it was opened; null before its arguments are read. */
    @Nullable
    public GamePageArgs args() {
        return args != null ? args : GamePageArgs.fromBundle(getArguments());
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (!(context instanceof MainHost)) {
            throw new IllegalStateException("GamePageFragment needs a MainHost activity");
        }
        host = (MainHost) context;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        GamePageArgs given = GamePageArgs.fromBundle(getArguments());
        if (given == null) {
            throw new IllegalStateException("GamePageFragment needs its GamePageArgs");
        }
        args = given;
        model = new ViewModelProvider(this).get(GamePageViewModel.class);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_game_page, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        text = new StatsText(requireContext());
        root = view;
        column = view.findViewById(R.id.gamePageColumn);
        origin = view.findViewById(R.id.gamePageOrigin);
        hero = view.findViewById(R.id.gamePageHero);
        backdrop = view.findViewById(R.id.gamePageBackdrop);
        scrim = view.findViewById(R.id.gamePageScrim);
        top = view.findViewById(R.id.gamePageTop);
        coverColumn = view.findViewById(R.id.gamePageCoverColumn);
        coverFrame = view.findViewById(R.id.gamePageCoverFrame);
        cover = view.findViewById(R.id.gamePageCover);
        badge = view.findViewById(R.id.gamePageBadge);
        buttons = view.findViewById(R.id.gamePageButtons);
        primary = view.findViewById(R.id.gamePagePrimary);
        quit = view.findViewById(R.id.gamePageQuit);
        info = view.findViewById(R.id.gamePageInfo);
        tag = view.findViewById(R.id.gamePageTag);
        title = view.findViewById(R.id.gamePageTitle);
        hostLine = view.findViewById(R.id.gamePageHost);
        meta = view.findViewById(R.id.gamePageMeta);
        body = view.findViewById(R.id.gamePageBody);
        status = view.findViewById(R.id.gamePageStatus);
        statusProgress = view.findViewById(R.id.gamePageStatusProgress);
        statusText = view.findViewById(R.id.gamePageStatusText);
        statusHint = view.findViewById(R.id.gamePageStatusHint);
        figures = view.findViewById(R.id.gamePageFigures);
        total = view.findViewById(R.id.gamePageTotal);
        count = view.findViewById(R.id.gamePageCount);
        countLabel = view.findViewById(R.id.gamePageCountLabel);
        averageBlock = view.findViewById(R.id.gamePageAverageBlock);
        average = view.findViewById(R.id.gamePageAverage);
        averageLabel = view.findViewById(R.id.gamePageAverageLabel);
        weeks = view.findViewById(R.id.gamePageWeeks);
        thisWeek = view.findViewById(R.id.gamePageThisWeek);
        bars = view.findViewById(R.id.gamePageBars);
        chipsScroll = view.findViewById(R.id.gamePageChipsScroll);
        chips = view.findViewById(R.id.gamePageChips);
        achievements = view.findViewById(R.id.gamePageAchievements);
        hints = view.findViewById(R.id.gamePageHints);

        cover.setClipToOutline(true);
        bars.setCompact(true);
        bars.setBarColor(ContextCompat.getColor(requireContext(), R.color.week_bar));
        origin.setText(args.originLabel);
        origin.setVisibility(args.originLabel.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        view.findViewById(R.id.gamePageBack).setOnClickListener(v -> leave());
        primary.setOnClickListener(v -> onPrimary());
        quit.setOnClickListener(v -> {
            LibraryApp shown = app;
            if (shown != null) {
                host.launchController().runAction(GameActionsSheet.Item.QUIT, shown);
            }
        });
        status.setOnClickListener(v -> {
            if (data != null && offersRetry(data)) {
                load(false);
            }
        });
        achievements.setListener(this);

        // Before the first draw, so a panel already on does not show the block here for a frame.
        split = Boolean.TRUE.equals(CompanionDisplayManager.panelShowing().getValue());
        // Before this screen's own observer: when the panel goes, PanelFocus brings the d-pad
        // back up first, and onPanelShowing then lays the block out here.
        panelFocus = PanelFocus.attach(this, (ViewGroup) view, this);
        CompanionDisplayManager.panelShowing().observe(getViewLifecycleOwner(),
                this::onPanelShowing);

        applyWindow();
        installLayout(view);
        focusWatch = (oldFocus, newFocus) -> {
            if (newFocus != null && isInside(newFocus)) {
                lastFocused = newFocus;
                reportFocus();
            }
        };
        // Added to the window's observer while attached, and taken off it on detach, as in
        // LibraryHomeFragment. Not added here and removed in onDestroyView: the view is not
        // attached yet here (its observer is a floating one, merged into the window's on attach),
        // and when the page is popped or replaced it is already detached there, when its observer
        // is a fresh floating one and the removal would miss, leaving every page ever opened
        // registered on the activity's window.
        final ViewTreeObserver.OnGlobalFocusChangeListener watch = focusWatch;
        focusWatchAttacher = new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                v.getViewTreeObserver().addOnGlobalFocusChangeListener(watch);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                v.getViewTreeObserver().removeOnGlobalFocusChangeListener(watch);
            }
        };
        view.addOnAttachStateChangeListener(focusWatchAttacher);
        if (view.isAttachedToWindow()) {
            view.getViewTreeObserver().addOnGlobalFocusChangeListener(watch);
        }

        // Now, not on the observer's first delivery (only once started): the first load needs
        // the session the snapshot leads to.
        HostRepository repository = host.hostRepository();
        onSnapshot(repository.getLibrary().getValue());
        repository.getLibrary().observe(getViewLifecycleOwner(), this::onSnapshot);
        model.data().observe(getViewLifecycleOwner(), this::onData);
        if (model.data().getValue() == null) {
            load(false);
        }
        publishPage();
        updateHints();
    }

    @Override
    public void onStart() {
        super.onStart();
        // Back from a stream, most likely of this game: what it played and unlocked has changed.
        if (startedBefore && getView() != null && model.data().getValue() != null) {
            load(true);
        }
        startedBefore = true;
    }

    @Override
    public void onDestroyView() {
        if (root != null && focusWatchAttacher != null) {
            root.removeOnAttachStateChangeListener(focusWatchAttacher);
            // Still attached (a view destroyed in place): its observer is the window's.
            if (root.isAttachedToWindow() && focusWatch != null) {
                root.getViewTreeObserver().removeOnGlobalFocusChangeListener(focusWatch);
            }
        }
        focusWatchAttacher = null;
        focusWatch = null;
        lastFocused = null;
        cancelCover();
        coverAppId = NO_APP;
        coverArtVersion = -1;
        coverShowsArt = false;
        if (coverLoader != null) {
            coverLoader.shutdown();
            coverLoader = null;
        }
        drawnChips = null;
        compactApplied = null;
        // Its view lifecycle has ended: it has taken the page off the panel already.
        panelFocus = null;
        if (isRemoving()) {
            reportRootFocus();
        }
        root = null;
        super.onDestroyView();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (getView() != null) {
            applyWindow();
        }
    }

    /** Another screen pushed over this one on its stack, or popped off it. */
    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        onShownChanged(hidden);
    }

    @Override
    public void onSectionHiddenChanged(boolean hidden) {
        onShownChanged(hidden);
        if (!hidden && isAdded() && getView() != null) {
            reportFocus();
            // Back from another section, perhaps after a stream: ask again, quietly.
            if (model.data().getValue() != null) {
                load(true);
            }
        }
    }

    // --- The game, from the library ---

    /**
     * A new snapshot: the game as the library has it now. One of another host means the user
     * switched PCs, and the page leaves; a null one (no host yet) only leaves the game unlisted.
     */
    private void onSnapshot(@Nullable HostLibrary.Snapshot s) {
        if (!isAdded() || getView() == null) {
            return;
        }
        if (s != null && !s.hostUuid.equalsIgnoreCase(args.hostUuid)) {
            leave();
            return;
        }
        snapshot = s;
        boolean hadSession = session() != null;
        HostLibrary current = host.hostRepository().currentLibrary();
        library = current != null && current.getHostUuid().equalsIgnoreCase(args.hostUuid)
                ? current : null;
        app = s != null ? findApp(s.apps) : null;
        achievements.setSession(session());
        renderGame();
        if (isOnScreen()) {
            reportFocus();
        }
        // The first load may have run without a session (a restored page before its snapshot,
        // a host given no address yet) and failed at once. A session that turns up now asks
        // again, or a reachable PC would keep saying it cannot be reached until a retry.
        GamePageData shown = model.data().getValue();
        if (!hadSession && session() != null
                && (shown == null || shown.state == GamePageData.State.FAILED)) {
            load(false);
        }
    }

    // By id when the opener knew it, by UUID otherwise (or when the id is gone: a host that
    // renumbered its apps).
    @Nullable
    private LibraryApp findApp(@NonNull List<LibraryApp> apps) {
        if (args.appId != 0) {
            for (LibraryApp a : apps) {
                if (a.appId() == args.appId) {
                    return a;
                }
            }
        }
        for (LibraryApp a : apps) {
            String uuid = a.app.getAppUUID();
            if (uuid != null && uuid.equalsIgnoreCase(args.appUuid)) {
                return a;
            }
        }
        return null;
    }

    @Nullable
    private HostSession session() {
        return library != null ? library.session() : null;
    }

    private String gameName() {
        return app != null ? app.app.getAppName() : args.name;
    }

    private void renderGame() {
        // A redraw can take the focused view with it: Resume or Play go when the game leaves the
        // library, and its chips with them. The framework then hands focus to the window's first
        // stop, the rail, which placeFocusIfLost leaves alone; the d-pad would leave the page.
        boolean hadFocus = root.hasFocus();
        boolean running = app != null && app.running;
        title.setText(gameName());
        badge.setVisibility(running ? View.VISIBLE : View.GONE);
        renderTag(running);
        renderMeta();
        renderButtons();
        renderChips();
        renderCover();
        renderBackdrop();
        refocusIfTaken(hadFocus);
        // The title and the actions are the panel's too.
        publishPage();
        updateHints();
    }

    // Running is green, as on the badge; blue is kept for focus.
    private void renderTag(boolean running) {
        Context context = requireContext();
        HostEntry entry = host.hostRepository().getHost(args.hostUuid);
        String pcName = entry != null && entry.details.name != null ? entry.details.name : "";
        boolean online = entry != null && entry.details.state == ComputerDetails.State.ONLINE;
        String pcState = context.getString(online ? R.string.pcview_status_online
                : R.string.pcview_status_offline);
        String pcLine = pcName.isEmpty() ? pcState
                : context.getString(R.string.game_page_joined, pcName, pcState);
        boolean compact = Boolean.TRUE.equals(compactApplied);

        int green = ContextCompat.getColor(context, R.color.status_online);
        if (running) {
            tag.setVisibility(View.VISIBLE);
            tag.setText(compact || pcName.isEmpty() ? context.getString(R.string.game_page_running)
                    : context.getString(R.string.game_page_running_on, pcName));
            tag.setTextColor(green);
            TextViewCompat.setCompoundDrawableTintList(tag, ColorStateList.valueOf(green));
        } else if (compact) {
            // The PC has a line of its own there.
            tag.setVisibility(View.GONE);
        } else {
            tag.setVisibility(View.VISIBLE);
            tag.setText(pcLine);
            tag.setTextColor(ContextCompat.getColor(context, R.color.content_secondary));
            TextViewCompat.setCompoundDrawableTintList(tag, ColorStateList.valueOf(online ? green
                    : ContextCompat.getColor(context, R.color.content_muted)));
        }
        hostLine.setVisibility(compact ? View.VISIBLE : View.GONE);
        hostLine.setText(pcLine);
    }

    /**
     * The line under the title. Wide: the metadata (year, genres, score) joined with when the
     * game was last played. Compact: only when it was last played, under the PC. A part that is
     * missing leaves no gap.
     */
    private void renderMeta() {
        String last = lastPlayedLine();
        String metadata = "";
        boolean compact = Boolean.TRUE.equals(compactApplied);
        if (!compact && snapshot != null) {
            metadata = CompanionState.Browsing.metaLine(
                    snapshot.metadata.get(args.appUuid.toUpperCase()));
        }
        String line;
        if (metadata.isEmpty()) {
            line = last;
        } else if (last.isEmpty()) {
            line = metadata;
        } else {
            line = getString(R.string.game_page_joined, metadata, last);
        }
        meta.setText(line);
        meta.setVisibility(line.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private String lastPlayedLine() {
        GamePageData d = data;
        GamePageModel.LastPlayed last = d != null && d.state == GamePageData.State.READY
                ? GamePageModel.lastPlayed(d.stats, System.currentTimeMillis()) : null;
        if (last == null) {
            return "";
        }
        return GamePageModel.lastPlayedLine(last, app != null && app.running, new Words());
    }

    private void renderButtons() {
        GamePageModel.Primary kind = GamePageModel.primary(app);
        boolean quitHadFocus = quit.hasFocus();
        buttons.setVisibility(kind == GamePageModel.Primary.NONE ? View.GONE : View.VISIBLE);
        boolean resume = kind == GamePageModel.Primary.RESUME_QUIT;
        primary.setText(resume ? R.string.library_resume : R.string.game_page_play);
        primary.setContentDescription(getString(resume ? R.string.library_resume
                : R.string.game_page_play) + ", " + gameName());
        quit.setVisibility(resume ? View.VISIBLE : View.GONE);
        if (quitHadFocus && !resume) {
            // The game ended: the d-pad stays by the buttons rather than fall to the rail.
            primary.requestFocus();
        }
    }

    private void onPrimary() {
        LibraryApp shown = app;
        if (shown == null) {
            return;
        }
        if (shown.running) {
            host.launchController().runAction(GameActionsSheet.Item.RESUME, shown);
        } else {
            // As a tile: starts, or asks first when another game is running.
            host.launchController().onTileClicked(shown);
        }
    }

    private void renderChips() {
        List<GameActionsSheet.Item> items = GamePageModel.chips(app,
                host.launchController().actionState());
        boolean hidden = app != null && app.hidden;
        String companion = app != null && items.contains(GameActionsSheet.Item.COMPANION_APP)
                ? host.launchController().companionLabel(app.app) : null;
        if (items.equals(drawnChips) && hidden == drawnHidden
                && Objects.equals(companion, drawnCompanionLabel)) {
            applyChipsVisibility();
            return;
        }
        int focusedIndex = -1;
        for (int i = 0; i < chips.getChildCount(); i++) {
            if (chips.getChildAt(i).hasFocus()) {
                focusedIndex = i;
            }
        }
        drawnChips = items;
        drawnHidden = hidden;
        drawnCompanionLabel = companion;

        chips.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (final GameActionsSheet.Item item : items) {
            TextView chip = (TextView) inflater.inflate(R.layout.item_game_page_chip, chips, false);
            chip.setText(item == GameActionsSheet.Item.COMPANION_APP && companion != null
                    ? companion : getString(item.label));
            // Hide is the sheet's checked row: selected while the game is hidden.
            chip.setSelected(item == GameActionsSheet.Item.HIDE && hidden);
            chip.setOnClickListener(v -> {
                LibraryApp shown = app;
                if (shown != null) {
                    host.launchController().runAction(item, shown);
                }
            });
            chips.addView(chip);
        }
        applyChipsVisibility();
        if (focusedIndex >= 0 && chipsScroll.getVisibility() == View.VISIBLE
                && chips.getChildCount() > 0) {
            chips.getChildAt(Math.min(focusedIndex, chips.getChildCount() - 1)).requestFocus();
        } else if (focusedIndex >= 0) {
            placeFocusIfLost();
        }
    }

    /**
     * The chip row shows while it has chips, unless the panel shows them as its rows: a game
     * without achievements on a split page ({@link BottomKind#ACTIONS}).
     */
    private void applyChipsVisibility() {
        boolean shown = chips.getChildCount() > 0 && bottomKind() != BottomKind.ACTIONS;
        chipsScroll.setVisibility(shown ? View.VISIBLE : View.GONE);
    }

    /**
     * The cover: the library's, through its loader, when the library lists the game; the
     * statistics' {@link CoverLoader} by UUID otherwise. The title card stands in meanwhile, and
     * for good when there is no art, as on a tile.
     */
    private void renderCover() {
        Context context = requireContext();
        String name = gameName();
        if (app != null && library != null && snapshot != null) {
            // The library has the game now: a statistics fetch still running would land later
            // and cover its art. Without the tag, the fetch's own check drops what it brings.
            if (coverLoader != null) {
                coverLoader.shutdown();
                coverLoader = null;
            }
            cover.setTag(R.id.coverAppUuid, null);
            final int appId = app.appId();
            if (appId == coverAppId && snapshot.artVersion == coverArtVersion) {
                return;
            }
            cancelCover();
            if (appId != coverAppId) {
                coverShowsArt = false;
                cover.setImageDrawable(new TitleCardDrawable(context, name, true));
            }
            coverAppId = appId;
            coverArtVersion = snapshot.artVersion;
            int width = coverFrame.getLayoutParams().width;
            coverRequest = library.loadArt(app.app, width, (bitmap, state) -> {
                if (!isAdded() || getView() == null || coverAppId != appId) {
                    return;
                }
                if (state == ArtState.ART && bitmap != null) {
                    coverShowsArt = true;
                    cover.setImageBitmap(bitmap);
                } else if (state != ArtState.LOADING || !coverShowsArt) {
                    // A refresh's LOADING keeps the art already shown.
                    coverShowsArt = false;
                    cover.setImageDrawable(new TitleCardDrawable(context, name,
                            state == ArtState.LOADING));
                }
            });
            return;
        }
        if (coverLoader == null && coverAppId == NO_APP) {
            HostSession session = session();
            if (session != null) {
                coverLoader = new CoverLoader(context, session, args.hostUuid);
                coverLoader.load(cover, name, args.appUuid);
            } else {
                cover.setImageDrawable(new TitleCardDrawable(context, name, false));
            }
        }
    }

    private void cancelCover() {
        if (coverRequest != null) {
            coverRequest.cancel();
            coverRequest = null;
        }
    }

    // Only a background the library has already loaded (the hero's, most often): the page does
    // not fetch one, the art is decoration.
    private void renderBackdrop() {
        Bitmap background = library != null && app != null ? library.cachedBackground(app.appId()) : null;
        backdrop.setImageBitmap(background);
        int visibility = background != null ? View.VISIBLE : View.GONE;
        backdrop.setVisibility(visibility);
        scrim.setVisibility(visibility);
    }

    // --- The host's answer ---

    private void load(boolean quiet) {
        model.load(session(), args.appUuid, quiet);
    }

    private void onData(@Nullable GamePageData d) {
        if (!isAdded() || getView() == null || d == null) {
            return;
        }
        // The swap below can hide the focused view (the status row, a row of the list); see
        // renderGame for why that is caught here rather than by placeFocusIfLost.
        boolean hadFocus = root.hasFocus();
        if (d != data) {
            dataAt = System.currentTimeMillis();
        }
        data = d;
        switch (d.state) {
            case LOADING:
            case FAILED:
            case NOTHING:
                showStatus(d.state);
                break;
            case READY:
            default:
                if (offersRetry(d)) {
                    // Half an answer: the play statistics failed, the achievements came. The
                    // failed row takes the figures' place, so they can be asked for again.
                    bindStatus(GamePageData.State.FAILED);
                } else {
                    status.setVisibility(View.GONE);
                }
                renderFigures(d);
                renderAchievements();
                break;
        }
        renderMeta();
        // Whether the chips are the panel's depends on whether the game has achievements.
        applyChipsVisibility();
        if (!refocusIfTaken(hadFocus)) {
            placeFocusIfLost();
        }
        publishPage();
        updateHints();
    }

    /**
     * Whether the page offers a retry for this answer: when it failed outright, or when the play
     * statistics failed and the achievements alone came (the achievements' own half failing has
     * its retry in their block).
     */
    private static boolean offersRetry(@NonNull GamePageData d) {
        return d.state == GamePageData.State.FAILED
                || (d.state == GamePageData.State.READY && d.statsFailed && !d.hasPlay());
    }

    /** Loading, failed or nothing: in place of the figures and the list; buttons and chips stay. */
    private void showStatus(GamePageData.State state) {
        bindStatus(state);
        figures.setVisibility(View.GONE);
        weeks.setVisibility(View.GONE);
        achievements.setVisibility(View.GONE);
    }

    /** Shows the status row in one of its forms; leaves the rest of the body alone. */
    private void bindStatus(GamePageData.State state) {
        status.setVisibility(View.VISIBLE);
        statusProgress.setVisibility(state == GamePageData.State.LOADING ? View.VISIBLE : View.GONE);
        statusHint.setVisibility(state == GamePageData.State.FAILED ? View.VISIBLE : View.GONE);
        statusText.setText(state == GamePageData.State.LOADING ? R.string.game_stats_loading
                : state == GamePageData.State.FAILED ? R.string.game_stats_failed
                : R.string.game_stats_nothing);
        statusText.setTextColor(ContextCompat.getColor(requireContext(),
                state == GamePageData.State.FAILED ? R.color.danger : R.color.content_secondary));
        // A stop while loading and failed: a retry pressed on it keeps the d-pad there while
        // the next answer comes. "Nothing" has nothing to do.
        status.setFocusable(state != GamePageData.State.NOTHING);
        status.setClickable(state == GamePageData.State.FAILED);
    }

    private void renderFigures(GamePageData d) {
        GamePageModel.Figures f = GamePageModel.figures(d.stats);
        if (f == null) {
            figures.setVisibility(View.GONE);
            weeks.setVisibility(View.GONE);
            return;
        }
        figures.setVisibility(View.VISIBLE);
        total.setText(text.duration(f.totalSeconds));
        count.setText(text.number(f.count));
        boolean sessions = f.unit == GamePageModel.Unit.SESSIONS;
        countLabel.setText(sessions
                ? getResources().getQuantityString(R.plurals.game_stats_sessions, (int) f.count)
                : getString(R.string.game_stats_launches));
        if (f.averageSeconds < 0) {
            averageBlock.setVisibility(View.INVISIBLE);
        } else {
            averageBlock.setVisibility(View.VISIBLE);
            average.setText(text.duration(f.averageSeconds));
            averageLabel.setText(sessions ? R.string.game_stats_per_session : R.string.game_stats_per_launch);
        }
        if (f.weeks == null) {
            weeks.setVisibility(View.GONE);
        } else {
            weeks.setVisibility(View.VISIBLE);
            thisWeek.setText(text.duration(f.thisWeekSeconds()));
            bars.setData(f.weeks, null, null, f.weeks.length - 1);
        }
    }

    private void renderAchievements() {
        GamePageData d = data;
        if (split || d == null || d.state != GamePageData.State.READY) {
            // Split, the panel has them.
            achievements.setVisibility(View.GONE);
            return;
        }
        achievements.setVisibility(View.VISIBLE);
        if (d.hasAchievements() && d.achievements != null) {
            achievements.showAchievements(d.achievements, model.filter(), model.revealed(),
                    System.currentTimeMillis());
        } else if (d.achievementsFailed) {
            achievements.showFailed();
        } else {
            achievements.showUntracked(gameName());
        }
    }

    // --- The achievements block ---

    @Override
    public void onFilterChosen(@NonNull AchievementFilter.Filter filter) {
        if (filter == model.filter()) {
            return;
        }
        model.setFilter(filter);
        renderAchievements();
        if (panelFocus != null && panelFocus.zone() == PanelFocus.Zone.BOTTOM) {
            // A d-pad on a row now names another achievement: it moves up to the filter.
            int stop = GameAchievementsPage.focusAfterFilter(panelFocus.focusedStop(), filter);
            if (stop != panelFocus.focusedStop()) {
                panelFocus.enterBottom(stop);
                return;
            }
        }
        publishPage();
    }

    @Override
    public void onRevealRequested(@NonNull Achievement achievement) {
        if (model.reveal(achievement.getId())) {
            achievements.refresh(achievement);
            publishPage();
        }
    }

    @Override
    public void onRetry() {
        load(false);
    }

    // --- Leaving ---

    /** Pops this page off its section's stack, whatever is over it. */
    private void leave() {
        Fragment parent = getParentFragment();
        if (parent instanceof StackedSectionFragment) {
            ((StackedSectionFragment) parent).pop(TAG);
        }
    }

    // --- Keys ---

    @Override
    public boolean onSectionKey(KeyEvent event) {
        if (!isAdded() || getView() == null || event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }
        // On the panel, the d-pad, A and B are the page's.
        if (panelFocus != null && panelFocus.onKey(event)) {
            return true;
        }
        boolean first = event.getRepeatCount() == 0;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BUTTON_R2: {
                if (split) {
                    return dropToPanel(first);
                }
                View target = achievements.firstStop();
                if (target == null) {
                    return false;
                }
                if (first) {
                    target.requestFocus();
                }
                return true;
            }
            case KeyEvent.KEYCODE_BUTTON_L2: {
                if (panelFocus != null && panelFocus.zone() == PanelFocus.Zone.BOTTOM) {
                    if (first) {
                        riseFromPanel();
                    }
                    return true;
                }
                View target = topStop();
                if (target == null) {
                    return false;
                }
                if (first) {
                    target.requestFocus();
                }
                return true;
            }
            case KeyEvent.KEYCODE_BUTTON_Y: {
                LibraryApp shown = app;
                if (shown == null) {
                    return false;
                }
                if (first) {
                    // Held, Y raises one sheet, not one per repeat.
                    host.launchController().showActions(shown);
                }
                return true;
            }
            default:
                return false;
        }
    }

    // --- The companion panel ---

    /** What the panel shows of this page: nothing (no panel), or one of three pages. */
    private enum BottomKind { NONE, STATUS, LIST, ACTIONS }

    // From the answer alone (see the class comment): the panel's page and the chips' place
    // follow it.
    @NonNull
    private BottomKind bottomKind() {
        if (!split) {
            return BottomKind.NONE;
        }
        GamePageData d = data;
        if (d == null || d.state == GamePageData.State.LOADING) {
            return BottomKind.STATUS;
        }
        if (d.hasAchievements()) {
            return BottomKind.LIST;
        }
        // Not reached for the achievements: the retry, not a claim that there are none.
        if (d.state == GamePageData.State.FAILED || d.achievementsFailed) {
            return BottomKind.STATUS;
        }
        return BottomKind.ACTIONS;
    }

    @Nullable
    @Override
    public CompanionPage bottomPage() {
        GamePageData d = data;
        String name = gameName();
        switch (bottomKind()) {
            case STATUS:
                return d == null || d.state == GamePageData.State.LOADING
                        ? GameAchievementsPage.loading(name) : GameAchievementsPage.failed(name);
            case LIST:
                if (d == null || d.achievements == null) {
                    return null;
                }
                return GameAchievementsPage.list(name, d.achievements, model.filter(),
                        model.revealed(), session(), dataAt);
            case ACTIONS:
                return actionsPage(name);
            case NONE:
            default:
                return null;
        }
    }

    // The chips, as the panel's rows: the same actions, labels and Hide's check.
    private GameActionsPage actionsPage(String name) {
        List<GameActionsSheet.Item> items = drawnChips != null ? drawnChips
                : Collections.<GameActionsSheet.Item>emptyList();
        List<String> labels = new ArrayList<>(items.size());
        for (GameActionsSheet.Item item : items) {
            labels.add(item == GameActionsSheet.Item.COMPANION_APP && drawnCompanionLabel != null
                    ? drawnCompanionLabel : getString(item.label));
        }
        return new GameActionsPage(name, items, labels, drawnHidden);
    }

    @NonNull
    @Override
    public PageFocus bottomStops() {
        CompanionPage page = bottomPage();
        if (page instanceof GameAchievementsPage) {
            return ((GameAchievementsPage) page).stops();
        }
        if (page instanceof GameActionsPage) {
            return ((GameActionsPage) page).stops();
        }
        return new PageFocus(Collections.<int[]>emptyList());
    }

    /** Where R2 lands on the panel, or {@link PageFocus#STAY} when the page has no stop. */
    private int entryStop() {
        CompanionPage page = bottomPage();
        if (page instanceof GameAchievementsPage) {
            return ((GameAchievementsPage) page).entryStop();
        }
        if (page instanceof GameActionsPage) {
            return ((GameActionsPage) page).entryStop();
        }
        return PageFocus.STAY;
    }

    // R2, split: down onto the page's first row. Consumed while there is a page to go to, even
    // when it has no stop yet, so the trigger does not fall through to anything else.
    private boolean dropToPanel(boolean first) {
        if (panelFocus == null || bottomPage() == null) {
            return false;
        }
        int stop = entryStop();
        if (first && stop >= 0) {
            panelFocus.enterBottom(stop);
            updateHints();
        }
        return true;
    }

    // L2 from the panel: back to Resume or Play, as L2 does inline, whatever the d-pad left.
    private void riseFromPanel() {
        if (panelFocus == null) {
            return;
        }
        panelFocus.leaveBottom(true);
        View target = topStop();
        if (target != null && !target.hasFocus()) {
            target.requestFocus();
        }
    }

    /** Hands the page as it stands to the panel, while split and this screen is shown. */
    private void publishPage() {
        if (panelFocus == null || !isAdded() || getView() == null) {
            return;
        }
        if (!split) {
            panelFocus.clear();
        } else if (!StackedSectionFragment.isHiddenInSection(this)) {
            panelFocus.publish();
        }
    }

    // Hidden on its stack or with its section: the panel is the screen shown now's. Shown again:
    // the page goes back.
    private void onShownChanged(boolean hidden) {
        if (panelFocus == null || getView() == null) {
            return;
        }
        if (hidden) {
            panelFocus.onSectionHidden();
        } else {
            publishPage();
        }
        updateHints();
    }

    /** The panel came or went: the lower half moves between it and this screen. */
    private void onPanelShowing(@Nullable Boolean showing) {
        boolean next = Boolean.TRUE.equals(showing);
        if (next == split || getView() == null) {
            return;
        }
        boolean hadFocus = root.hasFocus();
        split = next;
        renderAchievements();
        applyChipsVisibility();
        publishPage();
        if (!refocusIfTaken(hadFocus)) {
            placeFocusIfLost();
        }
        updateHints();
    }

    @Override
    public void onPageTap(int stopId) {
        act(stopId);
    }

    @Override
    public void onActivate(int stop) {
        act(stop);
    }

    // A or a tap on a stop of the page on the panel: what the same stop does inline.
    private void act(int stop) {
        if (getView() == null) {
            return;
        }
        CompanionPage page = bottomPage();
        if (page instanceof GameAchievementsPage) {
            GameAchievementsPage achievementsPage = (GameAchievementsPage) page;
            AchievementFilter.Filter filter = GameAchievementsPage.filterAt(stop);
            if (filter != null) {
                onFilterChosen(filter);
            } else if (stop == GameAchievementsPage.STOP_STATUS) {
                if (achievementsPage.mode == GameAchievementsPage.Mode.FAILED) {
                    load(false);
                }
            } else {
                Achievement a = achievementsPage.rowAt(stop);
                if (a != null && AchievementFilter.isMasked(a, model.revealed())) {
                    onRevealRequested(a);
                }
            }
        } else if (page instanceof GameActionsPage) {
            GameActionsSheet.Item item = ((GameActionsPage) page).itemAt(stop);
            LibraryApp shown = app;
            if (item != null && shown != null) {
                host.launchController().runAction(item, shown);
            }
        }
    }

    @Override
    public void onExit(@NonNull PageFocus.Nav direction) {
        // Up off the page's first row is back up to this screen; off the last row, nowhere.
        if (direction == PageFocus.Nav.UP && panelFocus != null) {
            panelFocus.leaveBottom(true);
        }
    }

    @Override
    public void onBottomLeft(boolean restored) {
        // Not given back (the panel went, the view the d-pad came from went): onto the first
        // stop, unless a touch took it up, which leaves focus to the touch.
        if (!restored && isOnScreen() && !root.isInTouchMode()) {
            View target = initialTarget();
            if (target != null) {
                target.requestFocus();
            }
        }
        updateHints();
    }

    /**
     * The gamepad hints: where R2 goes from up here, or, with the d-pad on the panel, that it is
     * there and how L2 comes back. None when there is nowhere to go.
     */
    private void updateHints() {
        if (hints == null || getView() == null) {
            return;
        }
        if (panelFocus != null && panelFocus.zone() == PanelFocus.Zone.BOTTOM) {
            View back = topStop();
            String label = back == primary
                    ? getString(R.string.game_page_hint_below, primary.getText())
                    : getString(R.string.game_page_hint_below_up);
            hints.setHints(new GamepadHintsView.Hint(label, "R2", "L2"));
            return;
        }
        boolean below = split ? entryStop() >= 0 : achievements.firstStop() != null;
        if (!below) {
            hints.setHints();
            return;
        }
        String label = getString(bottomKind() == BottomKind.ACTIONS
                ? R.string.game_page_hint_actions : R.string.stats_achievements);
        hints.setHints(new GamepadHintsView.Hint(label, "R2"));
    }

    // --- Focus ---

    @Nullable
    @Override
    public View restoreFocusTarget() {
        if (!isAdded() || getView() == null) {
            return null;
        }
        // Focusable too: the panel's focus sink, which the d-pad leaves unfocusable behind it.
        if (lastFocused != null && lastFocused.isShown() && lastFocused.isFocusable()
                && isInside(lastFocused)) {
            return lastFocused;
        }
        return initialTarget();
    }

    @Override
    public void requestInitialFocus() {
        if (!isAdded() || getView() == null) {
            return;
        }
        View target = initialTarget();
        if (target != null) {
            target.requestFocus();
        }
        reportFocus();
    }

    /**
     * Resume or Play, else the first chip, else the achievements' filter or their retry row, else
     * the status row.
     */
    @Nullable
    private View initialTarget() {
        View target = topStop();
        if (target == null) {
            target = achievements.filterStop();
        }
        if (target == null) {
            target = achievements.retryStop();
        }
        if (target == null && status.getVisibility() == View.VISIBLE && status.isFocusable()) {
            target = status;
        }
        return target;
    }

    /** Where L2 returns: Resume or Play, else the first chip. */
    @Nullable
    private View topStop() {
        if (buttons.getVisibility() == View.VISIBLE) {
            return primary;
        }
        if (chipsScroll.getVisibility() == View.VISIBLE && chips.getChildCount() > 0) {
            return chips.getChildAt(0);
        }
        return null;
    }

    /**
     * After a redraw hid the view that had focus, puts the d-pad back on the page's first stop.
     * Hiding a focused view moves focus at once to the window's first stop, the rail, where
     * {@link #placeFocusIfLost} would leave it, so the caller notes {@code root.hasFocus()}
     * before the redraw: focus that was on the page and no longer is was taken, not moved.
     *
     * @return whether focus was put back
     */
    private boolean refocusIfTaken(boolean hadFocus) {
        if (!hadFocus || root.hasFocus() || !isOnScreen() || root.isInTouchMode()) {
            return false;
        }
        View target = initialTarget();
        return target != null && target.requestFocus();
    }

    /**
     * After a redraw took the focused view away (a chip that went), puts the d-pad back on the
     * page's first stop. Only while the page is on screen and
     * focus is nowhere visible: focus on the rail is the user's and is left there.
     */
    private void placeFocusIfLost() {
        if (!isOnScreen() || root.isInTouchMode()) {
            return;
        }
        View focused = requireActivity().getCurrentFocus();
        if (focused != null && focused.isShown()) {
            return;
        }
        View target = initialTarget();
        if (target != null) {
            target.requestFocus();
        }
    }

    /** Tells the companion panel the page's game, while the page is the one on screen. */
    private void reportFocus() {
        if (!isOnScreen()) {
            return;
        }
        if (app != null) {
            host.gameFocus().focusGame(app);
        } else {
            host.gameFocus().none();
        }
    }

    // Popped: the panel goes back to what the section's first screen reports, as when the
    // section is switched to (CompanionFeed.onSection); that screen's own reports refine it.
    private void reportRootFocus() {
        Fragment parent = getParentFragment();
        if (parent == null || StackedSectionFragment.isHiddenInSection(parent)) {
            return;
        }
        if (parent instanceof StatsSectionFragment) {
            host.gameFocus().none();
        } else {
            host.gameFocus().focusHost(null);
        }
    }

    private boolean isInside(View view) {
        View self = getView();
        for (Object p = view; p instanceof View; p = ((View) p).getParent()) {
            if (p == self) {
                return true;
            }
        }
        return false;
    }

    // Shown to the user: added, with a view, on top of a section that is not hidden.
    private boolean isOnScreen() {
        View view = getView();
        return isAdded() && view != null && view.isShown()
                && !StackedSectionFragment.isHiddenInSection(this);
    }

    // --- Layout ---

    /**
     * Wide or compact, by the window's width (see the class comment). Moves the buttons and the
     * body between the cover column, the info column and the page's column; does nothing when
     * the window class has not changed.
     */
    private void applyWindow() {
        boolean compact = WindowClass.from(getResources().getConfiguration().screenWidthDp)
                == WindowClass.COMPACT;
        if (compactApplied != null && compactApplied == compact) {
            return;
        }
        compactApplied = compact;
        View focused = root.findFocus();
        float density = getResources().getDisplayMetrics().density;

        detach(buttons);
        detach(body);
        LinearLayout.LayoutParams primaryLp = (LinearLayout.LayoutParams) primary.getLayoutParams();
        LinearLayout.LayoutParams quitLp = (LinearLayout.LayoutParams) quit.getLayoutParams();
        LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = Math.round(12 * density);
        if (compact) {
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            primaryLp.width = 0;
            primaryLp.weight = 1;
            quitLp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            quitLp.topMargin = 0;
            quitLp.setMarginStart(Math.round(8 * density));
            buttonsLp.topMargin = Math.round(12 * density);
            int index = column.indexOfChild(hero);
            column.addView(buttons, index + 1, buttonsLp);
            column.addView(body, index + 2, bodyLp);
            top.setGravity(Gravity.CENTER_VERTICAL);
        } else {
            buttons.setOrientation(LinearLayout.VERTICAL);
            primaryLp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            primaryLp.weight = 0;
            quitLp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            quitLp.topMargin = Math.round(8 * density);
            quitLp.setMarginStart(0);
            buttonsLp.topMargin = Math.round(10 * density);
            coverColumn.addView(buttons, buttonsLp);
            info.addView(body, bodyLp);
            top.setGravity(Gravity.TOP);
        }
        primary.setLayoutParams(primaryLp);
        quit.setLayoutParams(quitLp);

        ViewGroup.LayoutParams coverLp = coverFrame.getLayoutParams();
        int oldCoverWidth = coverLp.width;
        coverLp.width = getResources().getDimensionPixelSize(compact
                ? R.dimen.game_page_cover_width_compact : R.dimen.game_page_cover_width);
        coverLp.height = getResources().getDimensionPixelSize(compact
                ? R.dimen.game_page_cover_height_compact : R.dimen.game_page_cover_height);
        coverFrame.setLayoutParams(coverLp);
        // The library's art is decoded for the frame's width: one loaded for the compact frame
        // would be stretched, and blurred, in the wide one. Forgetting the version asks for it
        // again at the new width; the art already shown stays meanwhile (a refresh's LOADING
        // keeps it, and the game has not changed, so no title card stands in).
        if (oldCoverWidth != coverLp.width && coverAppId != NO_APP) {
            coverArtVersion = -1;
            renderCover();
        }
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(compact
                ? R.dimen.game_page_title_size_compact : R.dimen.game_page_title_size));

        renderTag(app != null && app.running);
        renderMeta();
        if (focused != null && isInside(focused)) {
            focused.requestFocus();
        }
    }

    private static void detach(View view) {
        ViewGroup parent = (ViewGroup) view.getParent();
        if (parent != null) {
            parent.removeView(view);
        }
    }

    /**
     * One centred column, no wider than reads comfortably, measured from this screen's width
     * rather than the window's, since the rail takes its share of a wide one. As in Stats.
     */
    private void installLayout(View view) {
        final int maxWidth = getResources().getDimensionPixelSize(R.dimen.stats_max_width);
        view.addOnLayoutChangeListener((v, left, t, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int gutter = getResources().getDimensionPixelSize(Boolean.TRUE.equals(compactApplied)
                    ? R.dimen.section_gutter : R.dimen.stats_gutter);
            int side = Math.max(gutter, ((right - left) - maxWidth) / 2);
            // Padding changes only when the width does, so this settles after one extra pass.
            if (column.getPaddingLeft() != side || column.getPaddingRight() != side) {
                column.setPadding(side, column.getPaddingTop(), side, column.getPaddingBottom());
            }
        });
    }

    /** The last-played line's words, from resources. */
    private final class Words implements GamePageModel.Words {
        @Override
        public String today() {
            return getString(R.string.game_stats_today);
        }

        @Override
        public String yesterday() {
            return getString(R.string.game_stats_yesterday);
        }

        @Override
        public String daysAgo(int days) {
            return getResources().getQuantityString(R.plurals.game_stats_days_ago, days, days);
        }

        @Override
        public String date(long millis, boolean withYear) {
            return text.date(millis, withYear);
        }

        @Override
        public String duration(long seconds) {
            return text.duration(seconds);
        }

        @Override
        public String lastPlayed(String day) {
            return getString(R.string.game_stats_last_played, day);
        }

        @Override
        public String lastPlayedFor(String day, String duration) {
            return getString(R.string.game_stats_last_played_for, day, duration);
        }

        @Override
        public String sessionBefore(String day, String duration) {
            return getString(R.string.game_page_session_before, day, duration);
        }
    }
}
