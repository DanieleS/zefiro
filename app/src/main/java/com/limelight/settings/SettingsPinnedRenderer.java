package com.limelight.settings;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.limelight.R;
import com.limelight.companion.CompanionPage;
import com.limelight.companion.CompanionPageRenderer;
import com.limelight.ui.GamepadHintsView;

/**
 * Draws a {@link SettingsPinnedPage} on the companion panel: the active profile, then one row per
 * essential with its value, the signal on a changed one and a "profile" tag on one the profile
 * sets. Rows are built again only when their number changes; every bind rewrites their text.
 *
 * <p>A stateless singleton. Main thread only.
 */
final class SettingsPinnedRenderer implements CompanionPageRenderer {

    static final SettingsPinnedRenderer INSTANCE = new SettingsPinnedRenderer();

    private SettingsPinnedRenderer() {
    }

    @NonNull
    @Override
    public View create(@NonNull LayoutInflater inflater, @NonNull ViewGroup parent) {
        return inflater.inflate(R.layout.companion_page_settings_pinned, parent, false);
    }

    @Override
    public void bind(@NonNull View view, @NonNull CompanionPage value, @NonNull PageTaps taps) {
        SettingsPinnedPage page = (SettingsPinnedPage) value;
        TextView profile = view.findViewById(R.id.companionPinnedProfile);
        profile.setText(page.profile != null
                ? view.getContext().getString(R.string.settings_pinned_page_profile, page.profile)
                : view.getContext().getString(R.string.settings_profile_none));

        LinearLayout rows = view.findViewById(R.id.companionPinnedRows);
        if (rows.getChildCount() != page.rows.size()) {
            rows.removeAllViews();
            LayoutInflater inflater = LayoutInflater.from(rows.getContext());
            for (int i = 0; i < page.rows.size(); i++) {
                rows.addView(inflater.inflate(R.layout.companion_page_settings_pinned_row, rows,
                        false));
            }
        }
        int signal = ContextCompat.getColor(view.getContext(), R.color.signal);
        int primary = ContextCompat.getColor(view.getContext(), R.color.content_primary);
        for (int i = 0; i < page.rows.size(); i++) {
            final int stop = i;
            SettingsPinnedPage.Row row = page.rows.get(i);
            View item = rows.getChildAt(i);
            ((TextView) item.findViewById(R.id.companionPinnedLabel)).setText(row.label);
            TextView shown = item.findViewById(R.id.companionPinnedValue);
            shown.setText(row.value);
            shown.setTextColor(row.changed ? signal : primary);
            item.findViewById(R.id.companionPinnedTag)
                    .setVisibility(row.fromProfile ? View.VISIBLE : View.GONE);
            item.setContentDescription(row.label + ", " + row.value);
            // Set on every bind: a tap goes to the taps of the value on screen.
            item.setOnClickListener(v -> taps.tap(stop));
        }

        GamepadHintsView hints = view.findViewById(R.id.companionPinnedHints);
        hints.setHints(
                new GamepadHintsView.Hint(view.getContext().getString(
                        R.string.settings_choices_hint_search), "Y"),
                new GamepadHintsView.Hint(view.getContext().getString(
                        R.string.settings_pinned_page_hint_touch)));
    }
}
