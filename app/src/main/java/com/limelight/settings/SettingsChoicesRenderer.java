package com.limelight.settings;

import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.TextViewCompat;

import com.limelight.R;
import com.limelight.companion.CompanionPage;
import com.limelight.companion.CompanionPageRenderer;
import com.limelight.ui.GamepadHintsView;

/**
 * Draws a {@link SettingsChoicesPage} on the companion panel: the caption, title and summary,
 * then the body its kind calls for (radio rows, the switch, the slider's buttons, "A opens it"),
 * the default and the hints.
 *
 * <p>The radio rows are built again only when the entries change (another setting, a list
 * reshaped by a reload); a value moving along the same list only moves the selection, so Left and
 * Right held on the main screen do not re-inflate the panel at every step. The selected row is
 * kept in view, as a ring would be, without taking focus.
 *
 * <p>A disabled setting is drawn dimmed, and its targets are not clickable; the tap would be
 * refused by the preference anyway, and a press that does nothing should not look like one that
 * did.
 *
 * <p>A stateless singleton: the entries drawn last are kept on the view, as its tag. Main thread
 * only.
 */
final class SettingsChoicesRenderer implements CompanionPageRenderer {

    static final SettingsChoicesRenderer INSTANCE = new SettingsChoicesRenderer();

    // Material's disabled emphasis, as a disabled row in the list is drawn.
    private static final float DISABLED_ALPHA = 0.38f;

    private SettingsChoicesRenderer() {
    }

    @NonNull
    @Override
    public View create(@NonNull LayoutInflater inflater, @NonNull ViewGroup parent) {
        return inflater.inflate(R.layout.companion_page_settings_choices, parent, false);
    }

    @Override
    public void bind(@NonNull View view, @NonNull CompanionPage value, @NonNull PageTaps taps) {
        SettingsChoicesPage page = (SettingsChoicesPage) value;

        ((TextView) view.findViewById(R.id.companionSettingsLabel)).setText(page.sectionLabel);
        ((TextView) view.findViewById(R.id.companionSettingsTitle)).setText(page.title);
        setOptional(view.findViewById(R.id.companionSettingsSummary), page.summary);
        setOptional(view.findViewById(R.id.companionSettingsNeeds), page.needs);
        setOptional(view.findViewById(R.id.companionSettingsDefault), page.defaultText);

        View body = view.findViewById(R.id.companionSettingsBody);
        body.setAlpha(page.enabled ? 1f : DISABLED_ALPHA);

        bindChoices(view, page, taps);
        bindToggle(view, page, taps);
        bindSeek(view, page, taps);
        bindOpen(view, page);

        GamepadHintsView hints = view.findViewById(R.id.companionSettingsHints);
        hints.setHints(page.hints.toArray(new GamepadHintsView.Hint[0]));
    }

    private static void bindChoices(View view, SettingsChoicesPage page, PageTaps taps) {
        LinearLayout rows = view.findViewById(R.id.companionSettingsChoices);
        boolean list = page.kind == SettingsSearch.Kind.LIST;
        rows.setVisibility(list ? View.VISIBLE : View.GONE);
        if (!list) {
            rows.removeAllViews();
            rows.setTag(null);
            return;
        }
        Object drawn = rows.getTag();
        if (!page.choices.equals(drawn)) {
            buildChoices(rows, page);
            rows.setTag(page.choices);
            // Another setting: its list starts at the top, not where the last one was scrolled.
            view.findViewById(R.id.companionSettingsScroll).scrollTo(0, 0);
        }
        for (int i = 0; i < rows.getChildCount(); i++) {
            final int stop = i;
            View row = rows.getChildAt(i);
            boolean selected = i == page.selectedIndex;
            row.setSelected(selected);
            row.setClickable(page.enabled);
            // Set on every bind: a tap goes to the taps of the value on screen.
            row.setOnClickListener(page.enabled ? v -> taps.tap(stop) : null);
            if (selected) {
                CompanionPageRenderer.keepInView(row);
            }
        }
    }

