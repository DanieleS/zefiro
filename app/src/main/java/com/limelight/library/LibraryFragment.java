package com.limelight.library;

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
import com.limelight.grid.AppSortOrder;
import com.limelight.main.MainHost;
import com.limelight.main.SectionFocus;
import com.limelight.main.SectionKeys;

/**
 * The Library section: a frame holding the Library's own stack of screens in its child
 * fragment manager.
 *
 * <p>The Library is the one section with depth: its home lists shelves, and "See all" opens the
 * whole grid over them. The stack lives here, in a child manager, so the main screen's
 * navigator still sees one fragment per section and only shows and hides it; back pops the stack
 * through {@code MainActivity}, which watches this manager's back stack to know whether back has
 * work to do here.
 *
 * <pre>
 *   [Home] ──"See all"──▶ [Home (hidden), See all] ──back, or Library again──▶ [Home]
 * </pre>
 *
 * See all is added over the home, which is hidden rather than replaced: its views, its shelves'
 * scroll and its focus memory are all still there when back pops the grid, so focus can return
 * to the tile that led to it.
 *
 * <h3>Keys</h3>
 *
 * X is the Library's own: anywhere in it, on either screen, it raises the {@link HostSheet}.
 * Every other key, and focus, is the screen on top's business: this frame passes
 * {@link SectionKeys} and {@link SectionFocus} through to it. Main thread only.
 */
public class LibraryFragment extends Fragment implements SectionKeys, SectionFocus {

    private static final String TAG_HOME = "library:home";
    private static final String TAG_SEE_ALL = "library:seeAll";

    public LibraryFragment() {
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_library, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        FragmentManager children = getChildFragmentManager();
        // A restore brings the stack back by itself; only a first creation adds the root.
        if (children.findFragmentById(R.id.libraryContainer) == null) {
            // Now, not later: the navigator posts the section's initial focus right after the
            // section is shown, and the root has to be there to take it.
            children.beginTransaction()
                    .setReorderingAllowed(true)
                    .add(R.id.libraryContainer, new LibraryHomeFragment(), TAG_HOME)
                    .commitNow();
        }
    }

    /**
     * Pushes See all over the home, and gives it focus once it is laid out. A second push while
     * it is already up does nothing.
     */
    public void showSeeAll() {
        showSeeAll(null);
    }

    /**
     * As {@link #showSeeAll()}, listing the games in this order for as long as the grid is up,
     * without changing the library's own (Recently played's "›" opens it in recent order).
     *
     * @param order the order to open in; null for the library's
     */
    public void showSeeAll(@Nullable AppSortOrder order) {
        if (!isAdded() || isStateSaved()) {
            return;
        }
        FragmentManager children = getChildFragmentManager();
        if (children.findFragmentByTag(TAG_SEE_ALL) != null) {
            return;
        }
        Fragment home = children.findFragmentByTag(TAG_HOME);
        FragmentTransaction tx = children.beginTransaction()
                .setReorderingAllowed(true);
        if (home != null) {
            tx.hide(home);
        }
        tx.add(R.id.libraryContainer, SeeAllFragment.newInstance(order), TAG_SEE_ALL)
                .addToBackStack(TAG_SEE_ALL)
                .commit();
        // Run now, so See all's view exists for the focus posted below.
        children.executePendingTransactions();
        View view = getView();
        if (view != null) {
            view.post(this::requestInitialFocus);
        }
    }

    /**
     * Goes back to the home: pops See all when it is up, and does nothing otherwise. The
     * Library's navigation item chosen again, the touch way back where See all has no arrow.
     */
    public void showHome() {
        if (!isAdded() || isStateSaved()) {
            return;
        }
        FragmentManager children = getChildFragmentManager();
        if (children.findFragmentByTag(TAG_SEE_ALL) != null) {
            children.popBackStack(TAG_SEE_ALL, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }
    }

    /**
     * Raises the host sheet: dropped from the home's host chip when it is on screen, otherwise
     * wherever the window puts a menu (see {@link HostSheet#show}).
     *
     * @param anchor the chip that asked; null to use the home's own when it is shown
     */
    public void showHostSheet(@Nullable View anchor) {
        if (!isAdded() || !(getActivity() instanceof MainHost)) {
            return;
        }
        if (anchor == null) {
            Fragment top = top();
            if (top instanceof LibraryHomeFragment) {
                anchor = ((LibraryHomeFragment) top).hostChip();
            }
        }
        HostSheet.show(requireActivity(), (MainHost) requireActivity(), anchor);
    }

    /** The screen on top of the Library's stack, or null before the first one is added. */
    @Nullable
    private Fragment top() {
        if (!isAdded()) {
            return null;
        }
        Fragment top = getChildFragmentManager().findFragmentById(R.id.libraryContainer);
        return top != null && top.isAdded() && top.getView() != null ? top : null;
    }

    @Override
    public boolean onSectionKey(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_X) {
            if (event.getRepeatCount() != 0 || top() == null) {
                // Held, X raises one sheet, not one per repeat.
                return top() != null;
            }
            showHostSheet(null);
            return true;
        }
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
