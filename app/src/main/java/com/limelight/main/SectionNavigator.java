package com.limelight.main;

import android.view.View;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

/**
 * Switches the main screen between its sections.
 *
 * <p>Each section's fragment is added the first time it is shown and is then only shown and
 * hidden, never replaced. A hidden section keeps its views, and with them its scroll position
 * and the focus memory the gate returns to; a section the user never opens costs nothing.
 *
 * <pre>
 *   show(PCS):   LIBRARY [shown]  ──▶  LIBRARY [hidden]
 *                PCS     [absent] ──▶  PCS     [added, shown]
 *
 *   show(LIBRARY):                ──▶  LIBRARY [shown], PCS [hidden]
 * </pre>
 *
 * <h3>Restore</h3>
 *
 * Fragments are found by {@link Section#tag()}, so after a process restore the navigator adopts
 * the ones the fragment manager brought back instead of adding duplicates. The current section
 * itself is kept by the activity (in its view model and saved state), which hands it in.
 *
 * <h3>After the state is saved</h3>
 *
 * A switch asked for once the activity's state is saved (a late pairing result, say) is recorded
 * but not committed, since the commit would be lost from the saved state anyway;
 * {@link #resume()} from {@code onStart} applies it and only then posts its initial focus, so a
 * late switch still hands the d-pad to the new section instead of leaving it on a hidden view.
 *
 * Main thread only.
 */
final class SectionNavigator {

    /** Makes the fragment for a section the first time it is shown. */
    interface Factory {
        @NonNull
        Fragment create(@NonNull Section section);
    }

    private final FragmentManager fragments;
    @IdRes
    private final int containerId;
    private final View container;
    private final Factory factory;

    private Section current;
    // A switch whose initial focus has not been posted yet because its commit was deferred.
    private boolean focusPending;

    SectionNavigator(FragmentManager fragments, @IdRes int containerId, View container,
                     Factory factory, Section initial) {
        this.fragments = fragments;
        this.containerId = containerId;
        this.container = container;
        this.factory = factory;
        this.current = initial;
    }

    /** The section on screen, or about to be once {@link #sync()} runs. */
    Section current() {
        return current;
    }

    /** The fragment of a section, or null when it has not been added yet. */
    @Nullable
    Fragment fragment(Section section) {
        return fragments.findFragmentByTag(section.tag());
    }

    /** The visible section's fragment, or null before the first commit. */
    @Nullable
    Fragment currentFragment() {
        return fragment(current);
    }

    /**
     * Shows a section, adding its fragment the first time, and then posts its
     * {@link SectionFocus#requestInitialFocus()}.
     */
    void show(@NonNull Section section) {
        current = section;
        focusPending = true;
        resume();
    }

    /**
     * Applies a switch deferred by a saved state, and posts its initial focus once it is
     * committed. Called from {@code onStart}; a no-op when nothing is pending.
     */
    void resume() {
        if (sync() && focusPending) {
            focusPending = false;
            postInitialFocus(current);
        }
    }

    /**
     * Brings the fragments in line with {@link #current()}: it shown, every other section hidden.
     * Idempotent.
     *
     * @return false when nothing could be committed because the state is already saved
     */
    boolean sync() {
        if (fragments.isStateSaved()) {
            return false;
        }
        FragmentTransaction tx = fragments.beginTransaction().setReorderingAllowed(true);
        boolean changed = false;
        for (Section s : Section.values()) {
            Fragment f = fragment(s);
            if (s == current) {
                if (f == null) {
                    tx.add(containerId, factory.create(s), s.tag());
                    changed = true;
                } else if (f.isHidden()) {
                    tx.show(f);
                    changed = true;
                }
            } else if (f != null && !f.isHidden()) {
                tx.hide(f);
                changed = true;
            }
        }
        if (changed) {
            // Now, not later: the focus gate and the back callback read the shown fragment right
            // after a switch.
            tx.commitNow();
        }
        return true;
    }

    /** Posts the section's initial focus; it runs only if that section is still the one shown. */
    private void postInitialFocus(final Section section) {
        container.post(() -> {
            if (current != section) {
                return;
            }
            Fragment f = fragment(section);
            if (f instanceof SectionFocus && f.isAdded() && f.getView() != null) {
                ((SectionFocus) f).requestInitialFocus();
            }
        });
    }
}
