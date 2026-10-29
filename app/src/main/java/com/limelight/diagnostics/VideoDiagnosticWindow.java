package com.limelight.diagnostics;

/** Short-window counters; the receive, decode and render threads only take small bounded locks. */
public final class VideoDiagnosticWindow {
    private long startMs, bytes, received, rendered, lost, decodeCount, decodeSum, hostCount, hostSum;
    private long lastReceiveUs, gapUs;
    private int lastFrame;
    private boolean started, haveFrame;

    public synchronized void incoming(long nowMs, int frame, int length, long receiveUs, int hostTenthsMs) {
        if (!started) { startMs = nowMs; started = true; }
        bytes += length;
        if (haveFrame && frame == lastFrame) return;
        if (haveFrame) {
            int delta = frame - lastFrame;
            if (delta > 1) lost += delta - 1;
            gapUs = Math.max(gapUs, Math.max(0, receiveUs - lastReceiveUs));
        }
        haveFrame = true;
        lastFrame = frame;
        lastReceiveUs = receiveUs;
        received++;
        if (hostTenthsMs > 0) { hostSum += hostTenthsMs; hostCount++; }
    }

    public synchronized void rendered() { rendered++; }

    public synchronized void decoded(long ms) {
        if (ms >= 0 && ms < 1000) { decodeSum += ms; decodeCount++; }
    }

    /** gap, payload Mbps, receive FPS, render FPS, decode mean, frame loss %, host mean. */
    public synchronized float[] drain(long nowMs) {
        float seconds = Math.max(1, nowMs - startMs) / 1000f;
        float[] sample = {haveFrame ? gapUs / 1000f : Float.NaN, bytes * 8f / seconds / 1000000f,
                received / seconds, rendered / seconds,
                decodeCount == 0 ? Float.NaN : (float)decodeSum / decodeCount,
                received + lost == 0 ? Float.NaN : lost * 100f / (received + lost),
                hostCount == 0 ? Float.NaN : hostSum / 10f / hostCount};
        startMs = nowMs;
        bytes = received = rendered = lost = decodeCount = decodeSum = hostCount = hostSum = gapUs = 0;
        return sample;
    }
}
