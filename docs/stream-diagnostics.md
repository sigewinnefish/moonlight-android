# Streaming diagnostics

Enable the existing **Display performance overlay / 启用性能信息** preference, then start a stream. This branch includes the tested native multi-touch feature; master remains the upstream baseline.

The overlay retains original resolution/FPS/decoder/frame-loss/host-processing/decode statistics and adds:

- Active Android network transport; on Wi-Fi, RSSI, frequency and negotiated link rate when Android exposes them. No new permissions, SSID or BSSID collection. VPNs/multiple interfaces can hide the actual route to the host. Negotiated rate is not measured throughput.
- Compressed video payload Mbps (decode units only, not wire throughput, audio, FEC or retransmission overhead).
- Maximum interval between first-packet receive timestamps of distinct submitted video frames in the video sample window. This is not packet jitter, nor proof of an input-network fault. Lost frames or host pacing can also increase it.
- Client video pipeline average (upstream receive-to-decoder-output accounting; does not include touch delivery, the host game or physical display scanout).
- Touch MotionEvent count, current Android finger count, dispatch age P95 and exact interval peak. Age is Android uptime at the finger handler minus the current event timestamp. It does not include historical batched points and cannot measure motion-to-Android sampling delay.
- Native touch submission count, P95/peak local call duration, errors and unsupported-host results. This times NvConnection/JNI enqueue only. It is **not** transport delivery or a host acknowledgement. No send-time samples exist in legacy mouse/trackpad mode; NaN is intentional.
- Video sample age and main-thread sampling lateness. Video reports older than 2.5 seconds are explicitly marked stale; the independent UI timer continues updating even without incoming video. Graphs omit stale/missing samples rather than treating them as zero.
- Approximately 60 seconds of trend history: cyan RTT, gold RTT variation estimate, red video frame-arrival gap; green touch dispatch peak, purple native submission peak. Both panels show their current millisecond scale. Long UI pauses insert missing samples. There is no input-idle latency sample; graph gaps while not touching are normal.

The original video counters remain about two-second rolling statistics. Added video counters cover about one second and update when a frame arrives. Input counters reset at each UI tick (normally one second; delayed ticks cover a longer interval). Percentiles use the latest 256 observations in that interval; count/mean/peak include the entire interval. The overlay is passive, does not intercept touches and is absent unless the existing preference is enabled. UI text/snapshots update at 1 Hz; hot input paths use fixed-size storage. Stopping/destroying the stream removes the refresh callback. The text shrinks to fit landscape screens; large font settings/tiny screens still require device acceptance.

## Compare wired vs wireless

1. Use the same host/display, stream resolution/FPS/bitrate and game scene. Enable the overlay in both tests. First record a wired baseline, then Wi-Fi near the AP and at the problematic location.
2. Hold one finger and tap/move another until the fault occurs. Capture the screen with a second device if possible (Android screen recording itself can add load).
3. Rising green dispatch age or UI tick delay suggests Android scheduling/input delivery delay. Rising purple submission time/errors suggests a local submission problem. Neither conclusion proves a root cause on its own.
4. Smooth input curves but spikes in RTT/variation/frame gap, frame loss, or video staleness suggest a network/host/video issue. The control-stream RTT is not a separate input-channel RTT, so smooth RTT does not exclude wireless input stalls.
5. If all client metrics stay normal but the Windows application reacts late, host input injection/game processing/return video latency still need host-side instrumentation. This patch cannot measure touch-to-photon or Windows touch ACK latency with the existing protocol.

## Validation

`bash tests/diagnostics/run.sh` tests missing samples, negative/non-finite input, percentile rounding, bounded-window wrapping, exact interval peaks, 1,000 drain cycles, 60-slot history wrapping and graph scale. `bash tests/native-touch/run.sh` remains the input regression suite. CI runs both plus the complete nonRoot Debug build/lint. These host tests do not verify Canvas rendering, Wi-Fi measurements or an actual wireless stall; those require Android hardware.

No new protocol, service, third-party dependency, permission or environment publication. Diff is on `feature/stream-diagnostics`, based on `feature/native-multitouch`, to keep the existing multi-touch PR and upstream master separate.
