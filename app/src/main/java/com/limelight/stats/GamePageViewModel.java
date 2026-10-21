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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
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
 *   main: request still the latest? ──yes──▶ data = answer ◀───┘ read against the last answer
 *                                   ──no───▶ dropped              (GamePageData.settle)
 * </pre>
 *
 * The last answer with something in it is kept past the page too, per game, for as long as the
 * process lives: a page opened again while the host cannot read its files (a session running)
 * still has the figures it showed last time.
 *
 * Main thread only, apart from the worker's own requests.
 */
public class GamePageViewModel extends ViewModel {

    // The last answer with something in it, per host and game; main thread only.
    private static final int REMEMBERED = 24;
    private static final Map<String, GamePageData> LAST = new LinkedHashMap<String, GamePageData>(
            16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, GamePageData> eldest) {
            return size() > REMEMBERED;
        }
    };

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
     * @param playKnown whether the library knows the game was played, so a 404 on its
     *                  statistics is the host failing to read them (see GamePageData.settle)
     */
    public void load(@Nullable HostSession session, @NonNull String hostUuid,
                     @NonNull String appUuid, boolean quiet, boolean playKnown) {
        final int mine = ++request;
        final String key = hostUuid.toUpperCase(Locale.ROOT) + "/" + appUuid.toUpperCase(Locale.ROOT);
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
                final GameStats gotStats = stats;
                final GameAchievements gotAchievements = achievements;
                final boolean gotStatsFailed = statsFailed;
                final boolean gotAchievementsFailed = achievementsFailed;
                main.post(() -> {
                    if (mine != request) {
                        return;
                    }
                    GamePageData answer = GamePageData.settle(gotStats, gotAchievements,
                            gotStatsFailed, gotAchievementsFailed, before(key), playKnown);
                    if (answer.hasPlay() || answer.hasAchievements()) {
                        LAST.put(key, answer);
                    }
                    data.setValue(answer);
                });
            });
        } catch (RejectedExecutionException e) {
            // Cleared with the page; nothing is left to show the answer.
        }
    }

    // This page's last answer with something in it, else the one an earlier page kept.
    @Nullable
    private GamePageData before(@NonNull String key) {
        GamePageData shown = data.getValue();
        if (shown != null && (shown.hasPlay() || shown.hasAchievements())) {
            return shown;
        }
        return LAST.get(key);
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
