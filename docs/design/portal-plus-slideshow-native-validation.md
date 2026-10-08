# Portal+ native slideshow validation

Implementation branch: `codex/portal-plus-slideshow-overhaul`.
Preserved UX baseline: `2cbb603` on `codex/portal-plus-approved-ux`.

The final production candidate passed the controlled checks and two-hour Smart
frame loop below. These measurements apply to the connected Portal+ and owned
fixtures; coverage limits are stated at the end.

## Implementation

- Native Smart frame, portrait-pair and whole-photo modes; explicit legacy style
  IDs remain compatible and available.
- Two composition layers, a readiness-gated 900 ms dissolve, bounded upright
  decoding and one latest-request preparation worker with one lookahead photo.
- Shared 48 MiB admission covers retained photo pixels and decoder/EXIF scratch.
  It excludes encoded buffers, SDK allocations, GPU textures and delayed GC;
  process memory is measured separately.
- People framing uses owned small analysis derivatives, versioned bounded metadata
  and a safe whole-photo fallback. Cached geometry is applied to future scenes,
  without changing the visible composition during a slide.
- Deliberate Photos controls: Previous, Pause/Resume, Next, Adjust and Done.
  Saved zoom/focus is non-destructive. Idle Dream keeps its dismissal policy.
- Source discovery, authentication, cache, video, overlays and power policy remain
  attached to the existing controller rather than a second source implementation.

## Hardware and builds

Connected Portal+: Android 10/API 29, arm64, 2160 × 1440, 60 Hz, Adreno 615,
2,848,792 KiB OS-reported RAM. Tests use Wi-Fi ADB with USB disconnected.

Initial integrated checks passed 684 JVM tests. After navigation and shared
admission regressions, the next full run passed **691 tests, zero failures/errors**,
and built debug, benchmark and Android-test APKs. Benchmark is non-debuggable
with the existing debug package/signature so data survives installation.
The compatibility engine also passed 12 Node readiness/navigation fixtures.

Four further presentation regressions bring the current full run to **695 JVM
tests, zero failures/errors**. Basic style choices are the three native modes plus
any saved legacy selection. Advanced reveals all styles. The registry still
validates every existing ID; option tiers are shared with the phone schema.

## Device findings and corrections

The first candidate (`50da686f58860c196c6c2607ea08140f45ca2be7f9e4b8a9848a4c2540607de6`)
ran controlled synthetic and licensed NASA fixtures in approximately 21 seconds.

Passed: all EXIF orientations 1–8 through file/bytes/stream decoders, dimension
and allocation bounds, corrupt/huge input handling, native controls, adjustment,
latest-request navigation, singleton/pair behavior, pause/cancel/resize behavior,
synthetic video first-frame handoff and error recovery, and no WebView renderer
in the native modes.

That first smoke did not pass: ML Kit's FAST configuration returned no face for
the licensed astronaut portrait. A direct device comparison found that ACCURATE
returned one valid face box while FAST returned none. The native pipeline now
uses ACCURATE, and its metadata version invalidates the earlier negative cache.
Inference runs on the bounded background analysis worker.

Two subsequent complete device runs passed **518 assertions each**. They verified
actual bundled inference, cold/warm cached face framing and native motion,
all file/bytes/stream EXIF cases, bounded decode/admission, controls,
pause/cancel/resize, delayed and obsolete requests, synthetic video first-frame
handoff/error recovery, pair/singleton behavior, and legacy Ken Burns/filmstrip
navigation on the Portal WebView. A newly added webpage-source gesture regression
is included in the final candidate's sustained run.

The reviews also led to stable encoded identity across differently sized
decodes, rejection of identical-content portrait partners, and guards against old
save callbacks changing a later editing session. Drag/zoom now updates existing
image views instead of rebuilding them on every move.

One intermediate run timed out during initial local-photo readiness. The following
two complete runs did not reproduce it. A safe diagnostic now records controller,
loader, worker and stage state on any recurrence. This observation remains open
pending sustained and lifecycle validation; no root cause is claimed.

The latest complete smoke passed **521 assertions** in 40.1 seconds. Host
FrameMetrics (not delivered FPS): motion p95 12 ms, transition p95 13 ms,
navigation p95 9.75 ms, controls p95 26 ms. Previous passing runs had controls
p95 of 9–40 ms, so controls are more variable than steady photo motion.
The Done button's input-UP-to-exit-callback measurement was 4 ms; destruction
was observed after 679 ms, which includes Android's return animation.
These are short diagnostic samples,
not a controlled before/after superiority claim or sustained-performance result.
The old WebView observation used different content and overlays.

Short-smoke shared admission peaked at 33,786,624 bytes; sampled app PSS peaked
at 234,394 KiB in the latest smoke. Previous passing runs sampled 231,720 KiB
and 282,490 KiB.
Neither statistic is the complete GPU/process memory footprint.

The final candidate separates legacy frames from native startup frames and
records timing components for input, animation, layout, drawing, synchronization,
commands and swap. Histograms and component aggregates use fixed memory and
perform no per-frame file writes.

