package com.limelight.pcs;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.limelight.R;
import com.limelight.computers.HostEntry;
import com.limelight.main.MainHost;
import com.limelight.main.Section;
import com.limelight.main.SectionFocus;
import com.limelight.main.SectionKeys;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager.PairState;
import com.limelight.preferences.AddComputerManually;
import com.limelight.utils.UiHelper;

import java.util.List;

/**
 * The PCs section: every host the computer manager knows, one card each, and the way to add one.
 *
 * <p>It is {@code PcView}'s list without PcView: the hosts come from the shared
 * {@code HostRepository} (no binding or polling of its own), and everything done to a host goes
 * through the activity's {@link HostActions}, so a pairing started here survives the section
 * being hidden.
 *
 * <h3>What a press does</h3>
 *
 * <pre>
 *   A / tap       online and paired ──▶ select the host, show the Library
 *                 online, not paired ──▶ pair
 *                 otherwise (offline, checking) ──▶ the host's actions sheet
 *   X             the card's own action (Wake, Pair, Resume), when it has one
 *   Y / long-press  the host's actions sheet
 * </pre>
 *
 * X exists because the action is a button inside the card, and a d-pad cannot move onto a view
 * inside the focused one; the card keeps focus and the button it names fires it.
 *
 * <h3>Focus</h3>
 *
 * The section remembers the last card (by host, not position, since the list re-sorts) or the
 * Add PC chip, and the focus gate returns there from the navigation. Entered while no host is
 * known yet, focus waits on the chip and moves to the first card once one appears, unless the
 * user has moved it in the meantime: otherwise the first press of the d-pad would be spent
 * reaching the PCs this screen is for.
 *
 * Main thread only; every callback checks that the view still exists.
 */
public class PcsFragment extends Fragment implements SectionKeys, SectionFocus, HostCardAdapter.Listener {

    private MainHost host;

    @Nullable
    private View header;
    @Nullable
    private RecyclerView list;
    @Nullable
    private View empty;
    @Nullable
    private View addButton;
    @Nullable
    private HostCardBinder binder;
    @Nullable
    private HostCardAdapter adapter;

    // Focus memory: the host whose card last held focus, or the chip. Survives the view, so a
    // section shown again lands where it was left.
    @Nullable
    private String lastFocusedUuid;
    private boolean lastFocusedAdd;
    // Initial focus went to the chip only for want of a card; hand it on when one arrives.
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
        header = view.findViewById(R.id.pcsHeader);
        list = view.findViewById(R.id.pcsList);
        empty = view.findViewById(R.id.pcsEmpty);
        addButton = view.findViewById(R.id.pcsAddButton);

