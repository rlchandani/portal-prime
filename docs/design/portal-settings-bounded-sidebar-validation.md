# Bounded Settings sidebar validation

## Change and build

The baseline is local commit `2135d88`. The candidate replaces only the seven-category sidebar (eight with Advanced enabled) from LazyColumn with a keyed, vertically scrolling Column. Category order, Advanced gating, row dimensions, spacing, colors, fonts and actions remain the same. Collection and item accessibility metadata are explicit. The search results remain a lazy list.

The full unit-test gate passed 1,300 tests with zero failures, errors or skips. Debug and benchmark builds succeeded. Independent source review found no verified source regression, while reserving physical focus, overflow scrolling and screen-reader checks.

Both rooms received the same benchmark artifacts, with the installed APK SHA-256 verified after each deployment:

- Baseline: `dab673789a6a944ff2066d28c3947341041ac2760533ee1075f1fb1e75edc6b7`.
- Candidate: `6717bd78cc5976b21078d7c17de8a9f0c278dd5d84f5da92b8aa86d98f78358b`.

## Repeated matched protocol

Both Family Area and Living Area completed baseline-a, candidate-a, baseline-b and candidate-b. Each block used 20 warmup fresh Settings Activity entries followed by ten timed entries in the existing process. The actual source-known Home gear was tapped. Each timed capture lasted at most 15 seconds, used no stack sampler, and delayed metadata polling until at least one second after the tap response. Changed window identity, Settings frame records and exactly one 218-entry search-index preparation verified entry.

In total, 160 warmups and 80 timed entries passed, with 240 verified collector stops. Every block returned Home with clean ownership, inactive collectors and unchanged complete settings schema/screensaver configuration. No Dream hosts contaminated the captures. Every timed entry recorded three Settings frame callbacks, including two FIRST_DRAW flags: 240 callbacks and 160 first-draw flags overall, with zero callback drops or invalid frames. These are observed Window frame timings, not delivered FPS.

ART/profile/thermal state was not controlled or measured. The repeated order gives a bounded sequential comparison, not a randomized cold-process study or a universal latency guarantee.

## Observed timings

Each value below is the median over the ten timed entries in its block. Largest frame includes first draws. The root-phase proxy sums the recorded shell composition and shell measure spans only after their completion timestamps verify sequential order within one millisecond of timestamp precision; parent/child spans are not added together.

| Room | Block | Sidebar measure ms, baseline → candidate | Root-phase proxy ms, baseline → candidate | Largest frame ms, baseline → candidate |
| --- | --- | ---: | ---: | ---: |
| Family | a | 27.33 → 4.29 | 47.91 → 34.46 | 121.67 → 119.56 |
| Family | b | 30.31 → 2.64 | 54.79 → 37.98 | 140.78 → 122.30 |
| Living | a | 25.49 → 1.85 | 47.10 → 29.45 | 129.47 → 100.38 |
| Living | b | 24.77 → 1.77 | 44.86 → 29.81 | 117.73 → 101.72 |

Recorded root-phase proxy medians decreased 28.08/30.69% in Family and 37.47/33.56% in Living. Largest-frame medians decreased 1.73/13.13% and 22.47/13.60%, respectively. Family block a's worst frame increased from 143.59 to 150.44 ms. Family b and both Living blocks had lower maxima. The data supports less measured root-phase work and improved medians in these runs; it does not establish a consistent improvement to worst-case frame times.

## Extra recomposition and measurement limits

The baseline recorded eight sidebar-item composition invocations per timed entry; the candidate recorded sixteen. Source cardinality and composition invocation counts are different. All candidate entries contained eight row timing spans definitely outside the two root spans, even with their timestamp bounds expanded by one millisecond. Those disjoint row-span duration sums had block medians of 0.24–0.31 ms. Surrounding compiler restart, apply, draw and framework work is not fully measured by those row spans.

Consequently, the root-phase proxy is incomplete and must not be presented as total UI composition/layout work, CPU time, critical-path startup duration or exact touch-to-visible latency. It also does not account for every recomposition. The extra invocation round is a follow-up opportunity; its cause has not been isolated by this experiment. Footer version publication is a hypothesis, not an established diagnosis.

## Functional checks and preserved failure

The first functional test incorrectly required item invocations to equal the number of unique categories. Both rooms failed that assertion with Advanced off and fourteen invocations. These failed records remain preserved; cleanup restored the original Advanced setting and stopped the owned collector. A synthetic fault-injection check separately proved that collector-stop failure cannot prevent setting restoration or returning Home, and cannot mark the run complete.

The corrected test records actual invocation counts and verifies actions rather than equating invocations with cardinality. Both rooms passed all seven source-known category-position taps with Advanced off and all eight with it on. Each tap increased detail-composition observations while preserving the Settings window. Tapping the source-known Advanced-row position did not change the detail when disabled, and that position responded when enabled. The original setting and complete schema were restored. Synthetic search input applied, recomposed and cleared successfully; viewport swipes were accepted. These assertions do not prove exact selected-category identity, visible result correctness, actual scroll distance where there is no overflow, or screen-reader announcements.

Each room then passed five Home/Apps/browser/Back/screensaver navigation cycles with thirty foreground/ownership checks, including Apps from slideshow. Pin → cover → center tap revealed the identical existing Chromium window. Home cleared pin/cover and cleanup ownership. Hide dispatch was 2,126 microseconds in Family and 11,006 in Living; that measures dispatch, not visible reveal latency. Screensaver configuration stayed unchanged and collectors were inactive.

A final additional entry/return check confirmed changed Settings window identity and two new HomeActivity callbacks after returning Home in each room. This strengthens the package-only navigation assertions, which alone cannot distinguish Home from Settings. Final window ownership was clean, settings were unchanged and collectors were inactive.

Independent evidence review found no verified blocker under the limited proxy/functional claims. Main separately rechecked exact equality of the pre-stop and post-stop Settings frame, search-preparation and UI-work metrics for all eighty timed entries; there were no late records changing this analysis. Future cohorts should retain this equality gate or analyze the final snapshots. Current private evidence includes `sidebar-candidate-green-evidence.json`, eight immutable entry summaries with raw timed records, `matched-sidebar-analysis.json`, `sidebar-observation-containment.json`, the original and corrected functional records, navigation smoke records, final ownership records and `sidebar-final-verification.json`, under `build/fleet/diagnostics/settings-sidebar-2026-10-10/`. Baseline artifacts remain under `build/fleet/diagnostics/settings-layout-2026-10-09/`.

## Decision and remaining work

Retain this bounded candidate as a measured optimization for continuing evaluation: all four comparisons reduced recorded root-phase medians and largest-frame medians, and the scoped functional/navigation checks passed. Preserve the adverse Family-a maximum and extra row recomposition as limitations. This is not final performance sign-off.

First-use/cold Settings behavior, overflow/D-pad focus, screen-reader behavior, extra row restart work, slideshow tails, long-uptime memory, browser/website readiness, gesture smoothness and phone/web control performance remain open. The broad performance/UX goal remains active. Evidence stays private under ignored build/fleet diagnostics; no household photo bytes, screenshots, UI text, DOM content or credentials were exported.
