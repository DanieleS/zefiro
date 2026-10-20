package com.limelight.library;

import android.content.Context;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.google.android.material.button.MaterialButton;
import com.limelight.R;
import com.limelight.computers.HostEntry;
import com.limelight.computers.HostLibrary;
import com.limelight.computers.HostRepository;
import com.limelight.grid.AppSortOrder;
import com.limelight.grid.AutofitGridLayoutManager;
import com.limelight.grid.GridSpacingItemDecoration;
import com.limelight.main.MainHost;
import com.limelight.main.Section;
import com.limelight.main.SectionFocus;
import com.limelight.main.SectionKeys;
import com.limelight.ui.MenuSheet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Every game of the selected host, in one grid: the Library's "See all".
 *
 * <p>It is the old library grid without {@code AppView}: the games come from the shared
 * {@code HostRepository}'s snapshot (no binding or polling of its own), a tap goes through the
 * activity's {@link LaunchController}, and a host that is offline, unpaired or still being
 * reached shows the {@link HostStatePanel} where the grid would be, rather than closing.
 *
 * <h3>What a press does</h3>
 *
 * <pre>
 *   A / tap           LaunchController.onTileClicked: start, resume, or the actions sheet
 *   Y / long-press    the game's actions sheet
 *   the sort chip     the orders, and "Show hidden games" (what "View all apps" was)
 * </pre>
 *
 * <h3>Focus</h3>
 *
 * The section remembers the game (by app id, not position) whose tile last held focus, or the
 * sort chip, and the focus gate returns there from the navigation. A new order moves the game,
 * not the memory: the grid scrolls to keep the remembered game in view, and a tile that held
 * focus through the re-sort keeps it. DOWN from the chip goes back to that game too, rather than
 * to whichever tile happens to sit under the chip in the new order. Entered before any game is
 * known, focus waits on the chip and moves to the first tile once one appears, unless the user
 * moved it meanwhile.
 *
 * <h3>In the Library's stack</h3>
 *
 * The Library's home pushes it, from the All games shelf's link or its trailing tile, and back
 * pops it; the back arrow shows only when there is something under it to go back to. X raises
 * the host sheet here as on the home; the Library's frame handles it for both.
 *
 * Main thread only; every callback checks that the view still exists.
 */
