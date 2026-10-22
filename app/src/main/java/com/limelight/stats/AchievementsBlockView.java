package com.limelight.stats;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.R;
import com.limelight.nvstream.http.Achievement;
import com.limelight.nvstream.http.GameAchievements;
import com.limelight.ui.ButtonGlyphs;
import com.limelight.ui.SegmentedControlView;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Every achievement of one game, as the game page shows it below the game: how far along it is,
 * All / Unlocked / Locked, and the rows as the host sorted them (unlocked first, newest first,
 * then the rest). It replaced the achievements dialog, which the old per-game sheet raised over
 * itself.
 *
 * <p>The view decides nothing: the rules are {@link AchievementFilter}'s, and the state (the
 * filter, the hidden achievements revealed) is the page's, which keeps it across its views and
 * hands it in on every {@link #showAchievements}. A tap or A on a segment, or on a masked row,
 * goes to the {@link Listener}; the page records it and binds again, so what is drawn is always
 * what the page holds.
 *
 * <p>It has three faces, one at a time:
 *
 * <pre>
 *   showAchievements   header, meter, gamerscore, the filter and the rows (or "All done" / "None
 *                      yet" for a filter that keeps nothing)
 *   showUntracked      a card: SuccessStory does not follow the game
 *   showFailed         the header, and a row: the PC could not be reached for them; a stop,
 *                      and A or a tap on it asks again ({@link Listener#onRetry})
 * </pre>
 *
 * <h3>Two screens</h3>
 *
 * <pre>
 *   inline   on the main screen, in the page's one scroll: the segments, the rows and the
 *            failed row take real focus, and the list is as tall as its rows
 *   panel    on the companion panel ({@link #setInline}), drawn by GameAchievementsRenderer:
 *            nothing is focusable, the d-pad's place is the virtual focus
 *            ({@link #setVirtualFocus}, drawn as state_activated), the header and the filter
 *            stay put and the list scrolls under them in the space left
 * </pre>
 *
 * Main thread only.
 */
public class AchievementsBlockView extends LinearLayout {

    /** What the user asked for in the block. */
    public interface Listener {
        void onFilterChosen(@NonNull AchievementFilter.Filter filter);

        /** A masked row was selected: the page reveals it and binds again. */
        void onRevealRequested(@NonNull Achievement achievement);

        /** The failed row was selected: the page asks the host again. */
        void onRetry();
    }

    private final View header;
    private final TextView count;
    private final TextView percent;
    private final View tracked;
    private final View meter;
    private final TextView score;
    private final SegmentedControlView filterView;
    private final RecyclerView list;
    private final TextView empty;
    private final View untracked;
    private final TextView untrackedDetail;
    private final View failed;
    private final TextView failedText;
    private final Adapter adapter = new Adapter();
    private final StatsText text;

    @Nullable
    private Listener listener;
    @Nullable
    private HostSession session;
    private boolean inline = true;

    public AchievementsBlockView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        inflate(context, R.layout.view_achievements_block, this);
        header = findViewById(R.id.achievementsBlockHeader);
        count = findViewById(R.id.achievementsBlockCount);
        percent = findViewById(R.id.achievementsBlockPercent);
        tracked = findViewById(R.id.achievementsBlockTracked);
        meter = findViewById(R.id.achievementsBlockMeter);
        score = findViewById(R.id.achievementsBlockScore);
        filterView = findViewById(R.id.achievementsBlockFilter);
        list = findViewById(R.id.achievementsBlockList);
        empty = findViewById(R.id.achievementsBlockEmpty);
        untracked = findViewById(R.id.achievementsBlockUntracked);
        untrackedDetail = findViewById(R.id.achievementsBlockUntrackedDetail);
        failed = findViewById(R.id.achievementsBlockFailed);
        failedText = findViewById(R.id.achievementsBlockFailedText);
        ButtonGlyphs.bindText(findViewById(R.id.achievementsRetryHint), R.string.game_page_retry,
                R.string.game_page_retry_touch);
        text = new StatsText(context);

        meter.setPivotX(0);
        list.setLayoutManager(new LinearLayoutManager(context));
        // Revealing a row changes it in place; an animation would drop the focus mid-change.
        list.setItemAnimator(null);
        list.setAdapter(adapter);
        filterView.setOnSegmentChosenListener(index -> {
            if (listener != null) {
                listener.onFilterChosen(AchievementFilter.Filter.at(index));
            }
        });
        failed.setOnClickListener(v -> {
            if (listener != null) {
                listener.onRetry();
            }
        });
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    /**
     * Inline on the main screen, or drawn on the companion panel (see the class comment). On the
     * panel the block is meant to fill its parent, so the list can take the height left.
     */
    public void setInline(boolean inline) {
        if (this.inline == inline) {
            return;
        }
        this.inline = inline;
        filterView.setInline(inline);
        failed.setFocusable(inline);
        // Inline, everything wraps and the page's scroll moves it; on the panel, the list alone
        // scrolls, in what the header and the filter leave of the block's height.
        LayoutParams trackedLp = (LayoutParams) tracked.getLayoutParams();
        trackedLp.height = inline ? LayoutParams.WRAP_CONTENT : 0;
        trackedLp.weight = inline ? 0 : 1;
        tracked.setLayoutParams(trackedLp);
        LayoutParams listLp = (LayoutParams) list.getLayoutParams();
        listLp.height = inline ? LayoutParams.WRAP_CONTENT : 0;
        listLp.weight = inline ? 0 : 1;
        list.setLayoutParams(listLp);
        list.setNestedScrollingEnabled(!inline);
        if (inline) {
            adapter.focusedRow = RecyclerView.NO_POSITION;
            filterView.setVirtualFocus(SegmentedControlView.NO_FOCUS);
            failed.setActivated(false);
        }
        adapter.notifyDataSetChanged();
    }

    /**
     * On the panel: where the d-pad is, ringed, and kept in view. At most one of the two is
     * set; the other is -1 (both for none).
     *
     * @param segment the filter segment the d-pad is on, or -1
     * @param row     the row the d-pad is on, as a position in the filtered list, or -1
     */
    public void setVirtualFocus(int segment, int row) {
        if (inline) {
            return;
        }
        filterView.setVirtualFocus(segment >= 0 ? segment : SegmentedControlView.NO_FOCUS);
        int next = row >= 0 && row < adapter.items.size() ? row : RecyclerView.NO_POSITION;
        int previous = adapter.focusedRow;
        if (next == previous) {
            return;
        }
        adapter.focusedRow = next;
        // The rows on screen are marked here; one scrolled in later is marked by its bind.
        markRow(previous, false);
        if (next != RecyclerView.NO_POSITION) {
            markRow(next, true);
            list.scrollToPosition(next);
        }
    }

    private void markRow(int position, boolean activated) {
        if (position == RecyclerView.NO_POSITION) {
            return;
        }
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(position);
        if (holder != null) {
            holder.itemView.setActivated(activated);
        }
    }

    /** A row's position in the list as filtered now, or -1 when it is not in it. */
    public int positionOf(@NonNull Achievement achievement) {
        List<Achievement> items = adapter.items;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) == achievement) {
                return i;
            }
        }
        return -1;
    }

    /** Where the icons come from: the host the achievements belong to. */
    public void setSession(@Nullable HostSession session) {
        if (this.session != session) {
            this.session = session;
            adapter.notifyDataSetChanged();
        }
    }

    /**
     * Shows the game's achievements.
     *
     * @param revealed  the hidden ones the user revealed, by id; read when a row is bound
     * @param nowMillis the time NEW is judged against
     */
    public void showAchievements(@NonNull GameAchievements data, @NonNull AchievementFilter.Filter filter,
                                 @NonNull Set<String> revealed, long nowMillis) {
        header.setVisibility(VISIBLE);
        tracked.setVisibility(VISIBLE);
        untracked.setVisibility(GONE);
        failed.setVisibility(GONE);

        Context context = getContext();
        count.setText(context.getString(R.string.game_page_achievements_count,
                data.getUnlocked(), data.getTotal()));
        percent.setText(context.getString(R.string.achievements_percent, data.getPercent()));
        meter.setScaleX(data.getPercent() / 100f);
        long[] gamerScore = data.getGamerScore();
        if (gamerScore == null) {
            score.setVisibility(GONE);
        } else {
            score.setVisibility(VISIBLE);
            score.setText(context.getString(R.string.game_page_gamerscore,
                    (int) gamerScore[0], (int) gamerScore[1]));
        }

        int[] counts = AchievementFilter.counts(data.getItems());
        filterView.setSegments(
                context.getString(R.string.game_page_filter_all, counts[AchievementFilter.Filter.ALL.ordinal()]),
                context.getString(R.string.achievements_filter_unlocked,
                        counts[AchievementFilter.Filter.UNLOCKED.ordinal()]),
                context.getString(R.string.game_page_filter_locked, counts[AchievementFilter.Filter.LOCKED.ordinal()]));
        filterView.setSelected(filter.ordinal());

        List<Achievement> shown = AchievementFilter.apply(data.getItems(), filter);
        boolean filterChanged = adapter.filter != filter;
        adapter.set(shown, filter, revealed, nowMillis);
        boolean none = shown.isEmpty();
        list.setVisibility(none ? GONE : VISIBLE);
        empty.setVisibility(none ? VISIBLE : GONE);
        if (none) {
            empty.setText(filter == AchievementFilter.Filter.LOCKED
                    ? R.string.achievements_all_done : R.string.achievements_none_yet);
        }
        if (filterChanged) {
            list.scrollToPosition(0);
        }
    }

    /** SuccessStory does not follow this game (no list, or an empty one). */
    public void showUntracked(@NonNull String gameName) {
        header.setVisibility(GONE);
        tracked.setVisibility(GONE);
        failed.setVisibility(GONE);
        untracked.setVisibility(VISIBLE);
        untrackedDetail.setText(getContext().getString(R.string.game_page_no_achievements_detail, gameName));
        adapter.set(Collections.<Achievement>emptyList(), AchievementFilter.Filter.ALL,
                Collections.<String>emptySet(), 0);
    }

    /**
     * The PC answered for the play statistics but not for the achievements.
     *
     * @param unreadable it answered for them too, but could not say (GamePageData#unreadable):
     *                   "Couldn't read the statistics." rather than "Couldn't reach the PC."
     */
    public void showFailed(boolean unreadable) {
        failedText.setText(unreadable ? R.string.game_page_stats_unreadable
                : R.string.game_stats_failed);
        header.setVisibility(VISIBLE);
        count.setText(null);
        percent.setText(null);
        tracked.setVisibility(GONE);
        untracked.setVisibility(GONE);
        failed.setVisibility(VISIBLE);
        adapter.set(Collections.<Achievement>emptyList(), AchievementFilter.Filter.ALL,
                Collections.<String>emptySet(), 0);
    }

    /** Redraws one achievement's row, after the page revealed it. */
    public void refresh(@NonNull Achievement achievement) {
        int position = adapter.items.indexOf(achievement);
        if (position >= 0) {
            adapter.notifyItemChanged(position);
        }
    }

    /**
     * Where R2 puts the d-pad: the first row, or the chosen segment when the filter keeps no
     * row, or the failed row. Null when the block has no stop (untracked, or not shown at all).
     */
    @Nullable
    public View firstStop() {
        View retry = retryStop();
        if (retry != null) {
            return retry;
        }
        if (getVisibility() != VISIBLE || tracked.getVisibility() != VISIBLE) {
            return null;
        }
        if (list.getVisibility() == VISIBLE) {
            RecyclerView.ViewHolder first = list.findViewHolderForAdapterPosition(0);
            if (first != null) {
                return first.itemView;
            }
        }
        return filterStop();
    }

    /** The chosen segment, the filter's own stop; null while the filter is not shown. */
    @Nullable
    public View filterStop() {
        if (getVisibility() != VISIBLE || tracked.getVisibility() != VISIBLE) {
            return null;
        }
        int selected = filterView.getSelectedIndex();
        return filterView.getSegmentView(selected >= 0 ? selected : 0);
    }

    /**
     * The view inline that stands where a stop of the panel's page stood
     * ({@link GameAchievementsPage}'s numbering): a filter segment, a row, or the failed row.
     * Null when the block does not show it, or has not laid the row out yet.
     */
    @Nullable
    public View viewForStop(int stop) {
        if (getVisibility() != VISIBLE) {
            return null;
        }
        if (stop == GameAchievementsPage.STOP_STATUS) {
            return retryStop();
        }
        if (tracked.getVisibility() != VISIBLE) {
            return null;
        }
        AchievementFilter.Filter segment = GameAchievementsPage.filterAt(stop);
        if (segment != null) {
            return filterView.getSegmentView(segment.ordinal());
        }
        int row = GameAchievementsPage.rowPosition(stop);
        if (row < 0 || list.getVisibility() != VISIBLE) {
            return null;
        }
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(row);
        return holder != null ? holder.itemView : null;
    }

    /** The failed row, while the block shows it; null otherwise. */
    @Nullable
    public View retryStop() {
        return getVisibility() == VISIBLE && failed.getVisibility() == VISIBLE ? failed : null;
    }

    /**
     * Binds one row of the list. Kept apart from the adapter so whatever else draws an
     * achievement row (the companion panel's list) draws it the same way.
     *
     * @param masked whether the row stands in for a hidden achievement not revealed yet
     * @param fresh  whether it carries NEW
     */
    static void bindRow(@NonNull View row, @NonNull Achievement a, boolean masked, boolean fresh,
                        @Nullable HostSession session, @NonNull StatsText text) {
        Context context = row.getContext();
        ImageView icon = row.findViewById(R.id.achievementIcon);
        View content = row.findViewById(R.id.achievementText);
        TextView name = row.findViewById(R.id.achievementName);
        TextView isNew = row.findViewById(R.id.achievementNew);
        TextView description = row.findViewById(R.id.achievementDescription);
        TextView when = row.findViewById(R.id.achievementWhen);
        TextView rarity = row.findViewById(R.id.achievementRarity);
        TextView share = row.findViewById(R.id.achievementPercent);

        icon.setClipToOutline(true);
        if (masked) {
            // A lock until revealed: the picture would give away what the name keeps back. The
            // tag goes too, so a load of the row's earlier achievement does not land over it.
            AchievementIcons.showMasked(icon);
        } else if (session != null) {
            AchievementIcons.load(icon, a, session);
        } else {
            icon.setImageDrawable(null);
        }
        // The content dims, not the row: the ring is the row's and stays at full strength.
        float alpha = a.isUnlocked() ? 1f : 0.75f;
        icon.setAlpha(alpha);
        content.setAlpha(alpha);
        row.findViewById(R.id.achievementSide).setAlpha(alpha);
        // The icon is the lock already for a masked one.
        row.findViewById(R.id.achievementLock).setVisibility(a.isUnlocked() || masked
                ? GONE : VISIBLE);
        isNew.setVisibility(fresh ? VISIBLE : GONE);

        if (masked) {
            name.setText(R.string.achievements_hidden);
            name.setTextColor(ContextCompat.getColor(context, R.color.content_muted));
            description.setText(R.string.achievements_reveal);
            description.setTextColor(ContextCompat.getColor(context, R.color.signal));
            description.setVisibility(VISIBLE);
        } else {
            name.setText(a.getName());
            name.setTextColor(ContextCompat.getColor(context, R.color.content_primary));
            description.setText(a.getDescription());
            description.setTextColor(ContextCompat.getColor(context, R.color.content_muted));
            description.setVisibility(a.getDescription().isEmpty() ? GONE : VISIBLE);
        }

        String unlocked = a.isUnlocked() ? text.unlockedLabel(a.getUnlockedAt(), false) : null;
        Double gamerScore = a.getGamerScore();
        String points = gamerScore != null && gamerScore > 0
                ? context.getString(R.string.achievements_gamerscore, (int) Math.round(gamerScore)) : null;
        String whenLine = unlocked != null && points != null
                ? context.getString(R.string.game_page_joined, unlocked, points)
                : unlocked != null ? unlocked : points;
        when.setVisibility(whenLine == null ? GONE : VISIBLE);
        when.setText(whenLine);

        StatsFormat.Rarity band = AchievementFilter.rarity(a);
        if (band == null) {
            rarity.setVisibility(GONE);
        } else {
            boolean ultra = band == StatsFormat.Rarity.ULTRA_RARE;
            rarity.setVisibility(VISIBLE);
            rarity.setText(ultra ? R.string.achievements_rarity_ultra_rare
                    : band == StatsFormat.Rarity.RARE ? R.string.achievements_rarity_rare
                    : R.string.achievements_rarity_uncommon);
            rarity.setBackgroundResource(ultra ? R.drawable.stats_chip_rare_bg : R.drawable.stats_chip_bg);
            rarity.setTextColor(ContextCompat.getColor(context, ultra ? R.color.danger : R.color.signal));
        }

        if (a.getPercent() == null) {
            share.setVisibility(GONE);
        } else {
            String value = StatsFormat.percent(a.getPercent(), text.getLocale());
            share.setVisibility(VISIBLE);
            share.setText(context.getString(R.string.game_page_percent, value));
            share.setContentDescription(context.getString(R.string.achievements_of_players, value));
        }
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        private List<Achievement> items = Collections.emptyList();
        @Nullable
        private AchievementFilter.Filter filter;
        private Set<String> revealed = Collections.emptySet();
        private long nowMillis;

        // The row the panel's d-pad is on, or NO_POSITION; always NO_POSITION inline.
        private int focusedRow = RecyclerView.NO_POSITION;

        void set(List<Achievement> items, AchievementFilter.Filter filter, Set<String> revealed,
                 long nowMillis) {
            if (items.equals(this.items) && filter == this.filter
                    && revealed.equals(this.revealed) && nowMillis == this.nowMillis) {
                // The panel binds again for every move of its d-pad: the same rows are not
                // bound again for it, which would reload every icon on screen.
                return;
            }
            if (!items.equals(this.items)) {
                focusedRow = RecyclerView.NO_POSITION;
            }
            this.items = items;
            this.filter = filter;
            this.revealed = revealed;
            this.nowMillis = nowMillis;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.achievement_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Achievement a = items.get(position);
            holder.achievement = a;
            holder.itemView.setFocusable(inline);
            holder.itemView.setActivated(!inline && position == focusedRow);
            bindRow(holder.itemView, a, AchievementFilter.isMasked(a, revealed),
                    AchievementFilter.isNew(a, nowMillis), session, text);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private final class Holder extends RecyclerView.ViewHolder {
        @Nullable
        Achievement achievement;

        Holder(View itemView) {
            super(itemView);
            itemView.setOnClickListener(v -> {
                Achievement a = achievement;
                if (a != null && listener != null
                        && AchievementFilter.isMasked(a, adapter.revealed)) {
                    listener.onRevealRequested(a);
                }
            });
        }
    }
}
