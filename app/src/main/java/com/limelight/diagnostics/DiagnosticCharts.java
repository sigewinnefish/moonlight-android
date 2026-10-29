package com.limelight.diagnostics;

/** Series layout shared by collection and charts. Durations are milliseconds, not host ACKs. */
public final class DiagnosticCharts {
    public static final int RTT = 0, VARIATION = 1, FRAME_GAP = 2, INPUT_P95 = 3, INPUT_PEAK = 4,
            SUBMIT_P95 = 5, SUBMIT_PEAK = 6, RECEIVE_FPS = 7, RENDER_FPS = 8, DECODE = 9,
            CLIENT = 10, MBPS = 11, LOSS = 12, HOST = 13, ERRORS = 14, UI_LATE = 15, CONTACTS = 16,
            ANDROID_MOVE = 17, NATIVE_MOVE = 18, CONTROL_MOVE = 19,
            QUEUE_WAIT = 20, QUEUE_OLDEST = 21, QUEUE_DEPTH = 22, CONTROL_CALL = 23, CANCELS = 24;
    public static final int SERIES = 25;
    public static final int[][] GROUPS = {{RTT, VARIATION}, {FRAME_GAP}, {INPUT_P95, INPUT_PEAK},
            {SUBMIT_P95, SUBMIT_PEAK}, {RECEIVE_FPS, RENDER_FPS}, {DECODE, CLIENT, HOST},
            {MBPS}, {LOSS}, {UI_LATE}, {CONTACTS, ERRORS, CANCELS}, {ANDROID_MOVE, NATIVE_MOVE, CONTROL_MOVE},
            {QUEUE_WAIT, QUEUE_OLDEST}, {QUEUE_DEPTH}, {CONTROL_CALL}};

    public static float upperBound(float peak) {
        if (Float.isNaN(peak) || Float.isInfinite(peak) || peak <= 0) return 1;
        double magnitude = Math.pow(10, Math.floor(Math.log10(peak)));
        return (float)(Math.ceil(peak / magnitude) * magnitude);
    }

    public static float maximum(TrendHistory history, int[] series, long since) {
        float max = 0;
        for (int i = 0; i < history.size(); i++) {
            if (history.time(i) < since) continue;
            for (int s : series) {
                float value = history.get(s, i);
                if (!Float.isNaN(value) && !Float.isInfinite(value)) max = Math.max(max, value);
            }
        }
        return max;
    }

    private DiagnosticCharts() {}
}