public class SeeAllFragment extends Fragment
        implements SectionKeys, SectionFocus, GameGridAdapter.Listener, HostStatePanel.Listener {

    private static final int NO_APP = Integer.MIN_VALUE;

    private MainHost host;

    @Nullable
    private RecyclerView grid;
    @Nullable
    private GameGridAdapter adapter;
    @Nullable
    private HostStatePanel panel;
    @Nullable
    private TextView subtitle;
    @Nullable
    private MaterialButton sortChip;

    // The last state drawn, kept to tell what changed on the next one.
    @Nullable
    private HostLibrary.Snapshot snapshot;
    private boolean showHidden;
    private int drawnArtVersion;
    @Nullable
    private Object drawnStandIns;
    @Nullable
    private AppSortOrder drawnOrder;
    // A new order was drawn and its list is on its way to the grid; see onListCommitted.
    private boolean reorderPending;
    // What the grid's list was last made from.
    @Nullable
    private HostLibrary.Snapshot submittedSnapshot;
    private boolean submittedShowHidden;

    // Focus memory. Survives the view, so a section shown again lands where it was left.
    private int lastFocusedAppId = NO_APP;
    private boolean lastFocusedChip;
    // Initial focus went to the chip only for want of a tile; hand it on when one arrives.
    private boolean focusAwaitingTiles;

    public SeeAllFragment() {
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (!(context instanceof MainHost)) {
            throw new IllegalStateException("SeeAllFragment needs a MainHost activity");
        }
        host = (MainHost) context;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_see_all, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        grid = view.findViewById(R.id.seeAllGrid);
        subtitle = view.findViewById(R.id.seeAllSubtitle);
        sortChip = view.findViewById(R.id.seeAllSort);
        panel = new HostStatePanel(view.findViewById(R.id.hostStatePanel), this);

        adapter = new GameGridAdapter(this);
        grid.setLayoutManager(new AutofitGridLayoutManager(requireContext(), GridSpanRule.SEE_ALL));
        grid.addItemDecoration(new GridSpacingItemDecoration(
                getResources().getDimensionPixelSize(R.dimen.see_all_tile_gap)));
        grid.setAdapter(adapter);
        // A change rebinds the tile in place: the default change animation would swap the
        // focused tile for a new view while a poll lands, and the d-pad would lose its place.
        RecyclerView.ItemAnimator animator = grid.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }
        // The tiles take focus, never the grid itself: an empty grid that held focus would
        // swallow the first d-pad press.
        grid.setFocusable(false);

        // Touch only: B is back for a d-pad, and a focusable arrow would be one more stop
        // between the chip and the grid.
        View back = view.findViewById(R.id.seeAllBack);
        boolean stacked = getParentFragmentManager().getBackStackEntryCount() > 0;
        back.setVisibility(stacked ? View.VISIBLE : View.GONE);
        back.setOnClickListener(v -> requireActivity().getOnBackPressedDispatcher().onBackPressed());

        sortChip.setOnClickListener(v -> showSortSheet());
        sortChip.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                lastFocusedChip = true;
            } else {
                focusAwaitingTiles = false;
            }
        });
        sortChip.setOnKeyListener((v, keyCode, event) -> keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                && event.getAction() == KeyEvent.ACTION_DOWN
                && focusRememberedTile());

        HostRepository repository = host.hostRepository();
        repository.getLibrary().observe(getViewLifecycleOwner(), s -> {
            snapshot = s;
            render();
        });
        // Only for the host's name, which the snapshot does not carry.
        repository.getHosts().observe(getViewLifecycleOwner(), hosts -> render());
        host.showHiddenGames().observe(getViewLifecycleOwner(), show -> {
            showHidden = Boolean.TRUE.equals(show);
            render();
        });
    }

    @Override
    public void onDestroyView() {
        if (grid != null) {
            grid.setAdapter(null);
        }
        grid = null;
        adapter = null;
        panel = null;
        subtitle = null;
        sortChip = null;
        drawnOrder = null;
        drawnStandIns = null;
        submittedSnapshot = null;
        reorderPending = false;
        focusAwaitingTiles = false;
        super.onDestroyView();
    }

    // --- Drawing ---

    private void render() {
        if (!isAdded() || getView() == null || adapter == null || panel == null || grid == null) {
            return;
        }
        HostLibrary.Snapshot s = snapshot;
        HostRepository repository = host.hostRepository();
        HostLibrary library = repository.currentLibrary();
        // The snapshot of a host whose library is not open yet comes without one.
        if (library != null && (s == null || !library.getHostUuid().equals(s.hostUuid))) {
            library = null;
        }
        if (adapter.setLibrary(library)) {
            // Cleared: whatever was submitted before is no longer there.
            submittedSnapshot = null;
        }

        List<LibraryApp> visible = visibleApps(s, showHidden);
        HostEntry entry = s != null ? repository.getHost(s.hostUuid) : null;
        String hostName = entry != null ? entry.details.name : null;

        View focus = requireActivity().getCurrentFocus();
        boolean focusInGrid = grid.hasFocus();
        boolean focusInPanel = panel.contains(focus);
        boolean focusWaiting = focusAwaitingTiles && focus != null && focus == sortChip;
        HostStatePanel.Mode previous = panel.mode();
        HostStatePanel.Mode mode = HostStatePanel.modeFor(s, visible.size());
        boolean allHidden = s != null && !s.apps.isEmpty() && visible.isEmpty();
        panel.show(mode, hostName, allHidden);
        grid.setVisibility(mode == HostStatePanel.Mode.NONE ? View.VISIBLE : View.GONE);
        // Before focus moves: the chip it may move to shows only with an open library.
        renderHeader(s, hostName, visible.size(), library != null);
        if (isOnScreen()) {
            moveFocusForPanel(previous, mode, focus == null, focusInGrid, focusInPanel, focusWaiting);
        }

        if (s != null && library != null) {
            if (drawnOrder != null && s.sortOrder != drawnOrder) {
                reorderPending = true;
            }
            drawnOrder = s.sortOrder;
        }
        // The host list is published on every poll of every host; only a new snapshot or a new
        // filter is a new list for the grid, so the diff is not run every 1.5 s for nothing.
        if (s != submittedSnapshot || showHidden != submittedShowHidden) {
            submittedSnapshot = s;
            submittedShowHidden = showHidden;
            adapter.submitList(visible, this::onListCommitted);
        }

        // Covers moved on the host's side (landed on disk, or found to be its stand-in): the
        // games are the same, so the diff says nothing, and the tiles are asked to look again.
        if (s != null && (s.artVersion != drawnArtVersion || s.noArtFingerprints != drawnStandIns)) {
            boolean first = drawnStandIns == null;
            drawnArtVersion = s.artVersion;
            drawnStandIns = s.noArtFingerprints;
            if (!first) {
                adapter.refreshArt();
            }
        }
    }

    /**
     * Keeps the d-pad on something useful as the panel comes and goes. The panel takes the
     * grid's place, so focus in the grid goes to the panel's way out; and when what the panel
     * offers changes under the d-pad (connecting turns into offline), its new way out takes focus
     * too, which is how "offline" lands on Wake. When the panel goes, the games come back, and
     * focus that was in the panel goes to them.
     */
    private void moveFocusForPanel(HostStatePanel.Mode previous, HostStatePanel.Mode mode,
                                   boolean noFocus, boolean focusInGrid, boolean focusInPanel,
                                   boolean focusWaiting) {
        if (panel == null || grid == null || adapter == null) {
            return;
        }
        if (panel.isShowing()) {
            boolean offerChanged = mode != previous && (noFocus || focusInPanel || focusWaiting);
            if (focusInGrid || offerChanged) {
                View target = restoreFocusTarget();
                if (target != null) {
                    target.requestFocus();
                }
            }
            return;
        }
        if (!focusInPanel && !(focusWaiting && previous != HostStatePanel.Mode.NONE)) {
            return;
        }
        // The panel's button went with the panel; park on the chip until a tile is laid out.
        View chip = visibleChip();
        if (chip == null || !chip.requestFocus()) {
            return;
        }
        focusAwaitingTiles = true;
        if (adapter.getItemCount() > 0) {
            // The games were already in the grid under the panel: no commit will come to hand
            // focus on, so do it once the grid has laid them out.
            grid.post(() -> {
                View first = firstTile();
                if (first != null && chip.isFocused()) {
                    focusAwaitingTiles = false;
                    first.requestFocus();
                }
            });
        }
    }

    private void renderHeader(@Nullable HostLibrary.Snapshot s, @Nullable String hostName,
                              int count, boolean sortable) {
        if (subtitle == null || sortChip == null) {
            return;
        }
        if (s == null || hostName == null) {
            subtitle.setText(null);
        } else {
            subtitle.setText(getResources().getQuantityString(
                    R.plurals.library_see_all_subtitle, count, hostName, count));
        }
        // The order is kept by the open library; with none there is nothing to sort.
        if (s == null || !sortable) {
            sortChip.setVisibility(View.GONE);
            return;
        }
        String order = getString(s.sortOrder.getLabelRes());
        sortChip.setText(order);
        sortChip.setContentDescription(getString(R.string.library_sort_a11y, order));
        sortChip.setVisibility(View.VISIBLE);
    }

    /** The games the grid lists: every game, or the ones not hidden. */
    @NonNull
    static List<LibraryApp> visibleApps(@Nullable HostLibrary.Snapshot s, boolean showHidden) {
        if (s == null) {
            return Collections.emptyList();
        }
        if (showHidden) {
            return s.apps;
        }
        List<LibraryApp> visible = new ArrayList<>(s.apps.size());
        for (LibraryApp app : s.apps) {
            if (!app.hidden) {
                visible.add(app);
            }
        }
        return visible;
    }

    private void onListCommitted() {
        if (!isAdded() || getView() == null || grid == null || adapter == null) {
            return;
        }
        if (reorderPending) {
            reorderPending = false;
            keepRememberedTileInView();
        }
        if (!isOnScreen() || adapter.getItemCount() == 0) {
            return;
        }
        final View chip = sortChip;
        if (focusAwaitingTiles && chip != null && chip.isFocused()) {
            focusAwaitingTiles = false;
            // One more pass, so the first tile's view exists to be focused.
            grid.post(() -> {
                View first = firstTile();
                if (first != null && chip.isFocused()) {
                    first.requestFocus();
                }
            });
        } else if (requireActivity().getCurrentFocus() == null) {
            // The focused tile went (the game was hidden or left the host) and took focus with it.
            grid.post(() -> {
                if (isAdded() && getView() != null && isOnScreen()
                        && requireActivity().getCurrentFocus() == null) {
                    requestInitialFocus();
                }
            });
        }
    }

    // After a re-sort the remembered game is somewhere else; bring it into view, and if its
    // tile held focus, give focus back to it once it is laid out where it now is.
    private void keepRememberedTileInView() {
        if (grid == null || adapter == null || lastFocusedAppId == NO_APP) {
            return;
        }
        int position = adapter.positionOf(lastFocusedAppId);
        if (position == RecyclerView.NO_POSITION) {
            return;
        }
        final boolean hadFocus = grid.hasFocus();
        grid.scrollToPosition(position);
        if (hadFocus) {
            final int appId = lastFocusedAppId;
            grid.post(() -> {
                View tile = tileFor(appId);
                if (tile != null) {
                    tile.requestFocus();
                }
            });
        }
    }

    // --- Sorting ---

    private void showSortSheet() {
        HostLibrary library = host.hostRepository().currentLibrary();
        HostLibrary.Snapshot s = snapshot;
        if (library == null || s == null) {
            return;
        }
        // The orders that rank by Playnite metadata are listed even on stock hosts: they fall
        // back to the name there, which is a sane result rather than an empty library.
        MenuSheet sheet = new MenuSheet(requireContext()).setTitle(getString(R.string.title_sort_order));
        for (final AppSortOrder order : AppSortOrder.values()) {
            sheet.addChecked(getString(order.getLabelRes()), order == s.sortOrder,
                    () -> library.setSortOrder(order));
        }
        final boolean showing = showHidden;
        sheet.addChecked(getString(R.string.library_show_hidden), showing,
                () -> host.setShowHiddenGames(!showing));
        sheet.showCentered(requireContext());
    }

    // --- Tiles ---

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
        lastFocusedAppId = app.appId();
        lastFocusedChip = false;
    }

    // --- The host state panel ---

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

    @Nullable
    private HostEntry selectedEntry() {
        HostLibrary.Snapshot s = snapshot;
        return s != null ? host.hostRepository().getHost(s.hostUuid) : null;
    }

    // --- Keys ---

    @Override
    public boolean onSectionKey(KeyEvent event) {
        if (!isAdded() || getView() == null || event.getRepeatCount() != 0
                || event.getKeyCode() != KeyEvent.KEYCODE_BUTTON_Y) {
            return false;
        }
        LibraryApp focused = focusedApp();
        if (focused == null) {
            return false;
        }
        host.launchController().showActions(focused);
        return true;
    }

    /** The game whose tile holds focus, or null when focus is elsewhere (the chip, the rail). */
    @Nullable
    private LibraryApp focusedApp() {
        View focus = requireActivity().getCurrentFocus();
        if (focus == null || grid == null || adapter == null) {
            return null;
        }
        RecyclerView.ViewHolder holder = grid.findContainingViewHolder(focus);
        return holder == null ? null : adapter.appAt(holder.getBindingAdapterPosition());
    }

    // --- Focus ---

    @Nullable
    @Override
    public View restoreFocusTarget() {
        if (!isAdded() || getView() == null) {
            return null;
        }
        if (panel != null && panel.isShowing()) {
            View target = panel.focusTarget();
            return target != null ? target : visibleChip();
        }
        if (!lastFocusedChip) {
            View tile = tileFor(lastFocusedAppId);
            if (tile != null) {
                return tile;
            }
            View first = firstTile();
            if (first != null) {
                return first;
            }
        }
        return visibleChip();
    }

    @Override
    public void requestInitialFocus() {
        if (!isAdded() || getView() == null) {
            return;
        }
        View target = restoreFocusTarget();
        if (target == null) {
            return;
        }
        boolean waiting = target == sortChip && (adapter == null || adapter.getItemCount() == 0);
        if (target.requestFocus()) {
            focusAwaitingTiles = waiting;
        }
    }

    /**
     * Moves focus to the remembered game's tile, scrolling it into view first if it is not laid
     * out. From the chip's DOWN, so the d-pad goes back to the game it left.
     *
     * @return false when there is no such game, and the d-pad should do what it would anyway
     */
    private boolean focusRememberedTile() {
        if (grid == null || adapter == null || grid.getVisibility() != View.VISIBLE
                || lastFocusedAppId == NO_APP) {
            return false;
        }
        View tile = tileFor(lastFocusedAppId);
        if (tile != null) {
            return tile.requestFocus();
        }
        final int position = adapter.positionOf(lastFocusedAppId);
        if (position == RecyclerView.NO_POSITION) {
            return false;
        }
        final int appId = lastFocusedAppId;
        grid.scrollToPosition(position);
        grid.post(() -> {
            View laidOut = tileFor(appId);
            if (laidOut != null) {
                laidOut.requestFocus();
            }
        });
        return true;
    }

    @Nullable
    private View tileFor(int appId) {
        if (grid == null || appId == NO_APP) {
            return null;
        }
        RecyclerView.ViewHolder holder = grid.findViewHolderForItemId(appId);
        return holder != null && holder.itemView.isShown() ? holder.itemView : null;
    }

    @Nullable
    private View firstTile() {
        if (grid == null || grid.getVisibility() != View.VISIBLE || grid.getChildCount() == 0) {
            return null;
        }
        View first = grid.getChildAt(0);
        return first.isShown() ? first : null;
    }

    @Nullable
    private View visibleChip() {
        return sortChip != null && sortChip.getVisibility() == View.VISIBLE ? sortChip : null;
    }

    // Shown to the user: added, with a view, and neither this screen nor the Library section
    // hidden. A hidden section's fragment is still resumed, so the lifecycle cannot tell.
    private boolean isOnScreen() {
        View view = getView();
        return isAdded() && view != null && view.isShown();
    }
}
