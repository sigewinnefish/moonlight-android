package com.limelight.diagnostics;

import java.util.Arrays;

/** Optional MediaCodec observer. All times use System.nanoTime's monotonic clock.
 * A rendered callback is not GPU execution time or proof of physical panel scanout. */
public final class FrameTimingWindow {
    public static final int SUBMIT_P95 = 0, SUBMIT_PEAK = 1, PRESENT_P95 = 2, PRESENT_P99 = 3,
            PRESENT_PEAK = 4, PACING_P95 = 5, SURFACE_P95 = 6, PRESENT_FPS = 7,
            SLOW = 8, SEVERE = 9, EXCESS_PERCENT = 10, PRESENT_IDLE = 11, CALLBACK_IDLE = 12,
            CALLBACK_DELAY = 13, PENDING = 14, OVERFLOW = 15, BUDGET = 16, PRESENT_LAST = 17, METRICS = 18;
    private static final int SLOTS = 256, CAPACITY = 2048;
    private final int[] buffers = new int[SLOTS];
    private final long[] readyPts = new long[SLOTS], readyNs = new long[SLOTS];
    private final long[] pendingPts = new long[SLOTS], releasedNs = new long[SLOTS];
    private final long[] rawTimes = new long[CAPACITY];
    private final float[] rawValues = new float[CAPACITY];
    private final Distribution submit = new Distribution(), present = new Distribution(),
            pacing = new Distribution(), surface = new Distribution(), callback = new Distribution();
    private long lastSubmit, lastPresent, lastCallback;
    private double budgetNs, coveredNs, excessNs;
    private boolean callbacksEnabled;
    private int slow, severe, overflow, rawCount;

    public static final class Sample {
        public final float[] values;
        public final long[] timesNs;
        public final float[] intervalsMs;
        Sample(float[] values, long[] timesNs, float[] intervalsMs) {
            this.values = values; this.timesNs = timesNs; this.intervalsMs = intervalsMs;
        }
    }

    public synchronized void setCallbacksEnabled(boolean enabled) {
        callbacksEnabled = enabled;
        if (!enabled) Arrays.fill(releasedNs, 0);
    }

    public synchronized void setTargetFps(float fps) {
        budgetNs = fps > 0 && !Float.isInfinite(fps) ? 1e9 / fps : 0;
    }

    public synchronized void decoded(int buffer, long pts, long nowNs) {
        int slot = -1;
        for (int i = 0; i < SLOTS; i++) {
            if (readyNs[i] != 0 && buffers[i] == buffer) { slot = i; break; }
            if (slot < 0 && readyNs[i] == 0) slot = i;
        }
        if (slot < 0) { overflow++; return; }
        buffers[slot] = buffer; readyPts[slot] = pts; readyNs[slot] = nowNs;
    }

    public synchronized void discarded(int buffer) {
        for (int i = 0; i < SLOTS; i++) if (readyNs[i] != 0 && buffers[i] == buffer) readyNs[i] = 0;
    }

    /** Called immediately before the original render release to avoid callback races. */
    public synchronized void released(int buffer, long nowNs) {
        if (lastSubmit != 0 && nowNs > lastSubmit) submit.record((nowNs-lastSubmit)/1e6f);
        lastSubmit = nowNs;
        for (int i = 0; i < SLOTS; i++) {
            if (readyNs[i] == 0 || buffers[i] != buffer) continue;
            pacing.record((nowNs-readyNs[i])/1e6f);
            if (!callbacksEnabled) { readyNs[i] = 0; return; }
            int free = -1, oldest = 0;
            for (int j = 0; j < SLOTS; j++) {
                if (releasedNs[j] == 0) { free = j; break; }
                if (releasedNs[j] < releasedNs[oldest]) oldest = j;
            }
            // Missing callbacks may be dropped Surface frames. Bound the observer;
            // never call them confirmed drops or change the codec's buffer handling.
            if (free < 0) { free = oldest; overflow++; }
            pendingPts[free] = readyPts[i]; releasedNs[free] = nowNs; readyNs[i] = 0;
            return;
        }
        overflow++;
    }

