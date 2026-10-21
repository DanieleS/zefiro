package com.limelight.settings;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.DialogPreference;
import androidx.preference.ListPreferenceDialogFragmentCompat;

import com.limelight.R;

/**
 * A list setting's choices in a dialog on the main screen, when there is no companion panel to
 * offer them on ({@link SettingsLayout#choicesOnPanel}). androidx's own list dialog, with what it
 * does and the change listener it calls, and two differences:
 *
 * <ul>
 *   <li>Under the title, the setting's whole description, which its row shows cut short.</li>
 *   <li>It opens at the top of the list whenever the choice in effect is in view from there.
 *       androidx scrolls the choice in effect to the top, so a short window opened on 720p
 *       hid 360p and 480p above it with nothing to say they were there. When the choice is
 *       further down than one screen, it is shown at the bottom, the ones before it above.</li>
 * </ul>
 *
 * The d-pad's selection stays on the choice in effect either way: the list is scrolled, not
 * selected.
 */
public class SettingsListDialog extends ListPreferenceDialogFragmentCompat {

    /** For the fragment manager's restore; use {@link #newInstance}. */
    public SettingsListDialog() {
    }

    @NonNull
    public static SettingsListDialog newInstance(@NonNull String key) {
        SettingsListDialog dialog = new SettingsListDialog();
        Bundle args = new Bundle(1);
        args.putString(ARG_KEY, key);
        dialog.setArguments(args);
        return dialog;
    }

    @Override
    protected void onPrepareDialogBuilder(@NonNull AlertDialog.Builder builder) {
        super.onPrepareDialogBuilder(builder);
        DialogPreference preference = getPreference();
        CharSequence summary = preference.getSummary();
        if (summary == null || summary.length() == 0) {
            return;
        }
        View title = LayoutInflater.from(builder.getContext())
                .inflate(R.layout.dialog_settings_list_title, null, false);
        CharSequence name = preference.getDialogTitle() != null ? preference.getDialogTitle()
                : preference.getTitle();
        ((TextView) title.findViewById(R.id.settingsDialogTitle)).setText(name);
        ((TextView) title.findViewById(R.id.settingsDialogSummary)).setText(summary);
        builder.setCustomTitle(title);
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        ListView list = dialog instanceof AlertDialog ? ((AlertDialog) dialog).getListView() : null;
        if (list != null) {
            // Once the dialog has scrolled to the choice in effect and laid the rows out.
            list.post(() -> openAtTop(list));
        }
    }

    private static void openAtTop(@NonNull ListView list) {
        View first = list.getChildAt(0);
        int checked = list.getCheckedItemPosition();
        if (first == null || first.getHeight() == 0) {
            return;
        }
        int row = first.getHeight() + list.getDividerHeight();
        int rowsInView = Math.max(1, list.getHeight() / row);
        int scrolled = list.getFirstVisiblePosition() * row - first.getTop();
        if (checked < rowsInView) {
            // From the top, the choice in effect is in view.
            list.scrollListBy(-scrolled);
        } else {
            // The choice at the bottom of the view, those before it above.
            int target = (checked - rowsInView + 1) * row;
            list.scrollListBy(target - scrolled);
        }
    }
}
