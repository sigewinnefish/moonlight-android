package com.limelight.binding.video;

/** Immutable snapshot published by the video receive thread, never a touch acknowledgement. */
public final class PerfSample {
    public final String text;
    public final long timestampMs;
    public final float frameGapMaxMs;
    public final float videoMbps;
    public final float clientMs;

    public PerfSample(String text, long timestampMs, float frameGapMaxMs, float videoMbps, float clientMs) {
        this.text = text;
        this.timestampMs = timestampMs;
        this.frameGapMaxMs = frameGapMaxMs;
        this.videoMbps = videoMbps;
        this.clientMs = clientMs;
    }
}
