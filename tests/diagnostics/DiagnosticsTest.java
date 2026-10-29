import com.limelight.diagnostics.LatencyWindow;
import com.limelight.diagnostics.TrendHistory;

public final class DiagnosticsTest {
    private static int checks;
    private static void check(boolean v) {
        checks++;
        if (!v) throw new AssertionError("Check " + checks);
    }
    public static void main(String[] args) {
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
        System.out.println("PASS: " + checks + " diagnostics assertions");
    }
}
