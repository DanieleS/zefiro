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

import com.limelight.R;
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
 *   S6 (now):  [See all]                          root, nothing to pop
 *   S7:        [Home] ──"See all"──▶ [Home, See all]  back pops to Home
 * </pre>
 *
 * Keys and focus are the screen on top's business: this frame passes {@link SectionKeys} and
 * {@link SectionFocus} through to it. Main thread only.
 */
public class LibraryFragment extends Fragment implements SectionKeys, SectionFocus {

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
            // S7 replaces: the home screen becomes the root, and See all is pushed over it.
            // Now, not later: the navigator posts the section's initial focus right after the
            // section is shown, and the root has to be there to take it.
            children.beginTransaction()
                    .setReorderingAllowed(true)
                    .add(R.id.libraryContainer, new SeeAllFragment(), TAG_SEE_ALL)
                    .commitNow();
        }
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
