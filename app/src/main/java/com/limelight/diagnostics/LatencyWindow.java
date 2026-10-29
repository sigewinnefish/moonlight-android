package com.limelight.diagnostics;

import java.util.Arrays;

/** Bounded recent-event percentile, with an exact peak/count for the whole sampling interval. */
public final class LatencyWindow {
    private final float[] recent = new float[256];
    private int cursor;
    private int count;
    private float peak;
    private double sum;

    public void record(float ms) {
        if (Float.isNaN(ms) || Float.isInfinite(ms) || ms < 0) {
            return;
        }
        recent[cursor++ % recent.length] = ms;
        if (cursor == recent.length) cursor = 0;
        count++;
        sum += ms;
        peak = Math.max(peak, ms);
    }

    public float[] drain() {
        int n = Math.min(count, recent.length);
        float[] sorted = Arrays.copyOf(recent, n);
        Arrays.sort(sorted);
        float[] result = {count, count == 0 ? Float.NaN : (float)(sum / count),
                n == 0 ? Float.NaN : sorted[(int)Math.ceil(n * .95) - 1],
                count == 0 ? Float.NaN : peak};
        cursor = count = 0;
        peak = 0;
        sum = 0;
        return result;
    }
}
