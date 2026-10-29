import com.limelight.diagnostics.FrameTimingWindow;
import com.limelight.diagnostics.FrameTimeHistory;
import com.limelight.diagnostics.DiagnosticCharts;
import java.util.concurrent.atomic.AtomicReference;
import javax.xml.parsers.DocumentBuilderFactory;

public final class FrameTimingTest {
    private static int checks;
    private static void check(boolean value) {
        checks++; if (!value) throw new AssertionError("Frame timing check " + checks);
    }
    private static boolean near(float a, float b) { return Math.abs(a-b) < .01f; }
    private static FrameTimingWindow window() {
        FrameTimingWindow w = new FrameTimingWindow(); w.setCallbacksEnabled(true); w.setTargetFps(60); return w;
    }
    private static void frame(FrameTimingWindow w, int buffer, long pts, long render, long callback) {
        w.decoded(buffer, pts, render-6000000); w.released(buffer, render-4000000); w.rendered(pts, render, callback);
    }
    public static void main(String[] args) throws Exception {
        FrameTimingWindow unsupported = new FrameTimingWindow(); unsupported.setTargetFps(60);
        frame(unsupported, 0, 1, 1000000000, 1002000000);
        frame(unsupported, 0, 2, 1016000000, 1018000000);
        float[] v = unsupported.drain(1100000000).values;
        check(Float.isNaN(v[FrameTimingWindow.PRESENT_P95]) && Float.isNaN(v[FrameTimingWindow.PRESENT_FPS]));
        check(near(v[FrameTimingWindow.SUBMIT_P95], 16) && near(v[FrameTimingWindow.PACING_P95], 2));
        check(Float.isNaN(v[FrameTimingWindow.PENDING]));
        FrameTimingWindow w = window();
        check(Float.isNaN(w.drain(900000000).values[FrameTimingWindow.PRESENT_FPS]));
        long last = 0;
        for (int i = 0; i < 100; i++) { last = 1000000000L+i*16000000L; frame(w, i%8, i+1, last, last+2000000); }
        FrameTimingWindow.Sample s = w.drain(last+10000000);
        v = s.values;
        check(s.intervalsMs.length == 99 && s.timesNs[98] == last);
        check(near(v[FrameTimingWindow.PRESENT_P95], 16) && near(v[FrameTimingWindow.PRESENT_P99], 16));
        check(near(v[FrameTimingWindow.PRESENT_PEAK], 16) && near(v[FrameTimingWindow.PRESENT_FPS], 62.5f));
        check(v[FrameTimingWindow.SLOW] == 0 && v[FrameTimingWindow.SEVERE] == 0 && v[FrameTimingWindow.EXCESS_PERCENT] == 0);
        check(v[FrameTimingWindow.PENDING] == 0 && near(v[FrameTimingWindow.SURFACE_P95], 4));
        check(near(v[FrameTimingWindow.CALLBACK_DELAY], 2) && near(v[FrameTimingWindow.PRESENT_IDLE], 10));
        check(near(v[FrameTimingWindow.CALLBACK_IDLE], 8) && near(v[FrameTimingWindow.BUDGET], 1000f/60));
        // A completed slow interval crosses sampling windows and is counted once.
        frame(w, 0, 200, last+100000000, last+102000000);
        s = w.drain(last+110000000); v = s.values;
        check(s.intervalsMs.length == 1 && near(s.intervalsMs[0], 100));
        check(v[FrameTimingWindow.SLOW] == 1 && v[FrameTimingWindow.SEVERE] == 1);
        check(near(v[FrameTimingWindow.EXCESS_PERCENT], 83.33333f));
        v = w.drain(last+1110000000).values;
        check(Float.isNaN(v[FrameTimingWindow.PRESENT_P95]) && Float.isNaN(v[FrameTimingWindow.SLOW]));
        check(near(v[FrameTimingWindow.PRESENT_IDLE], 1010)); // Growing age during an ongoing stall.
        // Batched callback arrival must not turn smooth presentation timestamps into spikes.
        w = window();
        for (int i = 0; i < 100; i++) frame(w, 0, i, 1000000000L+i*16000000L, 5000000000L);
        v = w.drain(5100000000L).values;
        check(near(v[FrameTimingWindow.PRESENT_PEAK], 16) && near(v[FrameTimingWindow.PRESENT_FPS], 62.5f));
        check(near(v[FrameTimingWindow.CALLBACK_DELAY], 4000));
        // Duplicate, unknown, and pre-reset callbacks must not create intervals.
        w.rendered(99, 2584000000L, 5100000000L); w.rendered(999, 3000000000L, 5100000000L);
        check(w.drain(5200000000L).timesNs.length == 0);
        w.decoded(1, 1000, 6000000000L); w.released(1, 6002000000L); w.reset();
        w.rendered(1000, 6006000000L, 6008000000L);
        check(w.drain(6100000000L).timesNs.length == 0);
        frame(w, 0, 1001, 7000000000L, 7001000000L);
        check(w.drain(7100000000L).timesNs.length == 0); // No huge recovery gap.
        w.decoded(7, 1002, 7200000000L); w.discarded(7); w.rendered(1002, 7205000000L, 7207000000L);
        check(w.drain(7300000000L).values[FrameTimingWindow.PENDING] == 0);
        // Reordered or impossible presentation timestamps are rejected, not plotted backwards.
        w.decoded(0, 1003, 6900000000L); w.released(0, 6902000000L);
        w.rendered(1003, 6906000000L, 7400000000L);
        v = w.drain(7500000000L).values; check(v[FrameTimingWindow.OVERFLOW] == 1);
        w.decoded(0, 1004, 7600000000L); w.released(0, 7602000000L);
        w.rendered(1004, 7601000000L, 7608000000L);
        check(w.drain(7700000000L).values[FrameTimingWindow.OVERFLOW] == 1);
        // Bounded tracking when Surface drops frames / callbacks never arrive.
        w = window();
        for (int i = 0; i < 400; i++) { w.decoded(0, i, 1000000000L+i*16000000L); w.released(0, 1001000000L+i*16000000L); }
        v = w.drain(9000000000L).values;
        check(v[FrameTimingWindow.PENDING] == 256 && v[FrameTimingWindow.OVERFLOW] == 144);
        check(Float.isNaN(v[FrameTimingWindow.PRESENT_FPS]));
        w = window();
        for (int i = 0; i < 257; i++) w.decoded(i, i, 1000000000);
        check(w.drain(1100000000).values[FrameTimingWindow.OVERFLOW] == 1);
        // Raw trace overflow is explicit; percentiles are recent, peak stays exact.
        w = window(); long time = 1000000000;
        frame(w, 0, 0, time, time+1000000);
        for (int i = 1; i <= 3500; i++) { time += i == 1 ? 500000000 : 16000000; frame(w, 0, i, time, time+1000000); }
        s = w.drain(time+10000000); v = s.values;
        check(s.timesNs.length == 2048 && Float.isNaN(s.intervalsMs[0]));
        check(v[FrameTimingWindow.OVERFLOW] == 1452 && v[FrameTimingWindow.PRESENT_PEAK] == 500);
        check(v[FrameTimingWindow.PRESENT_P99] == 16);
        // Concurrent render callbacks and UI drains cannot corrupt bounded state.
        final FrameTimingWindow concurrent = window();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try { for (int i = 0; i < 10000; i++) frame(concurrent, 0, i, 1000000000L+i*16000000L, 1001000000L+i*16000000L); }
            catch (Throwable t) { failure.set(t); }
        });
        producer.start();
        for (int i = 0; i < 1000; i++) { v = concurrent.drain(200000000000L).values; check(v[FrameTimingWindow.PENDING] <= 256); }
        producer.join(); check(failure.get() == null);
        FrameTimeHistory raw = new FrameTimeHistory(200);
        for (int i = 0; i < 100; i++) raw.add(i, i == 50 ? 250 : 16);
        float[] minimum = new float[10], maximum = new float[10];
        check(raw.buckets(0, 100, minimum, maximum) == 250);
        check(minimum[4] == 16 && maximum[4] == 250); // Peak survives pixel reduction.
        raw.clear(); raw.add(10, 16); raw.add(90, 16);
        raw.buckets(0, 100, minimum, maximum); check(Float.isNaN(minimum[4]));
        raw = new FrameTimeHistory(3); raw.add(1, 100); raw.add(2, 2); raw.add(3, 3); raw.add(4, 4); raw.add(0, 999);
        check(raw.buckets(0, 5, minimum, maximum) == 4);
        raw.clear(); check(raw.buckets(0, 5, minimum, maximum) == 0);
        // Selection must survive IDs >=32, preserve old selections and allow new charts to be disabled.
        long migrated = DiagnosticCharts.migrateSelection(5, true, true, false);
        check((migrated & ((1L<<29)-1)) == 5 && (migrated & (1L<<38)) != 0);
        long selected = migrated & ~(1L<<33);
        check(DiagnosticCharts.migrateSelection(selected, true, true, true) == selected);
        check((selected & (1L<<1)) == 0 && (selected & (1L<<33)) == 0);
        boolean[] seen = new boolean[DiagnosticCharts.GROUPS.length];
        for (int i = 0; i < seen.length; i++) { int group = DiagnosticCharts.displayGroup(i); check(!seen[group]); seen[group] = true; }
        check(DiagnosticCharts.displayGroup(0) == DiagnosticCharts.RAW_FRAME_GROUP);
        // Resource/collector/chart indices must match, in both supported dashboard languages.
        for (String directory : new String[]{"values", "values-zh-rCN"}) {
            var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse("app/src/main/res/"+directory+"/strings.xml");
            var arrays = document.getElementsByTagName("string-array");
            boolean charts = false, series = false;
            for (int i = 0; i < arrays.getLength(); i++) {
                var array = (org.w3c.dom.Element)arrays.item(i);
                if (array.getAttribute("name").equals("diag_column_options")) check(array.getElementsByTagName("item").getLength() == 5);
                if (array.getAttribute("name").equals("diag_chart_names")) { check(array.getElementsByTagName("item").getLength() == DiagnosticCharts.GROUPS.length); charts = true; }
                if (array.getAttribute("name").equals("diag_series_names")) { check(array.getElementsByTagName("item").getLength() == DiagnosticCharts.SERIES); series = true; }
            }
            check(charts && series);
        }
        System.out.println("PASS: " + checks + " frame timing, callback batching/recovery, raw peaks, concurrency and chart-selection assertions");
    }
}
