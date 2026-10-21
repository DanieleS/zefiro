package com.limelight.settings;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceDialogFragmentCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.R;
import com.limelight.main.WindowClass;
import com.limelight.preferences.StreamPreferencesFragment;
import com.limelight.settings.SettingsChoicesPage.Choice;

import java.util.List;

/**
 * A list setting's choices in a dialog on the main screen, when there is no companion panel to
 * offer them on ({@link SettingsLayout#choicesOnPanel}). What androidx's own list dialog does (a
 * pick asks the change listener, then stores the value, and closes the dialog), drawn as the
 * panel draws the same choices ({@link SettingsChoicesPage}, the
 * {@code companion_page_settings_choice} row), so a list reads the same on either screen:
 *
 * <ul>
 *   <li>Under the title, the setting's description, which its row shows cut short.</li>
 *   <li>Each choice with what it does where that is written ({@link SettingsChoiceHints}), and
 *       "Default" on the one a reset would put back; the one in effect filled.</li>
 *   <li>Every choice in view. In a wide window a list of five or more takes two columns, so a
 *       turned phone shows all seven resolutions rather than four of them, and its description
 *       takes one line when the window is short as well. Should it still not fit, the list
 *       opens at the top, scrolled only as far as the choice in effect needs to be in view.</li>
 * </ul>
 *
 * The d-pad starts on the choice in effect. Main thread only.
 */
public class SettingsListDialog extends PreferenceDialogFragmentCompat {

    private static final String SAVE_STATE_INDEX = "SettingsListDialog.index";

    // A list this long takes two columns in a wide window.
    private static final int TWO_COLUMNS_MIN_CHOICES = 5;

