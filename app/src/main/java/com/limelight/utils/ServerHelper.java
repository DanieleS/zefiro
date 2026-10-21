package com.limelight.utils;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.view.Display;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;

import com.limelight.Game;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.ShortcutTrampoline;
import com.limelight.binding.PlatformBinding;
import com.limelight.computers.ComputerManagerService;
import com.limelight.library.LibraryIntents;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.HostHttpResponseException;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;

import org.xmlpull.v1.XmlPullParserException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.StringReader;
import java.net.UnknownHostException;
import java.security.cert.CertificateEncodingException;
import java.util.ArrayList;
import java.util.List;

public class ServerHelper {
    public static final String CONNECTION_TEST_SERVER = "android.conntest.moonlight-stream.org";

    public static ComputerDetails.AddressTuple getCurrentAddressFromComputer(ComputerDetails computer) throws IOException {
        if (computer.activeAddress == null) {
            throw new IOException("No active address for "+computer.name);
        }
        return computer.activeAddress;
    }

    public static Intent createPcShortcutIntent(Context parent, ComputerDetails computer) {
        Intent i = new Intent(parent, ShortcutTrampoline.class);
        i.putExtra(LibraryIntents.EXTRA_NAME, computer.name);
        i.putExtra(LibraryIntents.EXTRA_UUID, computer.uuid);
        i.setAction(Intent.ACTION_DEFAULT);
        return i;
    }

    public static Intent createAppShortcutIntent(Context parent, ComputerDetails computer, NvApp app) {
        Intent i = new Intent(parent, ShortcutTrampoline.class);
        i.putExtra(LibraryIntents.EXTRA_NAME, computer.name);
        i.putExtra(LibraryIntents.EXTRA_UUID, computer.uuid);
        i.putExtra(Game.EXTRA_APP_NAME, app.getAppName());
        i.putExtra(Game.EXTRA_APP_UUID, app.getAppUUID());
        i.putExtra(Game.EXTRA_APP_ID, ""+app.getAppId());
        i.putExtra(Game.EXTRA_APP_HDR, app.isHdrSupported());
        i.setAction(Intent.ACTION_DEFAULT);
        return i;
    }
    public static Display getActiveDisplay(Context context, PreferenceConfiguration prefs) {
        Display secondary = getSecondaryDisplay(context);
        if (secondary != null && (prefs.enableFullExDisplay)) {
            return secondary;
        } else {
            return ((DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE)).getDisplay(Display.DEFAULT_DISPLAY);
        }
    }

    public static Display getSecondaryDisplay(Context context) {
        DisplayManager displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        Display display = null;
        Display[] displays = displayManager.getDisplays();
        int mainDisplayId = Display.DEFAULT_DISPLAY;
        int secondaryDisplayId = -1;
        for (Display displayVariant : displays) {
            LimeLog.info(displayVariant.toString());
            if (displayVariant.getDisplayId() != mainDisplayId) {
                secondaryDisplayId = displayVariant.getDisplayId();
                break;
            }
        }

        if (secondaryDisplayId != -1) {
            display = displayManager.getDisplay(secondaryDisplayId);
        }
        return display;
    }

    public static Intent createStartIntent(Activity parent, NvApp app, ComputerDetails computer,
                                           ComputerManagerService.ComputerManagerBinder managerBinder,
                                           boolean withVDisplay) {
        return createStartIntent(parent, app, computer, managerBinder.getUniqueId(), withVDisplay);
    }

