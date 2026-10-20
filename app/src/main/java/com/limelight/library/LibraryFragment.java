package com.limelight.library;

import android.view.KeyEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.limelight.grid.AppSortOrder;
import com.limelight.main.MainHost;
import com.limelight.main.StackedSectionFragment;

/**
 * The Library section: a {@link StackedSectionFragment} whose root is the home's shelves, with
 * "See all" pushed over them to show the whole grid.
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
 * X is the Library's own while the home or See all is on top: on either, it raises the
 * {@link HostSheet}. On any other screen pushed here X is that screen's, like every other key;
 * the frame passes keys and focus through to the screen on top. Main thread only.
 */
public class LibraryFragment extends StackedSectionFragment {

    private static final String TAG_SEE_ALL = "library:seeAll";

    public LibraryFragment() {
    }

    @NonNull
    @Override
    protected Fragment createRoot() {
        return new LibraryHomeFragment();
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
        push(SeeAllFragment.newInstance(order), TAG_SEE_ALL);
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

    @Override
    public boolean onSectionKey(KeyEvent event) {
        Fragment top = top();
        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_X
                && (top instanceof LibraryHomeFragment || top instanceof SeeAllFragment)) {
            if (event.getRepeatCount() == 0) {
                // Held, X raises one sheet, not one per repeat.
                showHostSheet(null);
            }
            return true;
        }
        return super.onSectionKey(event);
    }
}
