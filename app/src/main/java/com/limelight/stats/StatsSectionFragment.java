package com.limelight.stats;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.limelight.main.StackedSectionFragment;

/**
 * The Stats section: a {@link StackedSectionFragment} whose root is the statistics page
 * ({@link StatsFragment}), so a screen opened from it (a game's page) is pushed over the page
 * and back returns to the period and the row that led there. Main thread only.
 */
public class StatsSectionFragment extends StackedSectionFragment {

    @NonNull
    @Override
    protected Fragment createRoot() {
        return new StatsFragment();
    }
}
