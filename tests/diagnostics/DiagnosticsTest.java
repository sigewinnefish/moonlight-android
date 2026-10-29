import com.limelight.diagnostics.LatencyWindow;
import com.limelight.diagnostics.TrendHistory;
import com.limelight.diagnostics.DiagnosticCharts;
import com.limelight.diagnostics.PanelGeometry;
import com.limelight.diagnostics.HudLayout;
import com.limelight.diagnostics.VideoDiagnosticWindow;

public final class DiagnosticsTest {
    private static int checks;
    private static void check(boolean v) {
        checks++;
        if (!v) throw new AssertionError("Check " + checks);
    }
    public static void main(String[] args) throws Exception {
        LatencyWindow w = new LatencyWindow();
        check(Float.isNaN(w.drain()[2]));
        w.record(-1); w.record(Float.NaN); w.record(Float.POSITIVE_INFINITY);
        check(w.drain()[0] == 0);
        for (int i = 1; i <= 100; i++) w.record(i);
        float[] s = w.drain();
        check(s[0] == 100 && s[1] == 50.5f && s[2] == 95 && s[3] == 100);
        check(w.drain()[0] == 0);
        w.record(5000);
        for (int i = 0; i < 1000; i++) w.record(2);
        s = w.drain();
        check(s[0] == 1001 && s[2] == 2 && s[3] == 5000); // Recent P95, exact interval peak.
        for (int round = 0; round < 1000; round++) {
            w.record(round); s = w.drain();
            check(s[0] == 1 && s[2] == round && s[3] == round);
        }
        TrendHistory h = new TrendHistory(2);
        for (int i = 0; i < 100; i++) h.add(i, Float.NaN);
        check(h.size() == 60);
        check(h.get(0, 0) == 40 && h.get(0, 59) == 99);
        check(Float.isNaN(h.get(1, 0)));
        check(h.scale(0, 2) == 100);
        for (int i = 0; i < 60; i++) h.add(Float.NaN, 1);
        check(Float.isNaN(h.get(0, 59)) && h.scale(0, 2) == 10);
        boolean rejected = false;
        try { h.add(1); } catch (IllegalArgumentException e) { rejected = true; }
        check(rejected);
        TrendHistory timed = new TrendHistory(2, 600);
        for (int i = 0; i < 700; i++) timed.addAt(i * 500L, i, Float.NaN);
        check(timed.size() == 600 && timed.time(0) == 50000 && timed.time(599) == 349500);
        check(timed.get(0, 0) == 100 && timed.get(0, 599) == 699);
        check(DiagnosticCharts.maximum(timed, new int[]{0, 1}, 349000) == 699);
        check(DiagnosticCharts.maximum(timed, new int[]{1}, 0) == 0);
        check(DiagnosticCharts.maximum(timed, new int[]{0}, 400000) == 0);
        check(DiagnosticCharts.upperBound(0) == 1);
        check(DiagnosticCharts.upperBound(Float.NaN) == 1);
        check(DiagnosticCharts.upperBound(.12f) >= .12f && DiagnosticCharts.upperBound(.12f) < 1);
        check(DiagnosticCharts.upperBound(123) == 200);
        check(DiagnosticCharts.UNITS.length == DiagnosticCharts.GROUPS.length);
        check(DiagnosticCharts.UNITS[27].equals("dBm"));
        boolean[] covered = new boolean[DiagnosticCharts.SERIES];
        for (int[] group : DiagnosticCharts.GROUPS) {
            for (int series : group) { check(series >= 0 && series < covered.length); covered[series] = true; }
        }
        for (boolean present : covered) check(present);
        timed.clear(); check(timed.size() == 0);
        timed.addAt(1234, 2, 3); check(timed.time(0) == 1234 && timed.get(1, 0) == 3);
        VideoDiagnosticWindow video = new VideoDiagnosticWindow();
        video.incoming(1000, 10, 1000, 5000000, 100);
        video.incoming(1000, 10, 100, 5000000, 100); // CSD fragment: bytes, not another frame.
        video.decoded(2); video.decoded(4); video.rendered();
        video.incoming(1050, 12, 1000, 5050000, 300);
        float[] fast = video.drain(1100);
        check(fast[0] == 50 && Math.abs(fast[1] - .168f) < .0001);
        check(fast[2] == 20 && fast[3] == 10 && fast[4] == 3);
        check(Math.abs(fast[5] - 100f/3) < .001 && fast[6] == 20);
        video.incoming(1250, 13, 100, 5200000, 0);
        video.decoded(-1); video.decoded(1000);
        fast = video.drain(1250);
        check(fast[0] == 150 && Float.isNaN(fast[4]) && Float.isNaN(fast[6]) && fast[5] == 0);
        VideoDiagnosticWindow wrap = new VideoDiagnosticWindow();
        wrap.incoming(1000, Integer.MAX_VALUE, 1, 1000, 0);
        wrap.incoming(1100, Integer.MIN_VALUE, 1, 2000, 0);
        check(wrap.drain(1200)[5] == 0);
        VideoDiagnosticWindow concurrent = new VideoDiagnosticWindow();
        Thread output = new Thread(() -> {
            for (int i = 0; i < 10000; i++) { concurrent.rendered(); concurrent.decoded(1); }
        });
        output.start();
        for (int i = 1; i <= 10000; i++) concurrent.incoming(1000, i, 100, i * 1000L, 10);
        output.join();
        fast = concurrent.drain(2000);
        check(fast[2] == 10000 && fast[3] == 10000 && fast[4] == 1 && fast[5] == 0);
        TrendHistory full = new TrendHistory(1, 3000);
        for (int i = 0; i < 3500; i++) full.addAt(i * 100L, i);
        check(full.size() == 3000 && full.time(0) == 50000 && full.get(0, 2999) == 3499);
        int[] panel = PanelGeometry.fit(1000, 500, 900, -100, 400, 300, 260, 160);
        check(panel[0] == 600 && panel[1] == 0 && panel[2] == 400 && panel[3] == 300);
        panel = PanelGeometry.fit(300, 120, 500, 500, 100, 10, 260, 160);
        check(panel[0] == 40 && panel[1] == 0 && panel[2] == 260 && panel[3] == 120);
        panel = PanelGeometry.fit(0, 0, -10, -10, 1000, 1000, 260, 160);
        check(panel[0] == 0 && panel[1] == 0 && panel[2] == 1 && panel[3] == 1);
        check(PanelGeometry.columns(419, 0) == 1 && PanelGeometry.columns(420, 0) == 2);
        check(PanelGeometry.columns(759, 0) == 2 && PanelGeometry.columns(760, 0) == 3);
        check(PanelGeometry.columns(300, 3) == 3 && PanelGeometry.columns(1000, 1) == 1);
        for (int vw = 1; vw <= 1000; vw += 37) {
            panel = PanelGeometry.fit(vw, 500, 10000, -5000, 700, 600, 260, 160);
            check(panel[0] >= 0 && panel[1] >= 0 && panel[0]+panel[2] <= vw && panel[1]+panel[3] <= 500);
        }
        check(DiagnosticCharts.formatValue(0, java.util.Locale.US).equals("0"));
        check(DiagnosticCharts.formatValue(Float.NaN, java.util.Locale.US).equals("—"));
        check(Float.parseFloat(DiagnosticCharts.formatValue(.00021f, java.util.Locale.US)) > 0);
        check(DiagnosticCharts.formatValue(-67, java.util.Locale.US).equals("-67.0"));
        int[] hud = HudLayout.plan(240, 8, 48, 14, 4, 76, 60);
        check(hud[0] == 48 && hud[1] >= 1 && hud[2] == 2 && hud[3] >= 60);
        hud = HudLayout.plan(120, 8, 48, 14, 10, 76, 60);
        check(hud[0] == 48 && hud[2] == 0 && hud[1] > 0);
        hud = HudLayout.plan(40, 8, 48, 14, 4, 76, 60);
        check(hud[0] == 32 && hud[1] == 0 && hud[2] == 0);
        hud = HudLayout.plan(240, 8, 96, 40, 4, 76, 60);
        check(hud[0] == 96 && hud[1] == 1 && hud[2] == 1 && hud[3] == 76);
        for (int height = 8; height <= 1000; height += 7) {
            for (int lineHeight : new int[]{10, 14, 24, 40}) {
                hud = HudLayout.plan(height, 8, 48, lineHeight, 10, 76, 60);
                check(hud[0]+hud[1]*lineHeight+hud[2]*hud[3]+8 <= height);
                check(hud[2] <= 2 && (hud[2] == 0 || hud[3] >= 60));
            }
        }
        System.out.println("PASS: " + checks + " diagnostics assertions");
    }
}
