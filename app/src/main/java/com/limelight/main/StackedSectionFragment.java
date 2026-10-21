package com.limelight.main;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.limelight.R;

/**
 * A section that holds its own stack of screens in its child fragment manager: a root, and the
 * screens pushed over it (the Library's See all, a game's page).
 *
 * <p>The stack lives here, in a child manager, so the main screen's navigator still sees one
 * fragment per section and only shows and hides it. Back reaches the stack through
 * {@link SectionBack}: the main screen asks the visible section first, and this frame pops.
 *
 * <pre>
 *   [Root] ──push(A)──▶ [Root (hidden), A] ──push(B)──▶ [Root (hidden), A (hidden), B]
 *      ▲                                                          │
 *      └────────── popToRoot (the section's item chosen again) ───┘
 *                  pop (back) removes B and shows A again
 * </pre>
 *
 * <p>Invariants: the root is never popped, so the section always has a screen. A pushed screen
 * is added over its predecessor, which is hidden rather than replaced: its views, its scroll and
 * its focus memory are all still there when back pops the screen over it, so focus can return to
 * what led there: a pop that takes the focused view with it hands the d-pad to the screen below's
 * {@link SectionFocus#restoreFocusTarget()}. A push of a tag already on the stack does nothing;
 * {@link #replace} pops that screen first.
 *
 * <h3>Keys, focus and visibility</h3>
 *
 * Keys and focus are the screen on top's business: this frame passes {@link SectionKeys} and
 * {@link SectionFocus} through to it. A child fragment is not told when its parent is hidden or
 * shown ({@link Fragment#isHidden()} and {@link Fragment#onHiddenChanged} answer for one manager
 * only), so the section's own hide and show are passed to the top screen through
 * {@link SectionHiddenListener}, and {@link #isHiddenInSection} answers across both.
 *
 * <p>Every change of the stack is reported to the main screen
 * ({@link MainHost#backWorkChanged()}), whose back callback follows it. Main thread only.
 */
