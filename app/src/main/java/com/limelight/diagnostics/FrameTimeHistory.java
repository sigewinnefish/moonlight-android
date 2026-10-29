package com.limelight.diagnostics;

import java.util.Arrays;

/** UI-thread raw presentation history. Pixel buckets retain spikes without drawing every frame. */
public final class FrameTimeHistory {
    private final long[] times;
    private final float[] values;
    private int next, count;

    public FrameTimeHistory(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException();
        times = new long[capacity]; values = new float[capacity];
    }
    public void add(long timeMs, float ms) {
        if (count > 0 && timeMs < time(count-1)) return;
        times[next] = timeMs; values[next] = ms;
        next = (next+1)%times.length; count = Math.min(count+1, times.length);
    }
    public void clear() { next = count = 0; }
    private int index(int i) { return (next-count+i+times.length)%times.length; }
    private long time(int i) { return times[index(i)]; }

    /** Return the visible maximum; holes stay NaN instead of bridging missing frame intervals. */
    public float buckets(long start, long end, float[] minimum, float[] maximum) {
        if (end <= start || minimum.length != maximum.length || minimum.length == 0) throw new IllegalArgumentException();
        Arrays.fill(minimum, Float.NaN); Arrays.fill(maximum, Float.NaN);
        int lo = 0, hi = count;
        while (lo < hi) { int mid = (lo+hi)>>>1; if (time(mid) < start) lo = mid+1; else hi = mid; }
        float peak = 0;
        for (int i = lo; i < count && time(i) <= end; i++) {
            float value = values[index(i)];
            if (Float.isNaN(value) || Float.isInfinite(value) || value < 0) continue;
            int bucket = (int)Math.min(minimum.length-1, (time(i)-start)*(minimum.length-1)/(end-start));
            minimum[bucket] = Float.isNaN(minimum[bucket]) ? value : Math.min(value, minimum[bucket]);
            maximum[bucket] = Float.isNaN(maximum[bucket]) ? value : Math.max(value, maximum[bucket]);
            peak = Math.max(peak, value);
        }
        return peak;
    }
}
