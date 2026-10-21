package com.limelight.settings;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * The settings list's layout manager: androidx's vertical list, except that a row brought into
 * view for focus stops below whatever the section draws over the list's top (the sticky label),
 * instead of at the list's very edge, where the label would cover it. Only that changes; the
 * rows are laid out from the top as before, so the list still scrolls under the label.
 */
final class SettingsLayoutManager extends LinearLayoutManager {

    /** How much of the list's top is covered right now; 0 for none. */
    interface TopInset {
        int topInset();
    }

    private final TopInset inset;

    SettingsLayoutManager(@NonNull Context context, @NonNull TopInset inset) {
        super(context);
        this.inset = inset;
    }

    @Override
    public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                 @NonNull Rect rect, boolean immediate,
                                                 boolean focusedChildVisible) {
        int covered = inset.topInset();
        if (covered <= 0) {
            return super.requestChildRectangleOnScreen(parent, child, rect, immediate,
                    focusedChildVisible);
        }
        // androidx's own sum, with the covered band taken off the top.
        int parentTop = getPaddingTop() + covered;
        int parentBottom = getHeight() - getPaddingBottom();
        int childTop = child.getTop() + rect.top - child.getScrollY();
        int childBottom = childTop + rect.height();
        int offTop = Math.min(0, childTop - parentTop);
        int offBottom = Math.max(0, childBottom - parentBottom);
        int dy = offTop != 0 ? offTop : Math.min(childTop - parentTop, offBottom);
        if (dy == 0) {
            return false;
        }
        if (immediate) {
            parent.scrollBy(0, dy);
        } else {
            parent.smoothScrollBy(0, dy);
        }
        return true;
    }
}