public abstract class StackedSectionFragment extends Fragment
        implements SectionKeys, SectionFocus, SectionBack {

    private static final String TAG_ROOT = "stack:root";

    /**
     * A screen on a stack that wants to know when its whole section is hidden or shown again, as
     * {@link Fragment#onHiddenChanged} would tell a section that is not stacked. Only the screen
     * on top is told: the ones under it are hidden on the stack already.
     */
    public interface SectionHiddenListener {
        /** Main thread only. */
        void onSectionHiddenChanged(boolean hidden);
    }

    // Registered with the child manager for the fragment's whole life, not its view's: a restore
    // brings the stack back before the view, and the main screen must hear of every change.
    private final FragmentManager.OnBackStackChangedListener stackWatcher = this::backWorkChanged;

    /** The section's first screen, added once, on the frame's first creation. */
    @NonNull
    protected abstract Fragment createRoot();

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getChildFragmentManager().addOnBackStackChangedListener(stackWatcher);
    }

    @Override
    public void onDestroy() {
        getChildFragmentManager().removeOnBackStackChangedListener(stackWatcher);
        super.onDestroy();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_section_stack, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        FragmentManager children = getChildFragmentManager();
        // A restore brings the stack back by itself; only a first creation adds the root.
        if (children.findFragmentById(R.id.sectionStackContainer) == null) {
            // Now, not later: the navigator posts the section's initial focus right after the
            // section is shown, and the root has to be there to take it.
            children.beginTransaction()
                    .setReorderingAllowed(true)
                    .add(R.id.sectionStackContainer, createRoot(), TAG_ROOT)
                    .commitNow();
        }
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        Fragment top = top();
        if (top instanceof SectionHiddenListener) {
            ((SectionHiddenListener) top).onSectionHiddenChanged(hidden);
        }
    }

    // --- The stack ---

    /**
     * Pushes a screen over the one on top, and gives it focus once it is laid out. Does nothing
     * when a screen with this tag is already on the stack, or once the state is saved.
     *
     * @param screen the screen to show
     * @param tag    its fragment tag, also its back-stack entry's name
     */
    public void push(@NonNull Fragment screen, @NonNull String tag) {
        if (!isAdded() || isStateSaved()) {
            return;
        }
        FragmentManager children = getChildFragmentManager();
        if (children.findFragmentByTag(tag) != null) {
            return;
        }
        Fragment top = top();
        FragmentTransaction tx = children.beginTransaction()
                .setReorderingAllowed(true);
        if (top != null) {
            tx.hide(top);
        }
        tx.add(R.id.sectionStackContainer, screen, tag)
                .addToBackStack(tag)
                .commit();
        // Run now, so the new screen's view exists for the focus posted below.
        children.executePendingTransactions();
        View view = getView();
        if (view != null) {
            view.post(this::requestInitialFocus);
        }
    }

    /**
     * Pushes a screen in place of the one with the same tag and every screen over it, or over
     * the top when there is none: the same kind of screen opened for something else (another
     * game's page). The pop runs at once, so the push does not find the tag still there.
     *
     * @param screen the screen to show
     * @param tag    its fragment tag, also its back-stack entry's name; never the root's
     */
    public void replace(@NonNull Fragment screen, @NonNull String tag) {
        if (!isAdded() || isStateSaved() || TAG_ROOT.equals(tag)) {
            return;
        }
        FragmentManager children = getChildFragmentManager();
        if (children.findFragmentByTag(tag) != null) {
            // No focus handed back to the screen below: the push gives it to the new screen.
            children.popBackStackImmediate(tag, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }
        push(screen, tag);
    }

    /** Pops the screen on top; never the root. */
    public void pop() {
        if (!isAdded() || isStateSaved() || depth() == 0) {
            return;
        }
        boolean hadFocus = topHasFocus();
        getChildFragmentManager().popBackStack();
        if (hadFocus) {
            restoreFocusAfterPop();
        }
    }

    /**
     * Pops the screen pushed with this tag, and every screen over it: a screen that has to go
     * whatever is on top of it (a game's page whose host is no longer the selected one). Does
     * nothing when no such screen is on the stack; never pops the root.
     */
    public void pop(@NonNull String tag) {
        if (!isAdded() || isStateSaved() || TAG_ROOT.equals(tag)
                || getChildFragmentManager().findFragmentByTag(tag) == null) {
            return;
        }
        boolean hadFocus = topHasFocus();
        getChildFragmentManager().popBackStack(tag, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        if (hadFocus) {
            restoreFocusAfterPop();
        }
    }

    /**
     * Pops every screen over the root. The section's navigation item chosen again: the touch way
     * back where a screen has no arrow.
     */
    public void popToRoot() {
        if (!isAdded() || isStateSaved() || depth() == 0) {
            return;
        }
        boolean hadFocus = topHasFocus();
        FragmentManager children = getChildFragmentManager();
        children.popBackStack(children.getBackStackEntryAt(0).getId(),
                FragmentManager.POP_BACK_STACK_INCLUSIVE);
        if (hadFocus) {
            restoreFocusAfterPop();
        }
    }

    private boolean topHasFocus() {
        Fragment top = top();
        View view = top != null ? top.getView() : null;
        return view != null && view.hasFocus();
    }

    /**
     * After a pop that took the focused view with it, hands the d-pad back to the screen now on
     * top: to what it last had focused ({@link SectionFocus#restoreFocusTarget()}), the row that
     * led to the popped screen. The framework would otherwise give focus to the first focusable
     * view in the window, the rail's first item, as the focused view is removed. Posted, so it
     * runs after the pop's transaction; a screen that places focus itself when shown again posts
     * after this, and has the last word.
     */
    private void restoreFocusAfterPop() {
        View view = getView();
        if (view == null) {
            return;
        }
        view.post(() -> {
            View self = getView();
            if (!isAdded() || self == null || isHiddenInSection(this) || self.isInTouchMode()) {
                return;
            }
            View focused = requireActivity().getCurrentFocus();
            if (focused != null && focused.isShown() && isInside(self, focused)) {
                return;
            }
            View target = restoreFocusTarget();
            if (target != null) {
                target.requestFocus();
            }
        });
    }

    private static boolean isInside(View ancestor, View view) {
        for (Object p = view; p instanceof View; p = ((View) p).getParent()) {
            if (p == ancestor) {
                return true;
            }
        }
        return false;
    }

    /** How many screens are pushed over the root: 0 at the root. */
    public int depth() {
        return isAdded() ? getChildFragmentManager().getBackStackEntryCount() : 0;
    }

    /** The screen on top of the stack, or null before the root is added or while it has no view. */
    @Nullable
    protected Fragment top() {
        if (!isAdded()) {
            return null;
        }
        Fragment top = getChildFragmentManager().findFragmentById(R.id.sectionStackContainer);
        return top != null && top.isAdded() && top.getView() != null ? top : null;
    }

    /**
     * Whether a screen is hidden, on its own stack or with the section around it. A screen that
     * holds off work while it is out of sight asks this rather than {@link Fragment#isHidden()}.
     */
    public static boolean isHiddenInSection(@NonNull Fragment screen) {
        for (Fragment f = screen; f != null; f = f.getParentFragment()) {
            if (f.isHidden()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tells the main screen that whether back has work here may have changed. The stack does it
     * on every change; a screen whose own back work changes (a mode entered) calls it too.
     */
    protected void backWorkChanged() {
        if (getActivity() instanceof MainHost) {
            ((MainHost) getActivity()).backWorkChanged();
        }
    }

    // --- Back ---

    @Override
    public boolean hasBackWork() {
        if (depth() > 0) {
            return true;
        }
        Fragment top = top();
        return top instanceof SectionBack && ((SectionBack) top).hasBackWork();
    }

    @Override
    public void onBack() {
        Fragment top = top();
        if (top instanceof SectionBack && ((SectionBack) top).hasBackWork()) {
            ((SectionBack) top).onBack();
            return;
        }
        pop();
    }

    // --- Keys and focus ---

    @Override
    public boolean onSectionKey(KeyEvent event) {
        Fragment top = top();
        return top instanceof SectionKeys && ((SectionKeys) top).onSectionKey(event);
    }

    @Nullable
    @Override
    public View restoreFocusTarget() {
        Fragment top = top();
        return top instanceof SectionFocus ? ((SectionFocus) top).restoreFocusTarget() : null;
    }

    @Override
    public void requestInitialFocus() {
        Fragment top = top();
        if (top instanceof SectionFocus) {
            ((SectionFocus) top).requestInitialFocus();
        }
    }
}
