package com.limelight.pcs;

import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.PlatformBinding;
import com.limelight.computers.CmsGateway;
import com.limelight.computers.HostEntry;
import com.limelight.computers.HostRepository;
import com.limelight.grid.assets.DiskAssetLoader;
import com.limelight.library.LibraryIntents;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.nvstream.http.PairingManager.PairState;
import com.limelight.nvstream.wol.WakeOnLanSender;
import com.limelight.utils.Dialog;
import com.limelight.utils.FrontendExporter;
import com.limelight.utils.HelpLauncher;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.UiHelper;

import org.xmlpull.v1.XmlPullParserException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * What can be done to a host from the main screen: pair it, wake it, delete it, export it for a
 * frontend, rejoin or quit its game, and the informational extras (details, network test, its
 * config page, the GameStream end-of-service FAQ).
 *
 * <p>These lived in {@code PcView} as private methods tied to that activity, its binder and its
 * adapter. They move here, against the {@link HostRepository} and any {@link FragmentActivity},
 * so the PCs section, the deep-link pairing hook and later the Library's host sheet all run the
 * same code. The activity owns this object (it outlives the sections' views); sections reach it
 * through {@code MainHost}.
 *
 * <h3>Pairing</h3>
 *
 * <pre>
 *   main      checks (online, service ready, not already pairing) ──▶ "Pairing…"
 *   worker    suspend polling and wait ──▶ PIN dialog ──▶ pm.pair ──▶ pin the server cert,
 *             invalidate the host ──▶ resume polling (always) ──▶ publish the host's shortcut
 *   main      toast the outcome; on success select the host and show the Library
 * </pre>
 *
 * Polling is suspended because the pairing handshake must be the only conversation with the host
 * (as {@code PcView} stopped its updates around {@code doPair}). Unlike PcView, polling is resumed
 * whatever the outcome: there is no activity switch on success to restart it.
 *
 * <h3>Threading</h3>
 *
 * Every public method is main-thread only. Blocking work (pairing, Wake-on-LAN, removal, export)
 * runs on this object's single worker, in order, so a delete queued behind a pairing never races
 * it. Results come back through a main handler and are dropped once the activity is finishing.
 * The one exception is {@link ServerHelper#doNetworkTest}, which keeps its own thread as it does
 * for every caller.
 *
 * <h3>Frontend export</h3>
 *
 * Two folder picks, ROMs then (optionally) ES-DE. The {@code OpenDocumentTree} launchers must be
 * registered before the activity starts, so the activity registers them and hands them in; their
 * results come back through {@link #onRomsFolderPicked} and {@link #onEsdeFolderPicked}. The host
 * between the two picks is held in memory only, as it was in PcView: a process death mid-export
 * drops the export, and the user starts it again.
 */
public class HostActions {

    /** What the actions need from the screen that owns them. Main thread. */
    public interface Shell {
        /**
         * Selects the host and shows its library.
         *
         * @param showHidden the old "View all apps": the library with hidden games shown
         */
        void showLibrary(@NonNull String hostUuid, boolean showHidden);

        /** Runs a game start once the GL renderer is known; decoder choice depends on it. */
        void whenRendererReady(@NonNull Runnable start);
    }

    private final FragmentActivity activity;
    private final HostRepository repository;
    private final Shell shell;
    private final ActivityResultLauncher<Uri> pickRomsFolder;
    private final ActivityResultLauncher<Uri> pickEsdeFolder;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    // Main thread only.
    private boolean pairing;
    @Nullable
    private ComputerDetails exportComputer;
    @Nullable
    private Uri exportRomsTree;

    /**
     * @param pickRomsFolder an {@code OpenDocumentTree} launcher whose result goes to
     *                       {@link #onRomsFolderPicked}
     * @param pickEsdeFolder the same, for {@link #onEsdeFolderPicked}
     */
    public HostActions(@NonNull FragmentActivity activity, @NonNull HostRepository repository,
                       @NonNull Shell shell, @NonNull ActivityResultLauncher<Uri> pickRomsFolder,
                       @NonNull ActivityResultLauncher<Uri> pickEsdeFolder) {
        this.activity = activity;
        this.repository = repository;
        this.shell = shell;
        this.pickRomsFolder = pickRomsFolder;
        this.pickEsdeFolder = pickEsdeFolder;
    }

    /**
     * Lets queued work finish but takes no more, and drops every result still to come. From the
     * activity's onDestroy.
     */
    public void release() {
        worker.shutdown();
        main.removeCallbacksAndMessages(null);
    }

    // --- The card's action ---

    /** Wake it, pair it, or rejoin it: what the card's button (or X on the card) says. */
    public void runCardAction(@NonNull HostEntry host, int action) {
        switch (action) {
            case HostCardBinder.ACTION_WAKE:
                wake(host.details);
                break;
            case HostCardBinder.ACTION_PAIR:
                pair(host.details, null, null);
                break;
            case HostCardBinder.ACTION_RESUME:
                resume(host);
                break;
            default:
                break;
        }
    }

    // --- Pairing ---

    /**
     * Pairs with the host: with a PIN this device makes up and shows (both null), or with the
     * PIN and passphrase of an OTP pairing.
     */
    public void pair(@NonNull final ComputerDetails computer, @Nullable final String otp,
                     @Nullable final String passphrase) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            toast(R.string.pair_pc_offline, Toast.LENGTH_SHORT);
            return;
        }
        final CmsGateway gateway = repository.gateway();
        final String uniqueId = repository.uniqueId();
        if (gateway == null || uniqueId == null) {
            toast(R.string.error_manager_not_running, Toast.LENGTH_LONG);
            return;
        }
        // One at a time: a second pairing would resume polling under the first one's feet.
        if (pairing) {
            toast(R.string.pair_already_in_progress, Toast.LENGTH_SHORT);
            return;
        }
        pairing = true;

        toast(R.string.pairing, Toast.LENGTH_SHORT);
        boolean queued = runOnWorker(() -> {
            String message;
            boolean success = false;
            // Blocks until no poll is in flight, so the handshake talks to the host alone.
            repository.suspendPollingBlocking();
            try {
                NvHTTP httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                        computer.httpsPort, uniqueId, computer.serverCert,
                        PlatformBinding.getCryptoProvider(activity));
                if (httpConn.getPairState() == PairState.PAIRED) {
                    // Already paired, from another session: no toast, straight to the library.
                    message = null;
                    success = true;
                }
                else {
                    String pinStr = otp != null ? otp : PairingManager.generatePinString();

                    // Shown from here on purpose: the dialog stays up for as long as pair()
                    // blocks, and the worker closes it below.
                    if (passphrase == null) {
                        Dialog.displayDialog(activity, activity.getString(R.string.pair_pairing_title),
                                activity.getString(R.string.pair_pairing_msg) + " " + pinStr + "\n\n"
                                        + activity.getString(R.string.pair_pairing_help), false);
                    }
                    else {
                        Dialog.displayDialog(activity, activity.getString(R.string.pair_pairing_title),
                                activity.getString(R.string.pair_otp_pairing_msg) + "\n\n"
                                        + activity.getString(R.string.pair_otp_pairing_help), false);
                    }

                    PairingManager pm = httpConn.getPairingManager();
                    PairState pairState = pm.pair(httpConn.getServerInfo(true), pinStr, passphrase);
                    if (pairState == PairState.PIN_WRONG) {
                        message = activity.getString(R.string.pair_incorrect_pin);
                    }
                    else if (pairState == PairState.FAILED) {
                        message = activity.getString(computer.runningGameId != 0
                                ? R.string.pair_pc_ingame : R.string.pair_fail);
                    }
                    else if (pairState == PairState.ALREADY_IN_PROGRESS) {
                        message = activity.getString(R.string.pair_already_in_progress);
                    }
                    else if (pairState == PairState.PAIRED) {
                        message = null;
                        success = true;

                        // Pin the certificate on the service's own object, the one every later
                        // HTTPS request reads; our copy is a snapshot that nothing else sees.
                        ComputerDetails live = gateway.getComputer(computer.uuid);
                        if (live != null) {
                            live.serverCert = pm.getPairedCert();
                        }
                        // Make the next poll read the pair state afresh rather than the cached one.
                        gateway.invalidateStateForComputer(computer.uuid);
                    }
                    else {
                        message = null;
                    }
                }
            } catch (UnknownHostException e) {
                message = activity.getString(R.string.error_unknown_host);
            } catch (FileNotFoundException e) {
                message = activity.getString(R.string.error_404);
            } catch (XmlPullParserException | IOException e) {
                LimeLog.warning("Pairing failed: " + e);
                message = e.getMessage();
            } finally {
                repository.resumePolling();
            }

            Dialog.closeDialogs();

            if (success) {
                // What the old library did when opened with "newly paired": force the host's
                // launcher shortcut in, and on a TV ask for its channel, which needs this activity.
                new ShortcutHelper(activity).createHostShortcut(computer, true, true);
            }

            final String toastMessage = message;
            final boolean paired = success;
            postToMain(() -> {
                if (toastMessage != null) {
                    Toast.makeText(activity, toastMessage, Toast.LENGTH_LONG).show();
                }
                if (paired) {
                    shell.showLibrary(computer.uuid, false);
                }
            }, () -> pairing = false);
        });
        if (!queued) {
            pairing = false;
        }
    }

    /** Asks for the PIN and passphrase the host's web UI shows, then pairs with them. */
    public void pairWithOtp(@NonNull final ComputerDetails computer) {
        Context context = activity;

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 40);

        final EditText otpInput = new EditText(context);
        otpInput.setHint("PIN");
        otpInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        otpInput.setFilters(new InputFilter[] { new InputFilter.LengthFilter(4) });

        final EditText passphraseInput = new EditText(context);
        passphraseInput.setHint(context.getString(R.string.pair_passphrase_hint));
        passphraseInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        layout.addView(otpInput);
        layout.addView(passphraseInput);

        final AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.pcview_menu_pair_pc_otp)
                .setView(layout)
                // Wired after show(): a builder's button always dismisses, and a short PIN must
                // keep the dialog up for another try.
                .setPositiveButton(context.getString(R.string.proceed), null)
                .setNegativeButton(context.getString(R.string.cancel), (d, which) -> d.dismiss())
                .create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String pin = otpInput.getText().toString();
            String passphrase = passphraseInput.getText().toString();
            if (pin.length() != 4) {
                toast(R.string.pair_pin_length_msg, Toast.LENGTH_SHORT);
                return;
            }
            if (passphrase.length() < 4) {
                toast(R.string.pair_passphrase_length_msg, Toast.LENGTH_SHORT);
                return;
            }
            pair(computer, pin, passphrase);
            dialog.dismiss();
        });
    }

    // --- Power and lifetime ---

    /** Sends a Wake-on-LAN packet to the host's last known MAC address. */
    public void wake(@NonNull final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.ONLINE) {
            toast(R.string.wol_pc_online, Toast.LENGTH_SHORT);
            return;
        }
        if (computer.macAddress == null) {
            toast(R.string.wol_no_mac, Toast.LENGTH_SHORT);
            return;
        }

        runOnWorker(() -> {
            int message;
            try {
                WakeOnLanSender.sendWolPacket(computer);
                message = R.string.wol_waking_msg;
            } catch (IOException e) {
                message = R.string.wol_fail;
            }
            final int result = message;
            postToMain(() -> toast(result, Toast.LENGTH_LONG), null);
        });
    }

    /**
     * Deletes the host after a confirmation: from the service, with its cached art, its hidden
     * games and its launcher shortcuts, then from the repository's list.
     */
    public void remove(@NonNull final ComputerDetails computer) {
        if (ActivityManager.isUserAMonkey()) {
            LimeLog.info("Ignoring delete PC request from monkey");
            return;
        }
        UiHelper.displayDeletePcConfirmationDialog(activity, computer, () -> {
            final CmsGateway gateway = repository.gateway();
            if (gateway == null) {
                toast(R.string.error_manager_not_running, Toast.LENGTH_LONG);
                return;
            }
            final String reason = activity.getString(R.string.scut_deleted_pc);
            runOnWorker(() -> {
                // A database write and an interrupt of the host's polling thread: not for main.
                gateway.removeComputer(computer);
                new DiskAssetLoader(activity).deleteAssetsForComputer(computer.uuid);
                activity.getSharedPreferences(LibraryIntents.PREFS_HIDDEN_APPS, Context.MODE_PRIVATE)
                        .edit()
                        .remove(computer.uuid)
                        .apply();
                new ShortcutHelper(activity).disableComputerShortcut(computer, reason);
                // Only now: forgetHost expects the service to deny knowing the host already.
                postToMain(() -> repository.forgetHost(computer.uuid), null);
            });
        }, null);
    }

    // --- The running game ---

    /** Rejoins the game the host is running. */
    public void resume(@NonNull final HostEntry host) {
        final String uniqueId = repository.uniqueId();
        if (repository.gateway() == null || uniqueId == null) {
            toast(R.string.error_manager_not_running, Toast.LENGTH_LONG);
            return;
        }
        final ComputerDetails computer = host.details;
        final NvApp app = new NvApp(appName(host), null, computer.runningGameId, false);
        shell.whenRendererReady(() -> ServerHelper.doStart(activity, app, computer, uniqueId, false));
    }

    /** Quits the game the host is running, after a confirmation. */
    public void quit(@NonNull final HostEntry host) {
        final String uniqueId = repository.uniqueId();
        if (repository.gateway() == null || uniqueId == null) {
            toast(R.string.error_manager_not_running, Toast.LENGTH_LONG);
            return;
        }
        final ComputerDetails computer = host.details;
        UiHelper.displayQuitConfirmationDialog(activity, () ->
                ServerHelper.doQuit(activity, computer, new NvApp(appName(host), null, 0, false),
                        uniqueId, null), null);
    }

    /** The library of a paired host with its hidden games shown, which was "View all apps". */
    public void showFullAppList(@NonNull ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.OFFLINE) {
            toast(R.string.error_pc_offline, Toast.LENGTH_SHORT);
            return;
        }
        shell.showLibrary(computer.uuid, true);
    }

    // --- Frontend export ---

    /** Starts the export: the ROMs folder pick, then the optional ES-DE one, then the files. */
    public void frontendExport(@NonNull ComputerDetails computer) {
        if (new FrontendExporter(activity, computer).loadApps().isEmpty()) {
            toast(R.string.frontend_export_no_apps, Toast.LENGTH_LONG);
            return;
        }
        exportComputer = computer;
        exportRomsTree = null;
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.frontend_export_roms_title)
                .setMessage(R.string.frontend_export_roms_message)
                .setPositiveButton(R.string.frontend_export_pick, (dialog, which) -> pickFolder(pickRomsFolder))
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> exportComputer = null)
                .setOnCancelListener(dialog -> exportComputer = null)
                .show();
    }

    /** The ROMs folder pick's result; null when the user backed out. */
    public void onRomsFolderPicked(@Nullable Uri romsTree) {
        if (exportComputer == null) {
            return;
        }
        if (romsTree == null) {
            exportComputer = null;
            return;
        }
        exportRomsTree = romsTree;
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.frontend_export_esde_title)
                .setMessage(R.string.frontend_export_esde_message)
                .setPositiveButton(R.string.frontend_export_pick, (dialog, which) -> pickFolder(pickEsdeFolder))
                .setNegativeButton(R.string.frontend_export_skip, (dialog, which) -> onEsdeFolderPicked(null))
                .setOnCancelListener(dialog -> onEsdeFolderPicked(null))
                .show();
    }

    /** The ES-DE folder pick's result, or null to export without ES-DE's find rules. */
    public void onEsdeFolderPicked(@Nullable final Uri esdeTree) {
        final ComputerDetails computer = exportComputer;
        final Uri romsTree = exportRomsTree;
        exportComputer = null;
        exportRomsTree = null;
        if (computer == null || romsTree == null) {
            return;
        }
        toast(R.string.frontend_export_working, Toast.LENGTH_SHORT);
        runOnWorker(() -> {
            String message;
            try {
                FrontendExporter exporter = new FrontendExporter(activity, computer);
                int count = exporter.export(exporter.loadApps(), romsTree, esdeTree);
                message = activity.getResources().getQuantityString(R.plurals.frontend_export_done, count, count);
            } catch (IOException | RuntimeException e) {
                LimeLog.warning("Frontend export failed: " + e);
                message = activity.getString(R.string.frontend_export_failed);
            }
            final String result = message;
            postToMain(() -> Toast.makeText(activity, result, Toast.LENGTH_LONG).show(), null);
        });
    }

    private void pickFolder(ActivityResultLauncher<Uri> launcher) {
        try {
            launcher.launch(null);
        } catch (ActivityNotFoundException e) {
            exportComputer = null;
            toast(R.string.frontend_export_no_picker, Toast.LENGTH_LONG);
        }
    }

    // --- Information ---

    public void details(@NonNull ComputerDetails computer) {
        Dialog.displayDialog(activity, activity.getString(R.string.title_details), computer.toString(), false);
    }

    public void testNetwork() {
        ServerHelper.doNetworkTest(activity);
    }

    /** The host's web config page, guessed from its address. */
    public void openManagementPage(@NonNull HostEntry host) {
        String url = host.guessManagementUrl();
        if (url == null) {
            toast(R.string.pcview_error_no_management_url, Toast.LENGTH_LONG);
        } else {
            HelpLauncher.launchUrl(activity, url);
        }
    }

    public void openGameStreamEolFaq() {
        HelpLauncher.launchGameStreamEolFaq(activity);
    }

    // --- Internals ---

    // The name only shows in the quit toast; "app" is what PcView passed when it had none.
    private static String appName(HostEntry host) {
        return host.runningAppName != null ? host.runningAppName : "app";
    }

    private void toast(@StringRes int message, int length) {
        Toast.makeText(activity, message, length).show();
    }

    /** @return false when the worker no longer takes work (the activity is going away) */
    private boolean runOnWorker(Runnable task) {
        try {
            worker.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    /**
     * Posts a result to main. {@code ui} runs only while the activity is alive; {@code always}
     * runs regardless, for bookkeeping that must not be skipped with it.
     */
    private void postToMain(Runnable ui, @Nullable Runnable always) {
        main.post(() -> {
            if (always != null) {
                always.run();
            }
            if (!activity.isFinishing() && !activity.isDestroyed()) {
                ui.run();
            }
        });
    }
}