    /**
     * The same, for a caller that holds the client's unique id rather than the service's binder:
     * the binder was only ever asked for that id. The new main screen reaches the service through
     * {@code HostRepository}, which hands out the id but never the binder.
     */
    public static Intent createStartIntent(Activity parent, NvApp app, ComputerDetails computer,
                                           String uniqueId, boolean withVDisplay) {
        Intent gameIntent = null;
        PreferenceConfiguration prefConfig = PreferenceConfiguration.readPreferences(parent);
        // Try to add secondary DisplayContext if supported and connected
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && prefConfig.enableFullExDisplay && getSecondaryDisplay(parent) != null) {
            Context displayContext = parent.createDisplayContext(getSecondaryDisplay(parent)); // use secondary display
            gameIntent = new Intent(displayContext, Game.class);
            gameIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        if(gameIntent == null) gameIntent = new Intent(parent, Game.class);
        gameIntent.putExtra(Game.EXTRA_HOST, computer.activeAddress.address);
        gameIntent.putExtra(Game.EXTRA_PORT, computer.activeAddress.port);
        gameIntent.putExtra(Game.EXTRA_HTTPS_PORT, computer.httpsPort);
        gameIntent.putExtra(Game.EXTRA_APP_NAME, app.getAppName());
        gameIntent.putExtra(Game.EXTRA_APP_UUID, app.getAppUUID());
        gameIntent.putExtra(Game.EXTRA_APP_ID, app.getAppId());
        gameIntent.putExtra(Game.EXTRA_APP_HDR, app.isHdrSupported());
        gameIntent.putExtra(Game.EXTRA_UNIQUEID, uniqueId);
        gameIntent.putExtra(Game.EXTRA_PC_UUID, computer.uuid);
        gameIntent.putExtra(Game.EXTRA_PC_NAME, computer.name);
        gameIntent.putExtra(Game.EXTRA_VDISPLAY, withVDisplay);
        gameIntent.putExtra(Game.EXTRA_SERVER_COMMANDS, (ArrayList<String>) computer.serverCommands);

        try {
            if (computer.serverCert != null) {
                gameIntent.putExtra(Game.EXTRA_SERVER_CERT, computer.serverCert.getEncoded());
            }
        } catch (CertificateEncodingException e) {
            e.printStackTrace();
        }

        if (prefConfig.enableFullExDisplay) {
            Display secondaryDisplay = getSecondaryDisplay(parent);
            if (secondaryDisplay != null) {
                int secondaryDisplayId = secondaryDisplay.getDisplayId();
                gameIntent.putExtra(Game.EXTRA_DISPLAY_ID, secondaryDisplayId);
                Intent touchpadIntent = new Intent(parent, ExternalDisplayControlActivity.class);
                touchpadIntent.putExtra(ExternalDisplayControlActivity.EXTRA_LAUNCH_INTENT, gameIntent);
                return touchpadIntent;
            }
        }

        return gameIntent;
    }

    /**
     * The name the PC's cached app list gives this game, for a launch that came without one (a
     * PC shortcut rejoining the running game, a link or launcher file naming only the ids), so
     * the stream is titled by its game everywhere it shows; null when the cache has no such app.
     * Reads a small file: called once per stream, as the shortcut path already does.
     */
    @Nullable
    public static String cachedAppName(Context context, @Nullable String pcUuid,
                                       @Nullable String appUuid, int appId) {
        if (pcUuid == null) {
            return null;
        }
        try {
            String raw = CacheHelper.readInputStreamToString(
                    CacheHelper.openCacheFileForInput(context.getCacheDir(), "applist", pcUuid));
            return raw.isEmpty() ? null
                    : appNameIn(NvHTTP.getAppListByReader(new StringReader(raw)), appUuid, appId);
        } catch (IOException | XmlPullParserException e) {
            return null;
        }
    }

