package com.limelight.grid;

import android.content.Context;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.library.GridSpanRule;

/**
 * A GridLayoutManager that picks its own column count from the available width, the way
 * GridView's numColumns="auto_fit" did. The span is recomputed on every layout pass, so
 * the grid reflows across rotations and different panels without being told a fixed count.
 *
 * <p>Two ways to pick it: a fixed column width in px (the legacy library grid, until it goes in
 * S10), or a {@link GridSpanRule} given the grid's width in dp, padding excluded. The rule is
 * asked during layout, before any child is laid out, so the adapter binding those children can
 * read {@link #getSpanCount()} and size its covers for the columns they will actually have.
 */
public class AutofitGridLayoutManager extends GridLayoutManager {
    private final int columnWidthPx;
    private final GridSpanRule rule;
    private final float density;

    public AutofitGridLayoutManager(Context context, int columnWidthPx) {
        super(context, 1);
        this.columnWidthPx = Math.max(1, columnWidthPx);
        this.rule = null;
        this.density = context.getResources().getDisplayMetrics().density;
    }

    public AutofitGridLayoutManager(Context context, GridSpanRule rule) {
        super(context, 1);
        this.columnWidthPx = 0;
        this.rule = rule;
        this.density = context.getResources().getDisplayMetrics().density;
    }

    @Override
    public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
        int usable = getWidth() - getPaddingLeft() - getPaddingRight();
        if (usable > 0) {
            int spans = rule != null
                    ? rule.spanCount(Math.round(usable / density))
                    : usable / columnWidthPx;
            setSpanCount(Math.max(1, spans));
        }
        super.onLayoutChildren(recycler, state);
    }
}
