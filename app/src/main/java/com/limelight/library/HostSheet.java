package com.limelight.library;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.limelight.R;
import com.limelight.companion.CompanionDisplayManager;
import com.limelight.computers.HostEntry;
import com.limelight.computers.HostLibrary;
import com.limelight.computers.HostRepository;
import com.limelight.main.MainHost;
import com.limelight.main.Section;
import com.limelight.main.WindowClass;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.pcs.HostCardBinder;
import com.limelight.ui.MenuSheet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Library's host sheet: which PC the Library shows, and what else that PC offers outside its
 * games. X raises it anywhere in the Library; the home's host chip raises it too.
 *
 * <p>It gathers what the old library's top bar and the old PC list held between them, so
 * switching PCs no longer means leaving the library for another screen:
 *
 * <pre>
 *   row                       when
 *   ───────────────────────   ─────────────────────────────────────────────────────────────
 *   ● Taverna  ✓              one per known PC, its state as a dot, the chosen one checked;
 *   ● Studio        [Wake]    an offline PC carries Wake (the chosen one has the row below)
 *   Virtual display           the chosen PC advertises one
 *   Remote session            the chosen PC advertises one
 *   Wake Taverna              the chosen PC is offline
 *   Show companion screen     the companion feature is switched on (hide, while it shows)
 *   Manage PCs                always: the PCs section
 * </pre>
 *
 * The rows are {@link #rowsFor}, a pure function of the hosts and the chosen one, so a test pins
 * them without a screen. Where the sheet shows is the window's call: a sheet from the bottom in
 * a compact window, a panel dropped from the chip in a wide one (centred when there is no chip
 * on screen, as from See all: its only chip is the sort's, and a PC list dropped from "Sort"
 * would read as that chip's menu). Main thread only.
 */
public final class HostSheet {

    /** What a row does. */
    public enum Kind {
        HOST,
        VIRTUAL_DISPLAY,
        REMOTE_SESSION,
        WAKE,
        COMPANION,
        MANAGE_PCS
    }

    /** One row. {@link #host} is the PC a HOST row stands for; null for every other kind. */
    public static final class Row {
        @NonNull
        public final Kind kind;
        @Nullable
        public final HostEntry host;
        /** For a HOST row: whether it is the chosen PC. */
        public final boolean selected;
        /** For a HOST row: whether it carries Wake of its own. */
        public final boolean wake;

        Row(@NonNull Kind kind, @Nullable HostEntry host, boolean selected, boolean wake) {
            this.kind = kind;
            this.host = host;
            this.selected = selected;
            this.wake = wake;
        }

        static Row of(@NonNull Kind kind) {
            return new Row(kind, null, false, false);
        }
    }

    private HostSheet() {
    }

    /**
     * The rows, in order.
     *
     * @param hosts               every known PC, in the order to list them
     * @param selectedUuid        the chosen PC's UUID; null when none is chosen
     * @param specials            what the chosen PC's library advertises besides games
     * @param companionConfigured whether the companion feature is switched on
     */
    @NonNull
    public static List<Row> rowsFor(@NonNull List<HostEntry> hosts, @Nullable String selectedUuid,
                                    @NonNull LibraryModel.Specials specials,
                                    boolean companionConfigured) {
        List<Row> rows = new ArrayList<>();
        HostEntry selected = null;
        for (HostEntry entry : hosts) {
            boolean chosen = selectedUuid != null && selectedUuid.equalsIgnoreCase(entry.details.uuid);
            if (chosen) {
                selected = entry;
            }
            // The chosen PC's Wake is a row of its own below, the one a d-pad finds going down;
            // a second on its own row would only be the same button twice.
            rows.add(new Row(Kind.HOST, entry, chosen, !chosen && isOffline(entry)));
        }
        if (selected != null) {
            if (specials.virtualDisplay != null) {
                rows.add(Row.of(Kind.VIRTUAL_DISPLAY));
            }
            if (specials.hasRemoteSession()) {
                rows.add(Row.of(Kind.REMOTE_SESSION));
            }
            if (isOffline(selected)) {
                rows.add(Row.of(Kind.WAKE));
            }
        }
        if (companionConfigured) {
            rows.add(Row.of(Kind.COMPANION));
        }
        rows.add(Row.of(Kind.MANAGE_PCS));
        return Collections.unmodifiableList(rows);
    }

    /**
     * Builds the sheet from the repository's state and shows it where the window wants it.
     *
     * @param anchor the host chip, when it is on screen; the wide window's menu drops from it
     */
    public static void show(@NonNull FragmentActivity activity, @NonNull final MainHost host,
                            @Nullable View anchor) {
        HostRepository repository = host.hostRepository();
        List<HostEntry> hosts = repository.getHosts().getValue();
        String selectedUuid = repository.getSelectedHostUuid().getValue();
        HostLibrary library = repository.currentLibrary();
        HostLibrary.Snapshot snapshot = library != null ? library.getSnapshot() : null;
        // Only the open library's specials are the chosen PC's: another's are not to be offered.
        LibraryModel.Specials specials = snapshot != null && selectedUuid != null
                && selectedUuid.equalsIgnoreCase(snapshot.hostUuid)
                ? snapshot.specials : LibraryModel.Specials.NONE;
        List<Row> rows = rowsFor(hosts != null ? hosts : Collections.<HostEntry>emptyList(),
                selectedUuid, specials, CompanionDisplayManager.isConfigured(activity));

        MenuSheet sheet = new MenuSheet(activity)
                .setTitle(activity.getString(R.string.library_host_sheet_title));
        for (Row row : rows) {
            addRow(activity, sheet, row, host);
        }

        boolean compact = WindowClass.from(activity.getResources().getConfiguration().screenWidthDp)
                == WindowClass.COMPACT;
        if (compact) {
            sheet.showBottom(activity);
        } else if (anchor != null && anchor.isShown()) {
            sheet.showAnchored(anchor);
        } else {
            sheet.showCentered(activity);
        }
    }

    private static void addRow(Context context, MenuSheet sheet, Row row, final MainHost host) {
        switch (row.kind) {
            case HOST: {
                final ComputerDetails details = row.host.details;
                final String uuid = details.uuid;
                String name = details.name != null ? details.name : context.getString(R.string.library_this_pc);
                String state = context.getString(HostCardBinder.statusLabel(details));
                sheet.addStatusRow(name,
                        ContextCompat.getColor(context, HostCardBinder.statusColor(details)),
                        row.selected,
                        // The check is only drawn, so the chosen row says so in words.
                        context.getString(row.selected ? R.string.library_host_row_selected_a11y
                                : R.string.library_host_row_a11y, name, state),
                        () -> host.hostRepository().select(uuid),
                        row.wake ? context.getString(R.string.library_wake) : null,
                        row.wake ? () -> host.hostActions().wake(details) : null);
                break;
            }
            case VIRTUAL_DISPLAY:
                sheet.add(context.getString(R.string.library_host_sheet_virtual_display),
                        R.drawable.ic_virtual_display,
                        () -> host.launchController().launchVirtualDisplay());
                break;
            case REMOTE_SESSION:
                sheet.add(context.getString(R.string.library_host_sheet_remote_session),
                        R.drawable.ic_remote_session,
                        () -> host.launchController().showRemoteSessionMenu());
                break;
            case WAKE: {
                final HostEntry selected = selectedEntry(host);
                String name = selected != null && selected.details.name != null
                        ? selected.details.name : context.getString(R.string.library_this_pc);
                sheet.add(context.getString(R.string.library_host_sheet_wake, name), () -> {
                    // The PC as polled when the row is pressed, not when the sheet was built.
                    HostEntry current = selectedEntry(host);
                    if (current != null) {
                        host.hostActions().wake(current.details);
                    }
                });
                break;
            }
            case COMPANION: {
                boolean showing = CompanionDisplayManager.isShowing();
                sheet.add(context.getString(showing
                                ? R.string.action_companion_hide : R.string.action_companion_show),
                        showing ? R.drawable.ic_companion_off : R.drawable.ic_companion_on,
                        CompanionDisplayManager::toggle);
                break;
            }
            case MANAGE_PCS:
            default:
                sheet.add(context.getString(R.string.library_host_sheet_manage), R.drawable.ic_computer,
                        () -> host.showSection(Section.PCS));
                break;
        }
    }

    @Nullable
    private static HostEntry selectedEntry(MainHost host) {
        HostRepository repository = host.hostRepository();
        return repository.getHost(repository.getSelectedHostUuid().getValue());
    }

    private static boolean isOffline(HostEntry entry) {
        return entry.details.state == ComputerDetails.State.OFFLINE;
    }
}
