package com.limelight.stats;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.limelight.utils.UiHelper;

/**
 * The statistics of one PC as a screen of its own, for the legacy library, which has no Stats
 * section to switch to.
 *
 * <p>It only hosts {@link StatsFragment}, the main screen's Stats section, handing it the
 * {@link HostLink} it was started with, so the two never drift apart. It forwards the gamepad
 * buttons the fragment binds, as the main screen does for its sections; B leaves, as everywhere.
 * It goes when the legacy library does.
 */
public class StatsActivity extends AppCompatActivity {

    private static final String TAG_STATS = "stats";

    /** Open the statistics of the host {@code link} points at. */
    public static void start(Context context, HostLink link) {
        Intent intent = new Intent(context, StatsActivity.class);
        link.putInto(intent);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiHelper.setLocale(this);

        HostLink link = HostLink.from(getIntent());
        if (link == null) {
            finish();
            return;
        }
        // A restore brings the fragment back by itself.
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .add(android.R.id.content, StatsFragment.forLink(link), TAG_STATS)
                    .commitNow();
        }
        // As the main screen does on entering a section, once the views are on the window.
        getWindow().getDecorView().post(() -> {
            Fragment stats = getSupportFragmentManager().findFragmentByTag(TAG_STATS);
            if (stats instanceof StatsFragment && !isFinishing()) {
                ((StatsFragment) stats).requestInitialFocus();
            }
        });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        Fragment stats = getSupportFragmentManager().findFragmentByTag(TAG_STATS);
        if (stats instanceof StatsFragment && stats.isAdded() && stats.getView() != null
                && ((StatsFragment) stats).onSectionKey(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