    public synchronized void rendered(long pts, long renderNs, long callbackNs) {
        if (!callbacksEnabled) return;
        int slot = -1;
        for (int i = 0; i < SLOTS; i++) {
            if (releasedNs[i] != 0 && pendingPts[i] == pts) { slot = i; break; }
        }
        // Ignore duplicates and late callbacks from a cleared codec epoch.
        if (slot < 0) return;
        long release = releasedNs[slot]; releasedNs[slot] = 0;
        if (renderNs < release || renderNs > callbackNs || renderNs <= lastPresent) { overflow++; return; }
        surface.record((renderNs-release)/1e6f);
        callback.record((callbackNs-renderNs)/1e6f);
        lastCallback = callbackNs;
        if (lastPresent != 0) {
            long gap = renderNs-lastPresent;
            float ms = gap/1e6f; present.record(ms);
            int index = rawCount++ % CAPACITY; rawTimes[index] = renderNs; rawValues[index] = ms;
            coveredNs += gap;
            if (budgetNs > 0 && gap > budgetNs*1.5) {
                slow++; excessNs += gap-budgetNs;
                if (gap > Math.max(50e6, budgetNs*3)) severe++;
            }
        }
        lastPresent = renderNs;
    }

    /** Recovery/foreground changes break intervals; pending old PTS must not cross epochs. */
    public synchronized void reset() {
        Arrays.fill(readyNs, 0); Arrays.fill(releasedNs, 0);
        lastSubmit = lastPresent = lastCallback = 0;
        slow = severe = overflow = rawCount = 0; coveredNs = excessNs = 0;
        submit.clear(); present.clear(); pacing.clear(); surface.clear(); callback.clear();
    }

    public synchronized Sample drain(long nowNs) {
        float[] v = new float[METRICS]; Arrays.fill(v, Float.NaN);
        submit.drain(v, SUBMIT_P95, -1, SUBMIT_PEAK);
        if (coveredNs > 0) v[PRESENT_FPS] = (float)(present.count*1e9/coveredNs);
        present.drain(v, PRESENT_P95, PRESENT_P99, PRESENT_PEAK);
        pacing.drain(v, PACING_P95, -1, -1); surface.drain(v, SURFACE_P95, -1, -1);
        callback.drain(v, -1, -1, CALLBACK_DELAY);
        if (coveredNs > 0 && budgetNs > 0) {
            v[SLOW] = slow; v[SEVERE] = severe;
            v[EXCESS_PERCENT] = (float)(excessNs*100/coveredNs);
        }
        if (lastPresent != 0) v[PRESENT_IDLE] = Math.max(0, nowNs-lastPresent)/1e6f;
        if (lastCallback != 0) v[CALLBACK_IDLE] = Math.max(0, nowNs-lastCallback)/1e6f;
        int pending = 0;
        for (long time : releasedNs) if (time != 0) pending++;
        if (callbacksEnabled) { v[PENDING] = pending; v[OVERFLOW] = overflow; }
        if (budgetNs > 0) v[BUDGET] = (float)(budgetNs/1e6);
        int n = Math.min(rawCount, CAPACITY);
        long[] times = new long[n]; float[] intervals = new float[n];
        for (int i = 0; i < n; i++) {
            int index = (rawCount-n+i)%CAPACITY; times[i] = rawTimes[index]; intervals[i] = rawValues[index];
        }
        if (n > 0) v[PRESENT_LAST] = intervals[n-1];
        if (rawCount > CAPACITY) { v[OVERFLOW] += rawCount-CAPACITY; intervals[0] = Float.NaN; }
        slow = severe = overflow = rawCount = 0; coveredNs = excessNs = 0;
        return new Sample(v, times, intervals);
    }

    private static final class Distribution {
        private final float[] recent = new float[CAPACITY];
        private int count;
        private float peak;
        void record(float ms) {
            if (ms < 0 || Float.isNaN(ms) || Float.isInfinite(ms)) return;
            recent[count++%CAPACITY] = ms; peak = Math.max(peak, ms);
        }
        void clear() { count = 0; peak = 0; }
        void drain(float[] values, int p95, int p99, int maximum) {
            if (count > 0) {
                int n = Math.min(count, CAPACITY);
                float[] sorted = Arrays.copyOf(recent, n); Arrays.sort(sorted);
                if (p95 >= 0) values[p95] = sorted[(int)Math.ceil(n*.95)-1];
                if (p99 >= 0) values[p99] = sorted[(int)Math.ceil(n*.99)-1];
                if (maximum >= 0) values[maximum] = peak;
            }
            clear();
        }
    }
}
