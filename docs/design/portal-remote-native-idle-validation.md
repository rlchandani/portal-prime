# Remote navigation and native idle validation

## Problem and bounded change

The baseline is local commit `824cebe`. A five-minute remote-only browser/Home run on both Portals reproduced native Immortal `PhotoDreamService` frames during the final dwell, despite always-on presence, zero app idle-sleep minutes and disabled overnight sleep. These preferences control app policy; they do not disable Android's native idle timer.

Both baseline rooms completed 30 cycles, 60 accepted commands and 60 destination checks without a foreground miss. Family recorded 11 native Dream frames and Living 117 in the final capture. The original `firstNativeFrameSeconds` fields remain null because per-cycle polls saw no native frames and the first positive observation was the final snapshot. Those records are preserved; final capture elapsed time provides an observation upper bound, not an exact Dream-start timestamp.

Direct authenticated remote Home/Apps/app/dashboard commands and direct companion Home/Apps/app operations now explicitly supply a successful-navigation callback. It runs inside the existing admitted main-thread action, after selection/launch and existing intentional-exit/surface retirement. A started action may acknowledge once after its worker reports completion uncertainty. Cancelled queued work, unavailable apps and failed launches cannot reach the callback.

The shared helpers default to no acknowledgement. Read-only polling, scene actions, preset steps and MQTT retain their existing power behavior. The callback is not stored in Home Intents or invoked from later lifecycle/Intent delivery.

While interactive, `ScreenControl.noteRemoteNavigation` acquires `SCREEN_DIM_WAKE_LOCK | ON_AFTER_RELEASE` with a 1,000 ms backstop and releases immediately in `finally` if still held. While noninteractive, it reuses the existing three-second wake path. This adds no persistent keep-awake flag, recurring timer, setting or preference write. Activity-signalling failure preserves a successful launch response.

