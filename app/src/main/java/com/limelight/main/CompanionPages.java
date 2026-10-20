package com.limelight.main;

import androidx.annotation.NonNull;

import com.limelight.companion.CompanionPage;

/**
 * Where a section screen puts its lower half on the companion panel, as a
 * {@link CompanionPage}: a game's achievements, the Stats remote.
 *
 * <p>One page is drawn at a time, and it has one owner: the screen that published it last. Its
 * taps go to that owner and nowhere else. An owner's {@link #clear} is honoured only while it is
 * the owner, so a screen tearing down late (hidden after another was shown) cannot take away the
 * page the screen now on top just published.
 *
 * <p>Implemented by {@link CompanionFeed}, which keeps the page across a stream (the main screen
 * stopped and started again) and draws it again when the screen comes back. Reached through
 * {@link MainHost#companionPages()}. Main thread only.
 */
public interface CompanionPages {

    /** The screen a page belongs to, told of the taps on it. */
    interface PageOwner {
        /** A stop on the owner's page was tapped on the panel. Main thread only. */
        void onPageTap(int stopId);
    }

    /** Draws {@code page} on the panel for {@code owner}, replacing any other screen's page. */
    void publish(@NonNull PageOwner owner, @NonNull CompanionPage page);

    /** Takes {@code owner}'s page off the panel; nothing when another screen owns it now. */
    void clear(@NonNull PageOwner owner);

    /**
     * Whether the page on the panel was tapped just now. Touch mode is global up to Android 13,
     * so a tap on the panel puts the main window into touch mode too, once it has its focus
     * back; that is the panel's doing, not a touch on the main screen, and must not take the
     * d-pad off the page.
     */
    default boolean pageTappedJustNow() {
        return false;
    }
}
