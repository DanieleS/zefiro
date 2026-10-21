package com.limelight.main;

/**
 * A section, or a screen inside one, that has a use for back before the main screen does.
 *
 * <p>Back on the main screen first asks the visible section: a section with a stack pops it
 * ({@link StackedSectionFragment}), and a screen may have a mode of its own to leave first. Only
 * when the section has nothing to undo does the main screen act, by returning to the Library.
 *
 * <p>Invariant: the main screen's back callback is enabled iff the visible section has back work
 * or is not {@link Section#LIBRARY}. At the Library's home with nothing to undo it is disabled,
 * and the system's default (leaving the app) applies. A section whose answer to
 * {@link #hasBackWork()} changes tells the main screen so through {@link MainHost#backWorkChanged()}.
 *
 * Main thread only.
 */
public interface SectionBack {

    /** Whether back would do something here: pop a screen, or leave a mode. */
    boolean hasBackWork();

    /** Does that one step back. Called only when {@link #hasBackWork()} has just said yes. */
    void onBack();
}
