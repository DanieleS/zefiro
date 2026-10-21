package com.limelight.grid.assets;

import android.graphics.Bitmap;

public class ScaledBitmap {
    public int originalWidth;
    public int originalHeight;

    public Bitmap bitmap;

    /**
     * The fingerprint of the cached file this was decoded from, kept with the bitmap so a cover
     * served from memory can still be checked against the host's stand-in art; null when unknown.
     */
    public NoArtDetector.ArtFingerprint fingerprint;

    public ScaledBitmap() {}

    public ScaledBitmap(int originalWidth, int originalHeight, Bitmap bitmap) {
        this.originalWidth = originalWidth;
        this.originalHeight = originalHeight;
        this.bitmap = bitmap;
    }
}
