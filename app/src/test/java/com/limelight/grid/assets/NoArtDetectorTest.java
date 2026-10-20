package com.limelight.grid.assets;

import com.limelight.grid.assets.NoArtDetector.ArtFingerprint;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NoArtDetectorTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final ArtFingerprint STAND_IN = new ArtFingerprint(5120, 0xCAFEL);
    private static final ArtFingerprint OWN_ART = new ArtFingerprint(81234, 0xBEEFL);

    // A real cover's original size: neither of GFE's dummy sizes.
    private static ScaledBitmap cover() {
        return new ScaledBitmap(600, 900, null);
    }

    private File file(String name, byte[] bytes) throws IOException {
        File f = folder.newFile(name);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(bytes);
        }
        return f;
    }

    private static byte[] bytes(int length, int seed) {
        byte[] b = new byte[length];
        for (int i = 0; i < length; i++) {
            b[i] = (byte) (i * 31 + seed);
        }
        return b;
    }

    // --- The acceptance case ---

    @Test
    public void twoAppsSharingAFingerprintAreNoArtWhileAUniqueOneIsArt() {
        Map<Integer, ArtFingerprint> byApp = new HashMap<>();
        byApp.put(1, STAND_IN);
        byApp.put(2, STAND_IN);
        byApp.put(3, OWN_ART);

        Set<ArtFingerprint> placeholders = NoArtDetector.placeholderFingerprints(byApp);

        assertEquals(Collections.singleton(STAND_IN), placeholders);
        assertEquals(ArtState.NO_ART, NoArtDetector.stateOf(cover(), byApp.get(1), placeholders));
        assertEquals(ArtState.NO_ART, NoArtDetector.stateOf(cover(), byApp.get(2), placeholders));
        assertEquals(ArtState.ART, NoArtDetector.stateOf(cover(), byApp.get(3), placeholders));
    }

    // --- placeholderFingerprints ---

    @Test
    public void aFingerprintOnlyOneAppHasIsNotAPlaceholder() {
        Map<Integer, ArtFingerprint> byApp = new HashMap<>();
        byApp.put(1, STAND_IN);
        byApp.put(2, OWN_ART);
        assertTrue(NoArtDetector.placeholderFingerprints(byApp).isEmpty());
    }

    @Test
    public void appsWithNoFingerprintCountTowardNothing() {
        Map<Integer, ArtFingerprint> byApp = new HashMap<>();
        byApp.put(1, null);
        byApp.put(2, null);
        byApp.put(3, OWN_ART);
        assertTrue(NoArtDetector.placeholderFingerprints(byApp).isEmpty());
        assertTrue(NoArtDetector.placeholderFingerprints(null).isEmpty());
        assertTrue(NoArtDetector.placeholderFingerprints(
                Collections.<Integer, ArtFingerprint>emptyMap()).isEmpty());
    }

    @Test
    public void everySharedFingerprintIsFound() {
        ArtFingerprint other = new ArtFingerprint(77, 7);
        Map<Integer, ArtFingerprint> byApp = new HashMap<>();
        byApp.put(1, STAND_IN);
        byApp.put(2, STAND_IN);
        byApp.put(3, STAND_IN);
        byApp.put(4, other);
        byApp.put(5, other);
        byApp.put(6, OWN_ART);
        Set<ArtFingerprint> found = NoArtDetector.placeholderFingerprints(byApp);
        assertEquals(2, found.size());
        assertTrue(found.contains(STAND_IN));
        assertTrue(found.contains(other));
    }

    // --- isPlaceholder ---

    @Test
    public void aMissingCoverIsAPlaceholder() {
        assertTrue(NoArtDetector.isPlaceholder(null, OWN_ART, Collections.<ArtFingerprint>emptySet()));
        assertEquals(ArtState.NO_ART, NoArtDetector.stateOf(null, null, null));
    }

    @Test
    public void gfeDummySizesArePlaceholdersWhateverTheirFingerprint() {
        Set<ArtFingerprint> none = Collections.emptySet();
        assertTrue(NoArtDetector.isPlaceholder(new ScaledBitmap(130, 180, null), OWN_ART, none));
        assertTrue(NoArtDetector.isPlaceholder(new ScaledBitmap(628, 888, null), null, none));
        assertFalse(NoArtDetector.isPlaceholder(new ScaledBitmap(130, 888, null), null, none));
    }

    @Test
    public void anUnknownFingerprintProvesNothing() {
        Set<ArtFingerprint> placeholders = Collections.singleton(STAND_IN);
        assertFalse(NoArtDetector.isPlaceholder(cover(), null, placeholders));
        assertFalse(NoArtDetector.isPlaceholder(cover(), STAND_IN, null));
    }

    // --- fingerprint ---

    @Test
    public void identicalFilesHaveTheSameFingerprint() throws IOException {
        ArtFingerprint a = NoArtDetector.fingerprint(file("1.png", bytes(20_000, 3)));
        ArtFingerprint b = NoArtDetector.fingerprint(file("2.png", bytes(20_000, 3)));
        assertNotNull(a);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(20_000, a.length);
    }

    @Test
    public void filesDifferingAtEitherEndDiffer() throws IOException {
        byte[] base = bytes(20_000, 3);
        byte[] head = base.clone();
        head[10] ^= 1;
        byte[] tail = base.clone();
        tail[base.length - 10] ^= 1;

        ArtFingerprint original = NoArtDetector.fingerprint(file("1.png", base));
        assertNotEquals(original, NoArtDetector.fingerprint(file("2.png", head)));
        assertNotEquals(original, NoArtDetector.fingerprint(file("3.png", tail)));
    }

    @Test
    public void filesOfDifferentLengthDifferEvenWithTheSameEnds() throws IOException {
        // Same first and last 4 KB, a different middle length.
        byte[] shorter = new byte[10_000];
        byte[] longer = new byte[12_000];
        assertNotEquals(NoArtDetector.fingerprint(file("1.png", shorter)),
                NoArtDetector.fingerprint(file("2.png", longer)));
    }

    @Test
    public void smallFilesAreFingerprintedWhole() throws IOException {
        byte[] small = bytes(100, 1);
        byte[] changed = small.clone();
        changed[50] ^= 1;
        ArtFingerprint a = NoArtDetector.fingerprint(file("1.png", small));
        assertNotNull(a);
        assertEquals(a, NoArtDetector.fingerprint(file("2.png", small.clone())));
        assertNotEquals(a, NoArtDetector.fingerprint(file("3.png", changed)));
        assertNotNull(NoArtDetector.fingerprint(file("4.png", new byte[0])));
    }

    @Test
    public void aMissingFileHasNoFingerprint() {
        assertNull(NoArtDetector.fingerprint(new File(folder.getRoot(), "absent.png")));
        assertNull(NoArtDetector.fingerprint(folder.getRoot()));
        assertNull(NoArtDetector.fingerprint(null));
    }

    // --- The loader's size buckets ---

    @Test
    public void sizeBucketsRoundUpWithinBounds() {
        assertEquals(100, CachedAppAssetLoader.sizeBucket(0));
        assertEquals(100, CachedAppAssetLoader.sizeBucket(1));
        assertEquals(100, CachedAppAssetLoader.sizeBucket(100));
        assertEquals(200, CachedAppAssetLoader.sizeBucket(101));
        // The legacy grid's standard width is a bucket of its own, not rounded up.
        assertEquals(300, CachedAppAssetLoader.sizeBucket(300));
        assertEquals(CachedAppAssetLoader.MAX_SIZE_BUCKET, CachedAppAssetLoader.sizeBucket(10_000));
    }
}