    /** The name of the app with this UUID, or else this id, in the list; null for neither. */
    @Nullable
    @VisibleForTesting
    static String appNameIn(List<NvApp> apps, @Nullable String appUuid, int appId) {
        if (appUuid != null && !appUuid.isEmpty()) {
            for (NvApp app : apps) {
                if (appUuid.equalsIgnoreCase(app.getAppUUID()) && app.getAppName() != null) {
                    return app.getAppName();
                }
            }
        }
        if (appId > 0) {
            for (NvApp app : apps) {
                if (app.getAppId() == appId && app.getAppName() != null) {
                    return app.getAppName();
                }
            }
        }
        return null;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public static void doStart(
            Activity parent,
            NvApp app,
            ComputerDetails computer,
            ComputerManagerService.ComputerManagerBinder managerBinder,
            boolean withVDisplay
    ) {
        doStart(parent, app, computer, managerBinder.getUniqueId(), withVDisplay);
    }

    /** {@link #doStart(Activity, NvApp, ComputerDetails, ComputerManagerService.ComputerManagerBinder, boolean)} by unique id. */
    public static void doStart(Activity parent, NvApp app, ComputerDetails computer, String uniqueId,
                               boolean withVDisplay) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(parent, parent.getString(R.string.pair_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }

        // Note the launch before we leave, so the library can offer a "last played" order.
        AppLaunchHistory.recordLaunch(parent, computer, app);

        Intent intent = createStartIntent(parent, app, computer, uniqueId, withVDisplay);
        parent.startActivity(intent);
    }

    public static void doNetworkTest(final Activity parent) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                SpinnerDialog spinnerDialog = SpinnerDialog.displayDialog(parent,
                        parent.getResources().getString(R.string.nettest_title_waiting),
                        parent.getResources().getString(R.string.nettest_text_waiting),
                        false);

                int ret = MoonBridge.testClientConnectivity(CONNECTION_TEST_SERVER, 443, MoonBridge.ML_PORT_FLAG_ALL);
                spinnerDialog.dismiss();

                String dialogSummary;
                if (ret == MoonBridge.ML_TEST_RESULT_INCONCLUSIVE) {
                    dialogSummary = parent.getResources().getString(R.string.nettest_text_inconclusive);
                }
                else if (ret == 0) {
                    dialogSummary = parent.getResources().getString(R.string.nettest_text_success);
                }
                else {
                    dialogSummary = parent.getResources().getString(R.string.nettest_text_failure);
                    dialogSummary += MoonBridge.stringifyPortFlags(ret, "\n");
                }

                Dialog.displayDialog(parent,
                        parent.getResources().getString(R.string.nettest_title_done),
                        dialogSummary,
                        false);
            }
        }).start();
    }

    public static void doQuit(final Activity parent,
                              final NvHTTP httpConn,
                              final String appName,
                              final Runnable onComplete,
                              final Runnable onFail
    ) {
        parent.runOnUiThread(() -> Toast.makeText(parent, parent.getResources().getString(R.string.applist_quit_app) + " " + appName + "...", Toast.LENGTH_SHORT).show());
        new Thread(new Runnable() {
            @Override
            public void run() {
                String message;
                boolean failed = false;
                try {
                    if (httpConn.quitApp()) {
                        message = parent.getResources().getString(R.string.applist_quit_success) + " " + appName;
                    } else {
                        message = parent.getResources().getString(R.string.applist_quit_fail) + " " + appName;
                    }
                } catch (HostHttpResponseException e) {
                    failed = true;
                    if (e.getErrorCode() == 599) {
                        message = "This session wasn't started by this device," +
                                " so it cannot be quit. End streaming on the original " +
                                "device or the PC itself. (Error code: "+e.getErrorCode()+")";
                    }
                    else {
                        message = e.getMessage();
                    }
                } catch (UnknownHostException e) {
                    failed = true;
                    message = parent.getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    failed = true;
                    message = parent.getResources().getString(R.string.error_404);
                } catch (IOException | XmlPullParserException e) {
                    failed = true;
                    message = e.getMessage();
                    e.printStackTrace();
                } finally {
                    if (failed) {
                        if (onFail != null) {
                            onFail.run();
                        }
                    } else {
                        if (onComplete != null) {
                            onComplete.run();
                        }
                    }
                }

                final String toastMessage = message;
                parent.runOnUiThread(() -> Toast.makeText(parent, toastMessage, Toast.LENGTH_LONG).show());
            }
        }).start();

    }

    public static void doQuit(final Activity parent,
                              final ComputerDetails computer,
                              final NvApp app,
                              final ComputerManagerService.ComputerManagerBinder managerBinder,
                              final Runnable onComplete
    ) {
        doQuit(parent, computer, app, managerBinder.getUniqueId(), onComplete);
    }

    /** {@link #doQuit(Activity, ComputerDetails, NvApp, ComputerManagerService.ComputerManagerBinder, Runnable)} by unique id. */
    public static void doQuit(final Activity parent,
                              final ComputerDetails computer,
                              final NvApp app,
                              final String uniqueId,
                              final Runnable onComplete
    ) {
        doQuit(parent, computer, app, uniqueId, onComplete, null);
    }

    /**
     * Ends a Remote Monitor session this client holds, through the host's Disconnect Monitor
     * control ({@link NvHTTP#runSessionControl}): the session runs no game, so the host's cancel
     * does not end it. Says so in the words a quit does, the session standing in for the game it
     * is offered as; {@code name} is that name. Exactly one of the two callbacks runs, on a
     * background thread, as with {@link #doQuit(Activity, ComputerDetails, NvApp, String, Runnable, Runnable)}.
     */
    public static void doDisconnectMonitor(final Activity parent,
                                           final ComputerDetails computer,
                                           final NvApp disconnect,
                                           final String name,
                                           final String uniqueId,
                                           final Runnable onComplete,
                                           final Runnable onFail) {
        parent.runOnUiThread(() -> Toast.makeText(parent, parent.getString(R.string.applist_quit_app) + " " + name + "...", Toast.LENGTH_SHORT).show());
        new Thread(() -> {
            String message;
            boolean failed = false;
            try {
                NvHTTP httpConn = new NvHTTP(getCurrentAddressFromComputer(computer),
                        computer.httpsPort, uniqueId, computer.serverCert,
                        PlatformBinding.getCryptoProvider(parent));
                httpConn.runSessionControl(disconnect);
                message = parent.getString(R.string.applist_quit_success) + " " + name;
            } catch (UnknownHostException e) {
                failed = true;
                message = parent.getString(R.string.error_unknown_host);
            } catch (FileNotFoundException e) {
                failed = true;
                message = parent.getString(R.string.error_404);
            } catch (IOException | XmlPullParserException e) {
                failed = true;
                message = parent.getString(R.string.applist_quit_fail) + " " + name
                        + (e.getMessage() != null ? ": " + e.getMessage() : "");
            }
            if (failed) {
                onFail.run();
            } else {
                onComplete.run();
            }
            final String toastMessage = message;
            parent.runOnUiThread(() -> Toast.makeText(parent, toastMessage, Toast.LENGTH_LONG).show());
        }).start();
    }

    /**
     * The same, also told when the quit fails: the host refused or could not be reached, or no
     * request could even be made (no address). Exactly one of the two runs, on a background
     * thread, except that a failure to make the request runs {@code onFail} on the caller's.
     * A caller that holds something back while the quit is in flight (the library ignores the
     * host meanwhile) needs to hear about a failure as much as a success, or it never lets go.
     */
    public static void doQuit(final Activity parent,
                              final ComputerDetails computer,
                              final NvApp app,
                              final String uniqueId,
                              final Runnable onComplete,
                              final Runnable onFail
    ) {
        try {
            NvHTTP httpConn = new NvHTTP(
                    ServerHelper.getCurrentAddressFromComputer(computer),
                    computer.httpsPort,
                    uniqueId,
                    computer.serverCert,
                    PlatformBinding.getCryptoProvider(parent)
            );
            doQuit(
                    parent,
                    httpConn,
                    app.getAppName(),
                    onComplete,
                    onFail
            );
        } catch (Exception e) {
            e.printStackTrace();

            final String toastMessage = e.getMessage();
            parent.runOnUiThread(() -> Toast.makeText(parent, toastMessage, Toast.LENGTH_LONG).show());
            if (onFail != null) {
                onFail.run();
            }
        }
    }
}
