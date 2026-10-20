package com.limelight.grid.assets;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Game covers, from memory, then the disk cache, then the host.
 *
 * <h3>The load</h3>
 *
 * <pre>
 *   load(tuple, width, cb)
 *     memory hit ─────────────────────────────▶ cb(bitmap, ART | NO_ART)   at once, on the caller
 *     host said none before ──────────────────▶ cb(null, NO_ART)           at once, on the caller
 *     miss ─▶ disk ─ hit ─────────────────────▶ cb(bitmap, ART | NO_ART)   on main
 *              └─ miss ─▶ cb(null, LOADING) ─▶ host ─ a cover ─────▶ cb(bitmap, ART | NO_ART)
 *                                                  ├─ says none ───▶ cb(null, NO_ART)
 *                                                  └─ no answer ───▶ nothing more: LOADING stays
 * </pre>
 *
 * NO_ART is final, so it is only ever the host's own answer: a 404, a file that will not decode,
 * or its stand-in. A host that could not be asked (no address yet, before the first poll; or
 * offline) has said nothing about the cover, and the tile keeps its LOADING card; the request
 * simply ends, so binding the tile again asks again. {@code HostLibrary} makes screens rebind
 * when that is worth it: when covers land on disk, and when the host becomes reachable.
 *
 * Being final, a host's "none" is also remembered, for as long as this loader lives: those
 * rebinds come in bursts, every tile at once, and without it every game the host has no cover
 * for would cost one more request to the host per burst, for the same 404. A new loader (a host
 * switched to again) asks once more, which is when a cover added on the host shows up.
 *
 * A cover is decoded for the width it is shown at, rounded up to a {@link #sizeBucket bucket}
 * so a shelf and a grid of near-equal tiles share one bitmap, and is remembered under that size.
 * Whether it is the game's own art or a stand-in is decided at delivery, against the host's
 * {@link #setPlaceholderFingerprints stand-in fingerprints} as they are then; so once those
 * change, binding the tile again (a memory hit) gives the corrected answer.
 *
 * <h3>Threading</h3>
 *
 * {@link #load} and everything that touches views run on the main thread. Disk and network work
 * runs on this loader's own pools, whose idle threads time out, and {@link #close()} stops them.
 * The tile pools drop their oldest queued work when full, which is what a fast scroll wants:
 * those are the tiles that went off screen. The prefetch pool ({@link #queueCacheLoad}) keeps
 * everything: it is handed a whole library at once, and dropping the oldest there would drop
 * the first hundred games' covers, not stale work. A dropped request never calls back and its tile stays
 * LOADING, title showing, until it is bound again. Every way a request can end without an
 * outcome (dropped, drained by {@link #cancelForegroundLoads}, given up on, its loader closed)
 * leaves it not {@link Request#isPending pending}, so a rebind that finds it asks again rather
 * than waiting on work that is gone.
 */
public class CachedAppAssetLoader {
    private static final int MAX_CONCURRENT_DISK_LOADS = 3;
    private static final int MAX_CONCURRENT_NETWORK_LOADS = 3;
    private static final int MAX_CONCURRENT_CACHE_LOADS = 1;

    // No bound for the prefetch: see the class doc.
    private static final int UNBOUNDED = 0;
    private static final int MAX_PENDING_NETWORK_LOADS = 40;
    private static final int MAX_PENDING_DISK_LOADS = 40;

    // Idle workers go away after this; a library left on screen has nothing to load.
    private static final long WORKER_KEEP_ALIVE_SECONDS = 30;

    // The box art standard every host serves at, and the legacy grid's decode width.
    private static final int STANDARD_ART_WIDTH = 300;

    // Decode widths step by this much. Coarse enough that the shelf, the grid and the hero of
    // one window share a few bitmaps between them; fine enough that none is decoded much
    // larger than it is drawn. It divides the standard 300 px width, so the legacy grid
    // decodes at the size it always did: a step that rounded 300 up to 384 cost it a third
    // more memory per cover, and the shared cache that many fewer covers.
    static final int SIZE_BUCKET_STEP = 100;
    // A cover is at most this wide on any screen this app draws on, a TV hero included.
    static final int MAX_SIZE_BUCKET = 1600;

    // Read by the workers and main; set once, by close().
    private volatile boolean closed;

    private final ThreadPoolExecutor cacheExecutor = newPool(
            MAX_CONCURRENT_CACHE_LOADS, UNBOUNDED);

    private final ThreadPoolExecutor foregroundExecutor = newPool(
            MAX_CONCURRENT_DISK_LOADS, MAX_PENDING_DISK_LOADS);

    private final ThreadPoolExecutor networkExecutor = newPool(
            MAX_CONCURRENT_NETWORK_LOADS, MAX_PENDING_NETWORK_LOADS);

    /** The host of the legacy view API; null for a loader that is only given tuples. */
    private final ComputerDetails computer;
    private final int legacyWidth;
    private final NetworkAssetLoader networkLoader;
    private final MemoryAssetLoader memoryLoader;
    private final DiskAssetLoader diskLoader;
    private final Bitmap noAppImageBitmap;
    private final Executor main;

    private volatile Set<NoArtDetector.ArtFingerprint> placeholders = Collections.emptySet();
    // The games the host said it has no cover for; see the class doc. Filled and read on main,
    // emptied by clearHost from any thread, hence concurrent.
    private final Set<LoaderTuple> hostHasNone =
            Collections.newSetFromMap(new ConcurrentHashMap<LoaderTuple, Boolean>());
    private volatile ArtFetchListener fetchListener;

    /** Receives a cover, on the main thread. */
    public interface ArtCallback {
        /**
         * Called at most twice: with {@code (null, LOADING)} when the cover has to come from the
         * host, then once with the outcome, unless the host could not be asked, in which case
         * the LOADING is the last word. The bitmap of a NO_ART outcome may be the stand-in
         * itself, or null when the host said it has none; a tile draws its title card either way.
         */
        void onArt(Bitmap bitmap, ArtState state);
    }

    /**
     * Told when a cover has just been written to the disk cache from the host, on the worker
     * thread that wrote it: the moment the set of the host's stand-ins may have changed.
     */
    public interface ArtFetchListener {
        void onArtFetched(LoaderTuple tuple);
    }

    /** One pending {@link #load}; cancel it when the view it was for is bound to something else. */
    public static final class Request {
        final CachedAppAssetLoader owner;
        final LoaderTuple tuple;
        final int sizeBucket;
        final ArtCallback callback;
        // Set when no callback will come any more: by the caller, by a pool dropping or
        // draining the request's work, or on main when the host could not be asked. Read by
        // the workers to skip work nobody wants any more, so volatile.
        volatile boolean cancelled;
        // Main thread only.
        boolean finished;

        Request(CachedAppAssetLoader owner, LoaderTuple tuple, int sizeBucket, ArtCallback callback) {
            this.owner = owner;
            this.tuple = tuple;
            this.sizeBucket = sizeBucket;
            this.callback = callback;
        }

        /** No callback comes after this. Any thread. */
        public void cancel() {
            cancelled = true;
        }

        /** Whether it ended without an outcome: cancelled, dropped, or given up on. */
        public boolean isCancelled() {
            return cancelled;
        }

        /** Whether the outcome was delivered (not merely LOADING). Main thread. */
        public boolean isFinished() {
            return finished;
        }

        /**
         * Whether a callback may still come: not finished, not ended, and its loader not closed.
         * A view that finds its last request for the same game still pending can leave it to
         * finish; any other request is to be replaced. Main thread.
         */
        public boolean isPending() {
            return !cancelled && !finished && !owner.closed;
        }
    }

    // A pool's unit of work for one request, so whatever drops it from the queue (the pool when
    // full, a drain, close) can tell the request that nothing will come of it.
    private static final class RequestTask implements Runnable {
        final Request request;
        final Runnable work;

        RequestTask(Request request, Runnable work) {
            this.request = request;
            this.work = work;
        }

        @Override
        public void run() {
            work.run();
        }
    }

    // Discards the oldest queued work for the new, as DiscardOldestPolicy did, but ends the
    // dropped request rather than leaving it pending forever. Unlike that policy it refuses
    // outright once the pool is shut down, so submit() can tell a closed loader.
    private static final RejectedExecutionHandler DROP_OLDEST = (task, pool) -> {
        if (pool.isShutdown()) {
            throw new RejectedExecutionException("closed");
        }
        endDropped(pool.getQueue().poll());
        pool.execute(task);
    };

    private static void endDropped(Runnable dropped) {
        if (dropped instanceof RequestTask) {
            ((RequestTask) dropped).request.cancel();
        }
    }

    /**
     * The legacy grid's loader, bound to one host and one tile size, for
     * {@link #populateImageView}. Results reach the views through the main looper.
     */
    public CachedAppAssetLoader(ComputerDetails computer, double scalingDivider,
                                NetworkAssetLoader networkLoader, MemoryAssetLoader memoryLoader,
                                DiskAssetLoader diskLoader, Bitmap noAppImageBitmap) {
        this.computer = computer;
        this.legacyWidth = (int) Math.round(STANDARD_ART_WIDTH / Math.max(1.0, scalingDivider));
        this.networkLoader = networkLoader;
        this.memoryLoader = memoryLoader;
        this.diskLoader = diskLoader;
        this.noAppImageBitmap = noAppImageBitmap;
        this.main = mainLooperExecutor();
    }

    /**
     * A loader that is handed its host in every {@link LoaderTuple}, for {@link #load}.
     *
     * @param main where callbacks run; the main thread in production
     */
    public CachedAppAssetLoader(NetworkAssetLoader networkLoader, MemoryAssetLoader memoryLoader,
                                DiskAssetLoader diskLoader, Executor main) {
        this.computer = null;
        this.legacyWidth = STANDARD_ART_WIDTH;
        this.networkLoader = networkLoader;
        this.memoryLoader = memoryLoader;
        this.diskLoader = diskLoader;
        this.noAppImageBitmap = null;
        this.main = main != null ? main : mainLooperExecutor();
    }

    private static Executor mainLooperExecutor() {
        final Handler handler = new Handler(Looper.getMainLooper());
        return handler::post;
    }

    // pending is the queue's bound, or UNBOUNDED; an unbounded pool only rejects once closed.
    private static ThreadPoolExecutor newPool(int threads, int pending) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads,
                WORKER_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                pending == UNBOUNDED
                        ? new LinkedBlockingQueue<Runnable>()
                        : new LinkedBlockingQueue<Runnable>(pending),
                DROP_OLDEST);
        // Without this the workers lived forever, and every loader ever made kept its own.
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /**
     * The decode width for a cover shown targetWidthPx wide: rounded up to the next
     * {@link #SIZE_BUCKET_STEP}, within [one step, {@link #MAX_SIZE_BUCKET}].
     */
    public static int sizeBucket(int targetWidthPx) {
        int steps = (Math.max(targetWidthPx, 1) + SIZE_BUCKET_STEP - 1) / SIZE_BUCKET_STEP;
        return Math.min(steps * SIZE_BUCKET_STEP, MAX_SIZE_BUCKET);
    }

    /**
     * The host's stand-in covers, as {@link NoArtDetector#placeholderFingerprints} found them.
     * Covers delivered from now on are judged against these. Main thread.
     */
    public void setPlaceholderFingerprints(Set<NoArtDetector.ArtFingerprint> fingerprints) {
        placeholders = fingerprints != null
                ? fingerprints : Collections.<NoArtDetector.ArtFingerprint>emptySet();
    }

    public void setArtFetchListener(ArtFetchListener listener) {
        this.fetchListener = listener;
    }

    public void cancelBackgroundLoads() {
        drain(cacheExecutor);
    }

    /**
     * Drops the queued tile loads. Their requests end, so the tiles they were for load again
     * when bound again (after a stream, say) instead of waiting on work that was thrown away.
     * Loads already running finish as usual.
     */
    public void cancelForegroundLoads() {
        drain(foregroundExecutor);
        drain(networkExecutor);
    }

    private static void drain(ThreadPoolExecutor pool) {
        Runnable r;
        while ((r = pool.getQueue().poll()) != null) {
            endDropped(r);
        }
    }

    /** Forgets one host's decoded covers, and its answers of no cover, for a host that is removed. */
    public void clearHost(String uuid) {
        memoryLoader.clearHost(uuid);
        if (uuid == null) {
            return;
        }
        for (Iterator<LoaderTuple> it = hostHasNone.iterator(); it.hasNext(); ) {
            if (uuid.equals(it.next().computer.uuid)) {
                it.remove();
            }
        }
    }

    /**
     * Stops the pools: queued work is dropped, running work is interrupted, and no callback
     * arrives for either. Loads asked for afterwards never call back, and no request of this
     * loader is {@link Request#isPending pending} any more. The memory cache stays, as it is
     * shared. Main thread.
     */
    public void close() {
        closed = true;
        hostHasNone.clear();
        for (ThreadPoolExecutor pool : new ThreadPoolExecutor[] {
                cacheExecutor, foregroundExecutor, networkExecutor}) {
            for (Runnable dropped : pool.shutdownNow()) {
                endDropped(dropped);
            }
        }
    }

    /**
     * Loads a cover for a view targetWidthPx wide. Main thread. A cover already in memory is
     * delivered before this returns, so a rebind does not flicker through LOADING.
     *
     * @return the request, to {@link Request#cancel} when the view moves on to another game
     */
    public Request load(LoaderTuple tuple, int targetWidthPx, ArtCallback callback) {
        final Request request = new Request(this, tuple, sizeBucket(targetWidthPx), callback);
        if (closed) {
            request.cancel();
            return request;
        }

        ScaledBitmap cached = memoryLoader.loadBitmapFromCache(tuple, request.sizeBucket);
        if (cached != null) {
            request.finished = true;
            callback.onArt(cached.bitmap, stateOf(cached));
            return request;
        }
        if (hostHasNone.contains(tuple)) {
            // The host's answer already, and a final one: no disk read, no request to the host.
            request.finished = true;
            callback.onArt(null, ArtState.NO_ART);
            return request;
        }

        if (!submit(foregroundExecutor, new RequestTask(request, () -> loadFromDisk(request)))) {
            // Closed: nothing will ever answer, so say so rather than leave a live-looking request.
            request.cancel();
        }
        return request;
    }

    /**
     * A game's cover from memory, sized for an icon: a software ARGB_8888 copy at most
     * {@link #STANDARD_ART_WIDTH} wide, or null when no cover is in memory or it is a stand-in.
     * Never reads the disk, so it is safe on main; meant for pinning a shortcut, which falls back
     * to the app icon on null.
     *
     * <p>Not the cached bitmap itself: the icon crosses binder to the launcher, whose
     * transactions top out at 1 MB, and a hero-sized cover is several times that. A cover
     * decoded on O and later may also be a HARDWARE bitmap, which not every release can send.
     * So the smallest cover at least the standard width is preferred, the largest smaller one
     * taken otherwise, and the result scaled down and copied.
     */
    public Bitmap getBitmap(LoaderTuple tuple) {
        ScaledBitmap cached = null;
        for (int bucket = sizeBucket(STANDARD_ART_WIDTH); cached == null && bucket <= MAX_SIZE_BUCKET;
                bucket += SIZE_BUCKET_STEP) {
            cached = memoryLoader.loadBitmapFromCache(tuple, bucket);
        }
        for (int bucket = sizeBucket(STANDARD_ART_WIDTH) - SIZE_BUCKET_STEP;
                cached == null && bucket >= SIZE_BUCKET_STEP; bucket -= SIZE_BUCKET_STEP) {
            cached = memoryLoader.loadBitmapFromCache(tuple, bucket);
        }
        if (cached == null || stateOf(cached) != ArtState.ART) {
            return null;
        }
        return iconCopy(cached.bitmap);
    }

    // A parcelable copy, never the shared bitmap the cache and the tiles still hold.
    private static Bitmap iconCopy(Bitmap cover) {
        // Copying first reads a HARDWARE bitmap back into memory, which scaling one needs.
        Bitmap software = cover.copy(Bitmap.Config.ARGB_8888, false);
        if (software == null || software.getWidth() <= STANDARD_ART_WIDTH) {
            return software;
        }
        int height = Math.max(1, Math.round(
                software.getHeight() * (float) STANDARD_ART_WIDTH / software.getWidth()));
        Bitmap scaled = Bitmap.createScaledBitmap(software, STANDARD_ART_WIDTH, height, true);
        if (scaled != software) {
            software.recycle();
        }
        return scaled;
    }

    /** Fetches a cover into the disk cache ahead of any view asking for it. */
    public void queueCacheLoad(final LoaderTuple tuple) {
        submit(cacheExecutor, () -> {
            // Check if the image is cached on disk
            if (diskLoader.checkCacheExists(tuple)) {
                return;
            }

            // Try to load the asset from the network and cache result on disk
            fetchFromHost(tuple, null);
        });
    }

    public void queueCacheLoad(NvApp app) {
        queueCacheLoad(new LoaderTuple(computer, app));
    }

    // --- Internals ---

    private ArtState stateOf(ScaledBitmap bitmap) {
        return NoArtDetector.stateOf(bitmap, bitmap != null ? bitmap.fingerprint : null, placeholders);
    }

    private static boolean submit(ThreadPoolExecutor pool, Runnable task) {
        try {
            pool.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            // Closed; DROP_OLDEST handles a full queue without throwing.
            endDropped(task);
            return false;
        }
    }

    // Foreground worker.
    private void loadFromDisk(final Request request) {
        if (request.cancelled) {
            return;
        }
        ScaledBitmap bmp = decodeFromDisk(request.tuple, request.sizeBucket);
        if (bmp != null) {
            deliver(request, new Fetch(bmp, true));
            return;
        }

        // Nothing on disk: say the cover is on its way, then ask the host for it.
        main.execute(() -> {
            if (!request.cancelled && !closed) {
                request.callback.onArt(null, ArtState.LOADING);
            }
        });
        submit(networkExecutor, new RequestTask(request, () -> {
            if (!request.cancelled) {
                deliver(request, fetchFromHost(request.tuple, request));
            }
        }));
    }

    // Worker thread. The fingerprint is taken here, from the file the bitmap was just decoded
    // from, so every cover in memory carries one and can be judged without the disk again.
    private ScaledBitmap decodeFromDisk(LoaderTuple tuple, int sizeBucket) {
        ScaledBitmap bmp = diskLoader.loadBitmapAtWidth(tuple, sizeBucket);
        if (bmp != null) {
            bmp.fingerprint = NoArtDetector.fingerprint(
                    diskLoader.getFile(tuple.computer.uuid, tuple.app.getAppId()));
            memoryLoader.populateCache(tuple, sizeBucket, bmp);
        }
        return bmp;
    }

    /** What asking the host for a cover came to. */
    private static final class Fetch {
        // Nobody to ask, or nobody answered: says nothing about the cover.
        static final Fetch NO_ANSWER = new Fetch(null, false);
        // The host answered, with no cover (a 404) or none that decodes.
        static final Fetch NONE = new Fetch(null, true);

        final ScaledBitmap bitmap;
        final boolean hostAnswered;

        Fetch(ScaledBitmap bitmap, boolean hostAnswered) {
            this.bitmap = bitmap;
            this.hostAnswered = hostAnswered;
        }
    }

    // Worker thread.
    private void deliver(final Request request, final Fetch fetch) {
        main.execute(() -> {
            if (request.cancelled || closed) {
                return;
            }
            if (fetch.bitmap != null) {
                request.finished = true;
                request.callback.onArt(fetch.bitmap.bitmap, stateOf(fetch.bitmap));
            } else if (fetch.hostAnswered) {
                request.finished = true;
                hostHasNone.add(request.tuple);
                request.callback.onArt(null, ArtState.NO_ART);
            } else {
                // Not NO_ART, which would be final: the tile keeps the LOADING card it was given,
                // and the request ends so that the next bind asks again.
                request.cancel();
            }
        });
    }

    // Worker thread. With a request, decodes what arrives for it; without, only fills the disk.
    private Fetch fetchFromHost(LoaderTuple tuple, Request request) {
        // A host with no address yet (a library shown from its cache before the first poll)
        // cannot answer; retrying would only hold a worker for three seconds.
        if (tuple.computer.activeAddress == null) {
            return Fetch.NO_ANSWER;
        }

        boolean hostAnswered = false;

        // Try 3 times
        for (int i = 0; i < 3; i++) {
            // Check again whether we've been cancelled
            if (request != null && request.cancelled) {
                return Fetch.NO_ANSWER;
            }

            InputStream in;
            try {
                in = networkLoader.getBitmapStream(tuple);
            } catch (FileNotFoundException e) {
                // The host's answer, not a failure to reach it: asking again changes nothing.
                return Fetch.NONE;
            }
            if (in != null) {
                hostAnswered = true;
                // Write the stream straight to disk
                diskLoader.populateCacheWithStream(tuple, in);

                // Close the network input stream
                try {
                    in.close();
                } catch (IOException ignored) {}

                if (diskLoader.checkCacheExists(tuple)) {
                    // A cover after all (the prefetch got one where a tile's request had not):
                    // the earlier "none" no longer holds.
                    hostHasNone.remove(tuple);
                    ArtFetchListener listener = fetchListener;
                    if (listener != null) {
                        listener.onArtFetched(tuple);
                    }
                }

                // If there's a request waiting on this load, we should return the bitmap
                if (request != null) {
                    // If the cached bitmap is valid, return it. Otherwise, we'll try the load again
                    ScaledBitmap bmp = decodeFromDisk(tuple, request.sizeBucket);
                    if (bmp != null) {
                        return new Fetch(bmp, true);
                    }
                }
                else {
                    // Otherwise it's a background load and we return nothing
                    return Fetch.NONE;
                }
            }

            // Wait 1 second with a bit of fuzz
            try {
                Thread.sleep((int) (1000 + (Math.random() * 500)));
            } catch (InterruptedException e) {
                // InterruptedException clears the thread's interrupt status. Since we can't
                // handle that here, we will re-interrupt the thread to set the interrupt
                // status back to true.
                Thread.currentThread().interrupt();

                return Fetch.NO_ANSWER;
            }
        }

        // Three answers that would not decode are the host's answer too; three silences are not.
        return hostAnswered ? Fetch.NONE : Fetch.NO_ANSWER;
    }

    /**
     * The legacy grid's binding, on top of {@link #load}: the cover into imgView, and the title
     * in textView whenever there is no real cover. Main thread. Kept for {@code AppGridAdapter}
     * until the new library replaces it.
     *
     * @return whether the cover was shown at once, from memory
     */
    public boolean populateImageView(NvApp app, final ImageView imgView, final TextView textView) {
        final LoaderTuple tuple = new LoaderTuple(computer, app);

        // If there's already a request in progress for this view, cancel it. If this loader is
        // still loading the same image for it, we return and let that load finish. A request
        // that was dropped, drained (the grid stopped for a stream) or made by a replaced
        // loader is not pending, and waiting on it would leave the cell blank for good.
        Object previous = imgView.getTag(R.id.artRequest);
        if (previous instanceof Request) {
            Request old = (Request) previous;
            if (old.owner == this && old.isPending() && old.tuple.equals(tuple)) {
                return true;
            }
            old.cancel();
        }

        // Always set the name text so we have it if needed later
        textView.setText(app.getAppName());

        // Hidden until there is something to show; a memory hit shows it before this returns.
        textView.setVisibility(View.INVISIBLE);
        imgView.setVisibility(View.INVISIBLE);
        imgView.setImageDrawable(null);

        final boolean[] synchronous = {true};
        Request request = load(tuple, legacyWidth, (bitmap, state) -> {
            if (synchronous[0]) {
                // Show the bitmap immediately. The host's remembered "none" answers at once with
                // no bitmap: the same app image placeholder the first bind faded in, or the cell
                // would lose it on every rebind.
                imgView.setVisibility(View.VISIBLE);
                imgView.setImageBitmap(bitmap != null ? bitmap : noAppImageBitmap);
                textView.setVisibility(state == ArtState.ART ? View.GONE : View.VISIBLE);
                return;
            }

            if (state == ArtState.LOADING || bitmap == null) {
                // On its way from the host, or the host had none: the app image placeholder,
                // titled. Only faded in once, not again when the host gives up.
                if (imgView.getVisibility() != View.VISIBLE) {
                    imgView.setImageBitmap(noAppImageBitmap);
                    imgView.startAnimation(AnimationUtils.loadAnimation(imgView.getContext(), R.anim.boxart_fadein));
                    imgView.setVisibility(View.VISIBLE);
                }
                textView.setVisibility(View.VISIBLE);
                return;
            }

            // Show the text if it's a placeholder
            textView.setVisibility(state == ArtState.ART ? View.GONE : View.VISIBLE);
            showLegacyArt(imgView, bitmap);
        });
        synchronous[0] = false;
        imgView.setTag(R.id.artRequest, request);
        return request.isFinished();
    }

    // Fade in the box art, fading the placeholder out first if one is up.
    private static void showLegacyArt(final ImageView imageView, final Bitmap bitmap) {
        if (imageView.getVisibility() == View.VISIBLE) {
            // Fade out the placeholder first
            Animation fadeOutAnimation = AnimationUtils.loadAnimation(imageView.getContext(), R.anim.boxart_fadeout);
            fadeOutAnimation.setAnimationListener(new Animation.AnimationListener() {
                @Override
                public void onAnimationStart(Animation animation) {}

                @Override
                public void onAnimationEnd(Animation animation) {
                    // Fade in the new box art
                    imageView.setImageBitmap(bitmap);
                    imageView.startAnimation(AnimationUtils.loadAnimation(imageView.getContext(), R.anim.boxart_fadein));
                }

                @Override
                public void onAnimationRepeat(Animation animation) {}
            });
            imageView.startAnimation(fadeOutAnimation);
        }
        else {
            // View is invisible already, so just fade in the new art
            imageView.setImageBitmap(bitmap);
            imageView.startAnimation(AnimationUtils.loadAnimation(imageView.getContext(), R.anim.boxart_fadein));
            imageView.setVisibility(View.VISIBLE);
        }
    }

    public static class LoaderTuple {
        public final ComputerDetails computer;
        public final NvApp app;

        public LoaderTuple(ComputerDetails computer, NvApp app) {
            this.computer = computer;
            this.app = app;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof LoaderTuple)) {
                return false;
            }

            LoaderTuple other = (LoaderTuple) o;
            return computer.uuid.equals(other.computer.uuid) && app.getAppId() == other.app.getAppId();
        }

        @Override
        public int hashCode() {
            return 31 * computer.uuid.hashCode() + app.getAppId();
        }

        @Override
        public String toString() {
            return "("+computer.uuid+", "+app.getAppId()+")";
        }
    }
}
