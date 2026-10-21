package com.limelight.main;

import android.content.ActivityNotFoundException;
import android.net.Uri;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.utils.ShortcutHelper;

/**
 * Saves a game's {@code .art} launcher file where the user picks, for the main screen.
 *
 * <p>The legacy screens ran this through {@code startActivityForResult}, with the file's content
 * parked in a static field of {@link ShortcutHelper} until {@code onActivityResult} came back:
 * any screen's export could overwrite another's, and every screen had to remember to forward
 * the result. Here the activity registers one {@code CreateDocument} contract and the content
 * waits in this object, next to the request it belongs to.
 *
 * <h3>Lifetime</h3>
 *
 * Made in the activity's {@code onCreate}, before it starts, as the result API requires. The
 * content waiting for the pick is held in memory only, as {@code HostActions} holds a frontend
 * export between its two picks: should the process die while the picker is up, the pick comes
 * back to a new instance with nothing to write, and the user is told the export failed.
 *
 * Main thread only.
 */
public final class ArtExport {

    // The .art format is ours and has no registered type; octet-stream is what the legacy
    // export asked for, so the pickers that accepted it then accept it now.
    private static final String MIME_TYPE = "application/octet-stream";

    private final ComponentActivity activity;
    private final ActivityResultLauncher<String> createDocument;

    @Nullable
    private String pendingContent;

    public ArtExport(@NonNull ComponentActivity activity) {
        this.activity = activity;
        this.createDocument = activity.registerForActivityResult(
                new ActivityResultContracts.CreateDocument(MIME_TYPE), this::onDocumentCreated);
    }

    /**
     * Builds the game's launcher file and asks where to save it. When the host or the game lacks
     * what the file needs, the user is told why and nothing is asked.
     */
    public void export(@NonNull ComputerDetails computer, @NonNull NvApp app) {
        String content = new ShortcutHelper(activity).buildArtFileContent(computer, app);
        if (content == null) {
            return;
        }
        pendingContent = content;
        try {
            createDocument.launch(ShortcutHelper.artFileName(app));
        } catch (ActivityNotFoundException e) {
            LimeLog.severe("Failed to start activity for file export: " + e.getMessage());
            Toast.makeText(activity, activity.getString(R.string.failed_to_initiate_file_export,
                    e.getMessage()), Toast.LENGTH_LONG).show();
            pendingContent = null;
        }
    }

    private void onDocumentCreated(@Nullable Uri uri) {
        String content = pendingContent;
        pendingContent = null;
        if (uri == null) {
            // The picker came back without a document: the user backed out.
            Toast.makeText(activity, R.string.file_export_cancelled, Toast.LENGTH_SHORT).show();
            return;
        }
        new ShortcutHelper(activity).writeArtFile(uri, content);
    }
}
