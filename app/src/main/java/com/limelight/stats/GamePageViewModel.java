package com.limelight.stats;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.limelight.LimeLog;
import com.limelight.nvstream.http.GameAchievements;
import com.limelight.nvstream.http.GameStats;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * One game page's state, kept apart from its views: the host's answer about the game, the
 * achievements filter, and which hidden achievements the user asked to see. A page's views go
 * and come back (a restore, a configuration change), and the answer, the filter and the reveals
 * come back with them instead of starting over behind a spinner.
 *
 * <h3>Requests</h3>
 *
 * Both of the game's requests run one after the other on the model's own thread, and their
 * answer is published as one {@link GamePageData}. Only the latest load's answer is published:
 * a retry pressed while an earlier load is still out makes that one stale, as in
 * {@link StatsViewModel}.
 *
 * <pre>
 *   load()        request++ ──▶ (LOADING, unless quiet) ──▶ worker: stats, achievements
 *                                                              │
 *   main: request still the latest? ──yes──▶ data = answer ◀───┘
 *                                   ──no───▶ dropped
 * </pre>
 *
 * Main thread only, apart from the worker's own requests.
 */
public class GamePageViewModel extends ViewModel {

    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MutableLiveData<GamePageData> data = new MutableLiveData<>();
    private final Set<String> revealed = new HashSet<>();
    private AchievementFilter.Filter filter = AchievementFilter.Filter.ALL;
    private int request;

    /** The latest answer; null until the first load. */
    @NonNull
    public LiveData<GamePageData> data() {
        return data;
    }

    /**
     * Asks the host for the game's statistics and achievements.
     *
     * @param session the way to the host; null when it has no address yet, which is published as
     *                FAILED at once, so the page offers the retry
     * @param quiet   keep what is on screen until the answer comes, instead of the spinner: a
     *                refresh of a page already drawn
     */
    public void load(@Nullable HostSession session, @NonNull String appUuid, boolean quiet) {
        final int mine = ++request;
        if (session == null) {
            data.setValue(GamePageData.unreachable());
            return;
        }
        if (!quiet || data.getValue() == null) {
            data.setValue(GamePageData.loading());
        }
        try {
            loader.execute(() -> {
                GameStats stats = null;
                GameAchievements achievements = null;
                boolean statsFailed = false;
                boolean achievementsFailed = false;
                try {
                    stats = session.http().getGameStats(appUuid);
                } catch (Exception e) {
                    LimeLog.warning("Game page stats: " + e.getMessage());
                    statsFailed = true;
                }
                try {
                    achievements = session.http().getAppAchievements(appUuid);
                } catch (Exception e) {
                    LimeLog.warning("Game page achievements: " + e.getMessage());
                    achievementsFailed = true;
                }
                final GamePageData answer = GamePageData.from(stats, achievements,
                        statsFailed, achievementsFailed);
                main.post(() -> {
                    if (mine == request) {
                        data.setValue(answer);
                    }
                });
            });
        } catch (RejectedExecutionException e) {
            // Cleared with the page; nothing is left to show the answer.
        }
    }

    @NonNull
    public AchievementFilter.Filter filter() {
        return filter;
    }

    public void setFilter(@NonNull AchievementFilter.Filter filter) {
        this.filter = filter;
    }

    /** The ids of the hidden achievements revealed on this page, read-only. */
    @NonNull
    public Set<String> revealed() {
        return Collections.unmodifiableSet(revealed);
    }

    /** @return whether it was not revealed yet */
    public boolean reveal(@NonNull String achievementId) {
        return revealed.add(achievementId);
    }

    @Override
    protected void onCleared() {
        main.removeCallbacksAndMessages(null);
        loader.shutdownNow();
    }
}
