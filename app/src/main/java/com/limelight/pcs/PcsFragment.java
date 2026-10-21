package com.limelight.pcs;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
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
import com.limelight.computers.HostEntry;
import com.limelight.grid.AutofitGridLayoutManager;
import com.limelight.main.MainHost;
import com.limelight.main.Section;
import com.limelight.main.SectionFocus;
import com.limelight.main.SectionKeys;
import com.limelight.main.WindowClass;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager.PairState;
import com.limelight.preferences.AddComputerManually;
import com.limelight.ui.GamepadHintsView;
import com.limelight.utils.UiHelper;

import java.util.List;

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
 *   Y / long-press     the host's actions sheet, centred (a bottom sheet in a compact window)
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
 * Main thread only; every callback checks that the view still exists.
 */
public class PcsFragment extends Fragment implements SectionKeys, SectionFocus, HostTileAdapter.Listener {

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
                // Not a PC of its own: the companion panel goes back to the selected one.
                host.gameFocus().focusHost(null);
                updateHints();
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
        if (!hidden) {
            // A pad plugged in while another section was showing still gets its letter.
            refreshGamepad();
        }
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
        adapter.submitList(hosts, this::onListCommitted);
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
        lastFocusedUuid = entry.details.uuid;
        lastFocusedAdd = false;
        // The companion panel follows the tile, whichever PC is selected.
        host.gameFocus().focusHost(entry.details.uuid);
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
        // Not a PC of its own: the companion panel goes back to the selected one.
        host.gameFocus().focusHost(null);
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
        if (!isAdded() || getView() == null || event.getRepeatCount() != 0) {
            return false;
        }
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_BUTTON_X && code != KeyEvent.KEYCODE_BUTTON_Y) {
            return false;
        }
        HostEntry focused = focusedEntry();
        if (focused == null) {
            return false;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_Y) {
            showActions(focused, null);
            return true;
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
     * {@link #onCardClicked} decides), X when the tile has an action, and Y; on Add PC, A. Left
     * as they are while focus is outside the section.
     */
    private void updateHints() {
        if (hints == null || getActivity() == null) {
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
        GamepadHintsView.Hint last = new GamepadHintsView.Hint(getString(R.string.pcs_hint_actions), "Y");
        int action = HostTileBinder.actionFor(entry);
        if (action == HostTileBinder.ACTION_NONE) {
            return new GamepadHintsView.Hint[]{first, last};
        }
        GamepadHintsView.Hint x = new GamepadHintsView.Hint(
                getString(HostTileBinder.actionLabel(action)), "X");
        return new GamepadHintsView.Hint[]{first, x, last};
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
