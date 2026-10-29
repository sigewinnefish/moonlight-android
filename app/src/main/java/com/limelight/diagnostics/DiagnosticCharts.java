package com.limelight.diagnostics;

/** Series layout shared by collection and charts. Durations are milliseconds, not host ACKs. */
public final class DiagnosticCharts {
    public static final int RTT = 0, VARIATION = 1, FRAME_GAP = 2, INPUT_P95 = 3, INPUT_PEAK = 4,
            SUBMIT_P95 = 5, SUBMIT_PEAK = 6, RECEIVE_FPS = 7, RENDER_FPS = 8, DECODE = 9,
            CLIENT = 10, MBPS = 11, LOSS = 12, HOST = 13, ERRORS = 14, UI_LATE = 15, CONTACTS = 16,
            ANDROID_MOVE = 17, NATIVE_MOVE = 18, CONTROL_MOVE = 19,
            QUEUE_WAIT = 20, QUEUE_OLDEST = 21, QUEUE_DEPTH = 22, CONTROL_CALL = 23, CANCELS = 24,
            NET_VIDEO_MBPS = 25, NET_AUDIO_MBPS = 26, NET_VIDEO_PPS = 27, NET_AUDIO_PPS = 28,
            NET_VIDEO_GAP = 29, NET_AUDIO_GAP = 30, NET_OOS = 31, NET_DUP = 32,
            NET_FEC_REC = 33, NET_FEC_FAIL = 34, NET_MISSING = 35,
            NET_AUDIO_REC = 36, NET_AUDIO_FAIL = 37, NET_AUDIO_OOS = 38,
            NET_AUDIO_BAD = 39, NET_AUDIO_FEC_BAD = 40,
            NET_CONTROL_TX = 41, NET_CONTROL_RX = 42, NET_CONTROL_LOSS = 43, NET_CONTROL_FLIGHT = 44,
            NET_CONTROL_TX_PPS = 45, NET_CONTROL_RX_PPS = 46,
            NET_VIDEO_IDLE = 47, NET_AUDIO_IDLE = 48, NET_AUDIO_DATA = 49, NET_AUDIO_FEC = 50,
            NET_SEQUENCE_GAPS = 51, WIFI_RSSI = 52, WIFI_LINK = 53,
            PRESENT_LAST = 54, FRAME_BUDGET = 55, PRESENT_P95 = 56, PRESENT_P99 = 57, PRESENT_PEAK = 58,
            DISPLAY_SUBMIT_P95 = 59, DISPLAY_SUBMIT_PEAK = 60, PACING_P95 = 61, SURFACE_P95 = 62,
            PRESENT_FPS = 63, SLOW_FRAMES = 64, SEVERE_FRAMES = 65, FRAME_EXCESS = 66,
            PRESENT_IDLE = 67, CALLBACK_IDLE = 68, CALLBACK_DELAY = 69,
            PRESENT_PENDING = 70, PRESENT_OVERFLOW = 71;
    public static final int SERIES = 72, RAW_FRAME_GROUP = 29;
    public static final int[][] GROUPS = {{RTT, VARIATION}, {FRAME_GAP}, {INPUT_P95, INPUT_PEAK},
            {SUBMIT_P95, SUBMIT_PEAK}, {RECEIVE_FPS, RENDER_FPS}, {DECODE, CLIENT, HOST},
            {MBPS}, {LOSS}, {UI_LATE}, {CONTACTS, ERRORS, CANCELS}, {ANDROID_MOVE, NATIVE_MOVE, CONTROL_MOVE},
            {QUEUE_WAIT, QUEUE_OLDEST}, {QUEUE_DEPTH}, {CONTROL_CALL},
            {NET_VIDEO_MBPS, NET_AUDIO_MBPS}, {NET_VIDEO_PPS, NET_AUDIO_PPS},
            {NET_VIDEO_GAP, NET_AUDIO_GAP}, {NET_OOS, NET_DUP, NET_SEQUENCE_GAPS},
            {NET_FEC_REC, NET_MISSING, NET_FEC_FAIL}, {NET_AUDIO_REC, NET_AUDIO_FAIL},
            {NET_AUDIO_OOS, NET_AUDIO_BAD, NET_AUDIO_FEC_BAD}, {NET_CONTROL_TX, NET_CONTROL_RX},
            {NET_CONTROL_LOSS}, {NET_CONTROL_FLIGHT}, {NET_CONTROL_TX_PPS, NET_CONTROL_RX_PPS},
            {NET_VIDEO_IDLE, NET_AUDIO_IDLE}, {NET_AUDIO_DATA, NET_AUDIO_FEC}, {WIFI_RSSI}, {WIFI_LINK},
            {PRESENT_LAST, FRAME_BUDGET}, {PRESENT_P95, PRESENT_P99, PRESENT_PEAK},
            {DISPLAY_SUBMIT_P95, DISPLAY_SUBMIT_PEAK}, {PACING_P95, SURFACE_P95}, {PRESENT_FPS},
            {SLOW_FRAMES, SEVERE_FRAMES}, {FRAME_EXCESS}, {PRESENT_IDLE, CALLBACK_IDLE},
            {CALLBACK_DELAY}, {PRESENT_PENDING, PRESENT_OVERFLOW}};
    public static final String[] UNITS = {"ms", "ms", "ms", "ms", "FPS", "ms", "Mbps", "%", "ms", "count",
            "/s", "ms", "count", "ms", "Mbps", "/s", "ms", "/s", "/s", "/s", "/s", "Mbps",
            "%", "B", "/s", "ms", "/s", "dBm", "Mbps", "ms", "ms", "ms", "ms", "FPS", "count", "%", "ms", "ms", "count"};

    public static long migrateSelection(long saved, boolean touchMigrated, boolean networkMigrated, boolean frameMigrated) {
        if (!touchMigrated) saved |= 15L << 10;
        if (!networkMigrated) saved |= ((1L << 29)-1) ^ ((1L << 14)-1);
        if (!frameMigrated) saved |= ((1L << GROUPS.length)-1) ^ ((1L << RAW_FRAME_GROUP)-1);
        return saved;
    }

    /** Put newly added frame charts first; original chart IDs/settings stay stable. */
    public static int displayGroup(int order) {
        int frames = GROUPS.length-RAW_FRAME_GROUP;
        return order < frames ? RAW_FRAME_GROUP+order : order-frames;
    }

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

    public static String formatValue(float value, java.util.Locale locale) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return "—";
        if (value == 0) return "0";
        float magnitude = Math.abs(value);
        return String.format(locale, magnitude >= 100 ? "%.0f" : magnitude >= 10 ? "%.1f" :
                magnitude >= 1 ? "%.2f" : "%.2g", value);
    }

    private DiagnosticCharts() {}
}
