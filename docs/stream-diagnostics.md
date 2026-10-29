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

## Network diagnostics (v4)

Fifteen additional selectable network charts bring the dashboard to 29 groups. Existing per-chart settings are retained; the new network charts are enabled once on upgrade and can then be individually hidden. Sampling follows the selected 100–2000 ms interval except Wi-Fi service information (approximately five seconds) and ENet's internally smoothed loss estimate.

- **RTP ingress Mbps and packet/s, video/audio:** observe the real `RtpvAddPacket` / `RtpaAddPacket` entries. Video is observed after its pre-queue decryption; audio is observed before later audio payload decoding/decryption. Queue-level invalid packets can still be included. These include RTP/FEC and duplicate packets that reach those entry points, not just decoded video payload. They exclude packets discarded before the queue (e.g. video authentication failure/startup audio drops), encryption wrappers and IP/UDP/link headers. They are not total physical-link throughput.
- **RTP delivery gap peak and idle age:** measured with a monotonic clock at those entry points. Gaps also include receive-thread scheduling, decryption and queue processing delays, so a gap is not by itself proof of a wireless interruption. Idle age keeps increasing even when no new RTP arrives; no fabricated arrival interval is inserted for an empty window.
- **Video late/duplicate/forward-gap hints:** sequence comparison supports 16-bit wrap. Late counts include older duplicate arrivals; recent duplicates are identified within a 1,024-sequence window. A forward sequence jump can later be filled by reordering and is explicitly **not final packet loss**. Very old packets outside that window are not classified as confirmed duplicates.
- **Video FEC recovered packets, reported missing data and failed block reports:** observe existing final FEC reports, with recent frame/block deduplication. A reported block with enough data+parity corresponds to upstream recovery; deficient reports count failed blocks. Missing data counts include repaired and unrepaired deficits. Completely unobserved frames/blocks cannot all be reconstructed from these reports. No overall wire packet-loss percentage is invented from partial FEC reports. In this common-c revision the similarly named public video recovery/OOS counters are not populated, so they are intentionally not displayed as zero.
- **Audio FEC recovered packets, failure events, late/invalid data/FEC packets, data/FEC rates:** use the real cumulative audio queue counters, converted to interval rates with unsigned counter-wrap handling. Audio failure events are not packets; legends identify the units. Counter snapshots follow audio input processing, so a final event can appear on the next packet.
- **Control UDP payload TX/RX Mbps and datagram/s:** inspect ENet host byte/datagram counters after its normal service call, while the caller still owns its transport mutex. Includes control traffic/retransmission payload as accounted by ENet, excludes wire headers, and is not touch-only traffic. No host/peer pointer is retained; no ENet structures are changed.
- **Control reliable loss estimate and unacknowledged reliable bytes:** ENet's aggregate reliable-packet loss estimate and `reliableDataInTransit`. The estimate is internally smoothed and may remain unchanged across multiple 100 ms display samples. It is not a touch-channel RTT, MOVE loss rate, retransmission count or host-injection acknowledgement. Control metric age is shown; old control snapshots become unavailable.
- **Wi-Fi RSSI and negotiated Mbps:** RSSI uses a proper negative dBm axis; negotiated link rate is not throughput. Android permission/device redaction yields missing samples; no new permissions or SSID/BSSID logging are added.

The observers preserve original call arguments/return values and leave the common-c submodule/protocol untouched. Only bounded counters/window metadata are kept, with no packet payloads, remote coordinates or packet capture files. Network collectors are disabled with the performance overlay. Tests cover sequence wrap, reordering versus duplicate classification, forward holes later filled, FEC report deduplication, counter resets/32-bit wrap, absent metrics, throughput/rate normalization and concurrent audio/video producers. Actual Wi-Fi/Windows diagnosis still requires device reproduction.

