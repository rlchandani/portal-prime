# Settings startup: direct preparation measurement

## Result

Ten fresh Settings Activity entries on each Portal measured the actual search-index builder, with main-stack sampling disabled. Index preparation is too small to explain the large observed Settings-frame tails in this protocol. Keep the existing index behavior; do not introduce deferred search work on the strength of the original hypothesis. The broader performance and UX objective remains open.

| Room | First index ms | Later index median / maximum ms | First entry maximum frame ms | Later per-entry maximum frame median / range ms | First layout maximum ms | Later layout maximum range ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Family Area | 7.895 | 0.452 / 0.679 | 486.835 | 210.452 / 166.485–241.055 | 341.423 | 109.713–179.714 |
| Living Area | 2.507 | 0.383 / 1.083 | 498.416 | 178.459 / 147.964–231.260 | 299.740 | 108.089–173.113 |

Each room produced 30 Settings frame records: 20 FIRST_DRAW_FRAME flags and 10 other frames. There were no invalid frame samples or callback drops. Every individual capture recorded exactly one index build, 218 entries, on the main thread, with no invalid or discarded preparation observations. Family's first queue maximum was 451.266 ms; Living's was 475.846 ms. Later queue maximum ranges were 149.793–218.154 ms and 134.124–211.026 ms respectively.

These are separate frame/component aggregates. Their maxima can occur on different frames and cannot be added into a supposed critical path. Index wall time is directly measured; it is not CPU time. The much larger layout and queue observations justify tracing initial UI work next, but do not identify a particular composable as the cause.

## Measurement boundary

The benchmark build wraps the real `approvedSettingsSearchIndex` through a bounded optional probe. Successful preparation still executes once and returns the same list object. Builder exceptions propagate unchanged. Failure of the optional provider, clock or record sink cannot break successful preparation. With no active capture, or with a closed session, the probe reads no clocks and starts no threads or timers. A capture replaced, stopped or expired during preparation cannot receive the observation.

A session retains at most 32 primitive preparation samples. Further valid records update aggregates and the discarded-record count. JSON snapshots are detached. The fields contain only elapsed completion time, duration, entry count and a main-thread boolean; they contain no search text, setting values, entry titles, credentials or UI content. The existing frame-capture mutex serializes access.

The test returned Home before each source-known gear tap and verified a changed window identity, Settings host frames and one preparation observation. Cursor movement occurred before capture. Status polling began one second after the tap request completed, and an additional 1.1 seconds allowed later draws before the final snapshot. Each capture used `sampleMain=false`. The delayed observation timestamp is a test protocol marker, not precise physical touch-to-visible latency.

The first entry is the first Settings entry measured in the existing process after deployment, not a measured cold process. Setup reads of the full settings schema can warm domain initialization. No process reset, compilation reset or profiling-state change was performed. These values are a direct diagnostic cohort, not a matched speedup comparison against the earlier pilot with stack sampling enabled.

## Build and unit evidence

The deliberate RED run used valid-empty accumulator and pass-through probe/capture stubs. All 30 new tests executed: 20 assertion failures, zero errors and zero skipped tests. The failures therefore exercised missing behavior rather than fixture or compilation failure.

The completed implementation passed the full debug unit suite: **1,270 tests, zero failures, zero errors and zero skipped tests**. Debug and benchmark APKs built successfully in the same serialized run. A read-only independent review of the seven scoped source/test files found no verified issue; that review was source-only, separate from the main agent's executed build and device evidence.

| Artifact | SHA-256 |
| --- | --- |
| Debug | `c71a5be50b5f2003653ace46cab7dba85042b45e18c09e2e57854bda9957b10c` |
| Benchmark installed on both rooms | `524a7c1c0c8dfb1b5df91b8d2613f0bf6fcd0d47b5d5261924d87035126e07df` |

The room fleet helper verified the installed benchmark APK hash on both Portals. Raw captures, immutable RED/GREEN XML evidence, APKs and analysis remain under the ignored private directory `build/fleet/diagnostics/settings-startup-2026-10-09/`; the public report contains only performance and test metadata. Screenshots, photo bytes, visible text and DOM content were not exported.

Both ten-entry cohorts completed with the full settings schema and screensaver configuration unchanged, no native Dream host in the captures, inactive collectors with zero listeners, and clean Home window ownership afterward.

## Device regression after the measurement cohort

Both rooms completed five navigation cycles with 30 verified foreground checks each, covering Home, Apps, Chromium, Back, slideshow, Apps from slideshow, and return Home. Pinning Chromium, covering it, then tapping the cover restored the same existing Chromium window on both devices. Home afterward cleared the pin, and window ownership/cleanup settled cleanly. Screensaver configuration remained unchanged and collectors were inactive with zero listeners.

Hide-dispatch observations were 3,452 microseconds Family / 3,081 microseconds Living. These measure dispatch work, not visible recovery latency or browser document readiness. The metadata protocol neither exported nor inspected household images or visible browser content.

## Next source hypothesis

The parallel read-only investigation found that a fresh default entry renders the Appearance overview's three navigation rows, plus the shell header, full search input, lazy sidebar and footer. An asynchronous version-name publication can invalidate the footer scope after initial composition. Initial pane/lifecycle effects also execute, though the initial pane is already at opacity 1; source inspection does not prove a visible animation or repeated expensive traversals.

The native-detail path loads Immortal and screensaver snapshots before its page switch and again on resume, but that path does not execute for the default overview measured here. Do not attribute overview cost to those detail-page reads. Prioritize separate timing/pass counts for initial shell composition, sidebar lazy subcomposition, search input/text measurement and detail measurement; timestamp version publication and pane/lifecycle effects. Runtime importance remains unmeasured.

## Remaining work

Measure the initial Settings composition and layout outside the search index, then choose a change supported by those measurements and compare it under a matched protocol. Settings detail transitions, slideshow preview tails, browser document readiness, long-uptime memory behavior, sliding gestures and phone/web responsiveness remain within the overall objective. These build and device checks do not establish delivered FPS, leak freedom, exact touch latency or completion of the entire objective.
