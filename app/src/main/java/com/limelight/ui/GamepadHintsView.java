package com.limelight.ui;

import android.content.Context;
import android.content.ContextWrapper;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.limelight.R;
import com.limelight.main.MainHost;

import java.util.Arrays;
import java.util.Objects;

/**
 * A row of gamepad hints, "A Open October · B Up · LB RB Year": each hint is its buttons' glyphs
 * (the mocks' {@code .gk}) and what they do.
 *
 * <p>Hints are for a screen driven by the pad. On the main screen the row is gone unless it is
 * d-pad first ({@link MainHost#isDpadFirst()}); a phone held in the hand has no buttons to name.
 * Anywhere without a main screen around it (the companion panel, whose context is its own) it is
 * shown: the panel's d-pad is the main screen's, and that is only steered on a device with a pad.
 * Asked again on every {@link #setHints} and attach, since a pad can be connected at any time.
 *
 * Main thread only.
 */
public class GamepadHintsView extends LinearLayout {

    /** One hint: its buttons, then what they do. Immutable. */
    public static final class Hint {
        @NonNull
        public final String label;
        @NonNull
        private final String[] keys;

        /**
         * @param label what the buttons do ("Open October")
         * @param keys  the buttons, as their glyphs read ("A", "LB", "RB")
         */
        public Hint(@NonNull String label, @NonNull String... keys) {
            this.label = label;
            this.keys = keys.clone();
        }

        @NonNull
        public String[] keys() {
            return keys.clone();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Hint)) {
                return false;
            }
            Hint that = (Hint) o;
            return label.equals(that.label) && Arrays.equals(keys, that.keys);
        }

        @Override
        public int hashCode() {
            return Objects.hash(label, Arrays.hashCode(keys));
        }

        @NonNull
        @Override
        public String toString() {
            return Arrays.toString(keys) + " " + label;
        }

        // "LB RB Year": the buttons by name, then what they do.
        @NonNull
        String toSpoken() {
            StringBuilder out = new StringBuilder();
            for (String key : keys) {
                out.append(key).append(' ');
            }
            return out.append(label).toString();
        }
    }

    private Hint[] hints = new Hint[0];

    public GamepadHintsView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setVisibility(GONE);
    }

    /** The hints, in order; none hides the row. An unchanged set is not laid out again. */
    public void setHints(@NonNull Hint... next) {
        if (!Arrays.equals(hints, next)) {
            hints = next.clone();
            rebuild();
        }
        updateVisibility();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateVisibility();
    }

    private void rebuild() {
        removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(getContext());
        int keyGap = getResources().getDimensionPixelSize(R.dimen.key_glyph_gap);
        int labelGap = getResources().getDimensionPixelSize(R.dimen.hint_label_gap);
        int hintGap = getResources().getDimensionPixelSize(R.dimen.hint_gap);
        StringBuilder spoken = new StringBuilder();
        for (int h = 0; h < hints.length; h++) {
            Hint hint = hints[h];
            for (int k = 0; k < hint.keys.length; k++) {
                TextView key = (TextView) inflater.inflate(R.layout.view_gamepad_hint_key, this,
                        false);
                key.setText(hint.keys[k]);
                LayoutParams params = (LayoutParams) key.getLayoutParams();
                params.setMarginStart(k == 0 ? (h == 0 ? 0 : hintGap) : keyGap);
                addView(key, params);
            }
            TextView label = (TextView) inflater.inflate(R.layout.view_gamepad_hint_label, this,
                    false);
            label.setText(hint.label);
            LayoutParams params = (LayoutParams) label.getLayoutParams();
            params.setMarginStart(hint.keys.length > 0 ? labelGap : (h == 0 ? 0 : hintGap));
            addView(label, params);
            if (spoken.length() > 0) {
                spoken.append(", ");
            }
            spoken.append(hint.toSpoken());
        }
        // Read as one line: the glyphs on their own are letters without meaning.
        setContentDescription(spoken.toString());
    }

    private void updateVisibility() {
        setVisibility(hints.length > 0 && isDpadFirst() ? VISIBLE : GONE);
    }

    private boolean isDpadFirst() {
        for (Context context = getContext(); context instanceof ContextWrapper;
             context = ((ContextWrapper) context).getBaseContext()) {
            if (context instanceof MainHost) {
                return ((MainHost) context).isDpadFirst();
            }
        }
        // No main screen around this view: the companion panel (see the class comment).
        return true;
    }
}