For the periodic touch freeze, compare the same time interval across MOVE-stage charts, control loss/in-flight bytes, video/audio RTP idle ages and FEC events. Normal video/audio while control evidence changes supports investigating the control path; normal local handoff/control aggregate metrics still do not rule out a touch-channel or host-injection issue.

## Floating grid layout and immersive windows (v4)

Both HUD and dashboard can be moved: use the HUD's dotted handle or the dashboard title bar. Drag the dashboard bottom resize bar to change width/height; position and dimensions are saved as viewport fractions. Bounds and minimum sizes are clamped so controls remain reachable after rotation or shrinking. Default dashboard size is 75% viewport width and 78% height, leaving the stream visible; it has no dimming scrim and does not consume outside-window touches. Opening the focusable dashboard still releases existing remote contacts; fresh game touches outside the panel remain subject to Android window-focus behavior and require device acceptance.

The detailed dashboard is a floating application dialog, not a system overlay and requires no overlay permission. Its scrollable grid uses automatic 1/2/3 columns by available width (2 columns at 420 dp, 3 at 760 dp); Settings can force 1, 2 or 3. Resizing reflows chart cells without discarding history. Detailed text is 10 sp and hidden by default behind the **Details / 文字数据** toolbar toggle. Compact chart titles/legends use ellipsis and right-aligned values so narrow grid cells do not draw legends over one another. Full chart names remain accessibility descriptions and settings labels.

Dashboard, settings and option dialogs explicitly inherit immersive/fullscreen behavior before focus is acquired, using WindowInsetsController on API 30+ and compatible system-UI flags on older Android. Gesture-triggered transient system bars remain normal Android behavior. Dialogs/option pickers and root layout listeners are cleaned up when the HUD detaches. None of these actions changes the streamed video's layout or its touch coordinate mapping.

Device checks still needed: drag HUD/title; resize from minimum to maximum; rotate; confirm bounds/persistence and grid reflow; open/close dashboard/settings/option pickers without unsolicited status/navigation bars; test Back and Close; inspect 1/2/3-column readability, RTL layouts and fresh input outside the floating window. Cloud host tests validate clamping/columns but do not prove Android window behavior or actual Canvas appearance.

## HUD clipping and settings refresh fixes (v5)

The small HUD now measures an explicit bounded width so weighted header buttons and mini-charts cannot shrink independently. A height budget reserves complete controls first, limits text lines, and sizes only complete mini-charts into the remaining space. Small/large-font viewports reduce the HUD contents with an explicit “open Dashboard for all data” notice; the detailed panel retains all data. Fallback is deliberate hiding/ellipsis rather than allowing children to extend beyond a clipped HUD rectangle. Geometry uses the actual parent content bounds/padding, not the larger window decor; folding/height changes clamp the current position instead of jumping to a saved fraction of a different-sized free area.

Applying settings updates chart selection/columns inside the existing dashboard window. It no longer dismisses/reopens the dialog. Dialog dismiss callbacks are asynchronous, so an old callback previously could clear the newly opened grid and shared chart references. Cleanup now checks the exact dialog instance, and Close targets that instance. The in-place path preserves current bounds, paused state, text visibility and chart history.

Host tests exercise HUD height budgets across short viewports/font heights, including controls-only and reduced-chart fallback. Real-device acceptance: use compact and expanded HUD text, large system fonts, fold/unfold, rotate, drag to each edge; verify controls and mini-chart axes remain complete. With the dashboard open/frozen/resized, save column/chart/refresh settings repeatedly and cancel settings; confirm the same panel remains populated and its geometry/history/freeze state are preserved. Test fast close/reopen. Android UI interactions remain unverified in cloud.

## Opacity and pass-through controls (v5)

Settings has separate 30–100% controls with numeric values for HUD background opacity and detailed-window opacity. Mini-chart backgrounds no longer paint an additional opaque layer over the HUD transparency. The detailed window uses its actual WindowManager alpha. Controls remain available at the minimum opacity.

