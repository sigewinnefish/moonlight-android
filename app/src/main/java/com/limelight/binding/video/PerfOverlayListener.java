package com.limelight.binding.video;

public interface PerfOverlayListener {
    default int getPerfRefreshMs() { return 1000; }
    void onPerfUpdate(final PerfSample sample);
}
