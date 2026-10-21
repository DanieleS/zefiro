package com.limelight.pcs;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.google.android.material.button.MaterialButton;
import com.limelight.R;
import com.limelight.companion.CompanionDisplayManager;
import com.limelight.companion.CompanionPage;
import com.limelight.computers.HostEntry;
import com.limelight.grid.AutofitGridLayoutManager;
import com.limelight.main.MainHost;
import com.limelight.main.PageFocus;
import com.limelight.main.PanelFocus;
import com.limelight.main.Section;
import com.limelight.main.SectionFocus;
import com.limelight.main.SectionKeys;
import com.limelight.main.WindowClass;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager.PairState;
import com.limelight.preferences.AddComputerManually;
import com.limelight.ui.GamepadHintsView;
import com.limelight.utils.UiHelper;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The PCs section: every host the computer manager knows, one tile each in a grid, and the way
 * to add one.
 *
 * <p>The hosts come from the shared {@code HostRepository} (no binding or polling of its own), and
 * everything done to a host goes through the activity's {@link HostActions}, so a pairing started
 * here survives the section being hidden.
 *
 * <h3>What a press does</h3>
 *
 * <pre>
 *   A / tap            online and paired ──▶ select the host, show the Library
 *                      online, not paired ──▶ pair
 *                      otherwise (offline, checking) ──▶ the host's actions sheet
 *   X / the button     the tile's own action (Resume, Wake, Pair), when it has one
 *   Y / R2             with the companion panel, the d-pad goes down to the PC's page there;
 *                      without it, Y opens the host's actions sheet, centred (a bottom sheet in
 *                      a compact window)
 *   long-press         the host's actions sheet
 *   ⋮ (touch only)     the same sheet, dropped from the button in a wide window
 * </pre>
 *
 * X exists because the action is a button inside the tile, and a d-pad cannot move onto a view
 * inside the focused one; the tile keeps focus and the button it names fires it. The hints at
 * the foot say what A, X and Y do on the focused tile.
 *
 * <h3>Focus</h3>
 *
 * The grid is the hosts, then the Add PC tile, always last. The section remembers the last host
 * tile (by host, not position, since the list re-sorts) or the Add PC tile, and the focus gate
 * returns there from the navigation. The header's Add PC chip is reachable with UP from the
 * first row, but is not remembered: it is a second way to the same thing.
 *
 * <pre>
 *   entered, no host yet ──▶ focus waits on the Add PC tile
 *        │ a host is published, focus still waiting (on the Add PC tile, or lost with a layout)
 *        ▼
 *   the first host tile
 * </pre>
 *
 * Otherwise the first press of the d-pad would be spent reaching the PCs this screen is for. Any
 * move to another view ends the wait. A tile that goes with its host (deleted) takes focus with
 * it; the grid hands it to a neighbour, or the section puts it back where it starts.
 *
 * <h3>The companion panel</h3>
 *
 * While the panel is up ({@link CompanionDisplayManager#panelShowing()}) and this section is on
 * screen, the panel shows the focused PC ({@link PcDetailsPage}): its facts, the game it is in,
 * and every action of its sheet as a big touch row. The tiles stay up here; the page is their
 * lower half.
 *
 * <pre>
 *   a host tile focused ──▶ its page (rebuilt on every poll; an unchanged one is not sent)
 *   Add PC focused, or no host ──▶ no page: the panel's idle hosts surface
 *   focus on the rail ──▶ the page stays
 *
 *   Y / R2 on a tile ──▶ PanelFocus: the d-pad on the page, the tile's ring gone meanwhile
 *        │  d-pad moves, A acts, a tap acts without moving it
 *        ▼
 *   B, L2, UP off the first row ──▶ back on the tile
 * </pre>
 *
 * A stop is resolved against the page as it stands, never the one the d-pad went down on, so a
 * poll that reshapes the page (the PC went offline) cannot run the wrong row. An action that
 * covers the screen (a pairing dialog, Delete's confirmation, a stream) first brings the d-pad
 * up, so the dialog gets the keys and focus is back on the tile afterwards. The section hidden,
 * the panel gone, or the view destroyed take the page off, and the panel goes back to what the
 * section shown now reports.
 *
 * Main thread only; every callback checks that the view still exists.
 */
public class PcsFragment extends Fragment
        implements SectionKeys, SectionFocus, HostTileAdapter.Listener, PanelFocus.Owner {

    private MainHost host;

    @Nullable
    private RecyclerView list;
    @Nullable
    private View empty;
    @Nullable
    private View looking;
    @Nullable
    private TextView subtitle;
    @Nullable
    private MaterialButton addButton;
    @Nullable
    private GamepadHintsView hints;
    @Nullable
    private HostTileAdapter adapter;
    @Nullable
    private ViewTreeObserver.OnGlobalFocusChangeListener focusWatcher;
    @Nullable
    private View.OnAttachStateChangeListener focusWatcherAttacher;

    // The Add PC chip's wide shape, read from the layout once, so a compact window can take the
    // label off and a wide one put it back.
    private CharSequence addLabel;
    private int addPaddingStart;
    private int addPaddingEnd;
    private int addIconPadding;

    // Focus memory: the host whose tile last held focus, or the Add PC tile. Survives the view,
    // so a section shown again lands where it was left.
    @Nullable
    private String lastFocusedUuid;
    private boolean lastFocusedAdd;
    // Initial focus went to the Add PC tile (or the chip) only for want of a host tile; hand it
    // on when one arrives.
    private boolean focusAwaitingCards;

    // The companion panel is up: the focused PC's details go there.
    private boolean split;
    @Nullable
    private PanelFocus panelFocus;
    @Nullable
    private RunningCovers covers;
    // The hosts as last published, for the page: the grid's own list lags a diff behind.
    @NonNull
    private List<HostEntry> hostsNow = Collections.emptyList();
    // The host whose page the panel shows: the tile focused last, until Add PC takes focus.
    @Nullable
    private String pageUuid;
    // That page as it stands, focus aside; null with no panel or no host to show.
    @Nullable
    private PcDetailsPage page;

    public PcsFragment() {
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (!(context instanceof MainHost)) {
            throw new IllegalStateException("PcsFragment needs a MainHost activity");
        }
        host = (MainHost) context;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_pcs, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        list = view.findViewById(R.id.pcsList);
        empty = view.findViewById(R.id.pcsEmpty);
        looking = view.findViewById(R.id.pcsLooking);
        subtitle = view.findViewById(R.id.pcsSubtitle);
        addButton = view.findViewById(R.id.pcsAddButton);
        hints = view.findViewById(R.id.pcsHints);

        adapter = new HostTileAdapter(new HostTileBinder(requireContext()), this);
        // The grid's own width picks the columns, so the rail's share is already taken out.
        list.setLayoutManager(new AutofitGridLayoutManager(requireContext(),
                (widthDp, heightDp) -> PcTileSpanRule.spans(widthDp)));
        list.setAdapter(adapter);
        // A change rebinds the tile in place: the default change animation would swap the
        // focused tile for a new view while a poll lands, and the d-pad would lose its place.
        RecyclerView.ItemAnimator animator = list.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }
        // The tiles take focus, never the grid itself: a grid that held focus would swallow the
        // first d-pad press.
        list.setFocusable(false);

        addLabel = addButton.getText();
        addPaddingStart = addButton.getPaddingStart();
        addPaddingEnd = addButton.getPaddingEnd();
        addIconPadding = addButton.getIconPadding();
        addButton.setOnClickListener(v -> startAddComputer());
        addButton.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                onAddFocused();
            }
        });

        final ViewTreeObserver.OnGlobalFocusChangeListener watcher = (oldFocus, newFocus) -> {
            // Any move to another view is the user's own: the hand-off to the first host is off.
            if (focusAwaitingCards && newFocus != null && newFocus != addButton
                    && !isAddTile(newFocus)) {
                focusAwaitingCards = false;
            }
        };
        focusWatcher = watcher;
        // On the window's observer while attached, as in LibraryHomeFragment: here the view is
        // not attached yet (its observer is a floating one), and in onDestroyView it is already
        // detached, so a removal there would miss and leave this fragment on the window.
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

        applyWindow();

        covers = new RunningCovers(requireContext(), this::refreshPage);
        // Before the first publish, so a panel already on gets the page with the first hosts.
        split = Boolean.TRUE.equals(CompanionDisplayManager.panelShowing().getValue());
        // Before this screen's own observer: when the panel goes, PanelFocus brings the d-pad
        // back up first, and onPanelShowing then takes the page off.
        panelFocus = PanelFocus.attach(this, (ViewGroup) view, this);
        CompanionDisplayManager.panelShowing().observe(getViewLifecycleOwner(),
                this::onPanelShowing);

        host.hostRepository().getHosts().observe(getViewLifecycleOwner(), this::onHosts);
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshGamepad();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) {
            // The panel is the section shown now's: the d-pad comes up and the page goes.
            if (panelFocus != null && getView() != null) {
                panelFocus.onSectionHidden();
            }
            return;
        }
        // A pad plugged in while another section was showing still gets its letter.
        refreshGamepad();
        // The focused tile's page goes back on the panel, as it stands now.
        publishPage();
        updateHints();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // The activity handles rotation itself, so the header is shaped again here; the grid
        // picks its columns again on its next layout.
        applyWindow();
    }

    @Override
    public void onDestroyView() {
        View view = getView();
        if (view != null && focusWatcherAttacher != null) {
            view.removeOnAttachStateChangeListener(focusWatcherAttacher);
            // Still attached (a view torn down in place): its observer is the window's.
            if (view.isAttachedToWindow() && focusWatcher != null) {
                view.getViewTreeObserver().removeOnGlobalFocusChangeListener(focusWatcher);
            }
        }
        if (list != null) {
            list.setAdapter(null);
        }
        if (covers != null) {
            covers.release();
            covers = null;
        }
        // Its view lifecycle has ended: PanelFocus has taken the page off the panel already.
        panelFocus = null;
        page = null;
        pageUuid = null;
        // The fragment outlives its view (a hidden section): let the hosts' bitmaps go.
        hostsNow = Collections.emptyList();
        list = null;
        empty = null;
        looking = null;
        subtitle = null;
        addButton = null;
        hints = null;
        adapter = null;
        focusWatcher = null;
        focusWatcherAttacher = null;
        focusAwaitingCards = false;
        super.onDestroyView();
    }

    // --- Hosts ---

    private void onHosts(@Nullable List<HostEntry> hosts) {
        if (!isAdded() || getView() == null || adapter == null || empty == null || looking == null
                || subtitle == null) {
            return;
        }
        boolean none = hosts == null || hosts.isEmpty();
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        looking.setVisibility(none ? View.GONE : View.VISIBLE);
        renderSubtitle(hosts);
        hostsNow = none ? Collections.<HostEntry>emptyList() : hosts;
        adapter.submitList(hosts, this::onListCommitted);
        // The focused PC's page follows its polls. A host gone (deleted) has no page: the d-pad
        // comes up onto its tile, and the grid's commit then hands focus on as for any deletion.
        refreshPage();
    }

    /** "3 saved · 1 online": online is online and paired, the hosts that can be played on now. */
    private void renderSubtitle(@Nullable List<HostEntry> hosts) {
        if (subtitle == null) {
            return;
        }
        if (hosts == null || hosts.isEmpty()) {
            subtitle.setVisibility(View.GONE);
            return;
        }
        int online = 0;
        for (HostEntry entry : hosts) {
            HostStatus.Kind kind = HostStatus.kind(entry.details);
            if (kind == HostStatus.Kind.ONLINE || kind == HostStatus.Kind.PLAYING) {
                online++;
            }
        }
        subtitle.setText(getString(R.string.pcs_subtitle, hosts.size(), online));
        subtitle.setVisibility(View.VISIBLE);
    }

    private void onListCommitted() {
        if (!isAdded() || getView() == null || list == null || adapter == null || isHidden()) {
            return;
        }
        // The focused tile's host may have changed state: A, X and Y may now say other things.
        updateHints();
        if (focusAwaitingCards && adapter.hostCount() > 0) {
            // One more pass, so the first tile's view exists to be focused.
            list.post(() -> {
                if (!focusAwaitingCards || !isAdded() || getView() == null || isHidden()) {
                    return;
                }
                View first = firstTile();
                if (first != null) {
                    focusAwaitingCards = false;
                    first.requestFocus();
                }
            });
        } else if (currentFocus() == null) {
            // The focused tile went (its host was deleted) and took focus with it. With no hosts
            // left this lands on the Add tile, which then waits for the first host to turn up.
            list.post(() -> {
                if (isAdded() && getView() != null && !isHidden()
                        && currentFocus() == null) {
                    requestInitialFocus();
                }
            });
        }
    }

    // --- Tiles ---

    @Override
    public void onCardClicked(@NonNull HostEntry entry) {
        ComputerDetails details = entry.details;
        boolean online = details.state == ComputerDetails.State.ONLINE;
        boolean paired = details.pairState == PairState.PAIRED;
        if (online && paired) {
            host.hostRepository().select(details.uuid);
            host.showSection(Section.LIBRARY);
        } else if (online) {
            host.hostActions().pair(details, null, null);
        } else {
            showActions(entry, null);
        }
    }

    @Override
    public void onCardLongPressed(@NonNull HostEntry entry) {
        showActions(entry, null);
    }

    @Override
    public void onActionClicked(@NonNull HostEntry entry, int action) {
        host.hostActions().runCardAction(entry, action);
    }

    @Override
    public void onMoreClicked(@NonNull HostEntry entry, @NonNull View anchor) {
        showActions(entry, anchor);
    }

    @Override
    public void onCardFocused(@NonNull HostEntry entry) {
        if (entry.details.uuid != null && entryFor(entry.details.uuid) == null) {
            // The tile of a host just deleted, given focus back by the d-pad leaving its page
            // before the grid's diff lands: the commit moves focus on, and the companion is not
            // pointed at a PC that is gone meanwhile.
            return;
        }
        lastFocusedUuid = entry.details.uuid;
        lastFocusedAdd = false;
        // The companion panel follows the tile, whichever PC is selected.
        host.gameFocus().focusHost(entry.details.uuid);
        pageUuid = entry.details.uuid;
        refreshPage();
        updateHints();
    }

    @Override
    public void onAddClicked() {
        startAddComputer();
    }

    @Override
    public void onAddFocusChanged(boolean hasFocus) {
        if (!hasFocus) {
            return;
        }
        lastFocusedUuid = null;
        lastFocusedAdd = true;
        onAddFocused();
    }

    // The Add PC tile or chip: not a PC of its own, so the companion panel goes back to the
    // selected one, on its idle hosts surface.
    private void onAddFocused() {
        host.gameFocus().focusHost(null);
        pageUuid = null;
        refreshPage();
        updateHints();
    }

    private void showActions(HostEntry entry, @Nullable View anchor) {
        HostActionsSheet.show(requireActivity(), entry, host.hostActions(), anchor);
    }

    private void startAddComputer() {
        startActivity(new Intent(requireContext(), AddComputerManually.class));
    }

    // --- Keys ---

    @Override
    public boolean onSectionKey(KeyEvent event) {
        if (!isAdded() || getView() == null) {
            return false;
        }
        // On the panel, the d-pad, A and B are the page's, held d-pad repeats included.
        if (panelFocus != null && panelFocus.onKey(event)) {
            return true;
        }
        if (event.getRepeatCount() != 0) {
            return false;
        }
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BUTTON_L2) {
            if (isBelow()) {
                panelFocus.leaveBottom(true);
                return true;
            }
            return false;
        }
        if (code != KeyEvent.KEYCODE_BUTTON_X && code != KeyEvent.KEYCODE_BUTTON_Y
                && code != KeyEvent.KEYCODE_BUTTON_R2) {
            return false;
        }
        HostEntry focused = focusedEntry();
        if (focused == null) {
            return false;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_Y || code == KeyEvent.KEYCODE_BUTTON_R2) {
            if (dropToPanel(focused)) {
                return true;
            }
            // No panel: Y is the sheet, as it has always been; R2 has nowhere to go.
            if (code == KeyEvent.KEYCODE_BUTTON_Y) {
                showActions(focused, null);
                return true;
            }
            return false;
        }
        int action = HostTileBinder.actionFor(focused);
        if (action == HostTileBinder.ACTION_NONE) {
            return false;
        }
        host.hostActions().runCardAction(focused, action);
        return true;
    }

    /** The host whose tile holds focus, or null when focus is elsewhere (Add PC, the rail). */
    @Nullable
    private HostEntry focusedEntry() {
        View focus = currentFocus();
        if (focus == null || list == null || adapter == null) {
            return null;
        }
        RecyclerView.ViewHolder holder = list.findContainingViewHolder(focus);
        return holder == null ? null : adapter.entryAt(holder.getBindingAdapterPosition());
    }

    // --- Hints ---

    /**
     * What the pad does on what has focus: on a host tile, A (Library, Pair or the actions, as
     * {@link #onCardClicked} decides), X when the tile has an action, and Y; on Add PC, A; with
     * the d-pad on the panel, A and B. Left as they are while focus is outside the section.
     */
    private void updateHints() {
        if (hints == null || getActivity() == null) {
            return;
        }
        if (isBelow()) {
            hints.setHints(new GamepadHintsView.Hint(getString(R.string.pcs_hint_select), "A"),
                    new GamepadHintsView.Hint(getString(R.string.pcs_hint_back), "B"));
            return;
        }
        HostEntry focused = focusedEntry();
        if (focused != null) {
            hints.setHints(hintsFor(focused));
            return;
        }
        View focus = currentFocus();
        if (focus != null && (focus == addButton || isAddTile(focus))) {
            hints.setHints(new GamepadHintsView.Hint(getString(R.string.pcs_add_pc), "A"));
        }
    }

    private GamepadHintsView.Hint[] hintsFor(@NonNull HostEntry entry) {
        HostStatus.Kind kind = HostStatus.kind(entry.details);
        int a;
        if (kind == HostStatus.Kind.ONLINE || kind == HostStatus.Kind.PLAYING) {
            a = R.string.pcs_hint_library;
        } else if (kind == HostStatus.Kind.UNPAIRED) {
            a = R.string.pcview_action_pair;
        } else {
            a = R.string.pcs_hint_actions;
        }
        GamepadHintsView.Hint first = new GamepadHintsView.Hint(getString(a), "A");
        // With the panel, Y takes the d-pad down to the PC's actions there.
        GamepadHintsView.Hint last = new GamepadHintsView.Hint(getString(split
                ? R.string.pcs_hint_actions_below : R.string.pcs_hint_actions), "Y");
        int action = HostTileBinder.actionFor(entry);
        if (action == HostTileBinder.ACTION_NONE) {
            return new GamepadHintsView.Hint[]{first, last};
        }
        GamepadHintsView.Hint x = new GamepadHintsView.Hint(
                getString(HostTileBinder.actionLabel(action)), "X");
        return new GamepadHintsView.Hint[]{first, x, last};
    }

    // --- The companion panel ---

    /** Whether the d-pad is down on the panel's page. */
    private boolean isBelow() {
        return panelFocus != null && panelFocus.zone() == PanelFocus.Zone.BOTTOM;
    }

    /** The panel came or went: the focused PC's page goes to it, or comes off. */
    private void onPanelShowing(@Nullable Boolean showing) {
        boolean next = Boolean.TRUE.equals(showing);
        if (next == split || getView() == null) {
            return;
        }
        split = next;
        refreshPage();
        updateHints();
    }

    /**
     * Builds the focused PC's page again from the hosts as they stand, and hands it to the panel
     * when it changed. A poll that changed nothing the page shows sends nothing.
     */
    private void refreshPage() {
        if (getView() == null) {
            return;
        }
        PcDetailsPage next = buildPage();
        if (Objects.equals(next, page)) {
            return;
        }
        page = next;
        publishPage();
    }

    @Nullable
    private PcDetailsPage buildPage() {
        if (!split || pageUuid == null || covers == null) {
            return null;
        }
        HostEntry entry = entryFor(pageUuid);
        if (entry == null) {
            return null;
        }
        Bitmap art = null;
        if (HostStatus.kind(entry.details) == HostStatus.Kind.PLAYING) {
            // The cover once read; until then, or for a game without one, the hero image.
            art = covers.coverFor(entry);
            if (art == null) {
                art = entry.runningBackground;
            }
        }
        return PcDetailsPage.of(requireContext(), entry, art);
    }

    /**
     * Hands the page as it stands to the panel, while split and this section is shown; takes it
     * off when not split, or when there is no host to show (the panel goes back to its idle
     * hosts surface).
     */
    private void publishPage() {
        if (panelFocus == null || !isAdded() || getView() == null) {
            return;
        }
        if (!split) {
            panelFocus.clear();
            return;
        }
        if (isHidden()) {
            return;
        }
        panelFocus.publish();
    }

    @Nullable
    private HostEntry entryFor(@NonNull String uuid) {
        for (HostEntry entry : hostsNow) {
            if (uuid.equals(entry.details.uuid)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Y or R2 on a host tile, with the panel up: down onto its page's first stop.
     *
     * @return whether the d-pad went down; false without a panel, so Y opens the sheet
     */
    private boolean dropToPanel(@NonNull HostEntry focused) {
        if (!split || panelFocus == null) {
            return false;
        }
        if (page == null || !page.uuid.equals(focused.details.uuid)) {
            // The focus report has not landed yet: the page is the focused tile's.
            pageUuid = focused.details.uuid;
            refreshPage();
        }
        PcDetailsPage shown = page;
        if (shown == null) {
            return false;
        }
        int stop = shown.entryStop();
        if (stop < 0 || !panelFocus.enterBottom(stop)) {
            return false;
        }
        updateHints();
        return true;
    }

    @Nullable
    @Override
    public CompanionPage bottomPage() {
        return page;
    }

    @NonNull
    @Override
    public PageFocus bottomStops() {
        PcDetailsPage shown = page;
        return shown != null ? shown.stops() : new PageFocus(Collections.<int[]>emptyList());
    }

    @Override
    public void onExit(@NonNull PageFocus.Nav direction) {
        // Up off the first row is back up to the tile; off the last row (Delete PC), nowhere.
        if (direction == PageFocus.Nav.UP && panelFocus != null) {
            panelFocus.leaveBottom(true);
        }
    }

    @Override
    public void onActivate(int stop) {
        act(stop);
    }

    @Override
    public void onPageTap(int stopId) {
        act(stopId);
    }

    /**
     * A or a tap on a stop: the row's action, on the host the page shows now. A stop the page no
     * longer has (it was reshaped by a poll) does nothing. Anything but Wake, which only says
     * so in a toast, covers this screen with a dialog, another section or a stream, so the
     * d-pad comes back up first: the dialog gets the keys, and focus is on the tile afterwards.
     */
    private void act(int stop) {
        if (!isAdded() || getView() == null) {
            return;
        }
        PcDetailsPage shown = page;
        if (shown == null) {
            return;
        }
        HostActionsSheet.Item item = shown.itemAt(stop);
        HostEntry entry = entryFor(shown.uuid);
        if (item == null || entry == null) {
            return;
        }
        if (item != HostActionsSheet.Item.WAKE && panelFocus != null) {
            panelFocus.leaveBottom(true);
        }
        HostActionsSheet.run(host.hostActions(), item, entry);
    }

    @Override
    public void onBottomLeft(boolean restored) {
        // Not given back (the panel went, the tile went): onto the tile it left, else where the
        // section starts; a touch that took the d-pad up leaves focus to the touch.
        View view = getView();
        if (!restored && isAdded() && view != null && !isHidden() && !view.isInTouchMode()) {
            requestInitialFocus();
        }
        updateHints();
    }

    // --- Focus ---

    @Nullable
    @Override
    public View restoreFocusTarget() {
        if (!isAdded() || getView() == null) {
            return null;
        }
        if (!lastFocusedAdd) {
            View tile = tileFor(lastFocusedUuid);
            if (tile != null) {
                return tile;
            }
            View first = firstTile();
            if (first != null) {
                return first;
            }
        }
        View add = addTile();
        // Before the grid's first layout the tile has no view yet; the chip does the same thing.
        return add != null ? add : addButton;
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
        boolean waiting = (target == addButton || isAddTile(target))
                && (adapter == null || adapter.hostCount() == 0);
        if (target.requestFocus()) {
            focusAwaitingCards = waiting;
        }
    }

    @Nullable
    private View tileFor(@Nullable String uuid) {
        if (list == null || adapter == null) {
            return null;
        }
        int position = adapter.positionOf(uuid);
        if (position == RecyclerView.NO_POSITION) {
            return null;
        }
        return shown(list.findViewHolderForAdapterPosition(position));
    }

    @Nullable
    private View firstTile() {
        if (list == null || adapter == null || adapter.hostCount() == 0) {
            return null;
        }
        return shown(list.findViewHolderForAdapterPosition(0));
    }

    @Nullable
    private View addTile() {
        return list == null ? null : shown(list.findViewHolderForItemId(HostTileAdapter.ADD_TILE_ID));
    }

    private boolean isAddTile(@NonNull View view) {
        if (list == null) {
            return false;
        }
        RecyclerView.ViewHolder holder = list.findContainingViewHolder(view);
        return holder != null && holder.getItemViewType() == HostTileAdapter.TYPE_ADD;
    }

    /** The view the window's focus is on, or null; asked of the window's views themselves. */
    @Nullable
    private View currentFocus() {
        if (getActivity() == null) {
            return null;
        }
        return requireActivity().getWindow().getDecorView().findFocus();
    }

    @Nullable
    private static View shown(@Nullable RecyclerView.ViewHolder holder) {
        return holder != null && holder.itemView.isShown() ? holder.itemView : null;
    }

    // --- Layout ---

    /**
     * The header for the window: the Add PC chip with its label in a wide one, a 48dp plus in a
     * compact one, where the title and the count need the width. Same view, same focus, so a
     * rotation does not move the d-pad.
     */
    private void applyWindow() {
        if (addButton == null) {
            return;
        }
        boolean compact = WindowClass.from(getResources().getConfiguration().screenWidthDp)
                == WindowClass.COMPACT;
        if (compact) {
            int size = getResources().getDimensionPixelSize(R.dimen.action_size);
            int icon = getResources().getDimensionPixelSize(R.dimen.header_chip_icon);
            int inset = (size - icon) / 2;
            addButton.setText(null);
            addButton.setIconPadding(0);
            addButton.setPaddingRelative(inset, addButton.getPaddingTop(), inset,
                    addButton.getPaddingBottom());
            addButton.setMinimumWidth(size);
            addButton.setMinWidth(size);
        } else {
            addButton.setText(addLabel);
            addButton.setIconPadding(addIconPadding);
            addButton.setPaddingRelative(addPaddingStart, addButton.getPaddingTop(), addPaddingEnd,
                    addButton.getPaddingBottom());
            addButton.setMinimumWidth(0);
            addButton.setMinWidth(0);
        }
    }

    private void refreshGamepad() {
        if (adapter != null) {
            adapter.setGamepadPresent(UiHelper.isGamepadConnected());
        }
    }
}
