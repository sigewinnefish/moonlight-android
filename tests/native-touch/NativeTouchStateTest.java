import com.limelight.binding.input.touch.NativeTouchState;
import com.limelight.nvstream.jni.MoonBridge;
import java.util.ArrayList;
import java.util.List;

public class NativeTouchStateTest {
    private static final List<String> events = new ArrayList<>();
    private static int failType = -1;
    private static int failure = -1;
    private static int checks;
    private static final NativeTouchState state = new NativeTouchState((type, id, x, y, p) -> {
        events.add(type + ":" + id);
        return type == failType ? failure : 0;
    });
    private static void expect(String... expected) {
        checks++;
        if (!events.equals(java.util.Arrays.asList(expected))) {
            throw new AssertionError(events + " != " + java.util.Arrays.toString(expected));
        }
        events.clear();
    }
    private static void down(int id) { state.down(id, .25f, .75f, 1); }
    private static void move(int id) { state.move(id, .75f, .25f, 1); }
    private static void up(int id) { state.up(id, .75f, .25f, 0, false); }
    public static void main(String[] args) {
        state.begin(); down(7); move(7); up(7); expect("1:7", "3:7", "2:7");
        for (boolean reverse : new boolean[]{false, true}) {
            state.begin(); down(9); down(2); move(2); move(9);
            up(reverse ? 9 : 2); move(reverse ? 2 : 9); up(reverse ? 2 : 9);
            expect("1:9", "1:2", "3:2", "3:9", reverse ? "2:9" : "2:2",
                    reverse ? "3:2" : "3:9", reverse ? "2:2" : "2:9");
        }
        state.begin(); down(0); down(4); down(8); down(12); down(31);
        move(31); up(8); down(8); state.cancel(); move(4); up(4); down(4);
        expect("1:0", "1:4", "1:8", "1:12", "1:31", "3:31", "2:8", "1:8", "7:0");
        state.begin(); down(3); down(3); up(3); up(3);
        expect("1:3", "4:3", "1:3", "2:3");
        state.begin(); down(1); down(5); state.reconcile(1L << 5); up(5);
        expect("1:1", "1:5", "4:1", "2:5");
        state.begin(); down(6); state.up(6, 0, 0, 0, true); expect("1:6", "4:6");
        state.begin(); down(1); state.begin(); down(2); state.cancel(); state.cancel();
        expect("1:1", "7:0", "1:2", "7:0");
        failType = MoonBridge.LI_TOUCH_EVENT_DOWN; failure = MoonBridge.LI_ERR_UNSUPPORTED;
        state.begin();
        if (state.down(1, 0, 0, 1)) throw new AssertionError("Unsupported host must fall back");
        expect("1:1");
        failType = -1; state.begin(); down(1);
        failType = MoonBridge.LI_TOUCH_EVENT_MOVE; failure = -1; move(1); move(1); up(1); down(2);
        expect("1:1", "3:1", "7:0");
        failType = -1;
        for (int i = 0; i < 1000; i++) {
            state.begin(); down(1); down(2); up(2); down(2); up(1); up(2);
            expect("1:1", "1:2", "2:2", "1:2", "2:1", "2:2");
        }
        if (NativeTouchState.normalize(150, 50, 200) != .5f ||
                NativeTouchState.normalize(0, 50, 200) != 0 ||
                NativeTouchState.normalize(300, 50, 200) != 1) throw new AssertionError("Video bounds");
        System.out.println("PASS: " + checks + " protocol sequence checks and coordinate checks");
    }
}
