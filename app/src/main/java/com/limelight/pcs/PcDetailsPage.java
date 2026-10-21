package com.limelight.pcs;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.limelight.R;
import com.limelight.companion.CompanionPage;
import com.limelight.companion.CompanionPageRenderer;
import com.limelight.computers.HostEntry;
import com.limelight.main.PageFocus;
import com.limelight.nvstream.http.ComputerDetails;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The focused PC on the Thor's bottom screen: its state, the facts the client really has about
 * it, the game it is in, and every one of its actions as a big touch row. What the actions sheet
 * offers on a phone, laid out for a second screen.
 *
 * <p>The actions are {@link HostActionsSheet#itemsFor}'s, in its order, so the sheet and the
 * panel can never disagree on what a PC in this state can do. Resume and Quit are taken out of
 * the list and drawn as the running card's two buttons instead, since they act on the game, not
 * on the PC.
 *
 * <h3>Stops</h3>
 *
 * <pre>
 *   row 0   [STOP_RESUME, STOP_QUIT]           only while a game runs
 *   then    [item 0, item 1], [item 2, item 3], …   two per row, in itemsFor order
 * </pre>
 *
 * An action's stop is {@link #stopFor its own id}, the same whatever else the list holds, never
 * its place in it: a poll that reshapes the list (the PC went offline) then leaves the d-pad on a
 * stop the page no longer has, which {@code PanelFocus} moves to the first one, rather than on
 * whichever action took that place, and a tap that raced it does nothing. The running buttons
 * take ids past any action's, since {@link PageFocus} reserves negative numbers for its own
 * answers. So a PC that is not paired opens on OTP pair and Legacy pair, an offline one on Wake,
 * and Delete PC is always the last stop.
 *
 * <p>A value, like every {@link CompanionPage}: lists are copied, facts compare by content, and
 * the cover by identity (a bitmap's pixels are never compared; the cache hands out the same
 * instance for the same game).
 */
public final class PcDetailsPage extends CompanionPage {

    /** The running card's Resume button. */
    public static final int STOP_RESUME = 1000;
    /** The running card's Quit button. */
    public static final int STOP_QUIT = 1001;
    /** The most facts a PC has: Address, Connection and Games. */
    static final int MAX_FACTS = 3;

    private static final HostActionsSheet.Item[] ITEMS = HostActionsSheet.Item.values();

    /** One cell of the facts grid: a value over its label. */
    public static final class Fact {
        @NonNull
        public final String label;
        @NonNull
        public final String value;

        public Fact(@NonNull String label, @NonNull String value) {
            this.label = label;
            this.value = value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Fact)) {
                return false;
            }
            Fact that = (Fact) o;
            return label.equals(that.label) && value.equals(that.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(label, value);
        }

        @NonNull
        @Override
        public String toString() {
            return label + "=" + value;
        }
    }

    @NonNull
    public final String uuid;
    @NonNull
    public final String name;
    @NonNull
    public final HostStatus.Kind kind;
    /** The state in words, as the tile's status line says it. */
    @NonNull
    public final String statusText;
    /** Address, Connection and Games, each only when known. */
    @NonNull
    public final List<Fact> facts;
    /** The game the PC is in; null when it is idle. */
    @Nullable
    public final String runningTitle;
    /** That game's cover, or its hero image standing in; null when idle or when it has neither. */
    @Nullable
    public final Bitmap cover;
    /** The PC's actions, Resume and Quit aside, in {@link HostActionsSheet#itemsFor} order. */
    @NonNull
    public final List<HostActionsSheet.Item> items;

    /**
     * The page for one host as it stands.
     *
     * @param art the running game's cover, else its hero image; ignored while the PC is idle
     */
    @NonNull
    public static PcDetailsPage of(@NonNull Context context, @NonNull HostEntry entry,
                                   @Nullable Bitmap art) {
        ComputerDetails details = entry.details;
        HostStatus.Kind kind = HostStatus.kind(details);

        List<Fact> facts = new ArrayList<>(MAX_FACTS);
        ComputerDetails.AddressTuple address = HostStatus.address(details);
        if (address != null) {
            facts.add(new Fact(context.getString(R.string.pcs_fact_address),
                    HostStatus.addressText(address)));
        }
        HostStatus.Connection connection = HostStatus.connection(details);
        if (connection != HostStatus.Connection.NONE) {
            facts.add(new Fact(context.getString(R.string.pcs_fact_connection),
                    context.getString(connection.label)));
        }
        if (entry.gameCount >= 0) {
            facts.add(new Fact(context.getString(R.string.pcs_fact_games),
                    String.valueOf(entry.gameCount)));
        }

        String runningTitle = null;
        Bitmap cover = null;
        if (kind == HostStatus.Kind.PLAYING) {
            runningTitle = entry.runningAppName != null ? entry.runningAppName
                    : context.getString(R.string.pcview_status_running_unnamed);
            cover = art;
        }

        List<HostActionsSheet.Item> items = new ArrayList<>();
        for (HostActionsSheet.Item item : HostActionsSheet.itemsFor(details)) {
            if (item != HostActionsSheet.Item.RESUME && item != HostActionsSheet.Item.QUIT) {
                items.add(item);
            }
        }

        // A string without a placeholder ignores the game's name; see HostStatus.textRes.
        String statusText = context.getString(HostStatus.textRes(entry), entry.runningAppName);
        return new PcDetailsPage(details.uuid, details.name != null ? details.name : "", kind,
                statusText, Collections.unmodifiableList(facts), runningTitle, cover,
                Collections.unmodifiableList(items), NO_STOP);
    }

    private PcDetailsPage(@NonNull String uuid, @NonNull String name,
                          @NonNull HostStatus.Kind kind, @NonNull String statusText,
                          @NonNull List<Fact> facts, @Nullable String runningTitle,
                          @Nullable Bitmap cover, @NonNull List<HostActionsSheet.Item> items,
                          int focusedStop) {
        super(focusedStop);
        this.uuid = uuid;
        this.name = name;
        this.kind = kind;
        this.statusText = statusText;
        this.facts = facts;
        this.runningTitle = runningTitle;
        this.cover = cover;
        this.items = items;
    }

    /** The stop of an action's row: its id, whichever page it is on. */
    public static int stopFor(@NonNull HostActionsSheet.Item item) {
        return item.ordinal();
    }

    /** Whether the running card, with its Resume and Quit, is drawn. */
    public boolean isRunning() {
        return runningTitle != null;
    }

    /** The stops, row by row: the running card's buttons, then the actions two by two. */
    @NonNull
    public PageFocus stops() {
        List<int[]> rows = new ArrayList<>(items.size() / 2 + 2);
        if (isRunning()) {
            rows.add(new int[]{STOP_RESUME, STOP_QUIT});
        }
        for (int i = 0; i < items.size(); i += 2) {
            rows.add(i + 1 < items.size()
                    ? new int[]{stopFor(items.get(i)), stopFor(items.get(i + 1))}
                    : new int[]{stopFor(items.get(i))});
        }
        return new PageFocus(rows);
    }

    /**
     * Where the d-pad lands from the tiles: Resume while a game runs, otherwise the first action
     * (the pairing, Wake, View all apps); {@link PageFocus#STAY} for a page with no stop.
     */
    public int entryStop() {
        if (isRunning()) {
            return STOP_RESUME;
        }
        return items.isEmpty() ? PageFocus.STAY : stopFor(items.get(0));
    }

    /**
     * The action a stop names: Resume and Quit for the running card's buttons, the action with
     * that id otherwise; null for a stop this page does not have (a tap that raced a change).
     */
    @Nullable
    public HostActionsSheet.Item itemAt(int stop) {
        if (stop == STOP_RESUME) {
            return isRunning() ? HostActionsSheet.Item.RESUME : null;
        }
        if (stop == STOP_QUIT) {
            return isRunning() ? HostActionsSheet.Item.QUIT : null;
        }
        if (stop < 0 || stop >= ITEMS.length) {
            return null;
        }
        HostActionsSheet.Item item = ITEMS[stop];
        return items.contains(item) ? item : null;
    }

    // --- CompanionPage ---

    @NonNull
    @Override
    public CompanionPageRenderer renderer() {
        return PcDetailsRenderer.INSTANCE;
    }

    @NonNull
    @Override
    public PcDetailsPage withFocus(int stop) {
        return new PcDetailsPage(uuid, name, kind, statusText, facts, runningTitle, cover, items,
                stop);
    }

    @Override
    protected boolean sameContent(@NonNull CompanionPage other) {
        PcDetailsPage that = (PcDetailsPage) other;
        return cover == that.cover && kind == that.kind && uuid.equals(that.uuid)
                && name.equals(that.name) && statusText.equals(that.statusText)
                && Objects.equals(runningTitle, that.runningTitle)
                && sameList(facts, that.facts) && sameList(items, that.items);
    }

    @Override
    protected int contentHash() {
        return Objects.hash(uuid, name, kind, statusText, facts, runningTitle,
                System.identityHashCode(cover), items);
    }
}
