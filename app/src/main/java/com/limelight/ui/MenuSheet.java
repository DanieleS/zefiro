package com.limelight.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.limelight.R;

import java.util.ArrayList;
import java.util.List;

/**
 * A menu drawn on the app's own raised surface, with the same focus grammar as the tiles.
 * Build it up with a title and a list of actions, then present it as a panel centred on the
 * screen and focused for a gamepad — the in-game menu and the library's per-game menu both
 * wear it.
 *
 * <h3>Where it shows</h3>
 *
 * The same rows, in one of three homes, chosen by the caller from the window it is in:
 *
 * <pre>
 *   showCentered(Context)   a panel over the middle of the screen: over a game, or anywhere
 *                           nothing on screen asked for it
 *   showBottom(Context)     a sheet risen from the bottom edge, full width, with a drag handle:
 *                           a compact window, where the thumb is
 *   showAnchored(View)      a panel dropped from the view that asked for it (a chip): a wide
 *                           window, where the eye is on that chip
 * </pre>
 *
 * Each lands focus on the first row. The bottom and anchored menus also close on B themselves,
 * rather than trusting the button's fallback to BACK, since they are raised from the d-pad
 * screens where B is the expected way out.
 *
 * <h3>Header and danger rows</h3>
 *
 * The title can carry a second line of facts about what the menu acts on
 * ({@link #setSubtitle}), and a row that destroys something is drawn in the danger tone
 * ({@link #addDanger}), focus ring included, so it never looks like one more ordinary row.
 */
public class MenuSheet {

    /** The fraction of the screen a menu may fill before it starts scrolling. */
    private static final float MAX_HEIGHT_FRACTION = 0.78f;

    public static final class Item {
        final CharSequence label;
        final int iconRes;   // 0 for none
        final boolean checked;
        final Runnable action;
        // The status dot before the label; drawn only when hasDot.
        final boolean hasDot;
        @ColorInt
        final int dotColor;
        // What TalkBack says for the row instead of the label; null for the label.
        @Nullable
        final CharSequence description;
        // The row's own second action, a stop RIGHT of the row; null for none.
        @Nullable
        final CharSequence trailingLabel;
        @Nullable
        final Runnable trailingAction;
        // Drawn in the danger tone: a row that destroys something.
        final boolean danger;

        Item(CharSequence label, int iconRes, boolean checked, Runnable action) {
            this(label, iconRes, checked, action, false, 0, null, null, null, false);
        }

        Item(CharSequence label, int iconRes, boolean checked, Runnable action, boolean hasDot,
             @ColorInt int dotColor, @Nullable CharSequence description,
             @Nullable CharSequence trailingLabel, @Nullable Runnable trailingAction,
             boolean danger) {
            this.label = label;
            this.iconRes = iconRes;
            this.checked = checked;
            this.action = action;
            this.hasDot = hasDot;
            this.dotColor = dotColor;
            this.description = description;
            this.trailingLabel = trailingLabel;
            this.trailingAction = trailingAction;
            this.danger = danger;
        }
    }

    private final Context context;
    private CharSequence title;
    private CharSequence subtitle;
    private final List<Item> items = new ArrayList<>();

    public MenuSheet(Context context) {
        this.context = context;
    }

    public MenuSheet setTitle(CharSequence title) {
        this.title = title;
        return this;
    }

    /**
     * A second header line under the title, for facts about what the menu acts on (a PC's
     * address and games); null or empty for none.
     */
    public MenuSheet setSubtitle(@Nullable CharSequence subtitle) {
        this.subtitle = subtitle;
        return this;
    }

    public MenuSheet add(CharSequence label, Runnable action) {
        items.add(new Item(label, 0, false, action));
        return this;
    }

    public MenuSheet add(CharSequence label, int iconRes, Runnable action) {
        items.add(new Item(label, iconRes, false, action));
        return this;
    }

    /**
     * A row that destroys something, such as deleting a PC: label, icon and focus ring in the
     * danger tone. It acts like any row; asking for confirmation is the action's job.
     *
     * @param iconRes the row's icon; 0 for none
     */
    public MenuSheet addDanger(CharSequence label, int iconRes, Runnable action) {
        items.add(new Item(label, iconRes, false, action, false, 0, null, null, null, true));
        return this;
    }

    public MenuSheet addChecked(CharSequence label, boolean checked, Runnable action) {
        items.add(new Item(label, 0, checked, action));
        return this;
    }

    /** A row with both an icon (0 for none) and the check; the stream's toggles wear both. */
    public MenuSheet add(CharSequence label, int iconRes, boolean checked, Runnable action) {
        items.add(new Item(label, iconRes, checked, action));
        return this;
    }

