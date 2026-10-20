package com.limelight.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.limelight.R;

/**
 * A game's title card as a drawable, for a cover drawn by an {@code ImageView} rather than a
 * {@link GameTileView}: the statistics' small covers. It is the tile's card in the same colours
 * (the raised surface, its hairline edge, the title in bold), so a game with no art reads the
 * same in Stats as in the Library, rather than as an empty grey box.
 *
 * <p>As on the tile, a card that stands in while the cover is on its way is dimmed, and one that
 * is the game's look for good is at full strength.
 *
 * <h3>Small covers</h3>
 *
 * A Stats row's cover is 36dp across, where even three lines of a title would be a smudge. Below
 * {@link #MONOGRAM_MAX_WIDTH_DP} the card shows the title's initials, large, instead; wider, the
 * title itself, wrapped and ellipsised to fit, at a size that follows the card's width as the
 * tile's auto-sized text does.
 */
public class TitleCardDrawable extends Drawable {

    private static final float MONOGRAM_MAX_WIDTH_DP = 64f;
    // Text size as a share of the card's width: 112dp gives the tile's 13sp, a monogram half the card.
    private static final float TITLE_SIZE_SHARE = 0.115f;
    private static final float MONOGRAM_SIZE_SHARE = 0.42f;
    private static final int MAX_LINES = 4;

    private final String title;
    private final float density;
    private final float corner;
    private final float padding;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();

    @Nullable
    private StaticLayout layout;
    private int layoutWidth = -1;

    /**
     * @param loading whether the card stands in while the cover is on its way (dimmed), rather
     *                than for a game that has none
     */
    public TitleCardDrawable(@NonNull Context context, @Nullable String title, boolean loading) {
        this.title = title == null ? "" : title.trim();
        density = context.getResources().getDisplayMetrics().density;
        corner = context.getResources().getDimension(R.dimen.tile_corner_small);
        padding = context.getResources().getDimension(R.dimen.tile_title_padding);

        fill.setColor(ContextCompat.getColor(context, R.color.tile_placeholder));
        edge.setColor(ContextCompat.getColor(context, R.color.tile_placeholder_outline));
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density);
        text.setColor(ContextCompat.getColor(context,
                loading ? R.color.content_muted : R.color.content_primary));
        text.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
    }

    /** The initials of up to the first two words, upper-cased; what a small card shows. */
    @NonNull
    static String monogram(@Nullable String title) {
        if (title == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(2);
        for (String word : title.trim().split("\\s+")) {
            if (!word.isEmpty()) {
                out.appendCodePoint(Character.toUpperCase(word.codePointAt(0)));
                if (out.length() >= 2) {
                    break;
                }
            }
        }
        return out.toString();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) {
            return;
        }
        box.set(b);
        float half = edge.getStrokeWidth() / 2;
        box.inset(half, half);
        canvas.drawRoundRect(box, corner, corner, fill);
        canvas.drawRoundRect(box, corner, corner, edge);

        if (title.isEmpty()) {
            return;
        }
        if (b.width() / density < MONOGRAM_MAX_WIDTH_DP) {
            drawMonogram(canvas, b);
        } else {
            drawTitle(canvas, b);
        }
    }

    private void drawMonogram(Canvas canvas, Rect b) {
        String initials = monogram(title);
        text.setTextSize(b.width() * MONOGRAM_SIZE_SHARE);
        text.setTextAlign(Paint.Align.CENTER);
        // Centred on the glyphs' own middle, not the baseline, or capitals sit visibly high.
        Paint.FontMetrics m = text.getFontMetrics();
        float y = b.exactCenterY() - (m.ascent + m.descent) / 2;
        canvas.drawText(initials, b.exactCenterX(), y, text);
    }

    private void drawTitle(Canvas canvas, Rect b) {
        int width = Math.max(1, (int) (b.width() - 2 * padding));
        if (layout == null || layoutWidth != width) {
            text.setTextAlign(Paint.Align.LEFT);
            text.setTextSize(b.width() * TITLE_SIZE_SHARE);
            layout = makeLayout(width);
            layoutWidth = width;
        }
        canvas.save();
        canvas.translate(b.left + padding, b.exactCenterY() - layout.getHeight() / 2f);
        layout.draw(canvas);
        canvas.restore();
    }

    private StaticLayout makeLayout(int width) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return StaticLayout.Builder.obtain(title, 0, title.length(), text, width)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setMaxLines(MAX_LINES)
                    .build();
        }
        // Before M a static layout cannot ellipsise to a line count; a long title runs past the
        // card's foot and is clipped there by the cover's outline instead.
        return new StaticLayout(title, text, width, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false);
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        edge.setAlpha(alpha);
        text.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        fill.setColorFilter(colorFilter);
        edge.setColorFilter(colorFilter);
        text.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        // Rounded corners let the ground show through.
        return PixelFormat.TRANSLUCENT;
    }
}