## Sustained and lifecycle validation

The first sustained attempt ended after 679 seconds of cycling because the viewer
received a tap-to-exit, followed by the runner inspecting a cleared stage. No
slideshow crash accompanies that lifecycle event. It is not a completed soak.
The runner now distinguishes an input exit and a closed host from a rendering
failure, and records sanitized code stack frames for unexpected probe exceptions.

That attempt observed 162 automatic changes, 14 warm real-face presentations
and 13 moving-face samples. Its 14,118 soak frames had host p95/p99 of
9.25/11.25 ms. Controls in its initial smoke were more variable (p95 40 ms,
primarily command timing), so these results do not establish universally smooth
controls. The initial baseline used different photos and overlays.

The Portal subsequently passed owned HTTP fixtures through actual WebDAV/Immich
listing/authentication, controller transport/cancellation/cache, decoder, loader
and a detached native stage. All checks passed in 9.7 seconds. Eight interrupted
header/body requests disconnected in 2–20 ms, before the 12-second read timeout;
main-thread close took 2–5 ms. Valid pixels survived delay, corrupt input and 401
responses. EXIF 6/7 was upright. Cached bytes redecoded after the test server
stopped in 15–17 ms. These source checks bypass public startup/navigation dispatch
and do not measure attached-window/GPU presentation.

Twelve separate fresh-process startup checks passed. An owned photo reached
the native stage in 317–438 ms. They did not reproduce the earlier isolated
readiness timeout; the safe failure diagnostic remains in the runner.

The complete two-hour repeat passed against the installed final production candidate
(`fcbc810b58435d3de8d07cf9f695920b37c812ed31d99737662d584af8f2cf5b`).
Smart frame cycled at a five-second configured interval, with people framing
enabled. All stage, ownership, admission, automatic-advance and cleanup checks
passed. It observed 1,723 controller-generation advances, 148 warm real-face
presentations and 138 moving-face samples.

| Two-hour host FrameMetrics | Result |
|---|---:|
| Frames sampled | 149,470 |
| Mean / p95 / p99 | 6.02 / 9.25 / 11.5 ms |
| Maximum | 44.87 ms |
| Above 16.667 / 33.333 ms | 129 / 2 |
| Callback drops / invalid samples | 0 / 0 |
| Shared pixel admission peak | 33,676,224 bytes |
| App PSS peak | 182,734 KiB (178.5 MiB) |

The 711 soak memory samples showed no continuing growth. Fifteen-minute PSS
medians decreased after startup and stayed near 116–119 MiB in the final hour:

| Window start | App PSS median |
|---|---:|
| 0 min | 138.3 MiB |
| 15 min | 131.5 MiB |
| 30 min | 125.8 MiB |
| 45 min | 120.8 MiB |
| 60 min | 118.3 MiB |
| 75 min | 116.8 MiB |
| 90 min | 119.0 MiB |
| 105 min | 116.1 MiB |

The Portal rejects shell DreamManager calls without
`WRITE_DREAM_STATE`; a lifecycle test must use an available privileged system
launcher or service-owned dismissal rather than assuming binder access. The
SystemUI Somnambulator started a **normal OS Dream**, with verified manager
ownership, broadcasts, Dream window, live owned native pixels, hidden viewer
controls and the shared admission budget. Waiting for that immediately finishing
Activity to draw was inappropriate; the final test checks the service directly.

Attached-window DOWN/UP dispatch exercised the actual service touch listener,
set its user-exit marker and stopped the OS Dream in 155 ms. The service detached,
cleared its stage, dispatched no deliberate-viewer controls, restored callbacks,
drained the stopped broadcast and restored its transient marker. The full
lifecycle probe passed in 1.2 seconds; the owned photo was ready after 684 ms.

Separate OS-injected touchscreen input woke the Dream before reaching the app's
touch listener on this firmware. That is OS wake evidence, not proof of the
physical-panel tap route. Physical touch, unattended idle/presence initiation
and forced-redream behavior are not claimed by this automated suite.

After testing, exact source, registry, secure Dream and presence restoration
passed. Generated/licensed fixture directories were removed. Smart frame was
then intentionally selected through the settings registry for the original
library.

## Coverage limits

- Face inference is verified on the licensed astronaut; group protection is
  covered by geometry fixtures, not an album-wide detector-accuracy study.
- HTTP fixtures cover real transport/cache/cancellation seams and detached-stage
  recovery. They do not establish every household server configuration or
  cold-restart offline manifest persistence.
- Host FrameMetrics are not GPU/compositor delivered FPS. The long loop isolates
  photo rendering with owned sources and simplified overlays.
- The 48 MiB admission cap excludes encoded buffers, SDK/GPU allocations and
  delayed GC. Process PSS is measured separately.
- Legacy effects remain Advanced choices and can be more expensive. One earlier
  startup timeout did not recur in twelve fresh-process launches or the completed
  soak; no unproven root-cause fix is claimed.

Private diagnostics and household state stay outside the repository. Only
generated or licensed fixtures are bundled with the instrumentation APK.
