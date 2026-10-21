package com.limelight.ui;

import android.content.Context;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatImageView;

/**
 * A picture laid behind a card's content, as big as the card and never what sizes it.
 *
 * <p>A plain ImageView set to match a parent that wraps its content measures to its bitmap: a
 * FrameLayout asks its match-parent children first, under an "at most" bound, and an ImageView
 * answers with its drawable's own size. A wide background art then makes the card as tall as the
 * picture, half a screen, whatever the content holds. This one asks for nothing under such a
 * bound, so the card takes its content's height, and the FrameLayout's second pass, which gives
 * match-parent children the card's exact size, stretches it to fill.
 */
public class BackdropImageView extends AppCompatImageView {

    public BackdropImageView(Context context) {
        super(context);
    }

    public BackdropImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public BackdropImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(exactOrNothing(widthMeasureSpec), exactOrNothing(heightMeasureSpec));
    }

    private static int exactOrNothing(int spec) {
        return MeasureSpec.getMode(spec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(spec) : 0;
    }
}
