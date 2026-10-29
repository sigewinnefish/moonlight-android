# Native Multi-Touch

Based on official moonlight-stream/moonlight-android master b48494cb96bff23d8886c4775cc4f39a1075495d (also the Fork's starting HEAD). No third-party fork, protocol, JNI, dependency, signing or application ID changes.

Enable **Settings → Input settings → Native Multi-Touch** (原生多点触控). Default is off. Start a new stream after changing settings. On a host advertising Sunshine's `LI_FF_PEN_TOUCH_EVENTS`, this takes precedence over the trackpad setting. On unsupported hosts (including NVIDIA GameStream), the first DOWN returns `LI_ERR_UNSUPPORTED` and the entire gesture uses the original touch mode. Native gestures never also generate mouse input. Physical mouse/keyboard/controller/pen paths remain unchanged.

Native mode disables the three-finger software keyboard gesture on supported hosts: all three contacts belong to the host. Use a physical keyboard or turn native mode off for the original keyboard gesture. No substitute keyboard overlay was added.

## Implementation

`Game.trySendTouchEvent()` adapts MotionEvent to existing NvConnection → MoonBridge JNI → LiSendTouchEvent. NativeTouchState stores 32 possible Android pointer IDs in a bitset, never pointer indexes. MOVE iterates every current pointer; DOWN/UP use actionIndex only to look up the stable ID. Missing/duplicate contacts are cancelled defensively. FLAG_CANCELED uses single-pointer CANCEL; ACTION_CANCEL and lifecycle cleanup use CANCEL_ALL, never synthetic UP. Focus/pause/rotation/layout changes/ungrab/stop/disconnect cancel contacts and suppress late events until a new ACTION_DOWN. State is UI-thread-owned; the disconnect callback posts cleanup to that thread. Input enqueue failures consume the remainder of a native gesture and attempt CANCEL_ALL rather than falling back mid-gesture.

Coordinates use the measured StreamView video rectangle, including its parent offset, clamped to [0,1] for black bars. The current upstream renderer sizes that view to the video aspect ratio, or stretches it when configured; it does not implement an independent crop transform. Layout changes cancel the previous gesture before new coordinates are used. Pressure is clamped; contact ellipse and rotation are reported unknown. No per-event objects are allocated by the new adapter/state machine. A failed/disconnected transport cannot guarantee delivery of cleanup; host-side connection teardown remains necessary.

## Automated validation

Run `bash tests/native-touch/run.sh` with a JDK. It compiles the production state class with constants extracted from the real MoonBridge source, using a recording protocol sender without Android/JNI. It checks independent/reordered IDs, hold-and-tap, five contacts including ID 31, movement, either release order, reuse, duplicate DOWN/UP, single/all cancellation, missing pointers, lifecycle restart, unsupported hosts, enqueue failure, 1,000 rapid multi-pointer sequences and video-edge normalization. This is a state/coordinate test, not a device MotionEvent or Windows injection test.

Build with the repository wrapper and SDK/NDK pins:

```
bash gradlew --no-daemon --max-workers=4 :app:assembleNonRootDebug :app:lintNonRootDebug
```

The workflow `.github/workflows/android-debug.yml` runs tests, build and lint on pushes/PRs and uploads `moonlight-native-multitouch-debug`. The debug package remains `com.limelight.debug`, co-installable with official `com.limelight`. It can conflict with another debug build signed by a different key; do not uninstall an existing app without backing up its settings.

## Required device acceptance (not performed in cloud)

Use current Sunshine on Windows with input/touch injection enabled and a touch-aware application. Match the streamed display to the application's display. Sunshine's advertised protocol capability is not proof that the Windows application accepts injected touches. Unity/game UI must itself support touch input; this patch cannot turn a mouse-only UI into a touch UI.

1. Install the Debug APK, enable Native Multi-Touch and reconnect. Use a Windows touch visualizer that shows contact IDs/count (a mouse cursor alone is insufficient).
2. Verify one contact, then two, then five simultaneously; hold the left finger on a joystick while repeatedly tapping with the right. Cross fingers and release in either order; every ID must remain independent and all contacts must disappear.
3. Verify three contacts stay three contacts and do not summon Android's keyboard. Try rapid taps, edge/black-bar touches and both aspect-fit and stretch modes.
4. While fingers are down, open a system overlay, switch apps, rotate, disconnect and leave the stream. Verify no stuck contacts after returning/reconnecting.
5. Disable Native Multi-Touch and reconnect: verify original trackpad, absolute mouse and three-finger keyboard behavior. Check physical keyboard, mouse and controller separately.
6. Test an unsupported host: verify original mouse mode and no duplicate injection. Finally test the target Unity game and its native touch UI.

Keep changes isolated when syncing upstream. Review upstream touch routing, StreamView transforms and common-c cancellation/capability semantics before rebasing. Historical issues #1271/#1297 and PR #1364 were reviewed; the latter's configurable gesture/enhancement framework was not adopted.
