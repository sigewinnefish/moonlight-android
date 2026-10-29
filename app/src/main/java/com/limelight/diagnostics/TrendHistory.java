package com.limelight.diagnostics;

/** Fixed allocation history, oldest to newest, with NaN representing unavailable data. */
public final class TrendHistory {
    public static final int CAPACITY = 60;
    private final float[][] values;
    private int next;
    private int count;

    public TrendHistory(int series) { values = new float[series][CAPACITY]; }

    public void add(float... sample) {
        if (sample.length != values.length) throw new IllegalArgumentException("Series mismatch");
        for (int i = 0; i < values.length; i++) values[i][next] = sample[i];
        next = (next + 1) % CAPACITY;
        count = Math.min(CAPACITY, count + 1);
    }

    public int size() { return count; }

    public float get(int series, int index) {
        if (index < 0 || index >= count) throw new IndexOutOfBoundsException();
        return values[series][(next - count + index + CAPACITY) % CAPACITY];
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
