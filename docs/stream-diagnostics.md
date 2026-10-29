# Streaming diagnostics

Enable the existing **Display performance overlay / 启用性能信息** preference, then start a stream. This branch includes the tested native multi-touch feature; master remains the upstream baseline.

The overlay retains original resolution/FPS/decoder/frame-loss/host-processing/decode statistics and adds:

- Active Android network transport (polled every five seconds on a dedicated background worker, stopped with the stream); on Wi-Fi, RSSI, frequency and negotiated link rate when Android exposes them. No new permissions, SSID or BSSID collection. VPNs/multiple interfaces can hide the actual route to the host. Negotiated rate is not measured throughput.
- Compressed video payload Mbps (decode units only, not wire throughput, audio, FEC or retransmission overhead).
- Maximum interval between first-packet receive timestamps of distinct submitted video frames in the video sample window. This is not packet jitter, nor proof of an input-network fault. Lost frames or host pacing can also increase it.
- Client video pipeline average (upstream receive-to-decoder-output accounting; does not include touch delivery, the host game or physical display scanout).
- Touch MotionEvent count, current Android finger count, dispatch age P95 and exact interval peak. Age is Android uptime at the finger handler minus the current event timestamp. It does not include historical batched points and cannot measure motion-to-Android sampling delay.
- Native touch submission count, P95/peak local call duration, errors and unsupported-host results. This times NvConnection/JNI enqueue only. It is **not** transport delivery or a host acknowledgement. No send-time samples exist in legacy mouse/trackpad mode; NaN is intentional.
- Video sample age and main-thread sampling lateness. Video reports older than max(500 ms, three display intervals) are explicitly marked stale; the independent UI timer continues updating even without incoming video. Graphs omit stale/missing samples rather than treating them as zero.
- Approximately 60 seconds of trend history: cyan RTT, gold RTT variation estimate, red video frame-arrival gap; green touch dispatch peak, purple native submission peak. Both panels show their current millisecond scale. Long UI pauses insert missing samples. There is no input-idle latency sample; graph gaps while not touching are normal.

The original video counters remain about two-second rolling statistics. Added video counters cover the selected interval and update when a frame arrives. Input counters reset at each UI tick (default 250 ms; delayed ticks cover a longer interval). Percentiles use the latest 256 observations in that interval; count/mean/peak include the entire interval. The HUD text/mini-charts are passive; only the explicit header controls consume touches. The entire overlay is absent unless the existing preference is enabled. UI text/snapshots update at the selected frequency (default 4 Hz); hot input paths use fixed-size storage. Stopping/destroying the stream removes the refresh callback. The text shrinks to fit landscape screens; large font settings/tiny screens still require device acceptance.

## Compare wired vs wireless

1. Use the same host/display, stream resolution/FPS/bitrate and game scene. Enable the overlay in both tests. First record a wired baseline, then Wi-Fi near the AP and at the problematic location.
2. Hold one finger and tap/move another until the fault occurs. Capture the screen with a second device if possible (Android screen recording itself can add load).
3. Rising green dispatch age or UI tick delay suggests Android scheduling/input delivery delay. Rising purple submission time/errors suggests a local submission problem. Neither conclusion proves a root cause on its own.
4. Smooth input curves but spikes in RTT/variation/frame gap, frame loss, or video staleness suggest a network/host/video issue. The control-stream RTT is not a separate input-channel RTT, so smooth RTT does not exclude wireless input stalls.
5. If all client metrics stay normal but the Windows application reacts late, host input injection/game processing/return video latency still need host-side instrumentation. This patch cannot measure touch-to-photon or Windows touch ACK latency with the existing protocol.

## Validation

`bash tests/diagnostics/run.sh` tests missing samples, negative/non-finite input, percentile rounding, bounded-window wrapping, exact interval peaks, 1,000 drain cycles, 60-slot history wrapping and graph scale. `bash tests/native-touch/run.sh` remains the input regression suite. CI runs both plus the complete nonRoot Debug build/lint. These host tests do not verify Canvas rendering, Wi-Fi measurements or an actual wireless stall; those require Android hardware.

No new protocol, service, third-party dependency, permission or environment publication. Diff is on `feature/stream-diagnostics`, based on `feature/native-multitouch`, to keep the existing multi-touch PR and upstream master separate.

## Signing and updates

Android updates require the same application ID **and signing certificate**. Early Actions builds generated temporary keys independently; their private keys were not retained. Those installations cannot be updated with a differently signed APK, even when both signatures verify successfully. Do not uninstall a working installation just to diagnose networking.

For a parallel install that preserves the old application/data:

```
bash gradlew -I scripts/diagnostics-side-by-side.init.gradle :app:assembleNonRootDebug
```

This optional build uses `com.limelight.debug.diagnostics` and the label `Moonlight Diagnostics`. It starts with its own settings/pairing and does not migrate the other app's private data. Normal builds retain the upstream debug application ID.

The current workspace keeps a stable debug key in `/workspace/.android/debug.keystore`. Keep that private key backed up securely; never commit it or include it in APK artifacts. For stable Actions signing, an administrator must configure repository Actions Secret `MOONLIGHT_DEBUG_KEYSTORE_BASE64` with the base64-encoded keystore (standard Android debug alias/password). The current GitHub integration cannot read/write Actions Secrets (HTTP 403). The workflow restores the secret when available and otherwise explicitly warns that its APK is for fresh installation only. Artifacts include the public signing certificate digest; compare it with the installed/baseline APK before promising an upgrade. This debug key is not a production release key.