Android documents `ON_AFTER_RELEASE` as renewing user activity on release; it cannot be combined with a partial lock. Hardware verification is required to establish its effect on these Portals. References: [PowerManager](https://developer.android.com/reference/android/os/PowerManager#ON_AFTER_RELEASE), [WakeLock timeout and release](https://developer.android.com/reference/android/os/PowerManager.WakeLock).

## Tests, review and builds

The first 24-test draft against original production had 17 failures, including fixture defects in interrupted-waiter joining, nested static-mock replay and Android device curation during inventory polling. After those fixtures were repaired, the original implementation completed 24 tests with 15 missing-signal failures and nine passing negative cases, zero errors/skips.

Initial production GREEN passed 1,238 tests and both APK builds. Independent source review found a test defect: callback-time assertions could be swallowed by production's exception handler. The fixture now records those failures and rethrows on the test thread after response/join, with a shared final check also covering the noninteractive test.

The lock double now tracks actual held-state changes. Two further cases cover acquire failure after taking ownership and a lock already expired before final release. The resulting 26-test navigation suite covers retained/cold Home and Apps, app/dashboard launch, noninteractive bounded wake, authentication, failed/unavailable launch, polling, real cancelled admission, started completion uncertainty and ordering, power failures, direct companion actions and shared/scheduled scene defaults.

The final full gate passed 1,240 tests, zero failures/errors/skips. Debug and benchmark builds passed; saved APK hashes remained unchanged through the test-only repairs. A final full unit run after the shared assertion check also passed. Reviewer source inspection confirmed the P2 test finding was resolved and reported no verified production regression.

Benchmark SHA-256:
`30c255c087acbd820239004ecc5ee9b1cd20600b1123b6b2ef5932a2a5eeee42`.

Debug SHA-256:
`5214aa8e776f3d14edead0a7dc6e214dc1147cfd895ce9a77a17def872e28cd7`.

## Candidate device evidence

The same benchmark APK was installed on both rooms and verified by installed SHA-256. Each measured phase used the baseline's neutral Home touch outside capture, then 4.2 seconds of settling. No touch gestures, extra wake commands or keep-awake controls were used during the five-minute measured navigation loop or subsequent passive polling.

Family's initial candidate attempt failed on its first Home setup request before capture started. The helper classified the request as unsuccessful; its HTTP status/body were not retained, so the reason is not established. Cleanup succeeded and its failed record remains intact. Living completed in that original combined process, whose final exit was nonzero because Family had failed. A separate Family retry saved a new file and completed the matched workload. It must not be described as a passing original combined run.

| Room | Measured cycles | Accepted commands / destination checks | Destination misses | Baseline native Dream frames during navigation | Candidate native Dream frames during navigation | Native frames first observed during passive polling after loop |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Family (separate retry) | 30 | 60 / 60 | 0 | 11 | 0 | 294.07 s |
| Living | 30 | 60 / 60 | 0 | 117 | 0 | 292.57 s |

Candidate navigation captures had no `PhotoDreamService` host at all. Following navigation, only read-only pin/performance polling continued at two-second intervals. Two native Dream frames were observed in each passive phase, within its approximately 330-second baseline-derived bound. The table gives observation times after passive polling began, not an exact service-start timestamp. Both complete candidate records verified unchanged full remote-settings schema and screensaver configuration, stopped the collector with zero listeners and returned Home.

### Timing tradeoff and limits

| Room | Command | Median ms, baseline → candidate | p95 ms, baseline → candidate |
| --- | --- | ---: | ---: |
| Family | Home | 30.88 → 34.59 | 49.15 → 52.89 |
| Family | Browser launch | 35.95 → 39.03 | 55.27 → 53.05 |
| Living | Home | 37.05 → 44.42 | 50.36 → 52.97 |
| Living | Browser launch | 43.71 → 45.59 | 76.35 → 54.76 |

Each cell is based on 30 commands. These durations include HTTP/network and main-thread admission, not browser page readiness or touch-to-visible latency. Medians increased in this sequential comparison. The verified result is correct native-idle behavior; it does not establish a general speedup.

| Room | Build | Home frames / FIRST_DRAW_FRAME flags / subsequent frames | Mean ms | p95 ms | Maximum ms |
| --- | --- | ---: | ---: | ---: | ---: |
| Family | Baseline | 93 / 60 / 33 | 75.61 | 132.00 | 494.67 |
| Family | Candidate retry | 64 / 60 / 4 | 80.60 | 138.50 | 178.12 |
| Living | Baseline | 93 / 60 / 33 | 47.66 | 83.50 | 100.42 |
| Living | Candidate | 65 / 60 / 5 | 53.24 | 89.75 | 138.36 |

Frame mixes differ and the baseline includes a native Dream interruption. Means and p95 worsened in both candidate rooms; Family maximum improved while Living maximum worsened. This is not a clean visual-speed comparison or a delivered-FPS measurement. No callbacks were dropped. FIRST_DRAW_FRAME is the Android metric flag, not proof of a cold process or a newly created Activity.

### Pin regression and post-install probe

Each room separately completed five Home/Apps/browser/Back/screensaver cycles and 30 destination checks after its own timed capture ended. Pin → overlay → actual center tap preserved the original Chromium window ID. Home cleared the pin, cover and cleanup state. Hide dispatch was 1,334 µs in Family and 6,283 µs in Living; this is code dispatch time, not touch-to-visible time. Screensaver configuration remained unchanged and collectors were inactive.

The unexplained Family setup rejection justified a same-candidate reinstall probe after all timed tests. Installed SHA-256 was reverified. The first Home request then returned HTTP 200 with `ok=true` and `dispatched=true` in 86.96 ms; a later separate probe returned 200 in 13.21 ms. Home ownership settled cleanly. Configuration digests across reinstall matched. This single probe did not reproduce or explain the original rejection and does not prove startup is free of intermittent failures.

An initial final-health assertion used an immediate snapshot after an accepted Home command and failed; its failing snapshot was not retained. Subsequent read-only snapshots showed both rooms clean. The final health check was corrected to use the established eight-second destination-settle boundary and passed in both rooms. The failed immediate attempt is preserved separately. Final state is Home, no pin/overlay/native cover, zero pending/failed cleanup, no active collectors/listeners. Family's configuration digests across reinstall matched.

### Next measured work

A separate read-only source investigation found that the Settings overview synchronously builds the entire search index even with an empty query. This is a startup hypothesis, not a measured explanation for the ≥500 ms tails. Measure index preparation and actual Settings entries before choosing a candidate; include first-search latency if work is deferred. The benchmark already packages library Baseline Profile assets and ProfileInstaller, but no on-device profile installation/compilation state or performance improvement is established by their presence.

## Limits

The native-idle experiment uses Immortal's registered Window metrics and application window/package metadata. It does not inspect household screen content, external Chromium rendering, website readiness or delivered compositor FPS. An Android service/renderer interruption outside this observer's scope is not excluded by an empty histogram.

The screen-off wake branch is covered at mocked Android boundaries; no physical forced-screen-off result is claimed. Remote Back/input/media policy and overnight-session renewal are separate paths. Long Settings/slideshow frame tails, broader UX, website/browser readiness and long-uptime memory remain unfinished work. The complete performance and UX goal remains active.
