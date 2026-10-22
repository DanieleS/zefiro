package com.limelight.settings;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.limelight.R;
import com.limelight.ui.ButtonGlyphs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Settings section's header ({@code view_settings_header}): the search field, the pinned
 * card and the chips. It draws what {@link SettingsSectionFragment} hands it
 * and reports what the user picks; it decides nothing about settings itself.
 *
 * <h3>Two states</h3>
 *
 * <pre>
 *   at rest    the field as a button ("Search 144 settings…"), the card, the chips as jumps to
 *              the sections (Pinned first), "{n} changed" (none: not shown)
 *   searching  back, the query and clear; no card; the chips as the results' filters ("All 8",
 *              one per section with matches, "Changed 2" while any match has changed); "Also
 *              showing matches for …"
 * </pre>
 *
 * <h3>Rebuilt only on change</h3>
 *
 * The section rebinds on every stored change, which a held Left on a slider makes many times a
 * second. The pins and chips are views the d-pad stands on, so they are rebuilt only when their
 * set changes, and otherwise their text is set in place: a rebuild would take focus out from
 * under the d-pad.
 *
 * Main thread only.
 */
final class SettingsHeader {

    /** What the user picked in the header. */
    interface Listener {
        /** The field at rest was chosen: start searching. */
        void onSearchRequested();

        /** The search bar's back arrow. */
        void onSearchLeave();

        void onQueryChanged(@NonNull String query);

        /** Search on the keyboard: done typing. */
        void onQuerySubmitted();

        /** The profile pin, or Switch beside the profile. */
        void onProfileRequested(@NonNull View anchor);

        /** A value pin: go to its row. */
        void onPinChosen(@NonNull String key);

        /** X or a long-press on a pin: its row's menu. */
        boolean onPinLongPressed(@NonNull String key, @NonNull View pin);

        /** A jump chip: null for Pinned, else a section id. */
        void onJumpChosen(@Nullable String sectionId);

        void onFilterChosen(@NonNull SettingsSearch.Filter filter);

        /** "{n} changed": search the changed settings. */
        void onChangedChosen();
    }

    /** One value on the card. Immutable; equal pins are not redrawn. */
    static final class Pin {
        @NonNull
        final String key;
        @NonNull
        final CharSequence label;
        @NonNull
        final CharSequence value;
        // Away from its default: the value takes the signal, as a changed row's does.
        final boolean changed;
        // The active profile sets this value in place of the stored one.
        final boolean fromProfile;

        Pin(@NonNull String key, @NonNull CharSequence label, @NonNull CharSequence value,
            boolean changed, boolean fromProfile) {
            this.key = key;
            this.label = label;
            this.value = value;
            this.changed = changed;
            this.fromProfile = fromProfile;
        }
    }

    private final View root;
    private final Listener listener;
    private final LayoutInflater inflater;

    private final View searchRest;
    private final TextView searchHint;
    private final View searchGlyph;
    private final View searchBar;
    private final EditText field;
    private final View clear;
    private final View card;
    // Where the card sits in the header, to put it back after it was hung in the list.
    private final int cardIndex;
    private final ViewGroup.LayoutParams cardParams;
    private final View profileRow;
    private final View cardRule;
    private final TextView profileName;
    private final TextView profileSubtitle;
    private final View profileSwitch;
    private final View profilePin;
    private final TextView profilePinName;
    private final LinearLayout pins;
    private final View prevGlyph;
    private final View nextGlyph;
    private final HorizontalScrollView chipsScroll;
    private final LinearLayout chips;
    private final TextView changedChip;
    private final TextView alsoMatches;

    private boolean wide;
    // Whether a gamepad is connected (GamepadPresence): the glyphs name its buttons, and a phone
    // in the hand has none to name.
    private boolean glyphs;
    private int changedCount;
    private boolean searching;
    // Set while the query is written from code, so the watcher does not report it back.
    private boolean settingQuery;

    // What the pins and chips were last built from, to tell a rebuild from an update.
    private List<String> pinKeys = Collections.emptyList();
    private boolean pinsWide;
    // At rest, the section id behind each chip (null for Pinned); searching, unused.
    private final List<String> jumpIds = new ArrayList<>();
    // Searching, the filter behind each chip; at rest, unused.
    private final List<SettingsSearch.Filter> filters = new ArrayList<>();
    private boolean chipsAreFilters;

