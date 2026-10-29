import com.limelight.nvstream.input.HeldInputState;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

public final class HeldInputStateTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Held input check " + checks); }
    public static void main(String[] args) throws Exception {
        HeldInputState state = new HeldInputState();
        ArrayList<String> releases = new ArrayList<>();
        HeldInputState.ReleaseListener sink = new HeldInputState.ReleaseListener() {
            public void keyUp(short code, byte flags) { releases.add("k:"+(code&0xffff)+":"+(flags&0xff)); }
            public void mouseUp(byte button) { releases.add("m:"+(button&0xff)); }
        };
        state.release(sink); check(releases.isEmpty());
        state.key((short)0x8041, (byte)1, true); state.key((short)0x8041, (byte)1, true);
        state.key((short)0x80a0, (byte)0, true); state.key((short)0x80a0, (byte)0, false);
        state.mouse((byte)1, true); state.mouse((byte)2, true); state.mouse((byte)2, false);
        state.mouse((byte)255, true);
        state.release(sink);
        check(releases.size() == 3 && releases.contains("k:32833:1"));
        check(releases.contains("m:1") && releases.contains("m:255"));
        releases.clear(); state.release(sink); check(releases.isEmpty());
        for (int i = 0; i < 256; i++) { state.key((short)(0x8000+i), (byte)i, true); state.mouse((byte)i, true); }
        state.release(sink); check(releases.size() == 512);
        check(releases.contains("k:33023:255") && releases.contains("m:0"));
        releases.clear(); state.key((short)0x8041, (byte)0, true); state.release(sink);
        check(releases.size() == 1 && releases.get(0).equals("k:32833:0"));
        // Production callers serialize state recording, original JNI sends and
        // background cleanup together; stress the same locking contract.
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread sender = new Thread(() -> {
            try { for (int i = 0; i < 10000; i++) synchronized (state) {
                state.key((short)(0x8000+i%256), (byte)0, true);
                state.key((short)(0x8000+i%256), (byte)0, false);
                state.mouse((byte)1, true); state.mouse((byte)1, false);
            } }
            catch (Throwable t) { failure.set(t); }
        });
        sender.start(); releases.clear();
        for (int i = 0; i < 1000; i++) synchronized (state) { state.release(sink); }
        sender.join(); check(failure.get() == null && releases.isEmpty());
        // Verify the Android declarations that make an ongoing hidden session possible.
        var document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse("app/src/main/AndroidManifest.xml");
        var services = document.getElementsByTagName("service"); boolean serviceFound = false;
        for (int i = 0; i < services.getLength(); i++) {
            var service = (org.w3c.dom.Element)services.item(i);
            if (service.getAttribute("android:name").equals(".StreamPlaybackService")) {
                check(service.getAttribute("android:exported").equals("false"));
                check(service.getAttribute("android:foregroundServiceType").equals("mediaPlayback")); serviceFound = true;
            }
        }
        check(serviceFound);
        var activities = document.getElementsByTagName("activity"); boolean activityFound = false;
        for (int i = 0; i < activities.getLength(); i++) {
            var activity = (org.w3c.dom.Element)activities.item(i);
            if (activity.getAttribute("android:name").equals(".Game")) {
                check(!activity.getAttribute("android:noHistory").equals("true"));
                check(activity.getAttribute("android:excludeFromRecents").equals("false")); activityFound = true;
            }
        }
        check(activityFound);
        var permissions = document.getElementsByTagName("uses-permission"); int foregroundPermissions = 0;
        for (int i = 0; i < permissions.getLength(); i++) {
            String name = ((org.w3c.dom.Element)permissions.item(i)).getAttribute("android:name");
            if (name.equals("android.permission.FOREGROUND_SERVICE") || name.equals("android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK")) foregroundPermissions++;
        }
        check(foregroundPermissions == 2);
        System.out.println("PASS: " + checks + " held-input cleanup and background service/Activity manifest checks");
    }
}
