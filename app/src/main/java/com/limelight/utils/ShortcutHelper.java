package com.limelight.utils;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import com.limelight.R;
import com.limelight.LimeLog;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * Launcher shortcuts and Android TV channel entries for hosts and games.
 *
 * <p>It works from any {@link Context}, so host shortcuts can be published by state that outlives
 * a screen (the host repository holds one on the application context). The two things that do
 * need a screen take one explicitly: asking the TV launcher to show a channel, which only happens
 * when this helper was made with an Activity, and exporting a launcher file, which takes the
 * Activity as a parameter.
 */
public class ShortcutHelper {

    private final ShortcutManager sm;
    private final Context context;
    private final TvChannelHelper tvChannelHelper;

    public static final int REQUEST_CODE_EXPORT_ART_FILE = 778; // Unique request code
    public static String artFileContentToExport;

    public static final String KEY_HOST_UUID = "host_uuid";
    public static final String KEY_HOST_NAME = "host_name";
    public static final String KEY_APP_UUID = "app_uuid";
    public static final String KEY_APP_NAME = "app_name";
    public static final String KEY_APP_ID = "app_id";
    public static final String KEY_GENERATED_BY = "generated_by";

    public ShortcutHelper(Context context) {
        this.context = context;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            sm = context.getSystemService(ShortcutManager.class);
        }
        else {
            sm = null;
        }
        this.tvChannelHelper = new TvChannelHelper(context);
    }

    @TargetApi(Build.VERSION_CODES.N_MR1)
    private void reapShortcutsForDynamicAdd() {
        List<ShortcutInfo> dynamicShortcuts = sm.getDynamicShortcuts();
        while (!dynamicShortcuts.isEmpty() && dynamicShortcuts.size() >= sm.getMaxShortcutCountPerActivity()) {
            ShortcutInfo maxRankShortcut = dynamicShortcuts.get(0);
            for (ShortcutInfo scut : dynamicShortcuts) {
                if (maxRankShortcut.getRank() < scut.getRank()) {
                    maxRankShortcut = scut;
                }
            }
            sm.removeDynamicShortcuts(Collections.singletonList(maxRankShortcut.getId()));
        }
    }

    @TargetApi(Build.VERSION_CODES.N_MR1)
    private List<ShortcutInfo> getAllShortcuts() {
        LinkedList<ShortcutInfo> list = new LinkedList<>();
        list.addAll(sm.getDynamicShortcuts());
        list.addAll(sm.getPinnedShortcuts());
        return list;
    }

    @TargetApi(Build.VERSION_CODES.N_MR1)
    private ShortcutInfo getInfoForId(String id) {
        List<ShortcutInfo> shortcuts = getAllShortcuts();

        for (ShortcutInfo info : shortcuts) {
            if (info.getId().equals(id)) {
                return info;
            }
        }

        return null;
    }

    @TargetApi(Build.VERSION_CODES.N_MR1)
    private boolean isExistingDynamicShortcut(String id) {
        for (ShortcutInfo si : sm.getDynamicShortcuts()) {
            if (si.getId().equals(id)) {
                return true;
            }
        }

        return false;
    }

    public void reportComputerShortcutUsed(ComputerDetails computer) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            if (getInfoForId(computer.uuid) != null) {
                sm.reportShortcutUsed(computer.uuid);
            }
        }
    }

    public void reportGameLaunched(ComputerDetails computer, NvApp app) {
        tvChannelHelper.createTvChannel(computer);
        tvChannelHelper.addGameToChannel(computer, app);
    }

    public void createAppViewShortcut(ComputerDetails computer, boolean forceAdd, boolean newlyPaired) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            ShortcutInfo sinfo = new ShortcutInfo.Builder(context, computer.uuid)
                    .setIntent(ServerHelper.createPcShortcutIntent(context, computer))
                    .setShortLabel(computer.name)
                    .setLongLabel(computer.name)
                    .setIcon(Icon.createWithResource(context, R.mipmap.ic_pc_scut))
                    .build();

            ShortcutInfo existingSinfo = getInfoForId(computer.uuid);
            if (existingSinfo != null) {
                // Update in place
                sm.updateShortcuts(Collections.singletonList(sinfo));
                sm.enableShortcuts(Collections.singletonList(computer.uuid));
            }

            // Reap shortcuts to make space for this if it's new
            // NOTE: This CAN'T be an else on the above if, because it's
            // possible that we have an existing shortcut but it's not a dynamic one.
            if (!isExistingDynamicShortcut(computer.uuid)) {
                // To avoid a random carousel of shortcuts popping in and out based on polling status,
                // we only add shortcuts if it's not at the limit or the user made a conscious action
                // to interact with this PC.

                if (forceAdd) {
                    // This should free an entry for us to add one below
                    reapShortcutsForDynamicAdd();
                }

                // We still need to check the maximum shortcut count even after reaping,
                // because there's a possibility that it could be zero.
                if (sm.getDynamicShortcuts().size() < sm.getMaxShortcutCountPerActivity()) {
                    // Add a shortcut if there is room
                    sm.addDynamicShortcuts(Collections.singletonList(sinfo));
                }
            }
        }

        if (newlyPaired) {
            // Avoid hammering the channel API for each computer poll because it will throttle us
            tvChannelHelper.createTvChannel(computer);
            tvChannelHelper.requestChannelOnHomeScreen(computer);
        }
    }

    public void createAppViewShortcutForOnlineHost(ComputerDetails details) {
        createAppViewShortcut(details, false, false);
    }

    /**
     * Publishes or refreshes a polled host's shortcut, without pushing another one out for it,
     * and says whether it took.
     *
     * <p>The check costs two more ShortcutManager IPCs, which is why it is not folded into
     * {@link #createAppViewShortcutForOnlineHost}: {@code PcView} calls that on every poll and
     * ignores the answer, while the publisher calls this once per host and needs it.
     *
     * @return whether the host has a shortcut afterwards. False when every dynamic slot was
     *         taken, so a caller that publishes once can try again later. Always true below
     *         Android 7.1, which has no launcher shortcuts to wait for.
     */
    public boolean publishAppViewShortcut(ComputerDetails details) {
        createAppViewShortcut(details, false, false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            return getInfoForId(details.uuid) != null;
        }
        return true;
    }

    private String getShortcutIdForGame(ComputerDetails computer, NvApp app) {
        return computer.uuid + app.getAppId();
    }

    @TargetApi(Build.VERSION_CODES.O)
    public boolean createPinnedGameShortcut(ComputerDetails computer, NvApp app, Bitmap iconBits) {
        if (sm.isRequestPinShortcutSupported()) {
            Icon appIcon;

            if (iconBits != null) {
                appIcon = Icon.createWithAdaptiveBitmap(iconBits);
            } else {
                appIcon = Icon.createWithResource(context, R.mipmap.ic_pc_scut);
            }

            ShortcutInfo sInfo = new ShortcutInfo.Builder(context, getShortcutIdForGame(computer, app))
                .setIntent(ServerHelper.createAppShortcutIntent(context, computer, app))
                .setShortLabel(app.getAppName() + " (" + computer.name + ")")
                .setIcon(appIcon)
                .build();

            return sm.requestPinShortcut(sInfo, null);
        } else {
            return false;
        }
    }

    public void disableComputerShortcut(ComputerDetails computer, CharSequence reason) {
        tvChannelHelper.deleteChannel(computer);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            // Delete the computer shortcut itself
            if (getInfoForId(computer.uuid) != null) {
                sm.disableShortcuts(Collections.singletonList(computer.uuid), reason);
            }

            // Delete all associated app shortcuts too
            List<ShortcutInfo> shortcuts = getAllShortcuts();
            LinkedList<String> appShortcutIds = new LinkedList<>();
            for (ShortcutInfo info : shortcuts) {
                if (info.getId().startsWith(computer.uuid)) {
                    appShortcutIds.add(info.getId());
                }
            }
            sm.disableShortcuts(appShortcutIds, reason);
        }
    }

    public void disableAppShortcut(ComputerDetails computer, NvApp app, CharSequence reason) {
        tvChannelHelper.deleteProgram(computer, app);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            String id = getShortcutIdForGame(computer, app);
            if (getInfoForId(id) != null) {
                sm.disableShortcuts(Collections.singletonList(id), reason);
            }
        }
    }

    public void enableAppShortcut(ComputerDetails computer, NvApp app) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            String id = getShortcutIdForGame(computer, app);
            if (getInfoForId(id) != null) {
                sm.enableShortcuts(Collections.singletonList(id));
            }
        }
    }

    /**
     * The body of a launcher file: the keys {@link com.limelight.ShortcutTrampoline} reads back
     * when a frontend hands us one. All three app keys are written when known, since they are not
     * interchangeable downstream — the connection scene finds a cover by App ID, the companion
     * panel keys metadata by App UUID.
     *
     * @param generatedBy stamped into the file when non-null, marking it as ours to manage. Files
     *                    without the stamp were put there by hand and are never touched.
     */
    public static String buildLauncherFileContent(ComputerDetails computer, NvApp app, String generatedBy) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Artemis app entry\n");
        sb.append("# Generated by Artemis for Android\n\n");

        if (generatedBy != null) {
            sb.append("[").append(KEY_GENERATED_BY).append("] ").append(generatedBy).append("\n");
        }

        sb.append("[").append(KEY_HOST_UUID).append("] ").append(computer.uuid).append("\n");
        sb.append("[").append(KEY_HOST_NAME).append("] ").append(computer.name).append("\n");

        if (app.getAppUUID() != null && !app.getAppUUID().isEmpty()) {
            sb.append("[").append(KEY_APP_UUID).append("] ").append(app.getAppUUID()).append("\n");
        }
        if (app.getAppId() > 0) {
            sb.append("[").append(KEY_APP_ID).append("] ").append(app.getAppId()).append("\n");
        }
        // Trimmed: a host title can carry stray whitespace, which only ever gets in the way of
        // matching the entry back to the game.
        String appName = app.getAppName() != null ? app.getAppName().trim() : "";
        if (!appName.isEmpty()) {
            sb.append("[").append(KEY_APP_NAME).append("] ").append(appName).append("\n");
        }

        return sb.toString();
    }

    /**
     * The body of the {@code .art} file for a game, or null when the host or the game lacks what
     * the file needs (the host's UUID and name, the game's name and UUID). On null the user has
     * already been told why, with a toast.
     *
     * <p>Unlike {@link #exportLauncherFile}, nothing is parked in {@link #artFileContentToExport}:
     * the caller keeps the content with the document request it makes, so two exports in flight
     * can never write each other's file.
     */
    public String buildArtFileContent(ComputerDetails computer, NvApp app) {
        if (computer == null || computer.uuid == null || computer.uuid.isEmpty() ||
            computer.name == null || computer.name.isEmpty()) {
            Toast.makeText(context, R.string.export_launcher_computer_details_incomplete, Toast.LENGTH_LONG).show();
            LimeLog.warning("exportLauncherFile: Computer details incomplete.");
            return null;
        }

        if (app == null || app.getAppName() == null || app.getAppName().isEmpty() || app.getAppUUID() == null || app.getAppUUID().isEmpty()) {
            Toast.makeText(context, R.string.export_launcher_app_details_incomplete, Toast.LENGTH_LONG).show();
            LimeLog.warning("exportLauncherFile: App details incomplete.");
            return null;
        }

        return buildLauncherFileContent(computer, app, null);
    }

    /** The name a game's {@code .art} file is offered under in the save dialog. */
    public static String artFileName(NvApp app) {
        return app.getAppName().trim() + ".art";
    }

    /**
     * Asks the user where to save a launcher file for this game. The result comes back to
     * {@code activity}'s {@code onActivityResult} under {@link #REQUEST_CODE_EXPORT_ART_FILE},
     * which must hand it to {@link #writeArtFileToUri}. The legacy screens' path; the main screen
     * uses {@link #buildArtFileContent} and {@link #writeArtFile} through the result API.
     */
    public void exportLauncherFile(Activity activity, ComputerDetails computer, NvApp app) {
        String content = new ShortcutHelper(activity).buildArtFileContent(computer, app);
        if (content == null) {
            return;
        }

        artFileContentToExport = content;

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // Using "application/octet-stream" for generic binary data
        // Since .art is a custom format, octet-stream is safer, or a custom MIME type could be defined.
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, artFileName(app));

        try {
            activity.startActivityForResult(intent, REQUEST_CODE_EXPORT_ART_FILE);
        } catch (Exception e) {
            LimeLog.severe("Failed to start activity for file export: " + e.getMessage());
            Toast.makeText(activity, activity.getString(R.string.failed_to_initiate_file_export, e.getMessage()), Toast.LENGTH_LONG).show();
            artFileContentToExport = null; // Clear content if we can't even start the activity
        }
    }

    public static void writeArtFileToUri(Activity activityContext, Uri uri) {
        try {
            new ShortcutHelper(activityContext).writeArtFile(uri, artFileContentToExport);
        } finally {
            artFileContentToExport = null; // Clear content after attempt
        }
    }

    /**
     * Writes a launcher file's content to the document the user picked, and toasts the outcome.
     * A null {@code uri} is a pick that came back empty; a null or empty {@code content} means
     * the content was lost on the way (a process death between the request and the pick).
     * Main thread: the file is a few hundred bytes.
     */
    public void writeArtFile(Uri uri, String content) {
        if (uri == null) {
            LimeLog.warning("writeArtFileToUri: URI is null.");
            Toast.makeText(context, R.string.file_export_failed_no_location_selected, Toast.LENGTH_LONG).show();
            return;
        }

        if (content == null || content.isEmpty()) {
            LimeLog.warning("writeArtFileToUri: No content to export.");
            // Potentially the content was cleared due to an earlier error or state loss.
            // Or this method was called inappropriately.
            Toast.makeText(context, R.string.file_export_failed_no_content_to_write, Toast.LENGTH_LONG).show();
            return;
        }

        try (OutputStream outputStream = context.getContentResolver().openOutputStream(uri)) {
            if (outputStream != null) {
                outputStream.write(content.getBytes());
                outputStream.flush(); // Ensure all data is written
                LimeLog.info("Successfully wrote .art file to: " + uri.toString());
                Toast.makeText(context, R.string.file_exported_successfully, Toast.LENGTH_SHORT).show();
            } else {
                LimeLog.severe("Failed to open output stream for URI: " + uri.toString());
                Toast.makeText(context, R.string.failed_to_open_file_for_writing, Toast.LENGTH_LONG).show();
            }
        } catch (IOException e) {
            LimeLog.severe("Error writing .art file to URI: " + uri.toString() + " - " + e.getMessage());
            Toast.makeText(context, context.getString(R.string.error_writing_file, e.getMessage()), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            LimeLog.severe("Unexpected error writing .art file to URI: " + uri.toString() + " - " + e.getMessage());
            Toast.makeText(context, R.string.unexpected_error_during_file_export, Toast.LENGTH_LONG).show();
        }
    }
}
