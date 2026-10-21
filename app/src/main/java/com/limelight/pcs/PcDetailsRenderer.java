package com.limelight.pcs;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.limelight.R;
import com.limelight.companion.CompanionPage;
import com.limelight.companion.CompanionPageRenderer;

import java.util.List;

/**
 * Draws a {@link PcDetailsPage} on the companion panel: the header, the facts, the running card
 * and the actions two to a row.
 *
 * <p>The facts and the rows are built again only when they change; a focus move, or a poll that
 * changed nothing they show, only rebinds. The page drawn last is kept on the view as its tag.
 *
 * <p>Every stop is clickable and its click names its stop through the {@link PageTaps} of the
 * value on screen, set again on every bind, so a tap never acts for a page that has gone. A
 * stateless singleton; main thread only.
 */
final class PcDetailsRenderer implements CompanionPageRenderer {

    static final PcDetailsRenderer INSTANCE = new PcDetailsRenderer();

    private PcDetailsRenderer() {
    }

    @NonNull
    @Override
    public View create(@NonNull LayoutInflater inflater, @NonNull ViewGroup parent) {
        View view = inflater.inflate(R.layout.companion_pc_details, parent, false);
        Context context = view.getContext();
        // The drawable is tinted itself: the Presentation's inflater makes plain TextViews, which
        // TextViewCompat cannot tint before API 23, and the glyph is white.
        Drawable icon = DrawableCompat.wrap(AppCompatResources.getDrawable(context,
                R.drawable.ic_resume).mutate());
        DrawableCompat.setTint(icon, ContextCompat.getColor(context, R.color.on_signal));
        TextView resume = view.findViewById(R.id.pcDetailsResume);
        resume.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null);
        return view;
    }

    @Override
    public void bind(@NonNull View view, @NonNull CompanionPage value, @NonNull PageTaps taps) {
        PcDetailsPage page = (PcDetailsPage) value;
        Context context = view.getContext();
        Object drawnTag = view.getTag(R.id.pcDetailsActions);
        PcDetailsPage drawn = drawnTag instanceof PcDetailsPage ? (PcDetailsPage) drawnTag : null;
        view.setTag(R.id.pcDetailsActions, page);

        bindHeader(view, context, page);

        LinearLayout facts = view.findViewById(R.id.pcDetailsFacts);
        if (drawn == null || !drawn.facts.equals(page.facts)) {
            buildFacts(facts, page.facts);
        }

        View running = view.findViewById(R.id.pcDetailsRunning);
        View resume = view.findViewById(R.id.pcDetailsResume);
        View quit = view.findViewById(R.id.pcDetailsQuit);
        if (page.isRunning()) {
            running.setVisibility(View.VISIBLE);
            ((TextView) view.findViewById(R.id.pcDetailsRunningTitle)).setText(page.runningTitle);
            bindCover(view.findViewById(R.id.pcDetailsCover), context, page);
            bindStop(resume, PcDetailsPage.STOP_RESUME, page, taps);
            bindStop(quit, PcDetailsPage.STOP_QUIT, page, taps);
        } else {
            running.setVisibility(View.GONE);
            resume.setActivated(false);
            quit.setActivated(false);
        }

        LinearLayout actions = view.findViewById(R.id.pcDetailsActions);
        if (drawn == null || !drawn.items.equals(page.items)) {
            buildActions(actions, page.items);
        }
        for (int r = 0; r < actions.getChildCount(); r++) {
            ViewGroup row = (ViewGroup) actions.getChildAt(r);
            for (int c = 0; c < row.getChildCount(); c++) {
                View cell = row.getChildAt(c);
                Object stop = cell.getTag(R.id.pcActionLabel);
                if (stop instanceof Integer) {
                    bindStop(cell, (Integer) stop, page, taps);
                }
            }
        }
    }

    private static void bindHeader(View view, Context context, PcDetailsPage page) {
        View dot = view.findViewById(R.id.pcDetailsDot);
        dot.setBackgroundResource(page.kind.hollow ? R.drawable.pc_status_ring
                : R.drawable.pc_status_dot);
        dot.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(context, page.kind.color)));
        ((TextView) view.findViewById(R.id.pcDetailsName)).setText(page.name);
        ((TextView) view.findViewById(R.id.pcDetailsStatus)).setText(page.statusText);
    }

    // The cover, else the host glyph in its box: a game the library never drew has none.
    private static void bindCover(ImageView cover, Context context, PcDetailsPage page) {
        if (page.cover != null) {
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setImageTintList(null);
            cover.setImageBitmap(page.cover);
        } else {
            cover.setScaleType(ImageView.ScaleType.CENTER);
            cover.setImageTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.content_muted)));
            cover.setImageResource(R.drawable.ic_computer);
        }
    }

    // The ring on the d-pad's stop, kept in view; a tap names the stop.
    private static void bindStop(View stop, int id, PcDetailsPage page, PageTaps taps) {
        stop.setOnClickListener(v -> taps.tap(id));
        boolean focused = id == page.focusedStop();
        stop.setActivated(focused);
        if (focused) {
            CompanionPageRenderer.keepInView(stop);
        }
    }

    private static void buildFacts(LinearLayout facts, List<PcDetailsPage.Fact> values) {
        facts.removeAllViews();
        facts.setVisibility(values.isEmpty() ? View.GONE : View.VISIBLE);
        // A share for every fact a PC can have, present or not, so a fact keeps its width and
        // the columns stay put as the d-pad moves from one PC to another.
        facts.setWeightSum(PcDetailsPage.MAX_FACTS);
        LayoutInflater inflater = LayoutInflater.from(facts.getContext());
        for (PcDetailsPage.Fact fact : values) {
            View cell = inflater.inflate(R.layout.companion_pc_fact, facts, false);
            ((TextView) cell.findViewById(R.id.pcFactValue)).setText(fact.value);
            ((TextView) cell.findViewById(R.id.pcFactLabel)).setText(fact.label);
            facts.addView(cell);
        }
    }

    /**
     * Two cells to a row, as {@link PcDetailsPage#stops} lays them out; an odd last cell keeps
     * half the width, beside an empty share, so the columns line up.
     */
    private static void buildActions(LinearLayout actions, List<HostActionsSheet.Item> items) {
        actions.removeAllViews();
        Context context = actions.getContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        int gap = context.getResources().getDimensionPixelSize(R.dimen.pc_tile_half_gap);
        for (int i = 0; i < items.size(); i += 2) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBaselineAligned(false);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                rowParams.topMargin = gap;
            }
            row.setLayoutParams(rowParams);
            for (int c = 0; c < 2; c++) {
                int index = i + c;
                View cell;
                if (index < items.size()) {
                    cell = actionCell(inflater, row, items.get(index));
                } else {
                    cell = new View(context);
                    cell.setLayoutParams(new LinearLayout.LayoutParams(0, 0, 1f));
                }
                if (c > 0) {
                    ((LinearLayout.LayoutParams) cell.getLayoutParams()).setMarginStart(gap);
                }
                row.addView(cell);
            }
            actions.addView(row);
        }
    }

    private static View actionCell(LayoutInflater inflater, ViewGroup row,
                                   HostActionsSheet.Item item) {
        Context context = row.getContext();
        View cell = inflater.inflate(R.layout.companion_pc_action_row, row, false);
        ImageView icon = cell.findViewById(R.id.pcActionIcon);
        TextView label = cell.findViewById(R.id.pcActionLabel);
        icon.setImageResource(item.icon);
        label.setText(item.label);
        if (item.danger) {
            int danger = ContextCompat.getColor(context, R.color.danger);
            cell.setBackgroundResource(R.drawable.companion_action_row_danger_bg);
            icon.setImageTintList(ColorStateList.valueOf(danger));
            label.setTextColor(danger);
        }
        cell.setTag(R.id.pcActionLabel, PcDetailsPage.stopFor(item));
        return cell;
    }
}
