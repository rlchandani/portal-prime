# Home Android-widget listener ownership

Branch: `codex/portal-widget-listener`, based on accepted activation commit `bd68d2c`.
The wider performance/UX goal remains active.

## Concrete issue and change

Home attaches an `AppWidgetHost` lifecycle observer even when its saved layout has
only Immortal Compose cards. AndroidX replays the current lifecycle when an observer
attaches; the previous explicit already-started check could start the same host twice.
Disposal also issued an unconditional stop, including an inactive interval that never
owned a request and an interval already stopped by the lifecycle.

`HomeWidgetListening` now owns one request per STARTED interval. Home enables it only
when its saved layout contains an Android app widget. Changing that condition disposes
the old owner; hiding, destruction and composition disposal stop an owned request once.
A failed start still receives one cleanup attempt because Android may have partially
registered before throwing. A failed stop does not trigger repeated disposal calls.

This changes no saved layout or widget IDs, provider binding/configuration, gestures,
photo sources or slideshow preferences. It adds no persisted setting.

## Functional evidence

- Extracted the previous listener implementation without changing its system-call
  behavior. Ten JVM tests exercised real AndroidX `LifecycleRegistry.createUnsafe`
  event replay, with only `AppWidgetHost` mocked. All ten failed for duplicate/unowned
  calls in the RED run; all ten passed with the owned listener.
- The full JVM/debug/benchmark gate passed: 1,008 tests, zero failures/errors/skips.
- Android's [AppWidgetHost contract](https://developer.android.com/reference/android/appwidget/AppWidgetHost)
  calls for listening during visible intervals. Reading the Android 10 implementation
  confirmed `stopListening` does not clear the retained view map; a later start requests
  pending updates for the existing views. This source check is not a provider integration
  certification on customized Portal firmware.
- Synthetic Compose runtime fixture: all 64 Boolean assertions true (including settlement
  and completion). It covers disabled phases, add/remove, hidden changes, resume/pause,
  stop/start, host replacement, disposal, main-thread callbacks and retained same-Window
  Home. The recording host made 5 starts/5 stops; its replacement made 2/2; peak ownership
  was one. It never called the real widget service or registered a provider.
- Existing runtime regressions passed: tab placement (16 Boolean assertions), activation
  and input (27), profiler ownership/expiry/disposal (30). These are API 34 software-rendered
  emulator checks, not physical Portal input latency or provider-rendering evidence.
- Full build and runtime logs: `/private/tmp/portal-widget-listener-full-build.log`,
  `portal-widget-listener-runtime-build.log` and the four `portal-widget-listener-*.log`
  command logs. Owned read-only emulator 5560 exited; snapshots were disabled.
- Independent read-only code review found no actionable issues in listener ownership,
  enable/host/owner changes, provider configuration-before-add or failure cleanup.

## Artifact identity

| Build | SHA-256 |
|---|---|
| Accepted activation baseline benchmark | `12a97cb15dd132cd3738407a919f008850cc0f0acd2f3ce02ed0aec470053c02` |
| Widget-listener candidate benchmark | `b9111334248dab8cfe9761933d2c114326080a28441516332335c11f8bceabd2` |
| Widget-listener candidate debug | `ba5c97c8c7a5b410012e30cf5388d69236eea8ae94a732991943dfe86da40d99` |

Raw captures and private APK archives live under
`build/fleet/diagnostics/tab-frame-placement-2026-10-09/`; no tokens or household
photo bytes are exported by this experiment.

## Hardware measurement boundary

Each build is reinstalled and its installed bytes verified in both rooms, warmed with
8 Home–Apps cycles, then measured without stack sampling for 20 cycles at a 3-second
cadence. Warmup samples are diagnostics only. Window FrameMetrics are the Immortal
host pipeline proxy, not compositor FPS, physical touch latency, browser readiness or
visible Dream recovery. Room widget inventory is not independently established by
this capture; do not claim all per-room activations avoided the system listener.

