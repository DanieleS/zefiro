package com.limelight.stats;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.limelight.R;
import com.limelight.companion.CompanionPage;
import com.limelight.companion.CompanionPageRenderer;
import com.limelight.nvstream.http.Achievement;

/**
 * Draws a {@link GameAchievementsPage} on the companion panel: the same
 * {@link AchievementsBlockView} the game page shows inline, in its panel mode (nothing
 * focusable, the d-pad's stop drawn activated, the list scrolling under a fixed header), or the
 * one status row while there is no list.
 *
 * <p>Taps name stops, as the page numbers them, and go back through {@link PageTaps}: a segment
 * by its filter, a masked row by its position, the failed row as the status stop. The block's
 * own listener is set on every bind, so a tap reaches the taps of the value being shown.
 *
 * <p>A stateless singleton; main thread only.
 */
final class GameAchievementsRenderer implements CompanionPageRenderer {

    static final GameAchievementsRenderer INSTANCE = new GameAchievementsRenderer();

    private GameAchievementsRenderer() {
    }

    @NonNull
    @Override
    public View create(@NonNull LayoutInflater inflater, @NonNull ViewGroup parent) {
        View view = inflater.inflate(R.layout.companion_page_game_achievements, parent, false);
        AchievementsBlockView block = view.findViewById(R.id.companionAchievementsBlock);
        block.setInline(false);
        return view;
    }

    @Override
    public void bind(@NonNull View view, @NonNull CompanionPage value, @NonNull PageTaps taps) {
        GameAchievementsPage page = (GameAchievementsPage) value;
        AchievementsBlockView block = view.findViewById(R.id.companionAchievementsBlock);
        View status = view.findViewById(R.id.companionAchievementsStatus);
        int stop = page.focusedStop();

        if (page.mode != GameAchievementsPage.Mode.LIST || page.data == null) {
            block.setVisibility(View.GONE);
            bindStatus(status, page, taps);
            return;
        }
        status.setVisibility(View.GONE);
        status.setOnClickListener(null);
        block.setVisibility(View.VISIBLE);
        block.setListener(new AchievementsBlockView.Listener() {
            @Override
            public void onFilterChosen(@NonNull AchievementFilter.Filter filter) {
                taps.tap(GameAchievementsPage.filterStop(filter));
            }

            @Override
            public void onRevealRequested(@NonNull Achievement achievement) {
                int position = block.positionOf(achievement);
                if (position >= 0) {
                    taps.tap(GameAchievementsPage.rowStop(position));
                }
            }

            @Override
            public void onRetry() {
                // The panel's failed row is the status row, bound above; the block's own is
                // never shown here.
            }
        });
        block.setSession(page.session);
        block.showAchievements(page.data, page.filter, page.revealed, page.nowMillis);
        AchievementFilter.Filter segment = GameAchievementsPage.filterAt(stop);
        block.setVirtualFocus(segment != null ? segment.ordinal() : -1,
                GameAchievementsPage.rowPosition(stop));
    }

    // Loading or failed: one row, a stop either way (see GameAchievementsPage), and a retry
    // when failed.
    private static void bindStatus(View status, GameAchievementsPage page, PageTaps taps) {
        boolean failed = page.mode == GameAchievementsPage.Mode.FAILED;
        status.setVisibility(View.VISIBLE);
        status.findViewById(R.id.companionAchievementsStatusProgress)
                .setVisibility(failed ? View.GONE : View.VISIBLE);
        status.findViewById(R.id.companionAchievementsStatusHint)
                .setVisibility(failed ? View.VISIBLE : View.GONE);
        TextView text = status.findViewById(R.id.companionAchievementsStatusText);
        text.setText(!failed ? R.string.game_stats_loading
                : page.unreadable ? R.string.game_page_stats_unreadable : R.string.game_stats_failed);
        text.setTextColor(ContextCompat.getColor(status.getContext(),
                failed ? R.color.danger : R.color.content_secondary));
        status.setActivated(page.focusedStop() == GameAchievementsPage.STOP_STATUS);
        if (failed) {
            status.setOnClickListener(v -> taps.tap(GameAchievementsPage.STOP_STATUS));
        } else {
            status.setOnClickListener(null);
            status.setClickable(false);
        }
    }
}
