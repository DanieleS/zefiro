package com.limelight.library;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.limelight.main.StackedSectionFragment;

/**
 * The Search section: a {@link StackedSectionFragment} whose root is the field and its results
 * ({@link SearchFragment}), so a screen opened from a result (a game's page) is pushed over them
 * and back returns to the query and the tile that led there. Main thread only.
 */
public class SearchSectionFragment extends StackedSectionFragment {

    @NonNull
    @Override
    protected Fragment createRoot() {
        return new SearchFragment();
    }
}
