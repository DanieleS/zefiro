package com.limelight.library;

import android.content.Context;
import android.content.res.Configuration;
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
import com.limelight.main.MainHost;
import com.limelight.main.Section;
import com.limelight.main.SectionFocus;
import com.limelight.main.SectionKeys;

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
 *   HeroAdapter         the game the host is running: Continue · Resume · Quit game   (or none)
 *   StatePanelAdapter   offline, not paired, connecting, no games…                   (or none)
 *   ShelfAdapter        Recently played                                               (or none)
 *   ShelfAdapter        All games                  See all · N   …  [See all · N]     (or none)
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
    private boolean focusSettlePosted;

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
        recent = new ShelfAdapter(R.string.library_recently_played, tiles, this, null);
        all = new ShelfAdapter(R.string.library_all_games, tiles, this, this::openSeeAll);

        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(new ConcatAdapter(header, hero, panel, recent, all));
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
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden || list == null) {
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

        View focus = requireActivity().getCurrentFocus();
        boolean focusInContent = focus != null && isInList(focus) && !header.isChip(focus);
        HostStatePanel.Mode previous = panel.mode();

        List<LibraryApp> apps = s != null ? s.apps : Collections.<LibraryApp>emptyList();
        int visible = LibraryShelves.visibleCount(apps);
        HostStatePanel.Mode mode = HostStatePanel.modeFor(s, visible);
        panel.show(mode, hostName, s != null && !apps.isEmpty() && visible == 0);
        hero.submit(HeroAdapter.heroFor(s), library, s != null ? s.metadata : null);

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
            if (focusAwaitingContent && focus != null && header.isChip(focus)) {
                View content = contentTarget();
                if (content != null) {
                    focusAwaitingContent = false;
                    content.requestFocus();
                }
                return;
            }
            if (focus == null || focusNeedsPlacing) {
                focusNeedsPlacing = false;
                // Focus outside the section (the rail) is the user's, not ours to move.
                if (focus == null || isInList(focus)) {
                    View target = initialTarget();
                    if (target != null && target.requestFocus()) {
                        focusAwaitingContent = header.isChip(target);
                    }
                }
            }
        });
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
        // The shelves remember their tile, and the focus watcher which shelf.
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
            library.showSeeAll();
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
        if (focus == null || header == null || hero == null || panel == null || recent == null
                || all == null || !isInList(focus)) {
            return;
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
