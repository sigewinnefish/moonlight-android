package com.limelight.diagnostics;

/** A bounded HUD plan: controls first, then text and complete mini-charts. */
public final class HudLayout {
    // header height, visible text lines, chart count, chart height
    public static int[] plan(int height, int padding, int header, int line,
                             int requestedLines, int desiredChart, int minimumChart) {
        int available = Math.max(0, height - Math.max(0, padding));
        int control = Math.min(Math.max(0, header), available);
        available -= control;
        int row = Math.max(1, line);
        if (available < row) return new int[]{control, 0, 0, 0};
        int minimum = Math.max(1, minimumChart);
        int charts = Math.min(2, Math.max(0, (available-row)/minimum));
        int lines = Math.min(Math.max(1, requestedLines), Math.max(1, (available-charts*minimum)/row));
        int chartHeight = charts == 0 ? 0 : Math.min(Math.max(minimum, desiredChart), (available-lines*row)/charts);
        return new int[]{control, lines, charts, chartHeight};
    }
    private HudLayout() {}
}
