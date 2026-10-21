package com.limelight.grid.assets;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * Tells a game's own cover from the stand-in a host serves for games that have none.
 *
 * <p>A host without a cover for a game does not say so: it answers the box-art request with a
 * default image of its own (Sunshine's grey controller, Apollo's logo), and that image would sit
 * in the grid as if it were the game's cover, the same picture on every such tile and no title
 * to tell them apart. The stand-in has no fixed size to recognise it by, unlike GFE's dummy
 * covers, so this looks at what it does have: it is the same image for every game.
 *
 * <h3>Why the bytes, not the pixels</h3>
 *
 * The disk cache keeps the host's response as it came ({@link DiskAssetLoader#populateCacheWithStream}
 * writes the raw stream, untouched), so two games given the host's default art have
 * byte-identical files. Comparing files needs no decode and works on whatever is on disk, before
 * any tile asked for it; comparing pixels would need every cover decoded at one size first.
 * Hashing the whole file is not needed either: two covers of the same length whose first and
 * last 4 KB agree are, in practice, the same file. So a fingerprint is the length plus a CRC of
 * those two ends.
 *
 * <h3>The rule</h3>
 *
 * A fingerprint that two or more of one host's games share is the host's stand-in; every game
 * carrying it has {@link ArtState#NO_ART}. A fingerprint only one game has is that game's own
 * cover. Hence the one blind spot: a host whose only uncovered game is a single one cannot be
 * told apart this way, and that game shows the stand-in as before. GFE's dummy sizes and a
 * cover that never arrived are recognised regardless.
 *
 * <p>Pure: no Android, no state. {@link #fingerprint(File)} reads the disk, so it belongs on a
 * worker thread; everything else is arithmetic.
 */
public final class NoArtDetector {

    /** How much of each end of the file goes into the fingerprint. */
    static final int SAMPLE_BYTES = 4 * 1024;

    // The dummy covers GFE serves in place of a missing one, recognised by size as they always
    // were: they are not shared bytes across hosts, but they are never a game's real art.
    private static final int GFE2_WIDTH = 130, GFE2_HEIGHT = 180;
    private static final int GFE3_WIDTH = 628, GFE3_HEIGHT = 888;

    private NoArtDetector() {}

    /** A cached cover file, reduced to what is enough to tell whether two files are the same. */
    public static final class ArtFingerprint {
        public final long length;
        public final long crc;

        public ArtFingerprint(long length, long crc) {
            this.length = length;
            this.crc = crc;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ArtFingerprint)) {
                return false;
            }
            ArtFingerprint that = (ArtFingerprint) o;
            return length == that.length && crc == that.crc;
        }

        @Override
        public int hashCode() {
            // Spelled out: Long.hashCode(long) is API 24.
            return 31 * (int) (length ^ (length >>> 32)) + (int) (crc ^ (crc >>> 32));
        }

        @Override
        public String toString() {
            return length + "/" + Long.toHexString(crc);
        }
    }

    /**
     * The fingerprint of a cached cover, or null when there is no file or it cannot be read.
     * Worker thread: it reads up to 8 KB from disk.
     */
    public static ArtFingerprint fingerprint(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            long length = in.length();
            CRC32 crc = new CRC32();

            byte[] head = new byte[(int) Math.min(SAMPLE_BYTES, length)];
            in.readFully(head);
            crc.update(head, 0, head.length);

            // The tail is whatever the head did not cover, up to its own 4 KB, so a small file
            // is hashed once rather than twice over.
            long tailStart = Math.max(head.length, length - SAMPLE_BYTES);
            byte[] tail = new byte[(int) (length - tailStart)];
            in.seek(tailStart);
            in.readFully(tail);
            crc.update(tail, 0, tail.length);

            return new ArtFingerprint(length, crc.getValue());
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * The fingerprints that two or more games share: the host's stand-in art. Games with no
     * fingerprint (no cached file yet) are left out, so they count toward nothing.
     *
     * @param byAppId each game's cover fingerprint, keyed by app id
     */
    public static Set<ArtFingerprint> placeholderFingerprints(Map<Integer, ArtFingerprint> byAppId) {
        if (byAppId == null || byAppId.isEmpty()) {
            return Collections.emptySet();
        }
        Map<ArtFingerprint, Integer> counts = new HashMap<>();
        for (ArtFingerprint fp : byAppId.values()) {
            if (fp != null) {
                Integer seen = counts.get(fp);
                counts.put(fp, seen == null ? 1 : seen + 1);
            }
        }
        Set<ArtFingerprint> shared = new HashSet<>();
        for (Map.Entry<ArtFingerprint, Integer> entry : counts.entrySet()) {
            if (entry.getValue() >= 2) {
                shared.add(entry.getKey());
            }
        }
        return shared;
    }

    /**
     * Whether a cover is no cover: none arrived, it is one of GFE's dummy sizes, or its file is
     * the host's shared stand-in.
     *
     * @param bitmap       the decoded cover, or null when none could be had
     * @param fp           its file's fingerprint; null when unknown, which proves nothing
     * @param placeholders what {@link #placeholderFingerprints} found for this host
     */
    public static boolean isPlaceholder(ScaledBitmap bitmap, ArtFingerprint fp,
                                        Set<ArtFingerprint> placeholders) {
        if (bitmap == null) {
            return true;
        }
        return isPlaceholder(bitmap.originalWidth, bitmap.originalHeight, fp, placeholders);
    }

    /** {@link #isPlaceholder(ScaledBitmap, ArtFingerprint, Set)} on a cover's original size. */
    public static boolean isPlaceholder(int originalWidth, int originalHeight, ArtFingerprint fp,
                                        Set<ArtFingerprint> placeholders) {
        if ((originalWidth == GFE2_WIDTH && originalHeight == GFE2_HEIGHT)
                || (originalWidth == GFE3_WIDTH && originalHeight == GFE3_HEIGHT)) {
            return true;
        }
        return fp != null && placeholders != null && placeholders.contains(fp);
    }

    /** What a tile shows for a cover that finished loading: {@link ArtState#ART} or NO_ART. */
    public static ArtState stateOf(ScaledBitmap bitmap, ArtFingerprint fp,
                                   Set<ArtFingerprint> placeholders) {
        return isPlaceholder(bitmap, fp, placeholders) ? ArtState.NO_ART : ArtState.ART;
    }
}