    SettingsHeader(@NonNull View root, @NonNull Listener listener) {
        this.root = root;
        this.listener = listener;
        this.inflater = LayoutInflater.from(root.getContext());

        searchRest = root.findViewById(R.id.settingsSearchRest);
        searchHint = root.findViewById(R.id.settingsSearchHint);
        searchGlyph = root.findViewById(R.id.settingsSearchGlyph);
        searchBar = root.findViewById(R.id.settingsSearchBar);
        field = root.findViewById(R.id.settingsSearchField);
        clear = root.findViewById(R.id.settingsSearchClear);
        card = root.findViewById(R.id.settingsCard);
        cardIndex = ((ViewGroup) root).indexOfChild(card);
        cardParams = card.getLayoutParams();
        profileRow = root.findViewById(R.id.settingsProfileRow);
        cardRule = root.findViewById(R.id.settingsCardRule);
        profileName = root.findViewById(R.id.settingsProfileName);
        profileSubtitle = root.findViewById(R.id.settingsProfileSubtitle);
        profileSwitch = root.findViewById(R.id.settingsProfileSwitch);
        profilePin = root.findViewById(R.id.settingsProfilePin);
        profilePinName = root.findViewById(R.id.settingsProfilePinName);
        pins = root.findViewById(R.id.settingsPins);
        prevGlyph = root.findViewById(R.id.settingsChipsPrevGlyph);
        nextGlyph = root.findViewById(R.id.settingsChipsNextGlyph);
        chipsScroll = root.findViewById(R.id.settingsChipsScroll);
        chips = root.findViewById(R.id.settingsChips);
        changedChip = root.findViewById(R.id.settingsChangedChip);
        alsoMatches = root.findViewById(R.id.settingsAlsoMatches);

        // The mock's glyphs: Y searches, L1 and R1 (the Thor's shoulder buttons) jump between
        // the chips, or step through the filters while searching, and B leaves the search.
        ((TextView) searchGlyph).setText("Y");
        // A key on the field, not of its colour: the hint's own key is the field's surface.
        searchGlyph.setBackgroundResource(R.drawable.settings_search_key_bg);
        ((TextView) prevGlyph).setText("L1");
        ((TextView) nextGlyph).setText("R1");
        ButtonGlyphs.bindImage(root.findViewById(R.id.settingsSearchBack), "B");

        searchRest.setOnClickListener(v -> listener.onSearchRequested());
        root.findViewById(R.id.settingsSearchBack).setOnClickListener(v -> listener.onSearchLeave());
        clear.setOnClickListener(v -> {
            setQuery("");
            listener.onQueryChanged("");
            field.requestFocus();
        });
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                clear.setVisibility(s.length() > 0 ? View.VISIBLE : View.INVISIBLE);
                if (!settingQuery) {
                    listener.onQueryChanged(s.toString());
                }
            }
        });
        field.setOnEditorActionListener((v, actionId, event) -> {
            boolean submit = actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN);
            if (submit) {
                listener.onQuerySubmitted();
            }
            return submit;
        });
        // Drawn as an "on" chip, as the mock's tonal Switch button is.
        profileSwitch.setSelected(true);
        profileSwitch.setOnClickListener(listener::onProfileRequested);
        profilePin.setOnClickListener(listener::onProfileRequested);
        changedChip.setOnClickListener(v -> listener.onChangedChosen());
    }

    @NonNull
    View view() {
        return root;
    }

    @NonNull
    EditText field() {
        return field;
    }

    @NonNull
    View searchRest() {
        return searchRest;
    }

    boolean isSearching() {
        return searching;
    }

    /** True for a view inside this header, its card included wherever the card hangs. */
    boolean contains(@Nullable View view) {
        for (View v = view; v != null; ) {
            if (v == root || v == card) {
                return true;
            }
            ViewParent parent = v.getParent();
            v = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    /** True for a view on the pinned card: the profile or a value. */
    boolean isOnCard(@Nullable View view) {
        for (View v = view; v != null && v != root; ) {
            if (v == card) {
                return true;
            }
            ViewParent parent = v.getParent();
            v = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    /**
     * Hangs the card in {@code holder}, out of the header, so it scrolls away with the list while
     * the field and the chips stay put (a phone in landscape, SettingsLayout.cardInList); null
     * puts it back in its place between them.
     */
    void hangCard(@Nullable ViewGroup holder) {
        ViewGroup target = holder != null ? holder : (ViewGroup) root;
        ViewParent parent = card.getParent();
        if (parent == target) {
            return;
        }
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(card);
        }
        if (holder != null) {
            holder.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            ((ViewGroup) root).addView(card, cardIndex, cardParams);
        }
    }

    // --- Window and state ---

    /** Lays the header out for a wide window (the Thor) or a compact one (a phone). */
    void setWide(boolean wide) {
        this.wide = wide;
        applyState();
    }

    /**
     * Shows the gamepad glyphs (Y, L1, R1) or hides them: shown while a pad is connected,
     * whatever the window's size.
     */
    void setGlyphs(boolean glyphs) {
        this.glyphs = glyphs;
        applyState();
    }

    void setSearching(boolean searching) {
        if (this.searching == searching) {
            return;
        }
        this.searching = searching;
        if (!searching) {
            setQuery("");
            alsoMatches.setVisibility(View.GONE);
        }
        applyState();
    }

    private void applyState() {
        searchRest.setVisibility(searching ? View.GONE : View.VISIBLE);
        searchBar.setVisibility(searching ? View.VISIBLE : View.GONE);
        searchGlyph.setVisibility(glyphs ? View.VISIBLE : View.GONE);
        card.setVisibility(searching ? View.GONE : View.VISIBLE);
        profileRow.setVisibility(wide ? View.GONE : View.VISIBLE);
        cardRule.setVisibility(wide ? View.GONE : View.VISIBLE);
        profilePin.setVisibility(wide ? View.VISIBLE : View.GONE);
        prevGlyph.setVisibility(glyphs ? View.VISIBLE : View.GONE);
        nextGlyph.setVisibility(glyphs ? View.VISIBLE : View.GONE);
        changedChip.setVisibility(wide && !searching && changedCount > 0 ? View.VISIBLE : View.GONE);
    }

    // --- At rest ---

    /**
     * Everything the header shows at rest.
     *
     * @param searchable how many settings the field searches
     * @param profile    the active profile's name, or null for none
     * @param changed    how many settings differ from their defaults
     * @param pinsShown  the card's values, essentials first
     * @param sections   the sections on screen, for the jump chips
     */
    void bindRest(int searchable, @Nullable String profile, int changed,
                  @NonNull List<Pin> pinsShown, @NonNull List<SettingsCatalog.Section> sections) {
        Context context = root.getContext();
        searchHint.setText(context.getString(wide ? R.string.settings_search_hint_wide
                : R.string.settings_search_hint, searchable));

        String name = profile != null ? profile : context.getString(R.string.settings_profile_none);
        profileName.setText(name);
        profilePinName.setText(name);
        profileSubtitle.setText(context.getString(R.string.settings_profile_subtitle, changed));
        profilePin.setContentDescription(context.getString(R.string.settings_pin_profile) + ", " + name);
        changedChip.setText(context.getString(R.string.settings_changed_count, changed));
        if (changedCount != changed) {
            changedCount = changed;
            applyState();
        }

        bindPins(pinsShown);
        if (!searching) {
            bindJumps(sections);
        }
    }

    private void bindPins(List<Pin> shown) {
        List<String> keys = new ArrayList<>();
        for (Pin pin : shown) {
            keys.add(pin.key);
        }
        if (!keys.equals(pinKeys) || pinsWide != wide) {
            rebuildPins(keys);
        }
        int at = 0;
        for (int i = 0; i < pins.getChildCount(); i++) {
            View child = pins.getChildAt(i);
            if (child.getTag() instanceof String && at < shown.size()) {
                bindPin(child, shown.get(at++));
            }
        }
    }

    private void rebuildPins(List<String> keys) {
        pins.removeAllViews();
        pinKeys = keys;
        pinsWide = wide;
        int ruleHeight = root.getResources().getDimensionPixelSize(R.dimen.settings_pin_height) / 2;
        int rule = ContextCompat.getColor(root.getContext(), R.color.settings_card_rule);
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0 || wide) {
                // A hairline between values, and between the profile and the first in a wide
                // window, as the mock's pins are ruled.
                View line = new View(root.getContext());
                line.setBackgroundColor(rule);
                pins.addView(line, new LinearLayout.LayoutParams(1, ruleHeight));
            }
            final String key = keys.get(i);
            View pin = inflater.inflate(wide ? R.layout.item_settings_pin : R.layout.item_settings_pill,
                    pins, false);
            pin.setTag(key);
            pin.setOnClickListener(v -> listener.onPinChosen(key));
            pin.setOnLongClickListener(v -> listener.onPinLongPressed(key, v));
            // Width 0 and an equal weight: in the scroll view's viewport (fillViewport) the
            // values share the card, and once they need more room than it has they keep their
            // own width and scroll.
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            pins.addView(pin, params);
        }
    }

    private static void bindPin(View view, Pin pin) {
        TextView label = view.findViewById(R.id.settingPinLabel);
        TextView value = view.findViewById(R.id.settingPinValue);
        View tag = view.findViewById(R.id.settingPinTag);
        label.setText(pin.label);
        value.setText(pin.value);
        value.setTextColor(ContextCompat.getColor(view.getContext(),
                pin.changed ? R.color.signal : R.color.content_primary));
        tag.setVisibility(pin.fromProfile ? View.VISIBLE : View.GONE);
        view.setContentDescription(pin.label + ", " + pin.value);
    }

    /** The view of a pinned value, or null when the card does not show it. */
    @Nullable
    View pinView(@NonNull String key) {
        return pins.findViewWithTag(key);
    }

    /** Where "Pinned" lands: the profile in a wide window, the first value in a compact one. */
    @Nullable
    View firstPin() {
        if (wide && profilePin.isShown()) {
            return profilePin;
        }
        for (int i = 0; i < pins.getChildCount(); i++) {
            View child = pins.getChildAt(i);
            if (child.getTag() instanceof String) {
                return child;
            }
        }
        return profileSwitch.isShown() ? profileSwitch : null;
    }

    /** The key of the value pin a view is, or null. */
    @Nullable
    String pinKeyOf(@Nullable View view) {
        return view != null && view.getParent() == pins && view.getTag() instanceof String
                ? (String) view.getTag() : null;
    }

    // --- Jump chips (at rest) ---

    private void bindJumps(List<SettingsCatalog.Section> sections) {
        List<String> ids = new ArrayList<>();
        if (wide) {
            // A phone has the card right above the chips, so it needs no chip to reach it.
            ids.add(null);
        }
        for (SettingsCatalog.Section section : sections) {
            ids.add(section.id);
        }
        if (!chipsAreFilters && ids.equals(jumpIds)) {
            return;
        }
        chipsAreFilters = false;
        jumpIds.clear();
        jumpIds.addAll(ids);
        filters.clear();
        chips.removeAllViews();
        Context context = root.getContext();
        for (int i = 0; i < ids.size(); i++) {
            final String id = ids.get(i);
            CharSequence label = id == null ? context.getString(R.string.settings_chip_pinned)
                    : context.getString(chipOf(sections, id));
            TextView chip = (TextView) inflater.inflate(R.layout.item_settings_chip, chips, false);
            chip.setText(label);
            chip.setOnClickListener(v -> listener.onJumpChosen(id));
            chips.addView(chip);
        }
    }

    private static int chipOf(List<SettingsCatalog.Section> sections, String id) {
        for (SettingsCatalog.Section section : sections) {
            if (section.id.equals(id)) {
                return section.chip;
            }
        }
        return R.string.settings_section_other;
    }

    /** Draws the chip of the section in view as the "on" one; null for none. */
    void setOnSection(@Nullable String sectionId) {
        if (chipsAreFilters) {
            return;
        }
        for (int i = 0; i < chips.getChildCount() && i < jumpIds.size(); i++) {
            String id = jumpIds.get(i);
            boolean on = sectionId != null && sectionId.equals(id);
            View chip = chips.getChildAt(i);
            if (chip.isSelected() != on) {
                chip.setSelected(on);
                if (on && !chip.isFocused()) {
                    scrollChipIntoView(chip);
                }
            }
        }
    }

    /** The jump chips' targets in order: null for Pinned, then section ids. */
    @NonNull
    List<String> jumpTargets() {
        return Collections.unmodifiableList(jumpIds);
    }

    /** The jump target of a chip view, or {@code ""} for a view that is not a jump chip. */
    @Nullable
    String jumpOf(@Nullable View view) {
        if (chipsAreFilters || view == null || view.getParent() != chips) {
            return "";
        }
        int index = chips.indexOfChild(view);
        return index >= 0 && index < jumpIds.size() ? jumpIds.get(index) : "";
    }

    // --- Filters (searching) ---

    /**
     * The chips as the results' filters, with the query's counts; {@code selected} is on.
     *
     * @param changed how many of the query's matches have changed: the Changed chip's count,
     *                and no chip at none unless it is the filter in force
     */
    void bindFilters(@NonNull SettingsSearch.Result result, @NonNull SettingsSearch.Filter selected,
                     int changed) {
        Context context = root.getContext();
        List<SettingsSearch.Filter> next = new ArrayList<>();
        List<CharSequence> labels = new ArrayList<>();
        next.add(SettingsSearch.Filter.ALL);
        labels.add(context.getString(R.string.settings_filter_count,
                context.getString(R.string.settings_filter_all), result.total));
        for (String id : result.sectionCounts.keySet()) {
            next.add(SettingsSearch.Filter.section(id));
            labels.add(context.getString(R.string.settings_filter_count,
                    shortTitle(context, id, result.sectionTitles.get(id)), result.sectionCounts.get(id)));
        }
        if (selected.sectionId != null && !next.contains(selected)) {
            // The section filtered to has no matches left: keep its chip, at 0, so the one in
            // force is still on screen to be turned off.
            next.add(selected);
            labels.add(context.getString(R.string.settings_filter_count,
                    shortTitle(context, selected.sectionId, null), 0));
        }
        if (changed > 0 || selected.changedOnly) {
            next.add(SettingsSearch.Filter.CHANGED);
            labels.add(context.getString(R.string.settings_filter_count,
                    context.getString(R.string.settings_filter_changed), changed));
        }

        if (chipsAreFilters && next.equals(filters) && chips.getChildCount() == next.size()) {
            for (int i = 0; i < next.size(); i++) {
                TextView chip = (TextView) chips.getChildAt(i);
                chip.setText(labels.get(i));
                chip.setSelected(next.get(i).equals(selected));
            }
            return;
        }
        boolean hadFocus = chips.hasFocus();
        chipsAreFilters = true;
        jumpIds.clear();
        filters.clear();
        filters.addAll(next);
        chips.removeAllViews();
        for (int i = 0; i < next.size(); i++) {
            final SettingsSearch.Filter filter = next.get(i);
            TextView chip = (TextView) inflater.inflate(R.layout.item_settings_chip, chips, false);
            chip.setText(labels.get(i));
            chip.setSelected(filter.equals(selected));
            chip.setOnClickListener(v -> listener.onFilterChosen(filter));
            chips.addView(chip);
            if (hadFocus && filter.equals(selected)) {
                chip.requestFocus();
            }
        }
    }

    /** The filter chips in order, while searching. */
    @NonNull
    List<SettingsSearch.Filter> filterChips() {
        return Collections.unmodifiableList(filters);
    }

    /** Forgets the chips, so the next bind builds them again (after a search, the jumps). */
    void resetChips() {
        chipsAreFilters = false;
        jumpIds.clear();
        filters.clear();
        chips.removeAllViews();
    }

    private static CharSequence shortTitle(Context context, String sectionId,
                                           @Nullable String fallback) {
        for (SettingsCatalog.Section section : SettingsCatalog.get().sections()) {
            if (section.id.equals(sectionId)) {
                return context.getString(section.chip);
            }
        }
        return fallback != null ? fallback : sectionId;
    }

    void setAlsoMatches(@Nullable CharSequence synonyms) {
        if (synonyms == null || synonyms.length() == 0) {
            alsoMatches.setVisibility(View.GONE);
        } else {
            alsoMatches.setText(root.getContext().getString(R.string.settings_also_matches, synonyms));
            alsoMatches.setVisibility(View.VISIBLE);
        }
    }

    /** The chip view for a jump target or filter at an index, or null. */
    @Nullable
    View chipAt(int index) {
        return index >= 0 && index < chips.getChildCount() ? chips.getChildAt(index) : null;
    }

    // --- Query ---

    @NonNull
    String query() {
        return field.getText().toString();
    }

    /** Writes the query without reporting it as typed. */
    void setQuery(@NonNull String query) {
        if (query.equals(field.getText().toString())) {
            return;
        }
        settingQuery = true;
        try {
            field.setText(query);
            field.setSelection(field.length());
        } finally {
            settingQuery = false;
        }
    }

    private void scrollChipIntoView(View chip) {
        chipsScroll.post(() -> {
            int left = chip.getLeft();
            int right = chip.getRight();
            int width = chipsScroll.getWidth();
            int x = chipsScroll.getScrollX();
            if (left < x) {
                chipsScroll.smoothScrollTo(left, 0);
            } else if (right > x + width) {
                chipsScroll.smoothScrollTo(right - width, 0);
            }
        });
    }
}
