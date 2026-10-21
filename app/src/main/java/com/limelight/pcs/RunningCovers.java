package com.limelight.pcs;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.limelight.computers.HostEntry;
import com.limelight.grid.assets.CachedAppAssetLoader;
import com.limelight.grid.assets.DiskAssetLoader;
import com.limelight.grid.assets.ScaledBitmap;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * The cover of the game a PC is in, for the focused PC's page on the companion panel
 * ({@link PcDetailsPage}). What the old PC card did for its leading slot, moved here with the
 * art: the cover is only ever read from the disk cache the Library fills, and only off the main
 * thread. A game the Library has never drawn has none, and the page shows the hero image or the
 * host glyph instead.
 *
 * <pre>
 *   coverFor ──▶ cached? ──yes──▶ the bitmap
 *                  │no, not being read, no recent miss
 *                  ▼
 *          executor: read the disk cache ──▶ hit:  main: cache it, tell the listener
 *                                        └─▶ miss: main: note when, ask again later
 * </pre>
 *
 * <p>A hit is read once: moving across the tiles re-asks for the same covers on every focus and
 * poll, and that is never a read per move. A miss (no cover on disk) is only kept for
 * {@link #MISS_RETRY_MS}: the Library may draw that game later (it was started on the host
 * itself) and fill the disk cache, and the page then picks the cover up on its next ask. A cover
 * evicted from the cache (eight hosts, one game each, is plenty) may be read again. The cache
 * hands out the same bitmap for the same game, which is what lets the page compare its cover by
 * identity.
 *
 * <p>Owned by {@code PcsFragment}, made with its view and {@link #release() released} with it.
 * Main thread only, except the disk read, on its own single-thread executor.
 */
final class RunningCovers {

    /** A cover was read; the page that wanted it can be built again. Main thread. */
    interface Listener {
        void onCoverLoaded();
    }

    private final DiskAssetLoader assetLoader;
    private final Listener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    // How long a miss stands before the disk is read again for that game.
    static final long MISS_RETRY_MS = 15_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    // Every key read (a hit) or being read; a hit leaves with its bitmap's eviction, a miss
    // when its read lands.
    private final Set<String> asked = new HashSet<>();
    // When each miss landed, on the uptime clock.
    private final Map<String, Long> missedAt = new HashMap<>();
    // A handful of hosts, one cover each. This is not the library.
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(8) {
        @Override
        protected void entryRemoved(boolean evicted, String key, Bitmap oldValue,
                                    Bitmap newValue) {
            if (evicted) {
                asked.remove(key);
            }
        }
    };
    private boolean released;

    RunningCovers(@NonNull Context context, @NonNull Listener listener) {
        this.assetLoader = new DiskAssetLoader(context.getApplicationContext());
        this.listener = listener;
    }

    /**
     * The cover of the game {@code entry} is in, when it has been read; null while it is read,
     * when the game has none on disk, or when the host is in no game. The first ask starts the
     * read, and the listener hears when it lands.
     */
    @Nullable
    Bitmap coverFor(@NonNull HostEntry entry) {
        final ComputerDetails details = entry.details;
        final int appId = details.runningGameId;
        if (appId == 0 || details.uuid == null) {
            return null;
        }
        final String key = details.uuid + ":" + appId;
        Bitmap cached = cache.get(key);
        if (cached != null || released) {
            return cached;
        }
        Long missed = missedAt.get(key);
        if (missed != null && SystemClock.uptimeMillis() - missed < MISS_RETRY_MS) {
            return null;
        }
        if (!asked.add(key)) {
            return null;
        }
        missedAt.remove(key);
        try {
            executor.execute(() -> {
                ScaledBitmap loaded = assetLoader.loadBitmapFromCache(
                        new CachedAppAssetLoader.LoaderTuple(details,
                                new NvApp(null, null, appId, false)), 1);
                if (loaded == null || loaded.bitmap == null) {
                    // A miss: not read again for a while, then asked for anew.
                    main.post(() -> {
                        if (released) {
                            return;
                        }
                        asked.remove(key);
                        missedAt.put(key, SystemClock.uptimeMillis());
                    });
                    return;
                }
                final Bitmap bitmap = loaded.bitmap;
                main.post(() -> {
                    if (released) {
                        return;
                    }
                    cache.put(key, bitmap);
                    listener.onCoverLoaded();
                });
            });
        } catch (RejectedExecutionException e) {
            // Released meanwhile: the page is going away with its view.
            asked.remove(key);
        }
        return null;
    }

    /** Stops the reads; a cover read after this is dropped. Main thread. */
    void release() {
        released = true;
        executor.shutdownNow();
        main.removeCallbacksAndMessages(null);
        cache.evictAll();
        asked.clear();
        missedAt.clear();
    }
}
