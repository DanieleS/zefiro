package com.limelight.ui;

import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.lifecycle.LifecycleOwner;

import com.google.android.material.button.MaterialButton;

/**
 * Puts the gamepad button that fires a button inside it ("B Cancel") while a pad is connected,
 * and takes it out again when the pad goes ({@link GamepadPresence}). Only for a button a key
 * fires wherever focus is; a button that A presses once focused carries no glyph.
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
        final Drawable own = button.getIcon();
        final ColorStateList ownTint = button.getIconTint();
        final int ownGravity = button.getIconGravity();
        final Drawable glyph = new KeyGlyphDrawable(button.getResources(), key,
                button.getCurrentTextColor());
        GamepadPresence.connected().observe(owner, pad -> {
            if (Boolean.TRUE.equals(pad)) {
                button.setIconTint(null);
                button.setIcon(glyph);
                button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
            } else {
                button.setIcon(own);
                button.setIconTint(ownTint);
                button.setIconGravity(ownGravity);
            }
        });
    }

    /** The same rule for a view that is not a button, such as a glyph beside a field. */
    public static void bindVisibility(@NonNull LifecycleOwner owner, @NonNull View glyph) {
        GamepadPresence.connected().observe(owner, pad ->
                glyph.setVisibility(Boolean.TRUE.equals(pad) ? View.VISIBLE : View.GONE));
    }
}