## Advanced dashboard (v2)

The HUD header now has **Hide/Show**, **Dashboard** and **Settings** buttons (收起/显示、展开面板、设置). Only these controls accept touches; the HUD text and mini-charts remain passive. The default compact HUD avoids covering the whole game; opening Dashboard shows full statistics and a scrollable set of **10 chart groups**:

1. Control-stream RTT and variation estimate.
2. Video frame first-packet arrival interval peak.
3. Touch dispatch age P95 and peak.
4. Native touch enqueue duration P95 and peak.
5. Received and rendered FPS.
6. Decoder/client pipeline/host-processing means (different pipeline measurements; not additive input latency).
7. Compressed video payload Mbps.
8. Video frame loss percentage (not packet loss).
9. UI sampler lateness.
10. Current Android contact count and interval native-submit errors.

Each chart has a labeled time axis, three numeric Y-axis ticks, explicit units, subtle gridlines, rounded dark cards, colored-dot series legends and latest values. Missing latest values display an em dash. There is no invented measurement for unavailable/idle data; NaN produces gaps. Charts use monotonic timestamps rather than assuming every callback occurred on time. Up to 3,000 timestamped samples are retained, supporting a 5-minute view at 100 ms. Video receive/render FPS, payload rate, frame loss, frame-gap peaks, decoder means and host-processing means use a real short-window collector at the chosen interval (only when video arrives); they are not synthetic interpolation. The original text and client pipeline mean still use the upstream approximately two-second video statistics.

Settings persist independently of the regular Moonlight preferences: select individual charts, history window (30/60/180/300 seconds), display/input/video short-window sampling (100/250/500/1000/2000 ms; default 250 ms), HUD opacity (30–100%), and compact HUD. Full statistics are always in the dashboard; the expanded HUD text is limited to ten lines to avoid covering a small screen. Defaults restores only these diagnostics settings. Freeze pauses displayed snapshots/history while collection continues; Resume inserts a gap. Clear history resets charts. Closing the dashboard resumes the HUD. Adjusting settings preserves a frozen dashboard. The 100 ms mode provides 10 chart updates per second, not a 60 Hz animation. Short-window FPS naturally fluctuates more than a multi-second mean; compare peaks with a wired baseline before diagnosing a fault. Wi-Fi service polling stays at five seconds on a background worker. Opening a modal panel causes normal focus-loss cancellation of active remote contacts; game input is not forwarded through the settings/dashboard.

Device acceptance: update the previous **parallel-install** Moonlight Diagnostics package (same certificate/package verified before delivery), enable the existing performance-overlay preference, open Dashboard and scroll through all ten charts. Toggle chart groups, change interval/window/opacity, close/reopen the stream and verify persistence. Freeze during a spike, change a setting and confirm it stays frozen, then resume; clear history. Check Hide/Show and tap mini-chart/text regions to ensure gameplay still receives those touches. While holding remote fingers, open Settings and confirm host contacts cancel safely. Actual Canvas layout and these UI interactions still require Android-device validation.

## Touch-only periodic freeze investigation (v3)

For freezes of existing finger motion while video/audio/gamepad stay smooth, four dedicated chart groups are added (14 total): Android/native/control-handoff MOVE rates, JNI-entry-to-control wait peak and oldest pending age, pending touch count, and control transport call duration. They default on even when a previous chart selection existed; subsequent settings remain user-selectable.

A native binding-side linker observer wraps `sendInputPacketOnControlStream` without changing its bytes, flags, channel, ordering, return value or original call. JNI timestamps a touch request before common-c enqueue; the observer matches FIFO requests by event type/pointer ID when the input thread hands the packet to control transport. A 512-slot bounded table and mutex-protected counters allocate no memory in the event path. Requests rejected by common-c are removed and counted. Queue wait includes the short JNI/common-c enqueue overhead. Control call duration includes an in-flight call even before it returns. Counts cover all touch types, while stage-rate charts specifically compare MOVE updates per pointer.

**Control handoff is not NIC transmission, ENet acknowledgement, Sunshine receipt or Windows injection.** The observer cannot diagnose ENet per-channel retransmissions or confirm host injection. A trace overflow or unrecognized touch-channel packet marks queue evidence unavailable rather than attributing it to a confirmed queue stall. Inspection is enabled only with the performance overlay; the common-c submodule and network protocol are unchanged. On upstream updates, recheck the wrapped symbol, packet layout and touch channel constant (currently 0x05). Unsupported layouts retain original sending behavior.

Reproduce with 100 ms sampling and 300 s history. Keep a finger **continuously moving**, because a stationary finger legitimately has no MOVE events. After a freeze, open Dashboard and freeze the charts. Compare the same time across stages:

- Android rate falls while a finger really kept moving: investigate device event delivery/UI scheduling; corroborate dispatch age/UI delay. It does not prove a Wi-Fi fault.
- Android/native rates continue, but handoff rate drops and pending age/depth rises: evidence of local queue/control handoff delay.
- Handoff continues normally, local queue/call times remain low, but Windows motion freezes: investigate transport after handoff or host processing. Smooth generic RTT does not exclude touch-channel issues.

Native tests exercise disabled/non-touch passthrough, exact argument/return preservation, FIFO matching, pending-age persistence, rejected requests, control errors, capacity overflow and unsupported trace format. The built library's input-call linkage is checked separately. No actual wireless stall or Windows input trace has been observed in cloud.