Both HUD and dashboard have a **Pass through / 透传** control, changing to **Cancel pass / 取消透传** when active:

- HUD mode disables click/long-click/drag interception on every header control except Cancel pass. The text and mini-charts were already passive. Ordinary touches fall through the Android view hierarchy to the existing stream input path.
- Detailed mode applies `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE` to the diagnostic window. It does not synthesize or manually forward MotionEvents. An independent non-focusable, non-modal PopupWindow contains only Cancel pass, aligned over the toolbar control. The rest of the diagnostic window, including its drag/resize/scroll areas, stops taking input. The underlying HUD is temporarily put into cancel-only mode too, so overlapping HUD controls cannot steal those clicks; its prior mode is restored when detailed pass-through ends. Android routes those touches to the underlying stream, preserving the existing input mode and pointer coordinates.
- Toggling modes releases active native remote contacts before ownership changes; fresh touches use the original Game path. Cancel restores interactive window flags and immersive mode. Android Back exits active pass-through before its normal stream-back action. Entering settings exits detailed pass-through; backgrounding/detaching the activity cleans up modes/popups. Mode state is not persisted, to avoid returning to a non-interactive panel unexpectedly.

These are application-owned windows and require no system-overlay permission. This is not a global input injector: underlying virtual-controller or trackpad settings still determine the remote input behavior. The pass-through cancellation window, multi-pointer delivery, touch ownership during mode changes, rotation, mouse/keyboard behavior and OEM window policies require actual Android-device acceptance; host tests and compilation cannot prove them. If showing the cancel window fails, the diagnostic window remains interactive instead of trapping the user.

Acceptance: verify small-HUD clipping with normal/large fonts; repeatedly apply chart/column/opacity settings with the dashboard open/frozen; verify independent alpha values; enable each pass-through mode and drag/tap through text/charts/disabled controls, confirm remote motion without local scrolling/resizing; cancel with the dedicated button and Back; verify two fingers outside the cancel control, rotate, and background/resume. The original stream UI and coordinates must remain unchanged.

## Independent HUD windows after clipping screenshots (v6)

The v5 size-budget change did **not** resolve clipping on the reported device. Screenshots show a hard rectangular cut through the small HUD, including its toolbar, even in the middle of the screen; the independent detailed dialog renders correctly. This is consistent with an inline translated overlay interacting with the transparent streaming SurfaceView/window region, but the exact OEM rendering cause has not been reproduced in cloud.

The small HUD now uses application-owned PopupWindows. Its Activity-layout view is only a zero-size lifecycle/data controller; no visible HUD pixels use `View.setX/setY` or occupy the former layout rectangle. Dragging updates actual window coordinates. The HUD body has a bounded width/natural height and remains non-touchable/non-focusable. A separate header window occupies only the reserved toolbar strip, preserving direct stream input through text/charts. HUD pass-through makes the whole header window non-touchable too and supplies an independent Cancel pass button. No event copying, video resizing, overlay permission or new dependency is involved. The existing detailed dialog/grid is retained.

Both HUD windows are dismissed on hiding/detaching the controller or hiding the Activity window and restored from saved viewport-relative coordinates when shown again. Window dismissal callbacks check instance identity, and transitions are disabled to allow immediate reuse of the content. Rotation and folding clamp the whole window, including controls, to the current content viewport. Missing HUD measurements now display an em dash rather than `NaN`.

Validation: all four host regression suites, complete four-ABI Debug APK build and Android lint; signing/package comparison against v5. These do not execute Android window composition. Device acceptance remains necessary: inspect all four HUD borders immediately after opening, drag it through the screen centre and to each edge, fold/unfold, change compact text/font size/opacity, apply settings with the dashboard open, rotate, background/resume, and toggle each pass-through mode. Verify all four toolbar buttons remain reachable, both mini-chart axes are complete when space allows, and the Windows stream receives fresh touches through the HUD body/pass-through toolbar. Compare the small HUD with the already-working detailed window.
