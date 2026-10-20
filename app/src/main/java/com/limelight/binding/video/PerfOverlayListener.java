package com.limelight.binding.video;

public interface PerfOverlayListener {
    /**
     * One second's measurements, unformatted: every surface that shows them (the stream HUD, the
     * companion panel) lays them out itself. Called on a decoder thread.
     */
    void onPerfUpdate(final PerfStats stats);
}
