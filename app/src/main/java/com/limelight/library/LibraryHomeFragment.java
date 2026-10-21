package com.limelight.library;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.limelight.R;
import com.limelight.computers.HostEntry;
import com.limelight.computers.HostLibrary;
import com.limelight.computers.HostRepository;
import com.limelight.grid.AppSortOrder;
import com.limelight.main.MainHost;
import com.limelight.main.Section;
import com.limelight.main.SectionFocus;
import com.limelight.main.SectionKeys;
import com.limelight.main.WindowClass;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The Library's home: the selected host's games as a glance, after the approved Shelves mock.
 *
 * <p>It is the root of the Library's own stack ({@link LibraryFragment}); "See all" pushes the
 * full grid over it. One vertical list, its rows each from an adapter of their own, joined by a
 * {@link ConcatAdapter}:
 *
 * <pre>
 *   HeaderAdapter       "Library"                                        [● Taverna ▾]
 *                                                       (none on a short window, see below)
 *   HeroAdapter         the game the host is running: Running on … · Resume · Quit   (or none)
 *   StatePanelAdapter   offline, not paired, connecting, no games…                   (or none)
 *   ShelfAdapter        Recently played                              ›                (or none)
 *   ShelfAdapter        All games                  See all · N   …  [See all · N]     (or none)
 * </pre>
 *
 * <h3>Shape</h3>
 *
 * The rows take the window's shape ({@link #applyWindow}, {@link #shapeShelves}):
 *
 * <pre>
 *   wide window      two rows of tiles, their height capped so the header, the hero when there
 *                    is one, and both shelves fit the window's height. When they would be
 *                    shorter than a floor (a hero over a phone in landscape), the first shelf
 *                    takes the room under the hero and the second scrolls into view, as the
 *                    mocks draw a hero over one shelf. All games names the host after it.
 *   short window     the same, without the header row: a phone in landscape, whose mock has
 *                    no page title and lets the rail say where the user is
 *   compact window   Recently played a row, All games a three-column grid of two rows whose
 *                    last cell is "See all", so a phone in portrait shows it whole above the
 *                    bottom bar (the Shelves mock's phone frame has one row, and no such cell:
 *                    here it is the d-pad's way to the grid, as on the wide shelf)
 * </pre>
 *
 * Like See all it reads the shared {@code HostRepository}'s snapshot, launches through the
 * activity's {@link LaunchController}, and never closes on an offline or unpaired host.
 *
 * <h3>What a press does</h3>
 *
 * <pre>
 *   A / tap on a tile         LaunchController.onTileClicked: start, resume, or the sheet
 *   Y / long-press on a tile  the game's actions sheet; Y on the hero, the running game's
 *   Resume / Quit game        the running game, resumed or quit (after a confirmation)
 *   the host chip, X          the host sheet (X is the Library's, see LibraryFragment)
 *   the "See all" tile/link   See all, pushed over this screen
 *   Recently played's "›"     See all in recent order (touch only)
 * </pre>
 *
 * <h3>Focus</h3>
 *
 * Each shelf remembers its own tile ({@link ShelfRecyclerView}); this screen remembers which
 * part of it the d-pad was in (the chip, the hero, the panel or a shelf), which is where focus
 * returns from the navigation. With nothing remembered, focus goes, in order, to the first of:
 *
 * <pre>
 *   the hero's Resume ─▶ Recently played's first tile ─▶ All games' first tile
 *        ─▶ the panel's way out (Wake on an offline host) ─▶ the host chip
 * </pre>
 *
 * A d-pad-first screen ({@link MainHost#isDpadFirst}) is given that focus at launch even
 * though it starts in touch mode, once, so the companion panel shows a game rather than the
 * host. Focus placed by this screen, and not moved since, moves up when a row earlier in the
 * order arrives: the history behind Recently played is read after the games are listed.
 * Focus left on the chip only for want of anything else moves on when the games arrive; focus
 * in the content when the panel comes or goes is placed again by that order, which is how an
 * offline host lands the d-pad on Wake. Coming back from See all, focus returns to the tile that
 * led there. A host switch forgets all of it and scrolls the shelves back to their start.
 *
 * Main thread only; every callback checks that the view still exists.
 */
public class LibraryHomeFragment extends Fragment implements SectionKeys, SectionFocus,
        GameGridAdapter.Listener, HostStatePanel.Listener, HeroAdapter.Listener,
        HeaderAdapter.Listener {

    /** Which part of the screen the d-pad was last in. */
    private enum Spot {
        NONE,
        CHIP,
        HERO,
        PANEL,
        RECENT,
        ALL
    }

    // A shelf shows at most eight tiles across; this keeps two shelves' worth, plus the ones a
    // scroll brings in, ready in the shared pool.
    private static final int TILE_POOL_SIZE = 24;
    // GameGridAdapter's one view type.
    private static final int TILE_VIEW_TYPE = 0;
    // All games on a compact window: a grid of this many columns by rows.
    private static final int COMPACT_GRID_COLUMNS = 3;
    private static final int COMPACT_GRID_ROWS = 2;
    // The shortest a tile gets to make two shelves fit a wide window; past it, the first shelf
    // takes the room and the second scrolls into view. A tile with no cover shows its title
    // instead, which has to stay readable at this size.
    private static final int MIN_TILE_HEIGHT_DP = 112;

    private MainHost host;

    @Nullable
    private RecyclerView list;
    @Nullable
    private HeaderAdapter header;
    @Nullable
    private HeroAdapter hero;
    @Nullable
    private StatePanelAdapter panel;
    @Nullable
    private ShelfAdapter recent;
    @Nullable
    private ShelfAdapter all;
    @Nullable
    private ViewTreeObserver.OnGlobalFocusChangeListener focusWatcher;
    @Nullable
    private View.OnAttachStateChangeListener focusWatcherAttacher;

    // The last state drawn, kept to tell what changed on the next one.
    @Nullable
    private HostLibrary.Snapshot snapshot;
    @Nullable
    private HostLibrary.Snapshot submittedSnapshot;
    @Nullable
    private String drawnHostUuid;
    private int drawnArtVersion;
    @Nullable
    private Object drawnStandIns;

    // Focus memory. Survives the view, so a section shown again lands where it was left.
    private Spot lastSpot = Spot.NONE;
    private boolean lastHeroQuit;
    // Initial focus went to the chip only for want of anything else; hand it on when it comes.
    private boolean focusAwaitingContent;
    // The panel came or went with focus in the content; place it again once laid out.
    private boolean focusNeedsPlacing;
    // See all is what was pushed over the home, so its pop returns to the shelf's end. Any other
    // screen (a game's page) leaves focus to the stack, which returns it to the remembered spot.
    private boolean seeAllOpen;
    private boolean focusSettlePosted;
    // A d-pad-first screen's launch focus, taken out of touch mode, is placed once.
    private boolean launchFocusPlaced;
    // The view this screen focused on its own, until the user moves off it: while it holds
    // focus, a row that arrives ahead of it in the order (Recently played, once the history is
    // read) takes the focus over.
    @Nullable
    private View placedFocus;
    private boolean shapePosted;
    // The rows above the shelves as last laid out, each kept while it is off screen; see
    // shapeShelves.
    private int headerHeight;
    private int heroHeight;
    private int panelHeight;

    public LibraryHomeFragment() {
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (!(context instanceof MainHost)) {
            throw new IllegalStateException("LibraryHomeFragment needs a MainHost activity");
        }
        host = (MainHost) context;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_library_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        list = view.findViewById(R.id.libraryHomeList);

        RecyclerView.RecycledViewPool tiles = new RecyclerView.RecycledViewPool();
        tiles.setMaxRecycledViews(TILE_VIEW_TYPE, TILE_POOL_SIZE);
        header = new HeaderAdapter(this);
        hero = new HeroAdapter(this);
        panel = new StatePanelAdapter(this);
        recent = new ShelfAdapter(R.string.library_recently_played, tiles, this,
                this::openSeeAllRecent, false);
        all = new ShelfAdapter(R.string.library_all_games, tiles, this, this::openSeeAll, true);

        list.setLayoutManager(new LinearLayoutManager(requireContext()) {
            @Override
            public void onLayoutCompleted(RecyclerView.State state) {
                super.onLayoutCompleted(state);
                // The rows above the shelves are laid out now, and their height is what the
                // shelves have left; see shapeShelves.
                shapeShelvesSoon();
            }
        });
        list.setAdapter(new ConcatAdapter(header, hero, panel, recent, all));
        // The space between the two shelves: above All games while Recently played is over it.
        // Not padding at each shelf's foot, which would leave it under the last one too.
        final int shelfSpacing = getResources().getDimensionPixelSize(R.dimen.shelf_spacing);
        list.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect outRect, @NonNull View child,
                                       @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                RecyclerView.ViewHolder holder = parent.getChildViewHolder(child);
                if (holder instanceof ShelfAdapter.Holder && holder.getBindingAdapter() == all
                        && recent != null && recent.isShown()) {
                    outRect.top = shelfSpacing;
                }
            }
        });
        applyWindow();
        // A row changes in place (the chip, a shelf's count); the default change animation would
        // swap the focused view for a new one while a poll lands.
        RecyclerView.ItemAnimator animator = list.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }
        list.setFocusable(false);

        // One watcher for every part, rather than a listener per view: rows are recycled, the
        // memory is not.
        final ViewTreeObserver.OnGlobalFocusChangeListener watcher =
                (oldFocus, newFocus) -> onFocusMoved(newFocus);
        focusWatcher = watcher;
        // Added to the window's observer while attached, and taken off it on detach. Not added
        // here and removed in onDestroyView: the view is not attached yet here (its observer is
        // a floating one, merged into the window's on attach), and on a fragment removal it is
        // already detached there, when its observer is a fresh floating one and the removal
        // would miss, leaving this fragment registered on the activity's window.
        focusWatcherAttacher = new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                v.getViewTreeObserver().addOnGlobalFocusChangeListener(watcher);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                v.getViewTreeObserver().removeOnGlobalFocusChangeListener(watcher);
            }
        };
        view.addOnAttachStateChangeListener(focusWatcherAttacher);
        if (view.isAttachedToWindow()) {
            view.getViewTreeObserver().addOnGlobalFocusChangeListener(watcher);
        }

        HostRepository repository = host.hostRepository();
        repository.getLibrary().observe(getViewLifecycleOwner(), s -> {
            snapshot = s;
            render();
        });
        // For the host's name and state, which the snapshot does not carry.
        repository.getHosts().observe(getViewLifecycleOwner(), hosts -> render());
    }

    @Override
    public void onDestroyView() {
        View view = getView();
        if (view != null && focusWatcherAttacher != null) {
            view.removeOnAttachStateChangeListener(focusWatcherAttacher);
            // Still attached (a view destroyed in place): its observer is the window's.
            if (view.isAttachedToWindow() && focusWatcher != null) {
                view.getViewTreeObserver().removeOnGlobalFocusChangeListener(focusWatcher);
            }
        }
        focusWatcherAttacher = null;
        focusWatcher = null;
        if (recent != null) {
            recent.release();
        }
        if (all != null) {
            all.release();
        }
        if (list != null) {
            list.setAdapter(null);
        }
        list = null;
        header = null;
        hero = null;
        panel = null;
        recent = null;
        all = null;
        submittedSnapshot = null;
        drawnHostUuid = null;
        drawnStandIns = null;
        focusAwaitingContent = false;
        focusNeedsPlacing = false;
        focusSettlePosted = false;
        placedFocus = null;
        super.onDestroyView();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // The hero sizes its cover by the window (see HeroAdapter), and the activity handles
        // rotation itself, so nothing else would rebind it.
        if (hero != null && hero.getItemCount() > 0) {
            hero.notifyItemChanged(0);
        }
        applyWindow();
        render();
        shapeShelvesSoon();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden || list == null) {
            return;
        }
        boolean fromSeeAll = seeAllOpen;
        seeAllOpen = false;
        if (!fromSeeAll) {
            // A game's page popped: the stack hands focus back to the spot remembered here.
            return;
        }
        // Shown again: See all was popped. The d-pad goes back to the tile that led there; a
        // touch user is left where the shelf was, not scrolled to its end.
        list.post(() -> {
            if (!isOnScreen() || all == null || list == null || list.isInTouchMode()) {
                return;
            }
            ShelfRecyclerView shelf = all.shelf();
            if (shelf != null) {
                lastSpot = Spot.ALL;
                shelf.focusLastTile();
            } else {
                requestInitialFocus();
            }
        });
    }

    // --- Drawing ---

    /** The header row for the window: gone on a short one (see the class comment). */
    private void applyWindow() {
        if (header == null) {
            return;
        }
        Configuration config = getResources().getConfiguration();
        header.setShown(!WindowClass.isShortWide(config.screenWidthDp, config.screenHeightDp));
    }

    private boolean isCompact() {
        return WindowClass.from(getResources().getConfiguration().screenWidthDp)
                == WindowClass.COMPACT;
    }

    private void shapeShelvesSoon() {
        if (shapePosted || list == null) {
            return;
        }
        shapePosted = true;
        // Posted: a shelf's new shape asks for a layout, which is not to be asked during one.
        list.post(() -> {
            shapePosted = false;
            shapeShelves();
        });
    }

    /**
     * Gives the shelves the window's shape (see the class comment). In a wide window the tiles'
     * height is what the list's height leaves two shelves once the rows above them (the header,
     * the hero, the state panel), each shelf's own label and the space between them are taken
     * out; below the floor, what it leaves the first shelf alone.
     *
     * <p>Each row above is measured when it is laid out, and that height is kept for it while
     * it is scrolled off and recycled: summing only the rows still attached would grow the
     * shelves as the d-pad goes down and shrink them on the way back, moving the tiles under it.
     * A row counts while its adapter has it, so the hero coming or going resizes the tiles.
     */
    private void shapeShelves() {
        if (!isAdded() || list == null || recent == null || all == null) {
            return;
        }
        if (isCompact()) {
            recent.setShape(0, 0, 0);
            all.setShape(COMPACT_GRID_COLUMNS, COMPACT_GRID_ROWS, 0);
            return;
        }
        int height = list.getHeight() - list.getPaddingTop() - list.getPaddingBottom();
        int shelves = (recent.isShown() ? 1 : 0) + (all.isShown() ? 1 : 0);
        if (height <= 0 || shelves == 0 || header == null || hero == null || panel == null) {
            return;
        }
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            RecyclerView.Adapter<?> row = list.getChildViewHolder(child).getBindingAdapter();
            if (row == header) {
                headerHeight = child.getHeight();
            } else if (row == hero) {
                heroHeight = child.getHeight();
            } else if (row == panel) {
                panelHeight = child.getHeight();
            }
        }
        int above = (header.getItemCount() > 0 ? headerHeight : 0)
                + (hero.getItemCount() > 0 ? heroHeight : 0)
                + (panel.getItemCount() > 0 ? panelHeight : 0);
        Resources res = getResources();
        // A shelf's label row, and the half gap each tile carries above and below it.
        int shelfChrome = res.getDimensionPixelSize(R.dimen.action_size)
                + res.getDimensionPixelSize(R.dimen.see_all_tile_gap);
        int spacing = res.getDimensionPixelSize(R.dimen.shelf_spacing);
        int floor = Math.round(MIN_TILE_HEIGHT_DP * res.getDisplayMetrics().density);
        int room = height - above;
        int tileHeight = (room - (shelves - 1) * spacing) / shelves - shelfChrome;
        if (tileHeight < floor) {
            tileHeight = room - shelfChrome;
        }
        tileHeight = Math.max(floor, tileHeight);
        recent.setShape(0, 0, tileHeight);
        all.setShape(0, 0, tileHeight);
    }

    private void render() {
        if (!isAdded() || getView() == null || list == null || header == null || hero == null
                || panel == null || recent == null || all == null) {
            return;
        }
        HostLibrary.Snapshot s = snapshot;
        HostRepository repository = host.hostRepository();
        HostLibrary library = repository.currentLibrary();
        // The snapshot of a host whose library is not open yet comes without one.
        if (library != null && (s == null || !library.getHostUuid().equals(s.hostUuid))) {
            library = null;
        }

        String hostUuid = s != null ? s.hostUuid : null;
        if (!Objects.equals(hostUuid, drawnHostUuid)) {
            drawnHostUuid = hostUuid;
            onHostSwitched();
        }
        // Not short-circuited: both shelves let go of the old host.
        boolean cleared = recent.setLibrary(library) | all.setLibrary(library);
        if (cleared) {
            submittedSnapshot = null;
        }

        HostEntry entry = hostUuid != null ? repository.getHost(hostUuid) : null;
        String hostName = entry != null ? entry.details.name : null;
        header.bind(hostName, entry != null ? entry.details : null, s != null, requireContext());
        // A wide window names the host after All games, as the wide mocks do; on a short one
        // there is no chip above to name it.
        all.setLabelDetail(isCompact() ? null : hostName);
        boolean recentWasShown = recent.isShown();

        View focus = requireActivity().getCurrentFocus();
        boolean focusInContent = focus != null && isInList(focus) && !header.isChip(focus);
        HostStatePanel.Mode previous = panel.mode();

        List<LibraryApp> apps = s != null ? s.apps : Collections.<LibraryApp>emptyList();
        int visible = LibraryShelves.visibleCount(apps);
        HostStatePanel.Mode mode = HostStatePanel.modeFor(s, visible);
        panel.show(mode, hostName, s != null && !apps.isEmpty() && visible == 0);
        hero.submit(HeroAdapter.heroFor(s), library, s != null ? s.metadata : null, hostName);

        // The host list is published on every poll of every host; only a new snapshot is a new
        // list for the shelves, so the diffs are not run every 1.5 s for nothing.
        if (s != submittedSnapshot) {
            submittedSnapshot = s;
            if (mode == HostStatePanel.Mode.NONE && library != null) {
                recent.submit(LibraryShelves.recent(apps, s.metadata, library.getLaunchHistory()), 0);
                all.submit(LibraryShelves.preview(apps, null), visible);
            } else {
                recent.submit(Collections.<LibraryApp>emptyList(), 0);
                all.submit(Collections.<LibraryApp>emptyList(), 0);
            }
        }
        if (recent.isShown() != recentWasShown) {
            // The space above All games follows whether Recently played is over it.
            list.invalidateItemDecorations();
        }

        // Covers moved on the host's side (landed on disk, or found to be its stand-in): the
        // games are the same, so the diffs say nothing, and the tiles are asked to look again.
        if (s != null && (s.artVersion != drawnArtVersion || s.noArtFingerprints != drawnStandIns)) {
            boolean first = drawnStandIns == null;
            drawnArtVersion = s.artVersion;
            drawnStandIns = s.noArtFingerprints;
            if (!first) {
                recent.refreshArt();
                all.refreshArt();
                hero.refreshArt();
            }
        }

        if (mode != previous && (focus == null || focusInContent)) {
            focusNeedsPlacing = true;
        }
        settleFocusSoon();
    }

    /** Another host, or none: nothing the d-pad remembered applies to its games. */
    private void onHostSwitched() {
        lastSpot = Spot.NONE;
        lastHeroQuit = false;
        if (recent != null) {
            recent.reset();
        }
        if (all != null) {
            all.reset();
        }
    }

    /**
     * Places focus after the rows just submitted are laid out: the posted pass runs after the
     * layout the adapters' notifications asked for. Only while this screen is the one shown.
     */
    private void settleFocusSoon() {
        if (focusSettlePosted || list == null || !isOnScreen()) {
            return;
        }
        focusSettlePosted = true;
        list.post(() -> {
            focusSettlePosted = false;
            if (!isAdded() || getView() == null || header == null || !isOnScreen()) {
                return;
            }
            View focus = requireActivity().getCurrentFocus();
            if (placedFocus != null && focus == placedFocus && !focusAwaitingContent) {
                View first = contentTarget();
                if (first != null && first != focus) {
                    placeFocus(first);
                }
                return;
            }
            if (focusAwaitingContent && focus != null && header.isChip(focus)) {
                View content = contentTarget();
                if (content != null) {
                    focusAwaitingContent = false;
                    placeFocus(content);
                }
                return;
            }
            if (focus == null || focusNeedsPlacing) {
                focusNeedsPlacing = false;
                // Focus outside the section (the rail) is the user's, not ours to move.
                if (focus == null || isInList(focus)) {
                    View target = initialTarget();
                    if (target != null && placeFocus(target)) {
                        focusAwaitingContent = header.isChip(target);
                    }
                }
            }
        });
    }

    /**
     * Focuses the view. A d-pad-first screen still in touch mode at launch has its first game
     * focused anyway, once: requestFocus would refuse in touch mode, and the companion panel
     * would show only the host until the first press. The chip does not count: it is where focus
     * waits for the games, not where it is meant to be.
     */
    private boolean placeFocus(@NonNull View target) {
        boolean placed;
        if (!launchFocusPlaced && target.isInTouchMode() && header != null
                && !header.isChip(target) && host.isDpadFirst()) {
            launchFocusPlaced = true;
            placed = target.requestFocusFromTouch();
        } else {
            placed = target.requestFocus();
        }
        placedFocus = placed ? target : null;
        return placed;
    }

    // --- Rows' callbacks ---

    @Override
    public void onTileClicked(@NonNull LibraryApp app) {
        host.launchController().onTileClicked(app);
    }

    @Override
    public void onTileLongPressed(@NonNull LibraryApp app) {
        host.launchController().showActions(app);
    }

    @Override
    public void onTileFocused(@NonNull LibraryApp app) {
        // The shelves remember their tile, and the focus watcher which shelf; the watcher also
        // tells the companion panel, for the hero as much as the shelves.
    }

    @Override
    public void onResume(@NonNull LibraryApp game) {
        host.launchController().resume(game.app);
    }

    @Override
    public void onQuit(@NonNull LibraryApp game) {
        host.launchController().quit(game.app);
    }

    @Override
    public void onHostChip(@NonNull View chip) {
        LibraryFragment library = libraryFrame();
        if (library != null) {
            library.showHostSheet(chip);
        }
    }

    @Override
    public void onWake() {
        HostEntry entry = selectedEntry();
        if (entry != null) {
            host.hostActions().wake(entry.details);
        }
    }

    @Override
    public void onPair() {
        HostEntry entry = selectedEntry();
        if (entry != null) {
            host.hostActions().pair(entry.details, null, null);
        }
    }

    @Override
    public void onChooseHost() {
        host.showSection(Section.PCS);
    }

    private void openSeeAll() {
        LibraryFragment library = libraryFrame();
        if (library != null) {
            seeAllOpen = true;
            library.showSeeAll();
        }
    }

    /** Recently played's "›": the whole grid, by when each game was last played. */
    private void openSeeAllRecent() {
        LibraryFragment library = libraryFrame();
        if (library != null) {
            seeAllOpen = true;
            library.showSeeAll(AppSortOrder.RECENT);
        }
    }

    @Nullable
    private LibraryFragment libraryFrame() {
        Fragment parent = getParentFragment();
        return parent instanceof LibraryFragment && parent.isAdded()
                ? (LibraryFragment) parent : null;
    }

    @Nullable
    private HostEntry selectedEntry() {
        HostLibrary.Snapshot s = snapshot;
        return s != null ? host.hostRepository().getHost(s.hostUuid) : null;
    }

    /** The host chip while it is laid out, for the host sheet to drop from. */
    @Nullable
    View hostChip() {
        return header != null ? header.chip() : null;
    }

    // --- Keys ---

    @Override
    public boolean onSectionKey(KeyEvent event) {
        if (!isAdded() || getView() == null || event.getRepeatCount() != 0
                || event.getKeyCode() != KeyEvent.KEYCODE_BUTTON_Y) {
            return false;
        }
        LibraryApp focused = focusedGame();
        if (focused == null) {
            return false;
        }
        host.launchController().showActions(focused);
        return true;
    }

    /** The game under the d-pad: a shelf's tile, or the hero's; null anywhere else. */
    @Nullable
    private LibraryApp focusedGame() {
        View focus = requireActivity().getCurrentFocus();
        if (focus == null) {
            return null;
        }
        if (hero != null && hero.contains(focus)) {
            return hero.game();
        }
        if (recent != null && recent.contains(focus)) {
            return recent.appFor(focus);
        }
        if (all != null && all.contains(focus)) {
            return all.appFor(focus);
        }
        return null;
    }

    // --- Focus ---

    private void onFocusMoved(@Nullable View focus) {
        if (focus != placedFocus) {
            placedFocus = null;
        }
        if (focus == null || header == null || hero == null || panel == null || recent == null
                || all == null || !isInList(focus)) {
            return;
        }
        // Every part of the screen reports through the one listener: a game for the hero and
        // the shelves, the selected host for the chip and the state panel.
        LibraryApp game = focusedGame();
        if (game != null) {
            host.gameFocus().focusGame(game);
        } else {
            host.gameFocus().focusHost(null);
        }
        if (header.isChip(focus)) {
            lastSpot = Spot.CHIP;
            return;
        }
        focusAwaitingContent = false;
        if (hero.contains(focus)) {
            lastSpot = Spot.HERO;
            lastHeroQuit = hero.isQuit(focus);
        } else if (panel.contains(focus)) {
            lastSpot = Spot.PANEL;
        } else if (recent.contains(focus)) {
            lastSpot = Spot.RECENT;
        } else if (all.contains(focus)) {
            lastSpot = Spot.ALL;
        }
    }

    @Nullable
    @Override
    public View restoreFocusTarget() {
        if (!isAdded() || getView() == null) {
            return null;
        }
        View remembered = rememberedTarget();
        return remembered != null ? remembered : initialTarget();
    }

    @Override
    public void requestInitialFocus() {
        if (!isAdded() || getView() == null || header == null) {
            return;
        }
        View target = restoreFocusTarget();
        if (target != null && target.requestFocus()) {
            focusAwaitingContent = header.isChip(target);
        }
    }

    @Nullable
    private View rememberedTarget() {
        if (header == null || hero == null || panel == null || recent == null || all == null) {
            return null;
        }
        switch (lastSpot) {
            case CHIP:
                return header.chip();
            case HERO:
                return hero.button(lastHeroQuit);
            case PANEL:
                return panel.focusTarget();
            case RECENT:
                return entryOf(recent);
            case ALL:
                return entryOf(all);
            case NONE:
            default:
                return null;
        }
    }

    /** The order in the class comment, the chip last. */
    @Nullable
    private View initialTarget() {
        View content = contentTarget();
        return content != null ? content : header != null ? header.chip() : null;
    }

    /** The order in the class comment, without the chip. */
    @Nullable
    private View contentTarget() {
        if (hero == null || panel == null || recent == null || all == null) {
            return null;
        }
        View target = hero.button(false);
        if (target == null) {
            target = entryOf(recent);
        }
        if (target == null) {
            target = entryOf(all);
        }
        if (target == null) {
            target = panel.focusTarget();
        }
        return target;
    }

    @Nullable
    private static View entryOf(ShelfAdapter shelfAdapter) {
        ShelfRecyclerView shelf = shelfAdapter.shelf();
        return shelf != null ? shelf.entryTile() : null;
    }

    private boolean isInList(View view) {
        for (Object p = view.getParent(); p instanceof View; p = ((View) p).getParent()) {
            if (p == list) {
                return true;
            }
        }
        return false;
    }

    // Shown to the user: added, with a view, and neither this screen (See all over it) nor the
    // Library section hidden. A hidden fragment is still resumed, so the lifecycle cannot tell.
    private boolean isOnScreen() {
        View view = getView();
        return isAdded() && view != null && view.isShown();
    }
}
