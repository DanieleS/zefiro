package com.limelight.pcs;

import android.app.Dialog;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.limelight.R;
import com.limelight.computers.HostEntry;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager.PairState;
import com.limelight.ui.MenuSheet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Everything that can be done to one host, as a {@link MenuSheet}: the PCs section's long-press,
 * its Y button, and a tap on a card that has nothing better to do (an offline host).
 *
 * <p>It replaces {@code PcView}'s context menu, with the same items under the same conditions, so
 * nothing a user could do to a PC is lost in the move. Unpair is still not offered: PcView defined
 * it but never added it, and adding it is a feature, not a port.
 *
 * <pre>
 *   offline or checking   Wake · GameStream end-of-service
 *   online, not paired    OTP pair · Legacy pair · (end-of-service | server config)
 *   online, paired        [Resume · Quit, when in a game] · (end-of-service | server config)
 *                         · View all apps · Export for frontends
 *   always, last          Test network · Delete PC · View details
 * </pre>
 *
 * "End-of-service" is for an NVIDIA host (GameStream is gone, the FAQ says what to do instead);
 * any other host has a web config page, guessed from its address.
 *
 * The items are decided by {@link #itemsFor}, a pure function of the host's state, so they can
 * be tested without a screen. Main thread only.
 */
public final class HostActionsSheet {

    /** One row of the sheet. The order of {@link #itemsFor}'s result is the order shown. */
    public enum Item {
        WAKE(R.string.pcview_menu_send_wol),
        GAMESTREAM_EOL(R.string.pcview_menu_eol),
        PAIR_OTP(R.string.pcview_menu_pair_pc_otp),
        PAIR(R.string.pcview_menu_pair_pc),
        OPEN_MANAGEMENT_PAGE(R.string.pcview_menu_open_management_page),
        RESUME(R.string.applist_menu_resume),
        QUIT(R.string.applist_menu_quit),
        FULL_APP_LIST(R.string.pcview_menu_app_list),
        FRONTEND_EXPORT(R.string.frontend_export_menu),
        TEST_NETWORK(R.string.pcview_menu_test_network),
        DELETE(R.string.pcview_menu_delete_pc),
        VIEW_DETAILS(R.string.pcview_menu_details);

        @StringRes
        public final int label;

        Item(@StringRes int label) {
            this.label = label;
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
            items.add(details.nvidiaServer ? Item.GAMESTREAM_EOL : Item.OPEN_MANAGEMENT_PAGE);
            items.add(Item.FULL_APP_LIST);
            items.add(Item.FRONTEND_EXPORT);
        }

        items.add(Item.TEST_NETWORK);
        items.add(Item.DELETE);
        items.add(Item.VIEW_DETAILS);
        return Collections.unmodifiableList(items);
    }

    /** The sheet's header: the host's name and its state, as the context menu's header was. */
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

    /** Builds the sheet for a host, each row running the matching {@link HostActions} call. */
    @NonNull
    public static MenuSheet build(@NonNull Context context, @NonNull HostEntry host,
                                  @NonNull HostActions actions) {
        MenuSheet sheet = new MenuSheet(context).setTitle(titleFor(context, host.details));
        for (final Item item : itemsFor(host.details)) {
            sheet.add(context.getString(item.label), () -> run(actions, item, host));
        }
        return sheet;
    }

    /** Builds and shows the sheet, centred and focused for the d-pad. */
    @NonNull
    public static Dialog show(@NonNull Context context, @NonNull HostEntry host,
                              @NonNull HostActions actions) {
        return build(context, host, actions).showCentered(context);
    }

    private static void run(HostActions actions, Item item, HostEntry host) {
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
