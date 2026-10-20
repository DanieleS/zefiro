package com.limelight.library;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.google.android.material.button.MaterialButton;
import com.limelight.R;
import com.limelight.computers.HostLibrary;

/**
 * What the Library shows in the games' place when it has none to show, and the way out of it.
 *
 * <p>The old library met an offline or unpaired host with a toast and {@code finish()}: the
 * library closed under the user, and with it the app when the library was all there was. In the
 * main screen nothing closes. The library's state comes in as a {@link HostLibrary.State}, and
 * this panel says what it means and offers what can be done:
 *
 * <pre>
 *   mode       title                     primary (focused first)    secondary
 *   ────────   ───────────────────────   ────────────────────────   ─────────────────
 *   NO_HOST    No PC selected            Choose a PC                -
 *   LOADING    Connecting to {host}…     -  (a spinner)             -
 *   OFFLINE    {host} is offline         Wake                       Choose another PC
 *   UNPAIRED   {host} is not paired      Pair                       Choose another PC
 *   EMPTY      No games on {host}        -                          -
 *   NONE       (the panel is gone; the games are shown)
 * </pre>
 *
 * Which mode applies is {@link #modeFor}, a pure function, so a test can pin it. The panel only
 * draws and reports presses; the section decides what Wake or Pair does. Main thread only.
 */
public final class HostStatePanel {

    public enum Mode {
        NONE,
        NO_HOST,
        LOADING,
        OFFLINE,
        UNPAIRED,
        EMPTY
    }

    /** The panel's buttons. */
    public interface Listener {
        void onWake();

        void onPair();

        /** Choose a PC, or another one: the PCs section. */
        void onChooseHost();
    }

    private final View root;
    private final View progress;
    private final TextView title;
    private final TextView message;
    private final MaterialButton primary;
    private final MaterialButton secondary;
    private final Listener listener;

    private Mode mode = Mode.NONE;

    /** @param root the inflated {@code view_host_state_panel} */
    public HostStatePanel(@NonNull View root, @NonNull Listener listener) {
        this.root = root;
        this.listener = listener;
        progress = root.findViewById(R.id.hostStateProgress);
        title = root.findViewById(R.id.hostStateTitle);
        message = root.findViewById(R.id.hostStateMessage);
        primary = root.findViewById(R.id.hostStatePrimary);
        secondary = root.findViewById(R.id.hostStateSecondary);

        // Wired once; each press asks the mode current at the time of the press.
        primary.setOnClickListener(v -> {
            switch (mode) {
                case OFFLINE:
                    listener.onWake();
                    break;
                case UNPAIRED:
                    listener.onPair();
                    break;
                case NO_HOST:
                    listener.onChooseHost();
                    break;
                default:
                    break;
            }
        });
        secondary.setOnClickListener(v -> listener.onChooseHost());
    }

    /**
     * Which mode the Library is in.
     *
     * @param snapshot     the selected host's library, or null when no host is selected
     * @param visibleGames how many games the screen would show (hidden ones left out unless the
     *                     user asked for them)
     */
    @NonNull
    public static Mode modeFor(@Nullable HostLibrary.Snapshot snapshot, int visibleGames) {
        if (snapshot == null) {
            return Mode.NO_HOST;
        }
        switch (snapshot.state) {
            case LOADING:
                return Mode.LOADING;
            case OFFLINE:
                return Mode.OFFLINE;
            case UNPAIRED:
                return Mode.UNPAIRED;
            case READY:
            default:
                return visibleGames == 0 ? Mode.EMPTY : Mode.NONE;
        }
    }

    /**
     * Shows a mode, or hides the panel for {@link Mode#NONE}.
     *
     * @param hostName the host's name as last polled; null before it has answered once
     * @param allHidden for EMPTY: the host has games, all of them hidden
     */
    public void show(@NonNull Mode mode, @Nullable String hostName, boolean allHidden) {
        this.mode = mode;
        if (mode == Mode.NONE) {
            root.setVisibility(View.GONE);
            return;
        }
        Context context = root.getContext();
        String host = hostName != null && !hostName.isEmpty()
                ? hostName : context.getString(R.string.library_this_pc);

        int titleRes;
        int messageRes = 0;
        int primaryRes = 0;
        int secondaryRes = 0;
        switch (mode) {
            case NO_HOST:
                titleRes = R.string.library_no_host_title;
                messageRes = R.string.library_no_host_message;
                primaryRes = R.string.library_choose_pc;
                break;
            case LOADING:
                titleRes = R.string.library_host_loading;
                break;
            case OFFLINE:
                titleRes = R.string.library_host_offline_title;
                messageRes = R.string.library_host_offline_message;
                primaryRes = R.string.library_wake;
                secondaryRes = R.string.library_choose_another_pc;
                break;
            case UNPAIRED:
                titleRes = R.string.library_host_unpaired_title;
                messageRes = R.string.library_host_unpaired_message;
                primaryRes = R.string.library_pair;
                secondaryRes = R.string.library_choose_another_pc;
                break;
            case EMPTY:
            default:
                titleRes = R.string.library_empty_title;
                messageRes = allHidden ? R.string.library_empty_hidden_message : 0;
                break;
        }

        // Only the titles that name a host take it; the others ignore the extra argument.
        title.setText(context.getString(titleRes, host));
        setOptional(message, messageRes);
        setOptional(primary, primaryRes);
        setOptional(secondary, secondaryRes);
        progress.setVisibility(mode == Mode.LOADING ? View.VISIBLE : View.GONE);
        root.setVisibility(View.VISIBLE);
    }

    public boolean isShowing() {
        return root.getVisibility() == View.VISIBLE;
    }

    @NonNull
    public Mode mode() {
        return mode;
    }

    /** Where focus goes in the panel: its way out, primary first; null when it offers none. */
    @Nullable
    public View focusTarget() {
        if (!isShowing()) {
            return null;
        }
        if (primary.getVisibility() == View.VISIBLE) {
            return primary;
        }
        return secondary.getVisibility() == View.VISIBLE ? secondary : null;
    }

    /** Whether the view is one of the panel's. */
    public boolean contains(@Nullable View view) {
        return view == primary || view == secondary;
    }

    private static void setOptional(TextView view, @StringRes int text) {
        if (text == 0) {
            view.setVisibility(View.GONE);
        } else {
            view.setText(text);
            view.setVisibility(View.VISIBLE);
        }
    }
}
