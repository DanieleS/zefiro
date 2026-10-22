package com.limelight.ui;

import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.Observer;

import com.google.android.material.button.MaterialButton;
import com.limelight.R;

/**
 * Puts the gamepad button that fires a button inside it ("B Cancel") while a pad is connected,
 * and takes it out again when the pad goes ({@link GamepadPresence}). Only for a button a key
 * fires wherever focus is; a button that A presses once focused carries no glyph.
 *
 * <p>A button with text keeps it, the glyph taking the icon's place ({@link #bind}); an icon-only
 * button (a back arrow, a chevron) shows the glyph instead of its icon ({@link #bindImage}); any
 * other control gets a {@code view_gamepad_hint_key} of its own beside or inside it, shown by
 * {@link #bindVisibility}. The variants without a lifecycle owner follow the pad while the view
 * is attached, for views that outlive no screen of their own: a list item, the companion panel.
 */
public final class ButtonGlyphs {

    private ButtonGlyphs() {
    }

    /**
     * Show {@code key} in {@code button} for as long as {@code owner} is started and a pad is
     * connected. The button's own icon, if any, comes back when the pad goes.
     */
    public static void bind(@NonNull LifecycleOwner owner, @NonNull MaterialButton button,
                            @NonNull String key) {
        GamepadPresence.connected().observe(owner, glyphIn(button, key));
    }

    /** {@link #bind}, for as long as the button is attached to a window. */
    public static void bind(@NonNull MaterialButton button, @NonNull String key) {
        whileAttached(button, glyphIn(button, key));
    }

    /**
     * Puts B on a shown dialog's negative button. Only for a cancelable dialog whose negative
     * button does what cancelling it does: B cancels a dialog (the framework falls back from B
     * to back), so the button is the one B fires. Asked again on a later show, it binds nothing
     * twice.
     */
    public static void bindCancel(@NonNull AlertDialog dialog) {
        Button button = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
        if (button instanceof MaterialButton && button.getTag(R.id.keyGlyph) == null) {
            button.setTag(R.id.keyGlyph, "B");
            bind((MaterialButton) button, "B");
        }
    }

    // The glyph in place of the button's icon while a pad is connected; its own icon otherwise.
    @NonNull
    private static Observer<Boolean> glyphIn(@NonNull MaterialButton button, @NonNull String key) {
        final Drawable own = button.getIcon();
        final ColorStateList ownTint = button.getIconTint();
        final int ownGravity = button.getIconGravity();
        final Drawable glyph = new KeyGlyphDrawable(button.getResources(), key,
                button.getCurrentTextColor());
        return pad -> {
            if (Boolean.TRUE.equals(pad)) {
                button.setIconTint(null);
                button.setIcon(glyph);
                button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
            } else {
                button.setIcon(own);
                button.setIconTint(ownTint);
                button.setIconGravity(ownGravity);
            }
        };
    }

    /** The same rule for a view that is not a button, such as a glyph beside a field. */
    public static void bindVisibility(@NonNull LifecycleOwner owner, @NonNull View glyph) {
        GamepadPresence.connected().observe(owner, pad ->
                glyph.setVisibility(Boolean.TRUE.equals(pad) ? View.VISIBLE : View.GONE));
    }

    /**
     * Shows {@code glyph} (a {@code view_gamepad_hint_key}) while a pad is connected, for as long
     * as it is attached to a window.
     */
    public static void bindVisibility(@NonNull View glyph) {
        whileAttached(glyph, pad ->
                glyph.setVisibility(Boolean.TRUE.equals(pad) ? View.VISIBLE : View.GONE));
    }

    /**
     * Ends {@code row} (a horizontal row, such as a menu row) with {@code key}'s glyph, shown
     * while a pad is connected for as long as it is attached: for a row that key fires wherever
     * the d-pad is ("← Video  B").
     */
    @NonNull
    public static TextView addKey(@NonNull ViewGroup row, @NonNull String key) {
        TextView glyph = (TextView) LayoutInflater.from(row.getContext())
                .inflate(R.layout.view_gamepad_hint_key, row, false);
        glyph.setText(key);
        ViewGroup.LayoutParams params = glyph.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) params).setMarginStart(
                    row.getResources().getDimensionPixelSize(R.dimen.hint_label_gap));
        }
        row.addView(glyph, params);
        bindVisibility(glyph);
        return glyph;
    }

    /**
     * Shows {@code key} in place of an icon-only button's icon while a pad is connected, for as
     * long as the button is attached to a window: "B" on a back arrow, "LB" on a chevron. The
     * icon, its tint and its scale come back when the pad goes. The glyph is drawn in the icon's
     * tint, untinted itself so its key stays readable on its plate.
     */
    public static void bindImage(@NonNull ImageView button, @NonNull String key) {
        final Drawable own = button.getDrawable();
        final ColorStateList ownTint = ImageViewCompat.getImageTintList(button);
        final ImageView.ScaleType ownScale = button.getScaleType();
        int color = ownTint != null ? ownTint.getDefaultColor()
                : ContextCompat.getColor(button.getContext(), R.color.content_primary);
        final Drawable glyph = new KeyGlyphDrawable(button.getResources(), key, color);
        whileAttached(button, pad -> {
            if (Boolean.TRUE.equals(pad)) {
                ImageViewCompat.setImageTintList(button, null);
                // At its own size: fitted to the icon's box, the key would be blown up.
                button.setScaleType(ImageView.ScaleType.CENTER);
                button.setImageDrawable(glyph);
            } else {
                button.setImageDrawable(own);
                ImageViewCompat.setImageTintList(button, ownTint);
                button.setScaleType(ownScale);
            }
        });
    }

    /**
     * Says {@code withPad} ("Press A or tap to try again") while a pad is connected and
     * {@code touch} ("Tap to try again") otherwise, for as long as {@code view} is attached: words
     * that name a key follow the same rule as its glyph.
     */
    public static void bindText(@NonNull TextView view, @StringRes int withPad,
                                @StringRes int touch) {
        whileAttached(view, pad -> view.setText(Boolean.TRUE.equals(pad) ? withPad : touch));
    }

    // Applies the pad's state now, then on every change while the view is attached.
    private static void whileAttached(@NonNull View view, @NonNull Observer<Boolean> apply) {
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(@NonNull View v) {
                GamepadPresence.connected().observeForever(apply);
            }

            @Override
            public void onViewDetachedFromWindow(@NonNull View v) {
                GamepadPresence.connected().removeObserver(apply);
            }
        });
        if (view.isAttachedToWindow()) {
            GamepadPresence.connected().observeForever(apply);
        } else {
            apply.onChanged(GamepadPresence.isConnected());
        }
    }
}
