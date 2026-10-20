package com.limelight.stream;

import android.app.Dialog;
import android.content.Context;
import android.content.res.Resources;
import android.view.KeyEvent;

import androidx.annotation.NonNull;

import com.limelight.ui.MenuSheet;

/**
 * The controls as the centred {@link MenuSheet}: over the game on a phone or tablet, and on the
 * phone that drives an external display. One page at a time, each a fresh sheet, as the old menu
 * drew its submenus.
 *
 * <p>The sheet is a Dialog, so it takes the window focus from the game while up; the router runs
 * focus-bound actions only once the game has it back. Main thread only.
 */
public final class MenuSheetSurface extends PagedSurface {

    private final Context context;
    private Dialog dialog;

    /** @param context where the sheet is drawn: the game, or the external display's controller */
    public MenuSheetSurface(@NonNull Context context, @NonNull Callbacks callbacks) {
        super(callbacks);
        this.context = context;
    }

    @Override
    protected void render(@NonNull StreamAction page) {
        dismissView();

        Resources res = context.getResources();
        MenuSheet sheet = new MenuSheet(context).setTitle(page.label(res));
        if (page.children != null) {
            for (StreamAction child : page.children) {
                sheet.add(child.label(res), child.iconRes, Boolean.TRUE.equals(child.checked),
                        () -> activate(child));
            }
        }

        Dialog shown = sheet.showCentered(context);
        // A tap outside closes the lot; BACK (and B, which falls back to it) steps back a page.
        shown.setOnCancelListener(d -> close());
        shown.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK) {
                return false;
            }
            // The press is taken whole, so the key-up does not reach the next page's sheet.
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                back();
            }
            return true;
        });
        dialog = shown;
    }

    @Override
    protected void dismissView() {
        Dialog shown = dialog;
        dialog = null;
        if (shown != null) {
            // Not a cancel: no listener should take this for the user closing the menu.
            shown.setOnCancelListener(null);
            if (shown.isShowing()) {
                shown.dismiss();
            }
        }
    }

    @Override
    public boolean isOpen() {
        return dialog != null && dialog.isShowing();
    }

    /** The sheet is a focused window and walks its own rows; there is nothing to route here. */
    @Override
    public boolean onNav(@NonNull Nav nav) {
        if (!isOpen()) {
            return false;
        }
        if (nav == Nav.BACK) {
            back();
        } else if (nav == Nav.CLOSE) {
            close();
        }
        return true;
    }
}
