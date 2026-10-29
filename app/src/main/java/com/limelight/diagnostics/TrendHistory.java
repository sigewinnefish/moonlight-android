package com.limelight.diagnostics;

/** Fixed allocation history, oldest to newest, with NaN representing unavailable data. */
public final class TrendHistory {
    public static final int CAPACITY = 60;
    private final float[][] values;
    private final long[] times;
    private int next;
    private int count;

    public TrendHistory(int series) { this(series, CAPACITY); }

    public TrendHistory(int series, int capacity) {
        if (series < 1 || capacity < 1) throw new IllegalArgumentException();
        values = new float[series][capacity];
        times = new long[capacity];
    }

    public void add(float... sample) { addAt(0, sample); }

    public void addAt(long timeMs, float... sample) {
        if (sample.length != values.length) throw new IllegalArgumentException("Series mismatch");
        for (int i = 0; i < values.length; i++) values[i][next] = sample[i];
        times[next] = timeMs;
        next = (next + 1) % times.length;
        count = Math.min(times.length, count + 1);
    }

    public int size() { return count; }

    public void clear() { next = count = 0; }

    public long time(int index) {
        if (index < 0 || index >= count) throw new IndexOutOfBoundsException();
        return times[(next - count + index + times.length) % times.length];
    }

    public float get(int series, int index) {
        if (index < 0 || index >= count) throw new IndexOutOfBoundsException();
        return values[series][(next - count + index + times.length) % times.length];
    }

    public float scale(int first, int end) {
        float max = 10;
        for (int s = first; s < end; s++) {
            for (int i = 0; i < count; i++) {
                float v = get(s, i);
                if (!Float.isNaN(v) && !Float.isInfinite(v)) max = Math.max(max, v);
            }
        }
        return (float)Math.ceil(max / 10) * 10;
    }
}
