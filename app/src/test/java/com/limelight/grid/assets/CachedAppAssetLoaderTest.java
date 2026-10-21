package com.limelight.grid.assets;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * How a load ends. The loader's pools are real threads here and "main" runs callbacks where they
 * are posted from, so each test waits for the outcome it expects rather than draining a queue.
 * Every test uses its own host uuid: the decoded-cover cache is process-wide.
 */
@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class CachedAppAssetLoaderTest {

    private static final Executor DIRECT = Runnable::run;
    private static final long WAIT_MS = 5_000;

    private Context context;
    private final List<CachedAppAssetLoader> loaders = new ArrayList<>();

    /** The states a request was called back with, in order. */
    private static final class Recorder implements CachedAppAssetLoader.ArtCallback {
        final List<ArtState> states = Collections.synchronizedList(new ArrayList<ArtState>());

        @Override
        public void onArt(android.graphics.Bitmap bitmap, ArtState state) {
            states.add(state);
        }
    }

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
    }

    @After
    public void tearDown() {
        for (CachedAppAssetLoader loader : loaders) {
            loader.close();
        }
    }

    private CachedAppAssetLoader loader(NetworkAssetLoader network, DiskAssetLoader disk) {
        CachedAppAssetLoader loader = new CachedAppAssetLoader(network, new MemoryAssetLoader(),
                disk, DIRECT);
        loaders.add(loader);
        return loader;
    }

    private static CachedAppAssetLoader.LoaderTuple tuple(String uuid, boolean withAddress, int appId) {
        ComputerDetails host = new ComputerDetails();
        host.uuid = uuid;
        if (withAddress) {
            host.activeAddress = new ComputerDetails.AddressTuple("10.0.0.1", 47989);
        }
        return new CachedAppAssetLoader.LoaderTuple(host, new NvApp("Game " + appId, null, appId, false));
    }

    private static void waitFor(String what, java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for " + what);
            }
            Thread.sleep(10);
        }
    }

    @Test
    public void aHostWithNoAddressYetLeavesTheTileLoadingNotNoArt() throws InterruptedException {
        // Before the first poll the tuple carries the bare identity: the host cannot be asked,
        // which says nothing about whether the game has a cover.
        CachedAppAssetLoader loader = loader(new NetworkAssetLoader(context, "id"),
                new DiskAssetLoader(context));
        Recorder recorder = new Recorder();

        CachedAppAssetLoader.Request request =
                loader.load(tuple("uuid-no-address", false, 1), 300, recorder);
        waitFor("the request to end", request::isCancelled);

        assertEquals(Collections.singletonList(ArtState.LOADING), recorder.states);
        assertFalse(request.isFinished());
        // A rebind finds nothing to wait on and asks again.
        assertFalse(request.isPending());
    }

    @Test
    public void aHostThatSaysItHasNoCoverIsNoArt() throws InterruptedException {
        NetworkAssetLoader saysNone = new NetworkAssetLoader(context, "id") {
            @Override
            public InputStream getBitmapStream(CachedAppAssetLoader.LoaderTuple tuple)
                    throws FileNotFoundException {
                throw new FileNotFoundException("404");
            }
        };
        CachedAppAssetLoader loader = loader(saysNone, new DiskAssetLoader(context));
        Recorder recorder = new Recorder();

        CachedAppAssetLoader.Request request =
                loader.load(tuple("uuid-says-none", true, 1), 300, recorder);
        waitFor("the outcome", () -> recorder.states.size() == 2);

        assertEquals(ArtState.LOADING, recorder.states.get(0));
        assertEquals(ArtState.NO_ART, recorder.states.get(1));
        assertTrue(request.isFinished());
        assertFalse(request.isPending());
    }

    @Test
    public void theHostsNoneIsRememberedSoARebindDoesNotAskAgain() throws InterruptedException {
        final AtomicInteger asked = new AtomicInteger();
        NetworkAssetLoader saysNone = new NetworkAssetLoader(context, "id") {
            @Override
            public InputStream getBitmapStream(CachedAppAssetLoader.LoaderTuple tuple)
                    throws FileNotFoundException {
                asked.incrementAndGet();
                throw new FileNotFoundException("404");
            }
        };
        CachedAppAssetLoader loader = loader(saysNone, new DiskAssetLoader(context));
        Recorder first = new Recorder();
        loader.load(tuple("uuid-none-remembered", true, 1), 300, first);
        waitFor("the outcome", () -> first.states.size() == 2);

        // The burst of rebinds after covers land: answered at once, the host left alone.
        Recorder again = new Recorder();
        CachedAppAssetLoader.Request request =
                loader.load(tuple("uuid-none-remembered", true, 1), 300, again);
        assertEquals(Collections.singletonList(ArtState.NO_ART), again.states);
        assertTrue(request.isFinished());
        assertEquals(1, asked.get());

        // A removed host's answers go with it.
        loader.clearHost("uuid-none-remembered");
        Recorder afterClear = new Recorder();
        loader.load(tuple("uuid-none-remembered", true, 1), 300, afterClear);
        waitFor("the second outcome", () -> afterClear.states.size() == 2);
        assertEquals(2, asked.get());
    }

    @Test
    public void theShortcutBitmapIsAnIconSizedCopyNotTheHeroCover() {
        MemoryAssetLoader memory = new MemoryAssetLoader();
        CachedAppAssetLoader loader = new CachedAppAssetLoader(new NetworkAssetLoader(context, "id"),
                memory, new DiskAssetLoader(context), DIRECT);
        loaders.add(loader);
        CachedAppAssetLoader.LoaderTuple game = tuple("uuid-shortcut", true, 1);
        assertNull(loader.getBitmap(game));

        // Only the hero has been shown: its cover is all there is, and far too big to send.
        Bitmap hero = Bitmap.createBitmap(1200, 1600, Bitmap.Config.RGB_565);
        memory.populateCache(game, 1200, new ScaledBitmap(600, 800, hero));
        Bitmap icon = loader.getBitmap(game);
        assertNotSame(hero, icon);
        assertEquals(300, icon.getWidth());
        assertEquals(400, icon.getHeight());
        assertEquals(Bitmap.Config.ARGB_8888, icon.getConfig());

        // With a grid-sized cover in memory too, that one is used rather than the hero.
        Bitmap grid = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888);
        memory.populateCache(game, 300, new ScaledBitmap(600, 800, grid));
        icon = loader.getBitmap(game);
        assertNotSame(grid, icon);
        assertEquals(300, icon.getWidth());
        memory.clearHost("uuid-shortcut");
    }

    @Test
    public void drainedAndClosedRequestsAreNoLongerPending() throws InterruptedException {
        // A disk that holds every read until released, so the pool's three workers are busy and
        // a fourth request waits in the queue.
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger reading = new AtomicInteger();
        DiskAssetLoader slowDisk = new DiskAssetLoader(context) {
            @Override
            public ScaledBitmap loadBitmapAtWidth(CachedAppAssetLoader.LoaderTuple tuple, int width) {
                reading.incrementAndGet();
                try {
                    release.await(WAIT_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }
        };
        CachedAppAssetLoader loader = loader(new NetworkAssetLoader(context, "id"), slowDisk);

        List<CachedAppAssetLoader.Request> running = new ArrayList<>();
        for (int appId = 1; appId <= 3; appId++) {
            running.add(loader.load(tuple("uuid-drain", false, appId), 300, new Recorder()));
        }
        waitFor("three reads to start", () -> reading.get() == 3);
        CachedAppAssetLoader.Request queued =
                loader.load(tuple("uuid-drain", false, 4), 300, new Recorder());
        assertTrue(queued.isPending());

        // What the library does when a stream starts. Waiting on the dropped work would leave the
        // tile blank after the stream; ended, it is asked for again on the rebind.
        loader.cancelForegroundLoads();
        assertTrue(queued.isCancelled());
        assertFalse(queued.isPending());
        for (CachedAppAssetLoader.Request request : running) {
            assertTrue(request.isPending());
        }

        // A replaced loader's requests, running ones included, are not worth waiting on either.
        loader.close();
        for (CachedAppAssetLoader.Request request : running) {
            assertFalse(request.isPending());
        }
        release.countDown();
    }
}
