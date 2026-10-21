package com.limelight.stream;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.List;

/**
 * The page stack every {@link ControlsSurface} shares, so each surface only has to draw a page
 * and take it down. Main thread only.
 *
 * <pre>
 *   open(root)      [root]              render(root)
 *   activate(page)  [root, page]        render(page)
 *   back()          [root]              render(root)
 *   back()          []                  dismissView(), onClosed()
 *   activate(leaf)  []                  dismissView(), onClosed(), then the router runs it
 *   openAt(root, p) [root, …, p]        render(p)    (no onPageOpening: the page was open)
 * </pre>
 */
abstract class PagedSurface implements ControlsSurface {

    private final ArrayDeque<StreamAction> pages = new ArrayDeque<>();
    protected final Callbacks callbacks;

    PagedSurface(@NonNull Callbacks callbacks) {
        this.callbacks = callbacks;
    }

    /** Draw this page, replacing the one shown, and land the selection on its first entry. */
    protected abstract void render(@NonNull StreamAction page);

    /** Take the surface down without touching the stack. */
    protected abstract void dismissView();

    /** The tree as this surface lays it out; as built, unless a surface arranges its own. */
    @NonNull
    protected StreamAction arrange(@NonNull StreamAction tree) {
        return tree;
    }

    @Override
    public void open(@NonNull StreamAction tree) {
        pages.clear();
        show(arrange(tree));
    }

    @Override
    public void openAt(@NonNull StreamAction tree, @Nullable StreamAction.Id page) {
        StreamAction arranged = arrange(tree);
        List<StreamAction> path = page != null ? arranged.pathTo(page) : null;
        if (path == null) {
            open(tree);
            return;
        }
        // Pushed without asking onPageOpening again: each was already let open once, and asking
        // would repeat what it said then (the special keys' toast).
        pages.clear();
        for (StreamAction step : path) {
            pages.push(step);
        }
        render(pages.peek());
    }

    @Nullable
    @Override
    public StreamAction shownPage() {
        return pages.peek();
    }

    @Override
    public void back() {
        if (pages.size() > 1) {
            pages.pop();
            render(pages.peek());
        } else {
            close();
        }
    }

    @Override
    public void close() {
        boolean wasShowing = !pages.isEmpty();
        pages.clear();
        dismissView();
        if (wasShowing) {
            callbacks.onClosed();
        }
    }

    /** Whether the page shown is the tree's root, which has no page to go back to. */
    protected final boolean isOnRootPage() {
        return pages.size() <= 1;
    }

    /** A row of the shown page was chosen. */
    protected final void activate(@NonNull StreamAction action) {
        if (action.isPage()) {
            show(action);
            return;
        }
        // Down before the action runs: it may open a dialog, inject keys or end the stream, and
        // none of that should happen under a menu still on screen.
        close();
        callbacks.onLeaf(action);
    }

    private void show(StreamAction page) {
        if (!callbacks.onPageOpening(page)) {
            close();
            return;
        }
        pages.push(page);
        render(page);
    }
}
