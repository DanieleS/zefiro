package com.limelight.stats;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.TypefaceSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.limelight.R;
import com.limelight.companion.CompanionPage;
import com.limelight.companion.CompanionPageRenderer;
import com.limelight.ui.ButtonGlyphs;
import com.limelight.ui.GamepadHintsView;
import com.limelight.ui.SegmentedControlView;

import java.util.List;

/**
 * Draws a {@link StatsRemotePage} on the companion panel: the remote, the tabs and the active
 * tab's rows, the totals and the hints (see the page for what each mode holds). The rows are
 * built again only when the tab or its list changes; a focus move only moves the activated ring
 * and keeps the ringed stop in view.
 *
 * <p>Every control here is a stop the page numbers, and a tap names it through {@link PageTaps};
 * the page value, not this view, says what is chosen, so a tap on a range draws nothing until
 * the Stats screen publishes the next value. Listeners are set on every bind, so a tap reaches
 * the taps of the value being shown.
 *
 * <p>A stateless singleton: the page drawn last is kept on the view, as its tag. Main thread
 * only.
 */
final class StatsRemoteRenderer implements CompanionPageRenderer {

    static final StatsRemoteRenderer INSTANCE = new StatsRemoteRenderer();

    // Previous and next with no period that way: their label and chevron at a quarter, as the
    // mock's disabled .big.
    private static final float DISABLED_ALPHA = 0.25f;

    private StatsRemoteRenderer() {
    }

    @NonNull
    @Override
    public View create(@NonNull LayoutInflater inflater, @NonNull ViewGroup parent) {
        View view = inflater.inflate(R.layout.companion_page_stats_remote, parent, false);
        Context context = view.getContext();
        SegmentedControlView ranges = view.findViewById(R.id.companionStatsRanges);
        ranges.setSegments(context.getString(R.string.stats_range_week),
                context.getString(R.string.stats_range_month),
                context.getString(R.string.stats_range_year));
        ranges.setInline(false);
        // The keys that fire them from anywhere, shown while a pad is connected.
        ranges.setKeys("X", "Y");
        TextView previousGlyph = view.findViewById(R.id.companionStatsPreviousGlyph);
        previousGlyph.setText("LB");
        ButtonGlyphs.bindVisibility(previousGlyph);
        TextView nextGlyph = view.findViewById(R.id.companionStatsNextGlyph);
        nextGlyph.setText("RB");
        ButtonGlyphs.bindVisibility(nextGlyph);
        return view;
    }

    @Override
    public void bind(@NonNull View view, @NonNull CompanionPage value, @NonNull PageTaps taps) {
        StatsRemotePage page = (StatsRemotePage) value;
        int stop = page.focusedStop();

        SegmentedControlView ranges = view.findViewById(R.id.companionStatsRanges);
        ranges.setSelected(page.rangeIndex);
        ranges.setVirtualFocus(StatsRemotePage.rangeAt(stop));
        ranges.setOnSegmentChosenListener(index -> taps.tap(StatsRemotePage.rangeStop(index)));
        keepInViewIf(ranges, StatsRemotePage.rangeAt(stop) >= 0);

        bindPeriod(view, page, taps, stop);
        bindTabs(view, page, taps, stop);
        bindRows(view, page, taps, stop);
        bindTotals(view, page);

        view.findViewById(R.id.companionStatsNothing)
                .setVisibility(page.saysNothing() ? View.VISIBLE : View.GONE);
        GamepadHintsView hints = view.findViewById(R.id.companionStatsHints);
        List<GamepadHintsView.Hint> list = page.hints;
        hints.setHints(list.toArray(new GamepadHintsView.Hint[0]));
    }

    private static void bindPeriod(View view, StatsRemotePage page, PageTaps taps, int stop) {
        ((TextView) view.findViewById(R.id.companionStatsCurrent)).setText(page.currentLabel);
        View previous = view.findViewById(R.id.companionStatsPrevious);
        ((TextView) view.findViewById(R.id.companionStatsPreviousLabel))
                .setText(page.previousLabel);
        bindButton(previous, page.previousEnabled, stop == StatsRemotePage.STOP_PREVIOUS,
                v -> taps.tap(StatsRemotePage.STOP_PREVIOUS));
        View next = view.findViewById(R.id.companionStatsNext);
        ((TextView) view.findViewById(R.id.companionStatsNextLabel)).setText(page.nextLabel);
        bindButton(next, page.nextEnabled, stop == StatsRemotePage.STOP_NEXT,
                v -> taps.tap(StatsRemotePage.STOP_NEXT));
    }

    private static void bindButton(View button, boolean enabled, boolean focused,
                                   View.OnClickListener click) {
        button.setEnabled(enabled);
        button.setOnClickListener(enabled ? click : null);
        // setOnClickListener makes it clickable again; a disabled one takes no press.
        button.setClickable(enabled);
        ViewGroup group = (ViewGroup) button;
        for (int i = 0; i < group.getChildCount(); i++) {
            group.getChildAt(i).setAlpha(enabled ? 1f : DISABLED_ALPHA);
        }
        button.setActivated(focused);
        keepInViewIf(button, focused);
    }

