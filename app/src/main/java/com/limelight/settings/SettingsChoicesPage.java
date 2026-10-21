package com.limelight.settings;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.TwoStatePreference;

import com.limelight.R;
import com.limelight.companion.CompanionPage;
import com.limelight.companion.CompanionPageRenderer;
import com.limelight.preferences.SeekBarPreference;
import com.limelight.ui.GamepadHintsView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The Settings section's lower half on the companion panel, after Settings_C's Thor bottom
 * screen: the setting whose row has focus on the main screen, spelled out, so Left and Right on
 * top change it without a dialog and the panel shows where the value now stands.
 *
 * <pre>
 *   LIST     every entry as a radio row, with its description where one is written
 *            ({@link SettingsChoiceHints}); the current one selected
 *   TOGGLE   the full summary, a large switch, "Default: Off"
 *   SEEK     the value, its range and default, and big − / + buttons
 *   TEXT     the summary, the current text and "A opens it"
 *   ACTION   the summary and "A opens it" ("in the browser" for a web page)
 * </pre>
 *
 * <p>The d-pad never comes down here: Settings keeps it on the main screen ("Change on top"), so
 * {@link #focusedStop()} is always {@link #NO_STOP} and the stops below are touch targets only.
 * A choice is stop {@code 0…n-1}, by its index; the switch and the slider's buttons have stops of
 * their own ({@link #STOP_SWITCH}, {@link #STOP_MINUS}, {@link #STOP_PLUS}). Every tap acts
 * through the live preference ({@link SettingsEditor}), never through this value.
 *
 * <p>A value, like every {@link CompanionPage}: built from the preference as it stands
 * ({@link #of}) and compared by content, so publishing it again on every focus move and stored
 * change redraws the panel only when something it shows changed.
 */
public final class SettingsChoicesPage extends CompanionPage {

    /** The switch of a TOGGLE page. */
    public static final int STOP_SWITCH = 1000;
    /** A SEEK page's − button. */
    public static final int STOP_MINUS = 1001;
    /** A SEEK page's + button. */
    public static final int STOP_PLUS = 1002;

    /** One entry of a list. Immutable. */
    public static final class Choice {
        @NonNull
        public final String label;
        /** What it does, with "Default" after it for the default entry; null for neither. */
        @Nullable
        public final String description;
        @NonNull
        public final String value;

        public Choice(@NonNull String label, @Nullable String description, @NonNull String value) {
            this.label = label;
            this.description = description;
            this.value = value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Choice)) {
                return false;
            }
            Choice that = (Choice) o;
            return label.equals(that.label) && value.equals(that.value)
                    && Objects.equals(description, that.description);
        }

        @Override
        public int hashCode() {
            return Objects.hash(label, description, value);
        }

        @NonNull
        @Override
        public String toString() {
            return value + " " + label;
        }
    }

    /** "VIDEO · CHOICES", "CONTROLLERS · RUMBLE": where the setting lives, drawn in caps. */
    @NonNull
    public final String sectionLabel;
    @NonNull
    public final String key;
    @NonNull
    public final String title;
    @NonNull
    public final SettingsSearch.Kind kind;
    @Nullable
    public final String summary;
    /** A LIST's entries, in order; empty for every other kind. */
    @NonNull
    public final List<Choice> choices;
    /** The entry in effect, or -1 (not a list, or a value none of the entries has). */
    public final int selectedIndex;
    /** The value as the row shows it ("40.0 Mbps", a text, "On"); null for an action. */
    @Nullable
    public final String valueText;
    /** "Default: Off"; null when the setting has no default to name. */
    @Nullable
    public final String defaultText;
    /** A SEEK's "1.0 Mbps to 800.0 Mbps"; null otherwise. */
    @Nullable
    public final String rangeText;
    /** A TOGGLE's state. */
    public final boolean checked;
    /** A SEEK's ends: whether − and + can move. */
    public final boolean canDecrease;
    public final boolean canIncrease;
    /** Away from its default, as the row's dot says. */
    public final boolean changed;
    /** A disabled setting is drawn dimmed and ignores taps, as its row ignores clicks. */
    public final boolean enabled;
    /** "Needs Enable rumble" while disabled by a dependency; null otherwise. */
    @Nullable
    public final String needs;
    /** An ACTION that opens a web page. */
    public final boolean external;
    @NonNull
    public final List<GamepadHintsView.Hint> hints;

    private SettingsChoicesPage(@NonNull Builder b) {
        super(NO_STOP);
        this.sectionLabel = b.sectionLabel;
        this.key = b.key;
        this.title = b.title;
        this.kind = b.kind;
        this.summary = b.summary;
        this.choices = Collections.unmodifiableList(new ArrayList<>(b.choices));
        this.selectedIndex = b.selectedIndex;
        this.valueText = b.valueText;
        this.defaultText = b.defaultText;
        this.rangeText = b.rangeText;
        this.checked = b.checked;
        this.canDecrease = b.canDecrease;
        this.canIncrease = b.canIncrease;
        this.changed = b.changed;
        this.enabled = b.enabled;
        this.needs = b.needs;
        this.external = b.external;
        this.hints = Collections.unmodifiableList(new ArrayList<>(b.hints));
    }

    /**
     * The page for a live setting, read as its row would show it now.
     *
     * @param defaults what the row compares with; null draws no default and no change
     */
    @NonNull
    public static SettingsChoicesPage of(@NonNull Preference p, @Nullable SettingsDefaults defaults,
                                         @Nullable SharedPreferences prefs) {
        Context context = p.getContext();
        String key = p.getKey() != null ? p.getKey() : "";
        SettingsSearch.Kind kind = SettingsIndex.kindOf(p);
        Object def = defaults != null && p.getKey() != null ? defaults.defaultFor(key, prefs) : null;

        Builder b = new Builder();
        b.key = key;
        b.kind = kind;
        b.title = text(p.getTitle(), key);
        b.summary = textOrNull(p.getSummary());
        b.sectionLabel = sectionLabel(context, p, kind);
        b.enabled = p.isEnabled();
        b.changed = defaults != null && defaults.isChanged(p, prefs);
        b.external = SettingValues.isExternal(p);
        b.valueText = textOrNull(p instanceof TwoStatePreference
                ? SettingValues.onOff(p, ((TwoStatePreference) p).isChecked())
                : SettingValues.display(p));
        if (!b.enabled) {
            CharSequence dependency = SettingRowBinder.dependencyTitle(p);
            b.needs = dependency != null
                    ? context.getString(R.string.settings_choices_needs, dependency) : null;
        }

        switch (kind) {
            case LIST:
                readList(context, (ListPreference) p, def, b);
                break;
            case TOGGLE:
                b.checked = ((TwoStatePreference) p).isChecked();
                b.defaultText = defaultText(context, p, def);
                break;
            case SEEK: {
                SeekBarPreference seek = (SeekBarPreference) p;
                int value = seek.getValue();
                b.rangeText = context.getString(R.string.settings_choices_range,
                        seek.formatValue(seek.getMinValue()), seek.formatValue(seek.getMaxValue()));
                b.canDecrease = b.enabled && value > seek.getMinValue();
                b.canIncrease = b.enabled && value < seek.getMaxValue();
                b.defaultText = defaultText(context, p, def);
                break;
            }
            case TEXT:
                b.defaultText = p instanceof EditTextPreference ? defaultText(context, p, def) : null;
                break;
            case ACTION:
            default:
                break;
        }
        b.hints = hints(context, kind, b.enabled);
        return new SettingsChoicesPage(b);
    }

    private static void readList(Context context, ListPreference list, @Nullable Object def,
                                 Builder b) {
        CharSequence[] entries = list.getEntries();
        CharSequence[] values = list.getEntryValues();
        if (entries == null || values == null) {
            return;
        }
        int count = Math.min(entries.length, values.length);
        String[] described = SettingsChoiceHints.descriptions(context, b.key, count);
        List<Choice> choices = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String value = values[i].toString();
            String description = described != null ? described[i] : null;
            if (def != null && value.equals(String.valueOf(def))) {
                // The mock's "… Default.": the entry a reset would put back says so itself, so a
                // list needs no separate default line.
                description = description != null
                        ? context.getString(R.string.settings_choice_default_after, description)
                        : context.getString(R.string.settings_choice_default);
            }
            choices.add(new Choice(entries[i].toString(), description, value));
        }
        b.choices = choices;
        b.selectedIndex = list.findIndexOfValue(list.getValue());
    }

    @Nullable
    private static String defaultText(Context context, Preference p, @Nullable Object def) {
        CharSequence shown = SettingValues.displayFor(p, def);
        return shown != null ? context.getString(R.string.settings_choices_default, shown) : null;
    }

    /** A list's "VIDEO · CHOICES"; any other setting's section and subgroup. */
    @NonNull
    private static String sectionLabel(Context context, Preference p, SettingsSearch.Kind kind) {
        SectionCategory section = SettingsIndex.sectionOf(p);
        String sectionTitle = section != null && section.getTitle() != null
                ? section.getTitle().toString() : "";
        String second;
        if (kind == SettingsSearch.Kind.LIST) {
            second = context.getString(R.string.settings_choices_choices);
        } else {
            SettingsCatalog.Subgroup subgroup = p.getKey() != null
                    ? SettingsCatalog.get().subgroupOf(p.getKey()) : null;
            second = subgroup != null ? context.getString(subgroup.title) : "";
        }
        if (sectionTitle.isEmpty() || second.isEmpty()) {
            return sectionTitle.isEmpty() ? second : sectionTitle;
        }
        return context.getString(R.string.settings_choices_label, sectionTitle, second);
    }

    /**
     * The footer: "◀▶ Change on top · X Reset or pin · Y Search · or touch a choice". A disabled
     * setting cannot change, so it names only what still works.
     */
    @NonNull
    private static List<GamepadHintsView.Hint> hints(Context context, SettingsSearch.Kind kind,
                                                     boolean enabled) {
        List<GamepadHintsView.Hint> hints = new ArrayList<>(4);
        boolean steps = kind == SettingsSearch.Kind.LIST || kind == SettingsSearch.Kind.TOGGLE
                || kind == SettingsSearch.Kind.SEEK;
        if (enabled) {
            hints.add(steps
                    ? new GamepadHintsView.Hint(
                            context.getString(R.string.settings_choices_hint_change), "◀▶")
                    : new GamepadHintsView.Hint(
                            context.getString(R.string.settings_choices_hint_open), "A"));
        }
        hints.add(new GamepadHintsView.Hint(context.getString(R.string.settings_choices_hint_menu), "X"));
        hints.add(new GamepadHintsView.Hint(context.getString(R.string.settings_choices_hint_search), "Y"));
        if (enabled && steps) {
            int touch = kind == SettingsSearch.Kind.LIST ? R.string.settings_choices_hint_touch_list
                    : kind == SettingsSearch.Kind.TOGGLE ? R.string.settings_choices_hint_touch_switch
                    : R.string.settings_choices_hint_touch_seek;
            hints.add(new GamepadHintsView.Hint(context.getString(touch)));
        }
        return hints;
    }

    @NonNull
    private static String text(@Nullable CharSequence text, @NonNull String fallback) {
        return text != null ? text.toString() : fallback;
    }

    // Strings, never the preference's CharSequences: a span compares by identity, and the
    // page must compare by what it draws.
    @Nullable
    private static String textOrNull(@Nullable CharSequence text) {
        return text != null && text.length() > 0 ? text.toString() : null;
    }

    /** The choice a stop names, or null. */
    @Nullable
    public Choice choiceAt(int stop) {
        return stop >= 0 && stop < choices.size() ? choices.get(stop) : null;
    }

    // --- CompanionPage ---

    @NonNull
    @Override
    public CompanionPageRenderer renderer() {
        return SettingsChoicesRenderer.INSTANCE;
    }

    /** The d-pad stays on the main screen: always this same page, with no stop. */
    @NonNull
    @Override
    public SettingsChoicesPage withFocus(int stop) {
        return this;
    }

    @Override
    protected boolean sameContent(@NonNull CompanionPage other) {
        SettingsChoicesPage that = (SettingsChoicesPage) other;
        return selectedIndex == that.selectedIndex && checked == that.checked
                && canDecrease == that.canDecrease && canIncrease == that.canIncrease
                && changed == that.changed && enabled == that.enabled
                && external == that.external && kind == that.kind
                && key.equals(that.key) && title.equals(that.title)
                && sectionLabel.equals(that.sectionLabel)
                && Objects.equals(summary, that.summary)
                && Objects.equals(valueText, that.valueText)
                && Objects.equals(defaultText, that.defaultText)
                && Objects.equals(rangeText, that.rangeText)
                && Objects.equals(needs, that.needs)
                && sameList(choices, that.choices) && sameList(hints, that.hints);
    }

    @Override
    protected int contentHash() {
        return Objects.hash(key, title, kind, summary, choices, selectedIndex, valueText,
                defaultText, rangeText, checked, canDecrease, canIncrease, changed, enabled,
                needs, external, hints, sectionLabel);
    }

    private static final class Builder {
        String sectionLabel = "";
        String key = "";
        String title = "";
        SettingsSearch.Kind kind = SettingsSearch.Kind.ACTION;
        String summary;
        List<Choice> choices = Collections.emptyList();
        int selectedIndex = -1;
        String valueText;
        String defaultText;
        String rangeText;
        boolean checked;
        boolean canDecrease;
        boolean canIncrease;
        boolean changed;
        boolean enabled = true;
        String needs;
        boolean external;
        List<GamepadHintsView.Hint> hints = Collections.emptyList();
    }
}
