package com.limelight.stream;

import android.content.res.Resources;

import androidx.annotation.NonNull;

import com.limelight.companion.CompanionDisplayManager;
import com.limelight.companion.CompanionMenuItem;

import java.util.ArrayList;
import java.util.List;

/**
 * The controls as the companion panel's list, on the Thor's bottom screen: each page flattened to
 * rows, tiles and danger entries included, as the panel's menu has always drawn them. The game on
 * the top screen stays whole.
 *
 * <p>The panel's window takes no focus; Game steers the gamepad into it while the menu is up and
 * hands the moves here as {@link Nav}. Main thread only.
 */
public final class CompanionListSurface extends PagedSurface {

    private final Resources res;

    public CompanionListSurface(@NonNull Resources res, @NonNull Callbacks callbacks) {
        super(callbacks);
        this.res = res;
    }

    @Override
    protected void render(@NonNull StreamAction page) {
        List<CompanionMenuItem> items = new ArrayList<>();
        if (page.children != null) {
            for (StreamAction child : page.children) {
                items.add(new CompanionMenuItem(child.label(res), child.iconRes, child.group,
                        child.checked, () -> activate(child)));
            }
        }
        // False only when the panel went away since the router chose it; the menu is then simply
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
                CompanionDisplayManager.moveMenuSelection(-1);
                break;
            case DOWN:
                CompanionDisplayManager.moveMenuSelection(1);
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
            default:
                // A list: LEFT and RIGHT have nowhere to go.
                break;
        }
        return true;
    }
}
