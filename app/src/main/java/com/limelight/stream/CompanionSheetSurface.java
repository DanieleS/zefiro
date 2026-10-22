package com.limelight.stream;

import android.content.res.Resources;

import androidx.annotation.NonNull;

import com.limelight.R;
import com.limelight.companion.CompanionDisplayManager;
import com.limelight.companion.CompanionMenuItem;

import java.util.ArrayList;
import java.util.List;

/**
 * The controls as a sheet on the companion panel, on the Thor's bottom screen, after the approved
 * Stream and StreamTelemetry mocks: eight tiles in two rows of four, then Disconnect and Quit
 * game ({@link StreamActions#compact}). The game on the top screen stays whole; nothing is drawn
 * over it.
 *
 * <p>The panel's window takes no focus; Game steers the gamepad into the sheet while it is up and
 * hands the moves here as {@link Nav}, which the panel walks in two dimensions along
 * {@link ControlsGrid}. A touch on the sheet acts directly. Main thread only.
 */
public final class CompanionSheetSurface extends PagedSurface {

    private final Resources res;

    public CompanionSheetSurface(@NonNull Resources res, @NonNull Callbacks callbacks) {
        super(callbacks);
        this.res = res;
    }

    @NonNull
    @Override
    protected StreamAction arrange(@NonNull StreamAction tree) {
        return StreamActions.compact(tree);
    }

    @Override
    protected void render(@NonNull StreamAction page) {
        List<CompanionMenuItem> items = new ArrayList<>();
        if (!isOnRootPage()) {
            // The way back, as the page's first row, so a thumb has one as much as the pad's B:
            // the sheet has no system back of its own, its window never being focused. B fires
            // it from any row, so the row says B.
            items.add(new CompanionMenuItem(page.label(res), R.drawable.ic_arrow_back,
                    StreamAction.Group.LIST, null, false, false, this::back, "B"));
        }
        if (page.children != null) {
            for (StreamAction child : page.children) {
                items.add(new CompanionMenuItem(child.label(res), child.iconRes, child.group,
                        child.checked, child.isPage(), child.id == StreamAction.Id.QUIT,
                        () -> activate(child)));
            }
        }
        // False only when the panel went away since the router chose it; the sheet is then simply
        // gone, as it would be with the panel.
        CompanionDisplayManager.showMenu(page.label(res).toString(), items);
    }

    @Override
    protected void dismissView() {
        CompanionDisplayManager.hideMenu();
    }

    @Override
    public boolean isOpen() {
        return CompanionDisplayManager.isMenuOpen();
    }

    @Override
    public boolean onNav(@NonNull Nav nav) {
        if (!isOpen()) {
            return false;
        }
        switch (nav) {
            case UP:
            case DOWN:
            case LEFT:
            case RIGHT:
                CompanionDisplayManager.moveMenuSelection(nav);
                break;
            case ACTIVATE:
                CompanionDisplayManager.activateMenuSelection();
                break;
            case BACK:
                back();
                break;
            case CLOSE:
                close();
                break;
        }
        return true;
    }
}
