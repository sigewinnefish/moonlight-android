package com.limelight.binding.video;

/** Immutable snapshot published by the video receive thread, never a touch acknowledgement. */
public final class PerfSample {
    public final String text;
    public final long timestampMs;
    public final float frameGapMaxMs;
    public final float videoMbps;
    public final float clientMs;
    public final float receiveFps, renderFps, decodeMs, lossPercent, hostMs;

    public PerfSample(String text, long timestampMs, float frameGapMaxMs, float videoMbps, float clientMs, float receiveFps, float renderFps,
                      float decodeMs, float lossPercent, float hostMs) {
        this.text = text;
        this.timestampMs = timestampMs;
        this.frameGapMaxMs = frameGapMaxMs;
        this.videoMbps = videoMbps;
        this.clientMs = clientMs;
        this.receiveFps = receiveFps;
        this.renderFps = renderFps;
        this.decodeMs = decodeMs;
        this.lossPercent = lossPercent;
        this.hostMs = hostMs;
    }
}
