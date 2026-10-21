package com.limelight.stats;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.limelight.R;
import com.limelight.companion.CompanionPage;
import com.limelight.companion.CompanionPageRenderer;
import com.limelight.library.GameActionsSheet;

/**
 * Draws a {@link GameActionsPage} on the companion panel: the "no achievements" card, the
 * game's name, and one 56dp row per action with its icon. The rows are built again only when
 * the actions change; a focus move only moves the activated ring and keeps that row in view.
 *
 * <p>A stateless singleton: the page drawn last is kept on the view, as its tag. Main thread
 * only.
 */
final class GameActionsRenderer implements CompanionPageRenderer {

    static final GameActionsRenderer INSTANCE = new GameActionsRenderer();

    private GameActionsRenderer() {
    }

    @NonNull
    @Override
    public View create(@NonNull LayoutInflater inflater, @NonNull ViewGroup parent) {
        return inflater.inflate(R.layout.companion_page_game_actions, parent, false);
    }

    @Override
    public void bind(@NonNull View view, @NonNull CompanionPage value, @NonNull PageTaps taps) {
        GameActionsPage page = (GameActionsPage) value;
        TextView detail = view.findViewById(R.id.companionActionsDetail);
        TextView title = view.findViewById(R.id.companionActionsTitle);
        LinearLayout rows = view.findViewById(R.id.companionActionsRows);

        detail.setText(view.getContext().getString(R.string.game_page_no_achievements_detail,
                page.title));
        title.setText(page.title);
        title.setVisibility(page.items.isEmpty() ? View.GONE : View.VISIBLE);

        Object drawn = view.getTag(R.id.companionActionsRows);
        if (!(drawn instanceof GameActionsPage)
                || !page.withFocus(CompanionPage.NO_STOP)
                        .equals(((GameActionsPage) drawn).withFocus(CompanionPage.NO_STOP))) {
            buildRows(rows, page);
        }
        view.setTag(R.id.companionActionsRows, page);

        for (int i = 0; i < rows.getChildCount(); i++) {
            final int stop = i;
            View row = rows.getChildAt(i);
            // Set on every bind: a tap goes to the taps of the value on screen.
            row.setOnClickListener(v -> taps.tap(stop));
            boolean focused = i == page.focusedStop();
            row.setActivated(focused);
            if (focused) {
                CompanionPageRenderer.keepInView(row);
            }
        }
    }

    private static void buildRows(LinearLayout rows, GameActionsPage page) {
        rows.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(rows.getContext());
        for (int i = 0; i < page.items.size(); i++) {
            GameActionsSheet.Item item = page.items.get(i);
            View row = inflater.inflate(R.layout.companion_page_game_action_row, rows, false);
            ((ImageView) row.findViewById(R.id.companionActionIcon)).setImageResource(iconOf(item));
            ((TextView) row.findViewById(R.id.companionActionLabel)).setText(page.labels.get(i));
            row.findViewById(R.id.companionActionCheck).setVisibility(
                    item == GameActionsSheet.Item.HIDE && page.hidden ? View.VISIBLE : View.GONE);
            rows.addView(row);
        }
    }

    /**
     * Each action's words on a game's page, chips and rows alike, in the mock's sentence case
     * ("Hide from the library", "Export launcher file"); the actions sheet keeps its own.
     */
    @StringRes
    static int labelOf(@NonNull GameActionsSheet.Item item) {
        switch (item) {
            case START_VIRTUAL_DISPLAY:
                return R.string.game_page_action_start_vdisplay;
            case START_PRIMARY_DISPLAY:
                return R.string.game_page_action_start_primary;
            case HIDE:
                return R.string.game_page_action_hide;
            case DETAILS:
                return R.string.game_page_action_details;
            case PIN_SHORTCUT:
                return R.string.game_page_action_shortcut;
            case EXPORT_LAUNCHER:
                return R.string.game_page_action_export;
            default:
                return item.label;
        }
    }

    /** Each action's icon, after the mock's rows. */
    @DrawableRes
    static int iconOf(@NonNull GameActionsSheet.Item item) {
        switch (item) {
            case START_VIRTUAL_DISPLAY:
            case QUIT_AND_START_VIRTUAL_DISPLAY:
                return R.drawable.ic_virtual_display;
            case START_PRIMARY_DISPLAY:
            case QUIT_AND_START_PRIMARY_DISPLAY:
            case QUIT_AND_START:
                return R.drawable.ic_computer;
            case RESUME:
                return R.drawable.ic_resume;
            case QUIT:
                return R.drawable.ic_stop;
            case HIDE:
                return R.drawable.ic_visibility_off;
            case COMPANION_APP:
                return R.drawable.ic_companion_on;
            case STATS:
                return R.drawable.ic_stats;
            case PIN_SHORTCUT:
                return R.drawable.ic_push_pin;
            case EXPORT_LAUNCHER:
                return R.drawable.ic_file_upload;
            case DETAILS:
            default:
                return R.drawable.ic_info;
        }
    }
}