    /**
     * A row of a choice among several (a radio row): a status dot before the label, the check
     * on the chosen one, and optionally an action of the row's own after the label, which the
     * d-pad reaches with RIGHT. Both actions close the menu first, as every row does.
     *
     * @param description what TalkBack says for the row, since the dot is only drawn; null for
     *                    the label alone
     */
    public MenuSheet addStatusRow(CharSequence label, @ColorInt int dotColor, boolean checked,
                                  @Nullable CharSequence description, Runnable action,
                                  @Nullable CharSequence trailingLabel,
                                  @Nullable Runnable trailingAction) {
        items.add(new Item(label, 0, checked, action, true, dotColor, description,
                trailingLabel, trailingAction, false));
        return this;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /** Sit centred over a game, focused for a gamepad. */
    public Dialog showCentered(Context ctx) {
        Dialog dialog = new Dialog(ctx, R.style.MenuSheetDialog);
        LayoutInflater inflater = LayoutInflater.from(dialog.getContext());

        View panel = buildPanel(inflater, null, dialog::dismiss, null);
        dialog.setContentView(panel);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = Math.min(widthCap(), (int) (screenWidth() * 0.92f));
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.CENTER;
            window.setAttributes(lp);
            window.setDimAmount(0.6f);
        }

        dialog.show();

        focusFirstRow(panel);
        return dialog;
    }

    /**
     * Rise from the bottom edge as a full-width sheet with a drag handle: the compact window's
     * home, where the thumb is. Opened all the way at once, so every row is reachable by the
     * d-pad without dragging first.
     */
    public Dialog showBottom(Context ctx) {
        final BottomSheetDialog dialog = new BottomSheetDialog(ctx);
        LayoutInflater inflater = LayoutInflater.from(dialog.getContext());

        View sheet = inflater.inflate(R.layout.menu_sheet_bottom, null, false);
        ViewGroup body = sheet.findViewById(R.id.menuSheetBottomBody);
        View panel = buildPanel(inflater, body, dialog::dismiss, null);
        // The sheet is the surface here, painted and rounded by its own theme; the panel's
        // raised card inside it would be a second edge.
        panel.setBackground(null);
        body.addView(panel);
        dialog.setContentView(sheet);

        BottomSheetBehavior<?> behavior = dialog.getBehavior();
        behavior.setSkipCollapsed(true);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);

