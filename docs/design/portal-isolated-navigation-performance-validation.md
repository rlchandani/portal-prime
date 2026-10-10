# Isolated navigation profiles and Settings work

## Hardware observation at 88a1e78

Family Area and Living Area ran the same signed retained-preview build. The operator split navigation into 20 Home/Apps cycles, 15 screensaver/Home cycles, and 20 Chromium/Home cycles. Each phase collected explicit expiring Window frame-duration components and bounded main-thread class/method observations. Each returned to verified Home, retained the original screensaver configuration, and stopped its collector with zero active listeners. There were 110 accepted action requests per room, with destination-package readiness checks after configured settle delays; these checks do not identify the individual tab or prove browser document readiness.

| Room | Workload | Home frames | Home mean ms | Home p95 ms | Preview mean / p95 ms |
| --- | --- | ---: | ---: | ---: | ---: |
| Family | Home/Apps | 520 | 15.14 | 45.75 | — |
| Living | Home/Apps | 520 | 15.35 | 47.75 | — |
| Family | Screensaver/Home | 31 | 74.95 | 122.25 | 30.36 / 94.00 |
| Living | Screensaver/Home | 31 | 62.90 | 89.75 | 37.47 / 92.75 |
| Family | Chromium/Home | 58 | 94.56 | 146.50 | — |
| Living | Chromium/Home | 56 | 53.04 | 90.50 | — |

These distributions include first draws and profiling overhead. The workloads and sample counts differ, and device thermal state, browser state and slideshow selections are uncontrolled. The much heavier Home transition cohorts prioritize further investigation; they do not isolate the cause, measure delivered FPS, or prove a regression from this change. External Chromium frames are not collected by Immortal's Window instrumentation.

The sampled preview cohort often observed `HardwareRenderer.nSyncAndDrawFrame`, Android Binder calls and WebView native symbols. The browser cohort also observed `PinWindowReaderKt.readPinAppWindows` and renderer-resource teardown. Foreground verification itself synchronously reads accessibility roots through `/remote/pin`; therefore its overhead must be separated before changing ordinary window-observation policy. Never replace live pin identity validation with a stale task/package cache to make the benchmark faster.

An initial attempt requested stack sampling for 75 seconds. The server correctly rejected it because sampled captures are capped at 60 seconds. No collector remained active. Correcting only the request produced all six completed phases; the failed request record remains in private diagnostic evidence.

## Settings source finding

Native `prepareSettingsList` executes in composition. Its row filter calls `visibleWhen` before the Advanced and screensaver-enabled gates; the hint scan repeats visibility and evaluates basic-control predicates before discovering that they are not Advanced. `screensaver.batterySaver` reaches `DreamPolicy.hasBattery`, which calls `registerReceiver(null, ACTION_BATTERY_CHANGED)` synchronously. This is a concrete redundant-work exposure, not a measured general Settings-entry bottleneck. Category-overview navigation does not necessarily execute that detail list.

The bounded change must preserve the exact row and hint selections while eliminating irrelevant and repeated visibility evaluation within one preparation. Hardware state is not cached between preparations.

## Validation status

The new helper is wired directly into the native renderer. Fourteen deterministic JVM tests cover predicate counts, skipped checks, Advanced on/off, excluded and derived entries, enabled/disabled parent gates, fresh hardware/parent results, hint discovery and stable row/group/card-edge/control-index projection.

A genuine RED run used the original row filter and hint scan behind the helper signature: 14 tests ran and 11 failed for redundant/irrelevant predicate evaluation (not a compile failure). Restoring the candidate produced a passing full unit gate: 1,174 tests, zero failures/errors/skips. Offline debug and benchmark builds passed. Independent static review found no verified semantic defect across the shared implementation and all six setting types. The direct new-test examples exercise BoolSpec and DerivedSpec; other types share the reviewed generic path.

Both rooms received the identical signed benchmark APK, with installed SHA-256 verification: `970677ef08bfb0e57eafca7fd46f2d283e15f0b10252123e055ed59472034bd2`.

Before deployment, each room passed 12 actual Settings-header-button entries and Home returns. After deployment, each passed another 12, with both full remote settings schema and screensaver response unchanged and collectors stopped with zero listeners. Window metadata confirmed a distinct application window and the collector observed `ImmortalSettingsActivity` frames. The source-positioned gear target was `(2059, 61)` for the known Portal+ header at 2160×1440 / density 1.125. No screenshot or visible content was captured.

This checks entry/return ownership, not every detail page or the changed helper's physical latency. The entry workload opens the overview, so its first-draw distributions cannot establish a speedup from a detail-list optimization.

| Room | Cohort | Settings frames | Mean ms | p95 ms | Maximum ms |
| --- | --- | ---: | ---: | ---: | ---: |
| Family | Baseline | 48 | 122.44 | 191.75 | 607.82 |
| Family | Candidate | 48 | 141.98 | 262.50 | 553.35 |
| Living | Baseline | 48 | 118.48 | 227.75 | 503.02 |
| Living | Candidate | 48 | 146.13 | 268.75 | 577.49 |

The candidate means and p95 values were higher in this capture. It followed a fresh install while the baseline was already running; both distributions include first draws and profiling overhead. These unmatched cohorts do not establish an A/B speedup or regression attributable to the helper. The slow Settings-entry tails remain unresolved.

A final regression completed five Home/Apps/Chromium/Back/screensaver navigation cycles and 30 verified foreground checks per room. Pin → screensaver cover → actual remote tap preserved the browser window ID; going Home cleared the pin. Hide-dispatch measurements were 8,603 microseconds in Family and 2,754 microseconds in Living, not touch-to-visible or document-readiness timings. Both rooms finished with clean Home ownership, no pin/cover, no pending/failed cleanup, unchanged screensaver configuration and inactive collectors. No device launch-latency or FPS gain is claimed.

## Remaining scope

Slow frame tails are still above the 16.67 ms screening target. Further investigation needs to distinguish Home resumed-layout/render costs, WebView creation/destruction, window-root querying, actual browser document readiness and full device compositor performance. The broader performance and UX goal remains active.


## Next isolated hypothesis

The profiles justify a separate investigation of no-pin navigation cleanup. `ImmortalDreamNavigation.dismissForNavigation` always calls `PinnedAppScreensaver.release`; the actual host's `release` invokes `windowsChanged`, which synchronously reads every application window root even when no pin/cover exists. The roots are needed for real pin identity/reveal checks and eligibility updates, but a redundant no-pin navigation path may not need another read. Establish a precise cleanup/eligibility invariant and isolate status-query overhead before changing it. Preserve pending/failed retirement barriers and fresh real-pin validation; do not keep stale window identities or disable accessibility to make tests pass.
