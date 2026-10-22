package com.limelight.ui;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.limelight.R;

/**
 * A gamepad button's glyph ("B", "LB") drawn as a button icon: the hint row's key
 * ({@code view_gamepad_hint_key}) in the colour of the button it sits on, so it reads on a
 * filled, tonal or ghost button alike.
 */
public class KeyGlyphDrawable extends Drawable {

    private final String key;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final float corner;
    private final int height;
    private final int width;

    public KeyGlyphDrawable(@NonNull Resources res, @NonNull String key, @ColorInt int color) {
        this.key = key;
        height = res.getDimensionPixelSize(R.dimen.key_glyph_height);
        corner = res.getDimension(R.dimen.key_glyph_corner);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11,
                res.getDisplayMetrics()));
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(color);
        // The key's plate: the button's own colour, faint, as key_glyph_bg is on the dark ground.
        fill.setColor(color);
        fill.setAlpha(0x33);
        int padding = Math.round(4 * res.getDisplayMetrics().density);
        width = Math.max(res.getDimensionPixelSize(R.dimen.key_glyph_min_width),
                Math.round(text.measureText(key)) + 2 * padding);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        bounds.set(b);
        canvas.drawRoundRect(bounds, corner, corner, fill);
        Paint.FontMetrics m = text.getFontMetrics();
        float baseline = bounds.centerY() - (m.ascent + m.descent) / 2f;
        canvas.drawText(key, bounds.centerX(), baseline, text);
    }

    @Override
    public int getIntrinsicWidth() {
        return width;
    }

    @Override
    public int getIntrinsicHeight() {
        return height;
    }

    @Override
    public void setAlpha(int alpha) {
        text.setAlpha(alpha);
        fill.setAlpha(alpha * 0x33 / 0xFF);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        text.setColorFilter(colorFilter);
        fill.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
