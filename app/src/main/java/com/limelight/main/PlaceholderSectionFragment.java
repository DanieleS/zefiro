package com.limelight.main;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.limelight.R;

/**
 * Stand-in content for a section that does not exist yet: its name and two focusable buttons.
 *
 * <p>It is there so the shell's focus gate, back handling and navigation can be tried on a
 * device before any real section lands. It remembers which button last held focus, which is
 * exactly what the gate needs to bring the d-pad back to the same place. Deleted in S8, once
 * every section is real.
 */
public class PlaceholderSectionFragment extends Fragment implements SectionKeys, SectionFocus {

    private static final String ARG_SECTION = "section";

    @Nullable
    private View lastFocused;

    public PlaceholderSectionFragment() {
    }

    public static PlaceholderSectionFragment newInstance(Section section) {
        PlaceholderSectionFragment f = new PlaceholderSectionFragment();
        Bundle args = new Bundle();
        args.putString(ARG_SECTION, section.name());
        f.setArguments(args);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_placeholder_section, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Bundle args = getArguments();
        Section section = Section.fromName(args == null ? null : args.getString(ARG_SECTION),
                Section.LIBRARY);
        ((TextView) view.findViewById(R.id.placeholderTitle)).setText(section.title);

        View.OnFocusChangeListener remember = (v, hasFocus) -> {
            if (hasFocus) {
                lastFocused = v;
            }
        };
        view.findViewById(R.id.placeholderFirst).setOnFocusChangeListener(remember);
        view.findViewById(R.id.placeholderSecond).setOnFocusChangeListener(remember);
    }

    @Override
    public void onDestroyView() {
        lastFocused = null;
        super.onDestroyView();
    }

    @Override
    public boolean onSectionKey(KeyEvent event) {
        return false;
    }

    @Nullable
    @Override
    public View restoreFocusTarget() {
        View root = getView();
        if (!isAdded() || root == null) {
            return null;
        }
        return lastFocused != null ? lastFocused : root.findViewById(R.id.placeholderFirst);
    }

    @Override
    public void requestInitialFocus() {
        View target = restoreFocusTarget();
        if (target != null) {
            target.requestFocus();
        }
    }
}
