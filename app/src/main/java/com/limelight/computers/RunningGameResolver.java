package com.limelight.computers;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.limelight.LimeLog;
import com.limelight.binding.PlatformBinding;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager.PairState;
import com.limelight.utils.CacheHelper;
import com.limelight.utils.ServerHelper;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Works out what a host is playing: the game's name, and its hero image.
 *
 * <p>The PC list and the library's "Continue" card both need these, and both used to be private
 * to {@code PcView}. They live here so the two screens share one answer, and one memory of what
 * was already asked, rather than each keeping its own copy of the same logic.
 *
 * <p>Both lookups are remembered per {@code uuid:gameId}: a host is polled every 1.5 s and the
 * game it runs rarely changes, so neither the app list nor the image is worth fetching twice
 * while it runs. A name is remembered for the life of this object, since it never goes stale.
 * An image request is remembered only until the caller drops the image it got, through
 * {@link #forgetBackground}: the caller keeps the bitmap, not this class, so a caller that
 * outlives one game must say so, or the same game played again later would never get its
 * image back. Thread-safe.
 */
public class RunningGameResolver {

    /** Receives a hero image; runs on the executor that fetched it. */
    public interface BackgroundCallback {
        void onBackground(Bitmap background);
    }

    private final Context context;
    private final File cacheDir;

    /** Games named from a host's app list, keyed by host and app id. */
    private final HashMap<String, String> names = new HashMap<>();

    /** Hero images asked of a host and not yet forgotten, keyed the same way. */
    private final HashSet<String> backgroundsRequested = new HashSet<>();

    public RunningGameResolver(Context context) {
        this.context = context.getApplicationContext();
        this.cacheDir = this.context.getCacheDir();
    }

    /** The key both lookups are remembered under; also how a caller tells the game moved on. */
    public static String keyOf(ComputerDetails details) {
        return details.uuid + ":" + details.runningGameId;
    }

    /**
     * The name of the game the host is in, or null: for a host that is idle, for one whose app
     * list we have never seen, and for one whose running game is not in the list we have.
     *
     * <p>A newer Sunshine host names the game in its serverinfo and there is nothing to work out.
     * For any other host the name comes from the app list: the one on the details when the app
     * list poller has filled it in, otherwise the copy cached by the last library visit, as for
     * a launcher shortcut. A host whose library has never been opened, running a game, can only
     * be said to be running something.
     *
     * <p>May read and parse the cached list, so it blocks: keep it off the main thread, and out of
     * the initial report a newly added polling listener gets, which runs on the adding thread
     * (often main) under the service's polling locks. A callback from a polling thread holds no
     * such lock, but a new caller should still only copy there and resolve elsewhere, as the
     * repository does on its executor.
     */
    public String resolveRunningAppName(ComputerDetails details) {
        if (details.runningGameId == 0) {
            return null;
        }

        if (details.runningGameName != null) {
            return details.runningGameName;
        }

        final String key = keyOf(details);
        synchronized (names) {
            String known = names.get(key);
            if (known != null) {
                return known;
            }
        }

        String rawAppList = readAppList(cacheDir, details);
        if (rawAppList == null || rawAppList.isEmpty()) {
            return null;
        }

        try {
            List<NvApp> apps = NvHTTP.getAppListByReader(new StringReader(rawAppList));
            for (NvApp app : apps) {
                if (app.getAppId() == details.runningGameId) {
                    synchronized (names) {
                        names.put(key, app.getAppName());
                    }
                    return app.getAppName();
                }
            }
        } catch (XmlPullParserException | IOException e) {
            // The caller falls back to saying only that a game is running.
            LimeLog.warning("Unable to name the running game: " + e.getMessage());
        }

        return null;
    }

    /**
     * A host's app list as the host sent it: the one on the details when the app list poller has
     * filled it in, otherwise the copy cached by the last library visit; null when there is
     * neither. Shared with the repository's game count, so both read the same list.
     *
     * <p>Reads a file: never on the main thread.
     */
    static String readAppList(File cacheDir, ComputerDetails details) {
        if (details.rawAppList != null) {
            return details.rawAppList;
        }
        try (InputStream in = CacheHelper.openCacheFileForInput(cacheDir, "applist", details.uuid)) {
            return CacheHelper.readInputStreamToString(in);
        } catch (IOException e) {
            // No list for this host yet.
            return null;
        }
    }

    /**
     * Fetches the hero image of the game a host is running, at most once per game until
     * {@link #forgetBackground} says the image is gone.
     *
     * <p>Only newer Sunshine hosts serve one, and only for a game that has one, so a failure is
     * ordinary and silent: the caller keeps whatever it shows instead, and the callback simply
     * never runs. It is a picture, not a status, so a failed fetch is not retried either.
     *
     * <p>The callback runs on {@code executor}, with a bitmap that is never null. By then the
     * host may have moved on to another game; compare {@link #keyOf} before using it.
     *
     * @param details  a snapshot of the host, not the live object a CMS callback hands out.
     * @param uniqueId this client's id, as the host knows it from pairing.
     * @return whether a fetch was started.
     */
    public boolean fetchRunningBackground(final ComputerDetails details, final String uniqueId,
                                          Executor executor, final BackgroundCallback callback) {
        if (details.runningGameId == 0 || details.state != ComputerDetails.State.ONLINE ||
                details.pairState != PairState.PAIRED || uniqueId == null) {
            return false;
        }

        synchronized (backgroundsRequested) {
            if (!backgroundsRequested.add(keyOf(details))) {
                return false;
            }
        }

        executor.execute(() -> {
            Bitmap loaded = null;
            try {
                NvHTTP httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(details),
                        details.httpsPort, uniqueId, details.serverCert,
                        PlatformBinding.getCryptoProvider(context));

                try (InputStream in = httpConn.getBackgroundArt(details.runningGameId)) {
                    // A hero image is made to fill a screen; this one fills a strip of a card.
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = 2;
                    loaded = BitmapFactory.decodeStream(in, null, options);
                }
            } catch (Exception e) {
                LimeLog.info("No hero image for the running game: " + e.getMessage());
            }

            if (loaded != null) {
                callback.onBackground(loaded);
            }
        });
        return true;
    }

    /**
     * Lets the next {@link #fetchRunningBackground} for this key fetch again. Call it when the
     * image fetched under it is dropped, typically because the host left that game: the request
     * is remembered, not the bitmap, so without this the game would stay without its image the
     * next time it runs. A fetch still in flight under the key is not cancelled; its result
     * arrives as usual, and the caller's {@link #keyOf} check discards it if it no longer fits.
     */
    public void forgetBackground(String key) {
        synchronized (backgroundsRequested) {
            backgroundsRequested.remove(key);
        }
    }
}
