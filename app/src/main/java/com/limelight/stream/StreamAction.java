package com.limelight.stream;

import android.content.res.Resources;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.util.Collections;
import java.util.List;

/**
 * One entry of the in-stream controls, as data: what it is, how it is named and drawn, and
 * where it sits. Nothing here runs anything; a surface draws the entry and hands it back to the
 * router (GameMenu) when chosen, which does the work by {@link #id}.
 *
 * <h3>Why data and not a list of runnables</h3>
 *
 * The same controls are drawn in several places (the centred sheet, the companion panel's list,
 * and later the phone's side panel and the Thor bottom sheet), each with its own idea of tiles,
 * rows and pages. Kept as a tree of plain values they can be built and checked off the device
 * ({@code StreamActionsTest}) and drawn by each surface its own way, while what an entry does
 * stays in one place.
 *
 * <h3>Pages</h3>
 *
 * An entry with {@link #children} (possibly empty) is a page: choosing it shows those children on
 * the same surface. An entry without is a leaf the router runs.
 *
 * <pre>
 *   ROOT ─┬─ tiles    KEYBOARD  GAMEPAD  MOUSE▸  OVERLAY
 *         ├─ list     SCREENSHOT  BITRATE▸  SPECIAL_KEYS▸  MORE▸
 *         └─ danger   DISCONNECT  QUIT
 * </pre>
 */
public final class StreamAction {

    /** Where an entry sits on a surface that draws the groups apart. */
    public enum Group {
        /** A square toggle in the top row. */
        TILE,
        /** A full-width row under the tiles. */
        LIST,
        /** A row on the More page. */
        MORE,
        /** The session's end: drawn last, and set apart. */
        DANGER
    }

    /** What an entry does; the router switches on it. */
    public enum Id {
        ROOT,
        KEYBOARD, GAMEPAD, MOUSE, OVERLAY,
        MOUSE_MODE, MOUSE_MODE_CHOICE, LOCAL_CURSOR, DEVICE_OPTION,
        SCREENSHOT,
        BITRATE, BITRATE_ADAPTIVE, BITRATE_CHOICE,
        SPECIAL_KEYS, SPECIAL_KEY,
        MORE,
        CLIPBOARD_UPLOAD, CLIPBOARD_FETCH,
        SERVER_COMMANDS, SERVER_COMMAND,
        PAN_ZOOM, ROTATE,
        COMPANION_SCREEN, COMPANION_APP,
        SPECIAL_KEYS_BAR, FULL_KEYBOARD, TASK_MANAGER, TOUCH_SENSITIVITY,
        SAVE_REPLAY, RECORD,
        DISCONNECT, QUIT
    }

    @NonNull
    public final Id id;
    /** The label's string; 0 when {@link #labelText} names the entry instead. */
    @StringRes
    public final int labelRes;
    /** The one argument {@link #labelRes} is formatted with; null for none. */
    @Nullable
    public final String labelArg;
    /** A label that is not a resource (a server command, a shortcut the user named). */
    @Nullable
    public final String labelText;
    /** 0 for none. */
    @DrawableRes
    public final int iconRes;
    @NonNull
    public final Group group;
    /** Whether a toggle is on; null for an entry that is not a toggle. */
    @Nullable
    public final Boolean checked;
    /**
     * Run only once the game window has focus again. Needed where a Dialog surface took the
     * focus and the action injects keys or opens something of its own over the game.
     */
    public final boolean withGameFocus;
    /** The page's entries; null for a leaf. */
    @Nullable
    public final List<StreamAction> children;
    /**
     * The entry's own number, where its id alone is not enough: the kbps of a bitrate choice,
     * the index of a server command, a device option or a mouse mode. 0 otherwise.
     */
    public final int arg;
    /** The keys a key-sending entry presses, in order; null for the others. */
    @Nullable
    private final short[] keys;

    private StreamAction(Builder b) {
        this.id = b.id;
        this.labelRes = b.labelRes;
        this.labelArg = b.labelArg;
        this.labelText = b.labelText;
        this.iconRes = b.iconRes;
        this.group = b.group;
        this.checked = b.checked;
        this.withGameFocus = b.withGameFocus;
        this.children = b.children == null ? null : Collections.unmodifiableList(b.children);
        this.arg = b.arg;
        this.keys = b.keys == null ? null : b.keys.clone();
    }

    public boolean isPage() {
        return children != null;
    }

    /** A copy, so a surface cannot change what a later press sends. */
    @Nullable
    public short[] keys() {
        return keys == null ? null : keys.clone();
    }

    /** The label as drawn, resolved against the surface's resources. */
    @NonNull
    public CharSequence label(@NonNull Resources res) {
        if (labelText != null) {
            return labelText;
        }
        return labelArg != null ? res.getString(labelRes, labelArg) : res.getString(labelRes);
    }

    /** The first entry with this id in the tree under (and including) this one; null if none. */
    @Nullable
    public StreamAction find(@NonNull Id wanted) {
        if (id == wanted) {
            return this;
        }
        if (children != null) {
            for (StreamAction child : children) {
                StreamAction hit = child.find(wanted);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    @NonNull
    @Override
    public String toString() {
        return id + (labelText != null ? "(" + labelText + ")" : arg != 0 ? "(" + arg + ")" : "");
    }

    static Builder of(@NonNull Id id, @NonNull Group group) {
        return new Builder(id, group);
    }

    static final class Builder {
        private final Id id;
        private final Group group;
        private int labelRes;
        private String labelArg;
        private String labelText;
        private int iconRes;
        private Boolean checked;
        private boolean withGameFocus;
        private List<StreamAction> children;
        private int arg;
        private short[] keys;

        private Builder(Id id, Group group) {
            this.id = id;
            this.group = group;
        }

        Builder label(@StringRes int res) {
            this.labelRes = res;
            return this;
        }

        Builder label(@StringRes int res, String arg) {
            this.labelRes = res;
            this.labelArg = arg;
            return this;
        }

        Builder text(String text) {
            this.labelText = text;
            return this;
        }

        Builder icon(@DrawableRes int res) {
            this.iconRes = res;
            return this;
        }

        Builder checked(boolean on) {
            this.checked = on;
            return this;
        }

        Builder withGameFocus() {
            this.withGameFocus = true;
            return this;
        }

        Builder children(List<StreamAction> page) {
            this.children = page;
            return this;
        }

        Builder arg(int value) {
            this.arg = value;
            return this;
        }

        Builder keys(short... sequence) {
            this.keys = sequence;
            return this;
        }

        StreamAction build() {
            return new StreamAction(this);
        }
    }
}