No frozen cache/thermal state or statistical significance is claimed. Keep means,
miss fractions and tails separate; do not pool trial percentiles. Listener resource
correctness and frame performance are independent acceptance considerations.


## Two matched unprofiled trial pairs

Milliseconds. The miss count uses the internal reported 60 Hz budget, not Android 31's
actual-deadline API (unavailable on these API 29 Portals).

| Room | Pair | Build | Over / frames | Mean | p50 | p95 | p99 | Max |
|---|---|---|---|---|---|---|---|---|
| Family | A | baseline | 82/520 | 16.263 | 10.50 | 59.25 | 69.00 | 80.54 |
| Family | A | candidate | 82/521 | 16.078 | 10.75 | 54.50 | 69.00 | 82.39 |
| Family | B | baseline | 83/521 | 16.034 | 9.75 | 60.25 | 69.75 | 82.45 |
| Family | B | candidate | 83/521 | 15.340 | 10.00 | 53.00 | 64.00 | 68.14 |
| Living | A | baseline | 83/519 | 16.885 | 10.50 | 60.50 | 72.50 | 89.71 |
| Living | A | candidate | 84/521 | 16.828 | 10.50 | 59.75 | 73.25 | 82.32 |
| Living | B | baseline | 82/522 | 15.789 | 9.75 | 56.00 | 68.00 | 74.85 |
| Living | B | candidate | 88/521 | 16.062 | 10.25 | 57.00 | 65.25 | 87.49 |

- Family: frame-weighted mean 16.148→15.709 ms; missed-budget frames 165/1041 (15.85%)→165/1042 (15.83%).
- Living: frame-weighted mean 16.335→16.445 ms; missed-budget frames 165/1041 (15.85%)→172/1042 (16.51%).

Family means and p95 are lower in both pairs. Living is mixed: its first mean/p95
change is small, while its second is worse; missed-budget fractions do not improve.
The baseline also varies across pairs. **No repeatable improvement across both rooms
is established; do not attribute the remaining slow frames to the widget listener.**
All trials still fail the broader p95≤16.67 ms / ≤1% host-proxy aspiration.

Retain the scoped ownership fix for proven reduction of duplicate/unowned system
calls and elimination of listener requests in Compose-only layouts. The hardware
observations do not establish a consistent rendering benefit or a consistent tail
regression. This is a resource/lifecycle correctness decision, not an FPS claim.

All eight unprofiled room captures have zero dropped callbacks, invalid samples,
first-draw frames or listener-attachment failures. Every owned capture stops its
collector, listeners and stack sampler. Both final candidate installations verified
the archived benchmark SHA-256. Slideshow configuration matched before/after the
second baseline→candidate installation in both rooms. Preview requests were accepted;
visible Dream recovery is not certified by that response.

The finite five-minute passive HTTP check completed with 20 samples per room and
zero failed requests. Family median/max request latency was 33.70/51.86 ms; Living
was 45.11/60.17 ms. Both final collector states were inactive with zero listeners
and no stack sampler. This proves HTTP liveness over that interval, not visual
slideshow endurance or app readiness. The broader performance/UX goal remains active.

## Next measurement boundary

The first candidate's component aggregates show UI-thread waiting peaks of
46.07/47.51 ms and animation-callback peaks of 57.88/57.05 ms in Family/Living.
Layout means are only about 0.11 ms. Maxima can come from different frames; current
mean/max component summaries cannot correlate them with a slow total frame.

Follow up with bounded component measurements conditioned on the same frame's
steady total duration before choosing the next rendering change. Android's
[FrameMetrics definitions](https://developer.android.com/reference/android/view/FrameMetrics)
identify unknown delay as waiting for the UI thread and animation duration as
callback execution, not the visual animation's wall duration. Components may overlap;
do not sum their maxima/means into a synthetic total or call these CPU profiles.
