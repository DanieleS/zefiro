package com.limelight.nvstream;

/**
 * Caps how often a stream is picked back up after another app covered it, so a prompt that keeps
 * reappearing (or a screen that keeps turning off) cannot loop stop and resume forever. Kept free
 * of Android so it can be unit-tested; Game asks it before it resumes.
 */
public final class StreamResumeLimit {
    public static final int MAX_RESUMES = 3;
    public static final long WINDOW_MS = 2 * 60 * 1000;

    private final long[] times = new long[MAX_RESUMES];
    private int count;

    /** Whether a resume at nowMs is allowed; when it is, it is counted. */
    public synchronized boolean tryAcquire(long nowMs) {
        int kept = 0;
        for (int i = 0; i < count; i++) {
            if (nowMs - times[i] < WINDOW_MS) {
                times[kept++] = times[i];
            }
        }
        count = kept;
        if (count >= MAX_RESUMES) {
            return false;
        }
        times[count++] = nowMs;
        return true;
    }
}