        binder = new HostCardBinder(requireContext());
        adapter = new HostCardAdapter(binder, this);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);
        // A change rebinds the card in place: the default change animation would swap the
        // focused card for a new view while a poll lands, and the d-pad would lose its place.
        RecyclerView.ItemAnimator animator = list.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }
        // The cards take focus, never the list itself: an empty list that held focus would
        // swallow the first d-pad press.
        list.setFocusable(false);

        addButton.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), AddComputerManually.class)));
        addButton.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                lastFocusedUuid = null;
                lastFocusedAdd = true;
                // Not a PC of its own: the companion panel goes back to the selected one.
                host.gameFocus().focusHost(null);
            } else {
                focusAwaitingCards = false;
            }
        });

        installCentring(view);

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
    public void onDestroyView() {
        if (binder != null) {
            binder.release();
        }
        if (list != null) {
            list.setAdapter(null);
        }
        header = null;
        list = null;
        empty = null;
        addButton = null;
        binder = null;
        adapter = null;
        focusAwaitingCards = false;
        super.onDestroyView();
    }

    // --- Hosts ---

    private void onHosts(List<HostEntry> hosts) {
        if (!isAdded() || getView() == null || adapter == null || empty == null) {
            return;
        }
        empty.setVisibility(hosts.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.submitList(hosts, this::onListCommitted);
    }

    private void onListCommitted() {
        if (!isAdded() || getView() == null || list == null || adapter == null || isHidden()) {
            return;
        }
        if (adapter.getItemCount() == 0) {
            return;
        }
        final View chip = addButton;
        if (focusAwaitingCards && chip != null && chip.isFocused()) {
            focusAwaitingCards = false;
            // One more pass, so the first card's view exists to be focused.
            list.post(() -> {
                View first = firstCard();
                if (first != null && chip.isFocused()) {
                    first.requestFocus();
                }
            });
        } else if (requireActivity().getCurrentFocus() == null) {
            // The focused card went (its host was deleted) and took focus with it.
            list.post(() -> {
                if (isAdded() && getView() != null && !isHidden()
                        && requireActivity().getCurrentFocus() == null) {
                    requestInitialFocus();
                }
            });
        }
    }

    // --- Cards ---

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
            showActions(entry);
        }
    }

    @Override
    public void onCardLongPressed(@NonNull HostEntry entry) {
        showActions(entry);
    }

    @Override
    public void onActionClicked(@NonNull HostEntry entry, int action) {
        host.hostActions().runCardAction(entry, action);
    }

    @Override
    public void onCardFocused(@NonNull HostEntry entry) {
        lastFocusedUuid = entry.details.uuid;
        lastFocusedAdd = false;
        // The companion panel follows the card, whichever PC is selected.
        host.gameFocus().focusHost(entry.details.uuid);
    }

    private void showActions(HostEntry entry) {
        HostActionsSheet.show(requireActivity(), entry, host.hostActions(), null);
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
            showActions(focused);
            return true;
        }
        int action = HostCardAdapter.actionFor(focused);
        if (action == HostCardBinder.ACTION_NONE) {
            return false;
        }
        host.hostActions().runCardAction(focused, action);
        return true;
    }

    /** The host whose card holds focus, or null when focus is elsewhere (the chip, the rail). */
    @Nullable
    private HostEntry focusedEntry() {
        View focus = requireActivity().getCurrentFocus();
        if (focus == null || list == null || adapter == null) {
            return null;
        }
        RecyclerView.ViewHolder holder = list.findContainingViewHolder(focus);
        return holder == null ? null : adapter.entryAt(holder.getBindingAdapterPosition());
    }

    // --- Focus ---

    @Nullable
    @Override
    public View restoreFocusTarget() {
        if (!isAdded() || getView() == null) {
            return null;
        }
        if (!lastFocusedAdd) {
            View card = cardFor(lastFocusedUuid);
            if (card != null) {
                return card;
            }
            View first = firstCard();
            if (first != null) {
                return first;
            }
        }
        return addButton;
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
        boolean waiting = target == addButton && (adapter == null || adapter.getItemCount() == 0);
        if (target.requestFocus()) {
            focusAwaitingCards = waiting;
        }
    }

    @Nullable
    private View cardFor(@Nullable String uuid) {
        if (list == null || adapter == null) {
            return null;
        }
        int position = adapter.positionOf(uuid);
        if (position == RecyclerView.NO_POSITION) {
            return null;
        }
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(position);
        return holder != null && holder.itemView.isShown() ? holder.itemView : null;
    }

    @Nullable
    private View firstCard() {
        if (list == null || list.getChildCount() == 0) {
            return null;
        }
        View first = list.getChildAt(0);
        return first.isShown() ? first : null;
    }

    // --- Layout ---

    /**
     * One centred column of cards, no wider than {@code pc_card_max_width}: the side padding is
     * what centres them, and what keeps a card on a TV from becoming a banner. Measured from this
     * section's width, not the screen's, since the rail takes its share in a wide window. The
     * header takes the same padding, so the title and the chip line up with the cards.
     */
    private void installCentring(View root) {
        final int maxWidth = getResources().getDimensionPixelSize(R.dimen.pc_card_max_width);
        final int minInset = getResources().getDimensionPixelSize(R.dimen.section_gutter);
        root.addOnLayoutChangeListener((v, left, top, right, bottom,
                                        oldLeft, oldTop, oldRight, oldBottom) -> {
            // Padding changes only when the width does, so this settles after one extra pass.
            int inset = Math.max(minInset, ((right - left) - maxWidth) / 2);
            applyInset(list, inset);
            applyInset(header, inset);
        });
    }

    private static void applyInset(@Nullable View view, int inset) {
        if (view != null && (view.getPaddingLeft() != inset || view.getPaddingRight() != inset)) {
            view.setPadding(inset, view.getPaddingTop(), inset, view.getPaddingBottom());
        }
    }

    private void refreshGamepad() {
        if (adapter != null) {
            adapter.setGamepadPresent(UiHelper.isGamepadConnected());
        }
    }
}
