package com.limelight.diagnostics;

/** Keep floating controls reachable across resizing and rotation. No Android dependencies. */
public final class PanelGeometry {
    public static int[] fit(int viewportWidth, int viewportHeight, int x, int y,
                            int width, int height, int minimumWidth, int minimumHeight) {
        int vw = Math.max(1, viewportWidth), vh = Math.max(1, viewportHeight);
        int w = Math.max(Math.min(minimumWidth, vw), Math.min(width, vw));
        int h = Math.max(Math.min(minimumHeight, vh), Math.min(height, vh));
        return new int[]{Math.max(0, Math.min(x, vw-w)), Math.max(0, Math.min(y, vh-h)), w, h};
    }

    public static int columns(float widthDp, int requested) {
        if (requested >= 1 && requested <= 3) return requested;
        return widthDp >= 760 ? 3 : widthDp >= 420 ? 2 : 1;
    }

    private PanelGeometry() {}
}
