package com.limelight.pcs;

import android.content.Context;
import android.view.View;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.limelight.R;
import com.limelight.computers.HostEntry;
import com.limelight.main.WindowClass;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager.PairState;
import com.limelight.ui.MenuSheet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Everything that can be done to one host, as a {@link MenuSheet}: the PCs section's long-press,
 * its Y button without a companion panel, a tile's overflow button, and a tap on a tile that has
 * nothing better to do (an offline host). With the panel, the same rows are the focused PC's page
 * there ({@link PcDetailsPage}), built from the same {@link #itemsFor} and run through the same
 * {@link #run}.
 *
 * <p>It replaces {@code PcView}'s context menu, with the same items under the same conditions, so
 * nothing a user could do to a PC is lost in the move. Unpair is still not offered: PcView defined
 * it but never added it, and adding it is a feature, not a port.
 *
 * <pre>
 *   offline or checking   Wake · GameStream end-of-service
 *   online, not paired    OTP pair · Legacy pair · (end-of-service | server config)
 *   online, paired        [Resume · Quit, when in a game] · View all apps
 *                         · (end-of-service | server config) · Export for frontends
 *   always, last          Test network · View details · Delete PC
 * </pre>
 *
 * "End-of-service" is for an NVIDIA host (GameStream is gone, the FAQ says what to do instead);
 * any other host has a web config page, guessed from its address.
 *
 * <p>The order is the PCs mock's: what the host is waiting for first (pairing, Wake, the game
 * it is in), Delete PC last and in the danger tone, so the one action that cannot be undone is
 * never the next row down from an ordinary one by accident. The set is PcView's, unchanged.
 *
 * <p>The sheet's header is the host's name, with its address and game count under it when
 * they are known.
 *
 * The items are decided by {@link #itemsFor}, a pure function of the host's state, so they can
 * be tested without a screen. Main thread only.
 */
public final class HostActionsSheet {

    /** One row of the sheet. The order of {@link #itemsFor}'s result is the order shown. */
    public enum Item {
        WAKE(R.string.pcview_menu_send_wol, R.drawable.ic_power),
        GAMESTREAM_EOL(R.string.pcview_menu_eol, R.drawable.ic_globe),
        PAIR_OTP(R.string.pcview_menu_pair_pc_otp, R.drawable.ic_link),
        PAIR(R.string.pcview_menu_pair_pc, R.drawable.ic_link),
        OPEN_MANAGEMENT_PAGE(R.string.pcview_menu_open_management_page, R.drawable.ic_globe),
        RESUME(R.string.applist_menu_resume, R.drawable.ic_resume),
        QUIT(R.string.applist_menu_quit, R.drawable.ic_stop),
        FULL_APP_LIST(R.string.pcview_menu_app_list, R.drawable.ic_apps_grid),
        FRONTEND_EXPORT(R.string.frontend_export_menu, R.drawable.ic_file_upload),
        TEST_NETWORK(R.string.pcview_menu_test_network, R.drawable.ic_network_test),
        VIEW_DETAILS(R.string.pcview_menu_details, R.drawable.ic_info),
        DELETE(R.string.pcview_menu_delete_pc, R.drawable.ic_delete, true);

        @StringRes
        public final int label;
        @DrawableRes
        public final int icon;
        /** Drawn in the danger tone: the row cannot be undone. Delete PC alone. */
        public final boolean danger;

        Item(@StringRes int label, @DrawableRes int icon) {
            this(label, icon, false);
        }

        Item(@StringRes int label, @DrawableRes int icon, boolean danger) {
            this.label = label;
            this.icon = icon;
            this.danger = danger;
        }
    }

    private HostActionsSheet() {
    }

    /** The rows for a host in this state, in order. */
    @NonNull
    public static List<Item> itemsFor(@NonNull ComputerDetails details) {
        List<Item> items = new ArrayList<>();
        if (details.state == ComputerDetails.State.OFFLINE
                || details.state == ComputerDetails.State.UNKNOWN) {
            items.add(Item.WAKE);
            items.add(Item.GAMESTREAM_EOL);
        }
        else if (details.pairState != PairState.PAIRED) {
            items.add(Item.PAIR_OTP);
            items.add(Item.PAIR);
            items.add(details.nvidiaServer ? Item.GAMESTREAM_EOL : Item.OPEN_MANAGEMENT_PAGE);
        }
        else {
            if (details.runningGameId != 0) {
                items.add(Item.RESUME);
                items.add(Item.QUIT);
            }
            items.add(Item.FULL_APP_LIST);
            items.add(details.nvidiaServer ? Item.GAMESTREAM_EOL : Item.OPEN_MANAGEMENT_PAGE);
            items.add(Item.FRONTEND_EXPORT);
        }

        items.add(Item.TEST_NETWORK);
        items.add(Item.VIEW_DETAILS);
        items.add(Item.DELETE);
        return Collections.unmodifiableList(items);
    }

    /**
     * The host's name and its state, as the context menu's header was. The sheet's own header is
     * now the name over {@link #subtitleFor}; this stays for whoever titles a dialog after the
     * host.
     */
    @NonNull
    public static String titleFor(@NonNull Context context, @NonNull ComputerDetails details) {
        int state;
        switch (details.state) {
            case ONLINE:
                state = R.string.pcview_menu_header_online;
                break;
            case OFFLINE:
                state = R.string.pcview_menu_header_offline;
                break;
            case UNKNOWN:
            default:
                state = R.string.pcview_menu_header_unknown;
                break;
        }
        return details.name + " - " + context.getString(state);
    }

    /**
     * The facts under the sheet's title: the host's address and how many games it has, each only
     * when known, joined with a middle dot; null when neither is.
     */
    @Nullable
    public static String subtitleFor(@NonNull Context context, @NonNull HostEntry host) {
        StringBuilder facts = new StringBuilder();
        ComputerDetails.AddressTuple address = HostStatus.address(host.details);
        if (address != null) {
            facts.append(HostStatus.addressText(address));
        }
        if (host.gameCount >= 0) {
            if (facts.length() > 0) {
                facts.append(" · ");
            }
            facts.append(context.getResources().getQuantityString(
                    R.plurals.pcs_game_count, host.gameCount, host.gameCount));
        }
        return facts.length() > 0 ? facts.toString() : null;
    }

    /** Builds the sheet for a host, each row running the matching {@link HostActions} call. */
    @NonNull
    public static MenuSheet build(@NonNull Context context, @NonNull HostEntry host,
                                  @NonNull HostActions actions) {
        MenuSheet sheet = new MenuSheet(context)
                .setTitle(host.details.name)
                .setSubtitle(subtitleFor(context, host));
        for (final Item item : itemsFor(host.details)) {
            String label = context.getString(item.label);
            Runnable action = () -> run(actions, item, host);
            if (item.danger) {
                sheet.addDanger(label, item.icon, action);
            } else {
                sheet.add(label, item.icon, action);
            }
        }
        return sheet;
    }

    /**
     * Builds and shows the sheet where the window wants it: risen from the bottom in a compact
     * window, where the thumb is; dropped from {@code anchor} in a wide one, when a control asked
     * for it; otherwise centred, for the d-pad.
     *
     * @param anchor the control that opened the sheet (a tile's overflow button); null when a
     *               key or a long-press did
     */
    public static void show(@NonNull Context context, @NonNull HostEntry host,
                            @NonNull HostActions actions, @Nullable View anchor) {
        MenuSheet sheet = build(context, host, actions);
        boolean compact = WindowClass.from(context.getResources().getConfiguration().screenWidthDp)
                == WindowClass.COMPACT;
        if (compact) {
            sheet.showBottom(context);
        } else if (anchor != null && anchor.isShown()) {
            sheet.showAnchored(anchor);
        } else {
            sheet.showCentered(context);
        }
    }

    /**
     * Runs one row's action on the host, as a tap on the row does. The focused PC's page on the
     * companion panel runs its stops through here too, so a row does the same on either screen.
     */
    static void run(@NonNull HostActions actions, @NonNull Item item, @NonNull HostEntry host) {
        ComputerDetails details = host.details;
        switch (item) {
            case WAKE:
                actions.wake(details);
                break;
            case GAMESTREAM_EOL:
                actions.openGameStreamEolFaq();
                break;
            case PAIR_OTP:
                actions.pairWithOtp(details);
                break;
            case PAIR:
                actions.pair(details, null, null);
                break;
            case OPEN_MANAGEMENT_PAGE:
                actions.openManagementPage(host);
                break;
            case RESUME:
                actions.resume(host);
                break;
            case QUIT:
                actions.quit(host);
                break;
            case FULL_APP_LIST:
                actions.showFullAppList(details);
                break;
            case FRONTEND_EXPORT:
                actions.frontendExport(details);
                break;
            case TEST_NETWORK:
                actions.testNetwork();
                break;
            case DELETE:
                actions.remove(details);
                break;
            case VIEW_DETAILS:
                actions.details(details);
                break;
        }
    }
}
