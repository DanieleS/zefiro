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
 * <p>The count comes from a {@link GridSpanRule}, given the grid's width in dp, padding
 * excluded. The rule is asked during layout, before any child is laid out, so the adapter binding
 * those children can read {@link #getSpanCount()} and size its covers for the columns they will
 * actually have.
 */
public class AutofitGridLayoutManager extends GridLayoutManager {
    private final GridSpanRule rule;
    private final float density;

    public AutofitGridLayoutManager(Context context, GridSpanRule rule) {
        super(context, 1);
        this.rule = rule;
        this.density = context.getResources().getDisplayMetrics().density;
    }

    @Override
    public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
        int usable = getWidth() - getPaddingLeft() - getPaddingRight();
        if (usable > 0) {
            setSpanCount(Math.max(1, rule.spanCount(Math.round(usable / density))));
        }
        super.onLayoutChildren(recycler, state);
    }
}
