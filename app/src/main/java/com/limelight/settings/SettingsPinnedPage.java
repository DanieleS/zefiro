package com.limelight.settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.limelight.companion.CompanionPage;
import com.limelight.companion.CompanionPageRenderer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The Settings section's companion page while focus is up in its header (the search field, the
 * pinned card, the chips) rather than on a setting: the five essentials and their values, so the
 * panel still says how the stream is set up.
 *
 * <p>Touch only, as every Settings page is: {@link #focusedStop()} is always {@link #NO_STOP}.
 * A row is stop {@code 0…n-1}, by its index, and a tap on one takes the main screen to that
 * setting's row, as a pin on the card does.
 *
 * <p>A value, like every {@link CompanionPage}; the rows are copied and compared by content.
 */
public final class SettingsPinnedPage extends CompanionPage {

    /** One essential. Immutable. */
    public static final class Row {
        @NonNull
        public final String key;
        /** "RESOLUTION", as the card labels it. */
        @NonNull
        public final String label;
        @NonNull
        public final String value;
        /** Away from its default: the value takes the signal, as on the card. */
        public final boolean changed;
        /** The active profile sets this value in place of the stored one. */
        public final boolean fromProfile;

        public Row(@NonNull String key, @NonNull String label, @NonNull String value,
                   boolean changed, boolean fromProfile) {
            this.key = key;
            this.label = label;
            this.value = value;
            this.changed = changed;
            this.fromProfile = fromProfile;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Row)) {
                return false;
            }
            Row that = (Row) o;
            return changed == that.changed && fromProfile == that.fromProfile
                    && key.equals(that.key) && label.equals(that.label) && value.equals(that.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(key, label, value, changed, fromProfile);
        }
    }

    /** The active profile's name, or null for none. */
    @Nullable
    public final String profile;
    @NonNull
    public final List<Row> rows;

    public SettingsPinnedPage(@Nullable String profile, @NonNull List<Row> rows) {
        super(NO_STOP);
        this.profile = profile;
        this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
    }

    /** The row a stop names, or null. */
    @Nullable
    public Row rowAt(int stop) {
        return stop >= 0 && stop < rows.size() ? rows.get(stop) : null;
    }

    // --- CompanionPage ---

    @NonNull
    @Override
    public CompanionPageRenderer renderer() {
        return SettingsPinnedRenderer.INSTANCE;
    }

    /** Touch only: always this same page, with no stop. */
    @NonNull
    @Override
    public SettingsPinnedPage withFocus(int stop) {
        return this;
    }

    @Override
    protected boolean sameContent(@NonNull CompanionPage other) {
        SettingsPinnedPage that = (SettingsPinnedPage) other;
        return Objects.equals(profile, that.profile) && sameList(rows, that.rows);
    }

    @Override
    protected int contentHash() {
        return Objects.hash(profile, rows);
    }
}
