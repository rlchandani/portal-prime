# Wi-Fi frame diagnostics and legacy analysis validation

Branch: `codex/portal-wifi-performance`. Date: 2026-10-09. Base: `5d4ba15`.

## Changes

The base revision fixes the process-wide WebView timer pause that left a later
legacy slideshow on “Loading your photos…”. This follow-up owns and cancels
legacy face warm-up work, merges cache updates through a shared atomic snapshot,
and exposes a temporary developer `/perf` capture through existing Fleet auth.

Face analysis uses one running job and one latest queued replacement. Exit
invalidates late results immediately, without joining workers or closing network
streams on the UI thread. A stalled asynchronous detector keeps only its bounded
input and cache path until completion, not its Activity/Dream context. This may
stall further legacy face analysis; it does not block slideshow display or grow
an unbounded queue. Existing `fx,fy,weight` and no-face cache entries stay compatible.
The shared decoder supplies input/pixel limits and EXIF handling.

The frame observer is dormant during ordinary use. Inactive tracking keeps only
weak Window references and does not force decor creation. Explicit captures
collect primitive histogram counters; they do not perform per-frame I/O or JSON
work. Stop/expiry removes observers and quits the worker asynchronously. Monotonic
deadlines, wake/lifecycle checks and generation guards reject stale callbacks.
Refresh budgets use the Window display. An already-begun command whose completion
cannot be confirmed reports HTTP 202 rather than a misleading failure/retry signal.

See [Fleet diagnostics](../features/fleet.md#timed-rendering-diagnostics) for usage.

## Build and regression evidence

- Full debug application and Android-test builds passed.
- All **843 JVM tests** passed, zero failures/errors/skips. This includes 12 new
  legacy cache/ownership tests and 13 frame histogram/request/gate/budget tests.
- The actual-Window probe passed **24 checks** on the API 34 arm64 emulator at
  2160 × 1440: rendered frames, session replacement/reset, explicit stop, destroyed
  Window removal, retained results and automatic expiry. The expiry assertion
  checks that the collector thread is already gone before reading diagnostics.
- The real-WebView recovery probe passed **97 checks** across four fresh sessions
  (Ken Burns twice, filmstrip twice), three exits/re-entries, an independent
  WebView heartbeat and a seeded process-wide timer pause.
- Static independent review corrections addressed delayed sleep cleanup,
  pre-attachment display rates, started-command timeouts, inactive decor creation,
  test counter races and worker Context retention. `git diff --check` passed.

The Window probe uses a test-owned animated/static surface inside a finished
Activity; its timings are not a Home benchmark. Private emulator logs retain an
initial probe failure caused by a helper shadowing the animator's `start()`.
The helper now has a distinct name and explicit animator receiver. Final results
above come from the corrected APKs. The owned emulator and display overrides
were cleaned up afterward; the phone was not modified.

## Family Area validation

The combined debug APK was installed over Wi-Fi with the existing same-signature
Fleet path. Deployment verified the installed APK SHA-256:
`59aefee57ee023e6f899277f8759433706c6b47d0ad34c53eb0586855c4e0a24`.

Four Home → photo preview → touch exit → remote-key Apps → Home cycles completed
in one unchanged app process, using the saved legacy `shuffle` mode and actual
12-photo local library. Each emitted a first-scene readiness event: **168, 528,
113 and 694 ms** from page start. A fifth preview used in the soak became ready
in 227 ms. These exclude view/source startup and subsequent fades; they are not
end-to-end visible readiness or delivered FPS measurements.

A finite **180-second** slideshow capture observed **3,979 host Window frames**,
including two first draws. Its first post-deadline read reported `active=false`,
`collectorRunning=false`, `activeListeners=0` and elapsed time capped at 180,000 ms.
Explicit stop after the four navigation cycles also released the collector.

| Slideshow capture metric | Observed value |
| --- | ---: |
| Window frame budget | approximately 16.67 ms |
| p50 total host time, including first draws | upper histogram bound 28.75 ms |
| p95 total host time, including first draws | upper histogram bound 34.00 ms |
| p99 total host time, including first draws | upper histogram bound 78.75 ms |
| Steady frames exceeding the display budget | 3,740 of 3,977 |
| Callback drops reported by Android | 0 |

This confirms continuing host-frame delivery and the loading recovery, while
showing that the legacy path still misses its display budget frequently. It is
not evidence that the broader smoothness goal is achieved. Window timings cover
host layout/drawing/command issue; they are not compositor-delivered FPS or a
complete account of embedded WebView/GPU work.

The app PID remained unchanged across cycles and soak. Sampled app RSS was
156.1 MiB after cycles, 204.7 MiB during the soak and 152.1 MiB afterward; thread
counts were 68, 73 and 72. Captured logs had no fatal-exception, app ANR or OOM
markers. These sparse, short observations do not prove leak freedom. No fair
before/after performance improvement is claimed from these differing scenes.

The Fleet contract returned 401 without auth and 400 for a numeric string,
fractional duration, out-of-range duration, unknown phase and unsupported fields.
The preview was left running with the collector stopped. Household settings,
photo sources and library were preserved. Raw logs and measurements are private
under ignored `build/fleet/diagnostics/` directories and contain no committed tokens.

## Remaining validation and performance work

Only Family Area received this build. These physical tests used the normal
photo-preview path, which shares `PhotoFrameController` with Dream; unattended
OS Dream initiation/attach/detach was not repeated. Physical Settings and retained
Home Apps/Tools tab transitions were not measured here. Apps samples came from
the existing remote-key `AppSwitcherActivity` path, which also deserves review
against the retained-tab navigation experience. Chrome/website frames, Dialogs,
system surfaces, direct screenshots and deep-sleep wake expiry are outside the
physical checks performed in this increment. Continue those checks and optimize
the measured legacy render cost before claiming the broad quality goal complete.

Reproduce the owned Android probes after installing both debug APKs:

```sh
adb shell am instrument -w -e command framePerformance \
  com.immortal.launcher.debug.test/com.immortal.launcher.PortalUxInstrumentation
adb shell am instrument -w -e command slideshow -e legacyOnly true \
  com.immortal.launcher.debug.test/com.immortal.launcher.PortalUxInstrumentation
```