    private static void buildChoices(LinearLayout rows, SettingsChoicesPage page) {
        rows.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(rows.getContext());
        for (SettingsChoicesPage.Choice choice : page.choices) {
            View row = inflater.inflate(R.layout.companion_page_settings_choice, rows, false);
            ((TextView) row.findViewById(R.id.companionChoiceLabel)).setText(choice.label);
            setOptional(row.findViewById(R.id.companionChoiceDescription), choice.description);
            rows.addView(row);
        }
    }

    private static void bindToggle(View view, SettingsChoicesPage page, PageTaps taps) {
        View toggle = view.findViewById(R.id.companionSettingsToggle);
        boolean shown = page.kind == SettingsSearch.Kind.TOGGLE;
        toggle.setVisibility(shown ? View.VISIBLE : View.GONE);
        if (!shown) {
            return;
        }
        ((TextView) view.findViewById(R.id.companionSettingsToggleState)).setText(page.valueText);
        View track = view.findViewById(R.id.companionSettingsSwitchTrack);
        View thumb = view.findViewById(R.id.companionSettingsSwitchThumb);
        track.setSelected(page.checked);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) thumb.getLayoutParams();
        int gravity = Gravity.CENTER_VERTICAL | (page.checked ? Gravity.END : Gravity.START);
        if (params.gravity != gravity) {
            params.gravity = gravity;
            thumb.setLayoutParams(params);
        }
        toggle.setClickable(page.enabled);
        toggle.setOnClickListener(page.enabled
                ? v -> taps.tap(SettingsChoicesPage.STOP_SWITCH) : null);
        toggle.setContentDescription(page.title + ", " + page.valueText);
    }

    private static void bindSeek(View view, SettingsChoicesPage page, PageTaps taps) {
        View seek = view.findViewById(R.id.companionSettingsSeek);
        boolean shown = page.kind == SettingsSearch.Kind.SEEK;
        seek.setVisibility(shown ? View.VISIBLE : View.GONE);
        if (!shown) {
            return;
        }
        ((TextView) view.findViewById(R.id.companionSettingsSeekValue)).setText(page.valueText);
        setOptional(view.findViewById(R.id.companionSettingsSeekRange), page.rangeText);
        bindStep(view.findViewById(R.id.companionSettingsMinus), page.canDecrease,
                SettingsChoicesPage.STOP_MINUS, taps);
        bindStep(view.findViewById(R.id.companionSettingsPlus), page.canIncrease,
                SettingsChoicesPage.STOP_PLUS, taps);
    }

    // An end of the range dims its button, as the list's dialog would stop its slider there.
    private static void bindStep(View button, boolean can, final int stop, PageTaps taps) {
        button.setClickable(can);
        button.setOnClickListener(can ? v -> taps.tap(stop) : null);
        button.setAlpha(can ? 1f : DISABLED_ALPHA);
    }

    private static void bindOpen(View view, SettingsChoicesPage page) {
        View open = view.findViewById(R.id.companionSettingsOpen);
        boolean shown = page.kind == SettingsSearch.Kind.TEXT
                || page.kind == SettingsSearch.Kind.ACTION;
        open.setVisibility(shown ? View.VISIBLE : View.GONE);
        if (!shown) {
            return;
        }
        setOptional(view.findViewById(R.id.companionSettingsOpenValue),
                page.kind == SettingsSearch.Kind.TEXT ? page.valueText : null);
        TextView note = view.findViewById(R.id.companionSettingsOpenNote);
        note.setText(page.external ? R.string.settings_choices_opens_browser
                : R.string.settings_choices_opens);
        note.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0,
                page.external ? R.drawable.ic_open_in_new : 0, 0);
        // The icon is white; muted like the note, as the row's own icon is tinted.
        TextViewCompat.setCompoundDrawableTintList(note,
                ContextCompat.getColorStateList(note.getContext(), R.color.content_muted));
    }

    private static void setOptional(TextView text, @Nullable String value) {
        text.setText(value);
        text.setVisibility(value == null || value.isEmpty() ? View.GONE : View.VISIBLE);
    }
}