    private static void bindTabs(View view, StatsRemotePage page, PageTaps taps, int stop) {
        List<StatsRemotePage.Tab> shown = page.visibleTabs();
        view.findViewById(R.id.companionStatsTabs)
                .setVisibility(shown.isEmpty() ? View.GONE : View.VISIBLE);
        bindTab(view.findViewById(R.id.companionStatsTabUnlocks), StatsRemotePage.Tab.UNLOCKS,
                view.getContext().getString(R.string.stats_tab_unlocks), page.unlockCount,
                page, shown, taps, stop);
        bindTab(view.findViewById(R.id.companionStatsTabResume), StatsRemotePage.Tab.RESUME,
                view.getContext().getString(R.string.stats_resume), page.resumeCount,
                page, shown, taps, stop);
    }

    private static void bindTab(TextView tab, StatsRemotePage.Tab which, String label,
                                String count, StatsRemotePage page,
                                List<StatsRemotePage.Tab> shown, PageTaps taps, int stop) {
        boolean visible = shown.contains(which);
        tab.setVisibility(visible ? View.VISIBLE : View.GONE);
        // The first tab drawn sits flush with the edge, whichever it is.
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) tab.getLayoutParams();
        int gap = Math.round(6 * tab.getResources().getDisplayMetrics().density);
        int start = visible && shown.indexOf(which) > 0 ? gap : 0;
        if (params.getMarginStart() != start) {
            params.setMarginStart(start);
            tab.setLayoutParams(params);
        }
        if (!visible) {
            tab.setOnClickListener(null);
            return;
        }
        // "Unlocks 64": the count in mono, coloured with the label (selected: the signal).
        SpannableStringBuilder text = new SpannableStringBuilder(label);
        if (!count.isEmpty()) {
            text.append("  ");
            int at = text.length();
            text.append(count);
            text.setSpan(new TypefaceSpan("monospace"), at, text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        tab.setText(text);
        tab.setSelected(page.tab == which);
        boolean focused = stop == StatsRemotePage.tabStop(which);
        tab.setActivated(focused);
        tab.setOnClickListener(v -> taps.tap(StatsRemotePage.tabStop(which)));
        keepInViewIf(tab, focused);
    }

    private static void bindRows(View view, StatsRemotePage page, PageTaps taps, int stop) {
        LinearLayout rows = view.findViewById(R.id.companionStatsRows);
        Object drawn = view.getTag(R.id.companionStatsRows);
        if (!(drawn instanceof StatsRemotePage) || !page.sameRows((StatsRemotePage) drawn)) {
            buildRows(rows, page);
        }
        view.setTag(R.id.companionStatsRows, page);
        int focusedRow = StatsRemotePage.rowIndex(stop);
        for (int i = 0; i < rows.getChildCount(); i++) {
            final int rowStop = StatsRemotePage.rowStop(i);
            View row = rows.getChildAt(i);
            row.setOnClickListener(v -> taps.tap(rowStop));
            boolean focused = i == focusedRow;
            row.setActivated(focused);
            keepInViewIf(row, focused);
        }
    }

    private static void buildRows(LinearLayout rows, StatsRemotePage page) {
        rows.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(rows.getContext());
        Context context = rows.getContext();
        if (page.tab == StatsRemotePage.Tab.UNLOCKS) {
            for (StatsRemotePage.UnlockRow item : page.unlocks) {
                View row = inflater.inflate(R.layout.companion_page_stats_row, rows, false);
                ((ImageView) row.findViewById(R.id.companionStatsRowIcon))
                        .setImageResource(R.drawable.ic_trophy);
                ((TextView) row.findViewById(R.id.companionStatsRowTitle)).setText(
                        context.getString(R.string.stats_achievement_detail, item.name,
                                item.game));
                ((TextView) row.findViewById(R.id.companionStatsRowDetail)).setText(item.when);
                rows.addView(row);
            }
        } else if (page.tab == StatsRemotePage.Tab.RESUME) {
            for (StatsRemotePage.ResumeRow item : page.resume) {
                View row = inflater.inflate(R.layout.companion_page_stats_row, rows, false);
                ((ImageView) row.findViewById(R.id.companionStatsRowIcon))
                        .setImageResource(R.drawable.ic_resume);
                ((TextView) row.findViewById(R.id.companionStatsRowTitle)).setText(item.name);
                ((TextView) row.findViewById(R.id.companionStatsRowDetail)).setText(
                        context.getString(R.string.stats_resume_detail, item.hours,
                                item.lastPlayed));
                rows.addView(row);
            }
        }
    }

    private static void bindTotals(View view, StatsRemotePage page) {
        View card = view.findViewById(R.id.companionStatsTotals);
        StatsRemotePage.Totals totals = page.totals;
        card.setVisibility(totals == null ? View.GONE : View.VISIBLE);
        if (totals == null) {
            return;
        }
        ((TextView) view.findViewById(R.id.companionStatsHours)).setText(totals.hours);
        ((TextView) view.findViewById(R.id.companionStatsGames)).setText(totals.games);
        ((TextView) view.findViewById(R.id.companionStatsGamesLabel)).setText(totals.gamesLabel);
        ((TextView) view.findViewById(R.id.companionStatsNeverPlayed))
                .setText(totals.neverPlayed);
        ((TextView) view.findViewById(R.id.companionStatsThisYear)).setText(totals.thisYear);
    }

    private static void keepInViewIf(View stop, boolean focused) {
        if (focused) {
            CompanionPageRenderer.keepInView(stop);
        }
    }
}
