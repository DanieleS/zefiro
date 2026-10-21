package com.limelight.stats;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.limelight.companion.CompanionPage;
import com.limelight.companion.CompanionPageRenderer;
import com.limelight.library.GameActionsSheet;
import com.limelight.main.PageFocus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A game page's lower half on the companion panel when the game has no achievements to list:
 * a card saying SuccessStory does not follow it, then the game's actions as big rows. The chip
 * row leaves the top screen meanwhile; these are the same actions, in the same order
 * ({@link GamePageModel#chips}), so R2 still has somewhere to go below.
 *
 * <p>Stops: one row per action, numbered by its index ({@code 0, 1, …}). A game the library does
 * not list has no actions, and the page is only the card: no stop, so the d-pad stays up top.
 *
 * <p>A value, like every {@link CompanionPage}: the lists are copied and compared by content.
 */
public final class GameActionsPage extends CompanionPage {

    @NonNull
    public final String title;
    @NonNull
    public final List<GameActionsSheet.Item> items;
    /** Each item's label, as the chip reads ("Companion app…" is the app's own name there). */
    @NonNull
    public final List<String> labels;
    /** Whether the game is hidden: Hide is drawn checked, as the sheet checks its row. */
    public final boolean hidden;

    /**
     * @param labels one per item, in the same order
     */
    public GameActionsPage(@NonNull String title, @NonNull List<GameActionsSheet.Item> items,
                           @NonNull List<String> labels, boolean hidden) {
        this(title, Collections.unmodifiableList(new ArrayList<>(items)),
                Collections.unmodifiableList(new ArrayList<>(labels)), hidden,
                NO_STOP);
        if (items.size() != labels.size()) {
            throw new IllegalArgumentException("one label per item");
        }
    }

    private GameActionsPage(@NonNull String title, @NonNull List<GameActionsSheet.Item> items,
                            @NonNull List<String> labels, boolean hidden, int focusedStop) {
        super(focusedStop);
        this.title = title;
        this.items = items;
        this.labels = labels;
        this.hidden = hidden;
    }

    /** One row per action. */
    @NonNull
    public PageFocus stops() {
        List<int[]> rows = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            rows.add(new int[]{i});
        }
        return new PageFocus(rows);
    }

    /** Where R2 lands: the first action, or {@link PageFocus#STAY} when there is none. */
    public int entryStop() {
        return items.isEmpty() ? PageFocus.STAY : 0;
    }

    /** The action a stop names, or null. */
    @Nullable
    public GameActionsSheet.Item itemAt(int stop) {
        return stop >= 0 && stop < items.size() ? items.get(stop) : null;
    }

    // --- CompanionPage ---

    @NonNull
    @Override
    public CompanionPageRenderer renderer() {
        return GameActionsRenderer.INSTANCE;
    }

    @NonNull
    @Override
    public GameActionsPage withFocus(int stop) {
        return new GameActionsPage(title, items, labels, hidden, stop);
    }

    @Override
    protected boolean sameContent(@NonNull CompanionPage other) {
        GameActionsPage that = (GameActionsPage) other;
        return hidden == that.hidden && title.equals(that.title) && sameList(items, that.items)
                && sameList(labels, that.labels);
    }

    @Override
    protected int contentHash() {
        return Objects.hash(title, items, labels, hidden);
    }
}
