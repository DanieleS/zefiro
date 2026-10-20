package com.limelight.grid.assets;

import android.content.ComponentCallbacks2;
import android.util.LruCache;

import com.limelight.LimeLog;

import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Decoded covers, shared by every loader in the process.
 *
 * <p>The cache is static on purpose: a screen that comes back (the library after a stream, a
 * host switched away from and back to) finds its covers still decoded. It is therefore no
 * screen's to empty when it stops; it shrinks when the system asks ({@link #trimMemory}) and
 * forgets a host when that host is removed ({@link #clearHost}).
 *
 * <p>Entries are keyed {@code uuid:appId:sizeBucket}. The same cover is decoded at different
 * widths for a shelf, the grid and the hero, and a key without the size would hand a hero the
 * thumbnail decoded for a shelf, blurred, or a shelf a hero-sized bitmap it does not need.
 *
 * <p>Thread safe: loaders read and fill it from their worker threads and from main. Every access
 * takes the class lock, which also covers the eviction map that LruCache feeds from inside
 * {@code put}.
 */
public class MemoryAssetLoader {
    private static final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
    private static final LruCache<String, ScaledBitmap> memoryCache = new LruCache<String, ScaledBitmap>(maxMemory / 16) {
        @Override
        protected int sizeOf(String key, ScaledBitmap bitmap) {
            // Sizeof returns kilobytes
            return bitmap.bitmap.getByteCount() / 1024;
        }

        @Override
        protected void entryRemoved(boolean evicted, String key, ScaledBitmap oldValue, ScaledBitmap newValue) {
            super.entryRemoved(evicted, key, oldValue, newValue);

            if (evicted) {
                // Keep a soft reference around to the bitmap as long as we can
                evictionCache.put(key, new SoftReference<>(oldValue));
            }
        }
    };
    private static final HashMap<String, SoftReference<ScaledBitmap>> evictionCache = new HashMap<>();

    private static final Object lock = MemoryAssetLoader.class;

    private static String hostPrefix(String uuid) {
        return uuid + ":";
    }

    static String constructKey(CachedAppAssetLoader.LoaderTuple tuple, int sizeBucket) {
        return hostPrefix(tuple.computer.uuid) + tuple.app.getAppId() + ":" + sizeBucket;
    }

    public ScaledBitmap loadBitmapFromCache(CachedAppAssetLoader.LoaderTuple tuple, int sizeBucket) {
        final String key = constructKey(tuple, sizeBucket);

        synchronized (lock) {
            ScaledBitmap bmp = memoryCache.get(key);
            if (bmp != null) {
                return bmp;
            }

            SoftReference<ScaledBitmap> bmpRef = evictionCache.get(key);
            if (bmpRef != null) {
                bmp = bmpRef.get();
                // Either way the entry leaves the eviction map: back into the LRU cache when the
                // bitmap survived, or dropped as a dangling reference when it did not.
                evictionCache.remove(key);
                if (bmp != null) {
                    LimeLog.info("Eviction cache hit for tuple: " + tuple);
                    memoryCache.put(key, bmp);
                    return bmp;
                }
            }

            return null;
        }
    }

    public void populateCache(CachedAppAssetLoader.LoaderTuple tuple, int sizeBucket, ScaledBitmap bitmap) {
        synchronized (lock) {
            memoryCache.put(constructKey(tuple, sizeBucket), bitmap);
        }
    }

    /**
     * Gives memory back as the system asks. A running app that is short of memory, or one that
     * has left the screen, keeps half; one about to be killed in the background, or critically
     * short, keeps nothing. Anything milder keeps everything: the covers are what makes the
     * library come back instantly.
     */
    public void trimMemory(int level) {
        synchronized (lock) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
                    || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
                clearCache();
            } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                memoryCache.trimToSize(memoryCache.maxSize() / 2);
                // What was trimmed went to the eviction map as soft references, which the
                // collector frees as it needs to; dropping them here would only undo that.
            }
        }
    }

    /** Forgets every cover of one host, at every size; for a host that is removed. */
    public void clearHost(String uuid) {
        if (uuid == null) {
            return;
        }
        final String prefix = hostPrefix(uuid);
        synchronized (lock) {
            List<String> doomed = new ArrayList<>();
            for (String key : memoryCache.snapshot().keySet()) {
                if (key.startsWith(prefix)) {
                    doomed.add(key);
                }
            }
            // remove() is not an eviction, so these do not land in the eviction map.
            for (String key : doomed) {
                memoryCache.remove(key);
            }
            doomed.clear();
            for (String key : evictionCache.keySet()) {
                if (key.startsWith(prefix)) {
                    doomed.add(key);
                }
            }
            for (String key : doomed) {
                evictionCache.remove(key);
            }
        }
    }

    public void clearCache() {
        synchronized (lock) {
            // We must evict first because that will push all items into the eviction cache
            memoryCache.evictAll();
            evictionCache.clear();
        }
    }
}