    // The choice picked, by index; -1 while none is (the dialog cancelled stores nothing).
    private int clickedIndex = -1;
    // The choice in effect when the dialog opened, where the d-pad starts.
    private int selectedIndex = -1;
    @Nullable
    private RecyclerView choicesView;

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
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            clickedIndex = savedInstanceState.getInt(SAVE_STATE_INDEX, -1);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(SAVE_STATE_INDEX, clickedIndex);
    }

    @NonNull
    private ListPreference listPreference() {
        return (ListPreference) getPreference();
    }

    @Override
    protected void onPrepareDialogBuilder(@NonNull AlertDialog.Builder builder) {
        super.onPrepareDialogBuilder(builder);
        ListPreference preference = listPreference();
        Context context = builder.getContext();
        Configuration config = context.getResources().getConfiguration();
        boolean wide = WindowClass.from(config.screenWidthDp) == WindowClass.WIDE;
        boolean shortWindow = WindowClass.isShortWide(config.screenWidthDp, config.screenHeightDp);

        CharSequence summary = preference.getSummary();
        if (summary != null && summary.length() > 0) {
            View title = LayoutInflater.from(context)
                    .inflate(R.layout.dialog_settings_list_title, null, false);
            CharSequence name = preference.getDialogTitle() != null ? preference.getDialogTitle()
                    : preference.getTitle();
            ((TextView) title.findViewById(R.id.settingsDialogTitle)).setText(name);
            TextView text = title.findViewById(R.id.settingsDialogSummary);
            text.setText(summary);
            if (shortWindow) {
                // The height goes to the choices; the row's focus shows the rest.
                text.setMaxLines(1);
            }
            builder.setCustomTitle(title);
        }

        SettingsChoicesPage page = SettingsChoicesPage.of(preference, defaults(),
                preference.getSharedPreferences());
        selectedIndex = page.selectedIndex;
        int columns = wide && page.choices.size() >= TWO_COLUMNS_MIN_CHOICES ? 2 : 1;
        RecyclerView list = new RecyclerView(context);
        list.setLayoutManager(new GridLayoutManager(context, columns));
        list.setAdapter(new ChoicesAdapter(page.choices, page.selectedIndex, this::choose));
        int gutter = context.getResources().getDimensionPixelSize(R.dimen.settings_dialog_gutter);
        list.setPadding(gutter, 0, gutter, 0);
        list.setClipToPadding(false);
        choicesView = list;
        builder.setView(list);

        // A pick closes the dialog at once, as androidx's list dialog does: no OK button.
        builder.setPositiveButton(null, null);
    }

    // The defaults the rows mark, from the list the dialog belongs to; null marks none.
    @SuppressWarnings("deprecation") // androidx finds the dialog's preference by target fragment
    @Nullable
    private SettingsDefaults defaults() {
        Fragment target = getTargetFragment();
        SettingsAdapter adapter = target instanceof StreamPreferencesFragment
                ? ((StreamPreferencesFragment) target).getSettingsAdapter() : null;
        return adapter != null ? adapter.getDefaults() : null;
    }

    @Override
    public void onStart() {
        super.onStart();
        final RecyclerView list = choicesView;
        final int selected = selectedIndex;
        if (list == null || selected < 0) {
            return;
        }
        // Once the rows are laid out from the top: only as far as the choice in effect needs,
        // and the d-pad on it (a window in touch mode leaves focus where it is).
        list.post(() -> {
            list.scrollToPosition(selected);
            list.post(() -> {
                RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(selected);
                if (holder != null) {
                    holder.itemView.requestFocus();
                }
            });
        });
    }

    @Override
    public void onDestroyView() {
        choicesView = null;
        super.onDestroyView();
    }

    private void choose(int index) {
        clickedIndex = index;
        Dialog dialog = getDialog();
        if (dialog != null) {
            onClick(dialog, DialogInterface.BUTTON_POSITIVE);
            dialog.dismiss();
        }
    }

    @Override
    public void onDialogClosed(boolean positiveResult) {
        ListPreference preference = listPreference();
        CharSequence[] values = preference.getEntryValues();
        if (!positiveResult || clickedIndex < 0 || values == null || clickedIndex >= values.length) {
            return;
        }
        String value = values[clickedIndex].toString();
        if (preference.callChangeListener(value)) {
            preference.setValue(value);
        }
    }

    /** What a pick on a choice does. */
    private interface OnChoice {
        void chosen(int index);
    }

    /** The choices, as the panel's rows: a radio, the label, what it does or "Default". */
    private static final class ChoicesAdapter extends RecyclerView.Adapter<ChoicesAdapter.Holder> {
        private final List<Choice> choices;
        private final int selected;
        private final OnChoice onChoice;

        ChoicesAdapter(@NonNull List<Choice> choices, int selected, @NonNull OnChoice onChoice) {
            this.choices = choices;
            this.selected = selected;
            this.onChoice = onChoice;
        }

        @Override
        public int getItemCount() {
            return choices.size();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View row = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.companion_page_settings_choice, parent, false);
            // A stop here, unlike on the panel: the dialog is on the screen the d-pad drives.
            row.setFocusable(true);
            // Two columns side by side keep apart as rows do.
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) row.getLayoutParams();
            int gap = parent.getResources().getDimensionPixelSize(R.dimen.settings_dialog_column_gap) / 2;
            params.setMarginStart(gap);
            params.setMarginEnd(gap);
            return new Holder(row);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Choice choice = choices.get(position);
            View row = holder.itemView;
            ((TextView) row.findViewById(R.id.companionChoiceLabel)).setText(choice.label);
            TextView description = row.findViewById(R.id.companionChoiceDescription);
            description.setText(choice.description);
            description.setVisibility(choice.description == null || choice.description.isEmpty()
                    ? View.GONE : View.VISIBLE);
            row.setSelected(position == selected);
            row.setOnClickListener(v -> {
                int at = holder.getBindingAdapterPosition();
                if (at != RecyclerView.NO_POSITION) {
                    onChoice.chosen(at);
                }
            });
        }

        static final class Holder extends RecyclerView.ViewHolder {
            Holder(@NonNull View itemView) {
                super(itemView);
            }
        }
    }
}