        // The dialog sees a key before the focused row does; B is caught here whatever has focus.
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BUTTON_B && event.getAction() == KeyEvent.ACTION_UP) {
                d.dismiss();
                return true;
            }
            return keyCode == KeyEvent.KEYCODE_BUTTON_B;
        });

        dialog.show();
        focusFirstRow(panel);
        return dialog;
    }

    /**
     * Drop from {@code anchor} as a panel under it, its right edge on the anchor's: a wide
     * window's home, where the menu opens where the eye already is. No dim, as for any menu
     * that belongs to a control; a touch outside closes it.
     */
    public PopupWindow showAnchored(@NonNull View anchor) {
        Context ctx = anchor.getContext();
        LayoutInflater inflater = LayoutInflater.from(ctx);
        final PopupWindow popup = new PopupWindow(ctx);

        View panel = buildPanel(inflater, null, popup::dismiss, popup::dismiss);
        int margin = ctx.getResources().getDimensionPixelSize(R.dimen.menu_sheet_window_margin);
        int gap = ctx.getResources().getDimensionPixelSize(R.dimen.menu_sheet_anchor_gap);

        // What is left under the anchor, so a long menu scrolls rather than running off screen.
        Rect visible = new Rect();
        anchor.getWindowVisibleDisplayFrame(visible);
        int[] at = new int[2];
        anchor.getLocationOnScreen(at);
        int below = visible.bottom - (at[1] + anchor.getHeight()) - gap - margin;
        MaxHeightScrollView scroll = panel.findViewById(R.id.menuSheetScroll);
        scroll.setMaxHeightPx(Math.max(dp(160), Math.min(below,
                (int) (screenHeight() * MAX_HEIGHT_FRACTION))));

        popup.setContentView(panel);
        popup.setWidth(Math.min(widthCap(), visible.width() - 2 * margin));
        popup.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        // Focusable, so the d-pad drives the menu and not the screen under it.
        popup.setFocusable(true);
        popup.setOutsideTouchable(true);
        // A background of its own is what lets a touch outside, and BACK, reach the popup on
        // every API level; transparent, since the panel draws the surface.
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(dp(8));
        popup.showAsDropDown(anchor, 0, gap, Gravity.END);

        focusFirstRow(panel);
        return popup;
    }

    // Land the gamepad on the first action.
    private static void focusFirstRow(View panel) {
        LinearLayout container = panel.findViewById(R.id.menuSheetItems);
        if (container.getChildCount() > 0) {
            container.getChildAt(0).requestFocus();
        }
    }

    /**
     * @param closeOnB when not null, B on any of the panel's views runs it; for the homes whose
     *                 window offers no key hook of its own (a popup)
     */
    private View buildPanel(LayoutInflater inflater, ViewGroup attachTo, Runnable dismiss,
                            @Nullable Runnable closeOnB) {
        View panel = inflater.inflate(R.layout.menu_sheet, attachTo, false);

        TextView titleView = panel.findViewById(R.id.menuSheetTitle);
        if (TextUtils.isEmpty(title)) {
            titleView.setVisibility(View.GONE);
        } else {
            titleView.setText(title);
        }
        if (!TextUtils.isEmpty(subtitle)) {
            TextView subtitleView = panel.findViewById(R.id.menuSheetSubtitle);
            subtitleView.setText(subtitle);
            subtitleView.setVisibility(View.VISIBLE);
            // The two lines read as one header; the gap to the rows is the subtitle's.
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) titleView.getLayoutParams();
            lp.bottomMargin = dp(2);
            titleView.setLayoutParams(lp);
        }

        LinearLayout container = panel.findViewById(R.id.menuSheetItems);
        for (Item item : items) {
            container.addView(buildRow(inflater, container, item, dismiss, closeOnB));
        }

        MaxHeightScrollView scroll = panel.findViewById(R.id.menuSheetScroll);
        scroll.setMaxHeightPx((int) (screenHeight() * MAX_HEIGHT_FRACTION));
        return panel;
    }

    private View buildRow(LayoutInflater inflater, ViewGroup container, Item item, Runnable dismiss,
                          @Nullable Runnable closeOnB) {
        View row = inflater.inflate(R.layout.menu_sheet_row, container, false);

        ((TextView) row.findViewById(R.id.menuRowLabel)).setText(item.label);
        if (item.description != null) {
            row.setContentDescription(item.description);
        }

        if (item.hasDot) {
            View dot = row.findViewById(R.id.menuRowDot);
            ViewCompat.setBackgroundTintList(dot, ColorStateList.valueOf(item.dotColor));
            dot.setVisibility(View.VISIBLE);
        }
        TextView trailing = null;
        if (item.trailingLabel != null) {
            trailing = row.findViewById(R.id.menuRowTrailing);
            trailing.setText(item.trailingLabel);
            trailing.setVisibility(View.VISIBLE);
            final Runnable trailingAction = item.trailingAction;
            trailing.setOnClickListener(v -> {
                dismiss.run();
                if (trailingAction != null) {
                    trailingAction.run();
                }
            });
            // The trailing stop sits inside its row, inside the row's end padding: the focus
            // search never finds a child to the RIGHT of the parent that holds it (the row's
            // right edge is past the child's), and UP or DOWN always prefer the row. So the
            // two are walked between by hand.
            wireKeys(trailing, null, row, closeOnB);
        }

        if (item.iconRes != 0) {
            ImageView icon = row.findViewById(R.id.menuRowIcon);
            icon.setImageResource(item.iconRes);
            icon.setVisibility(View.VISIBLE);
            if (item.danger) {
                icon.setImageTintList(ColorStateList.valueOf(
                        ContextCompat.getColor(row.getContext(), R.color.danger)));
            }
        }
        if (item.danger) {
            ((TextView) row.findViewById(R.id.menuRowLabel)).setTextColor(
                    ContextCompat.getColor(row.getContext(), R.color.danger));
            row.setBackgroundResource(R.drawable.menu_row_danger_bg);
        }
        if (item.checked) {
            row.findViewById(R.id.menuRowCheck).setVisibility(View.VISIBLE);
        }

        final Runnable action = item.action;
        row.setOnClickListener(v -> {
            dismiss.run();
            if (action != null) {
                action.run();
            }
        });
        wireKeys(row, trailing, null, closeOnB);
        return row;
    }

    /**
     * The row's keys, in one listener since a view keeps only the last one set.
     *
     * @param right where RIGHT goes; null to leave it to the focus search
     * @param left  where LEFT goes; null to leave it to the focus search
     * @param close what B runs; null to leave B to the window
     */
    private static void wireKeys(View view, @Nullable final View right, @Nullable final View left,
                                 @Nullable final Runnable close) {
        if (right == null && left == null && close == null) {
            return;
        }
        view.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BUTTON_B && close != null) {
                // The press is taken whole: the key-up is swallowed with the down, so nothing
                // behind the menu sees half of it once the menu is gone.
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    close.run();
                }
                return true;
            }
            View target = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? right
                    : keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? left : null;
            if (target == null) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                target.requestFocus();
            }
            return true;
        });
    }

    private int widthCap() {
        return context.getResources().getDimensionPixelSize(R.dimen.menu_sheet_max_width);
    }

    private int screenWidth() {
        return metrics().widthPixels;
    }

    private int screenHeight() {
        return metrics().heightPixels;
    }

    private DisplayMetrics metrics() {
        return context.getResources().getDisplayMetrics();
    }

    private int dp(int value) {
        return Math.round(value * metrics().density);
    }
}
