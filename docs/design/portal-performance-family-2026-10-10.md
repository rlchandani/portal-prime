# Family Area performance work — 10 October 2026

## Scope and acceptance

This pass targets the measured Home/Apps transition and Filmstrip outliers on Family Area.
Mobile work, healthy-feature sweeps and duplicate Living Area profiling are frozen. Living Area
receives the accepted final build after Family verification. Work stays on
`codex/portal-filmstrip-family-performance`; existing changes and failed trials are preserved.

The six-hour window ends at 20:01 Pacific on 10 October. This is a bounded implementation and
verification pass, not a claim that every possible defect is known or eliminated.

The performance gate requires at least 20% lower p95 and p99 Window callback durations against
both fresh before/after baseline blocks, with no mean regression above 5%. Functional, visual,
source ownership and lifecycle checks are separate gates. Native Filmstrip is now enabled by default after its acceptance gates passed. Native Home stays
disabled because its timing gate failed; `-PportalNativeFilmstrip=false` retains the legacy escape hatch.

## Measured corrected Filmstrip result

Family Portal+, Android 10, 2160 × 1440. Three 40-second timed entries per block, preceded by one
warmup; baseline/native/baseline order. Twelve generated 2560 × 1706 JPEGs, five-second interval,
no main-thread stack sampling. Each cell is the median of its block's three entries.

| Window callback metric | Baseline before | Final native v10 | Baseline after |
| --- | ---: | ---: | ---: |
| Mean | 29.14 ms | 10.14 ms | 28.80 ms |
| p95 | 53.25 ms | 14.50 ms | 50.50 ms |
| p99 | 120.50 ms | 17.50 ms | 125.50 ms |
| Frames over the 16.67 ms budget | 72.40% | 1.13% | 73.94% |
| App PSS | 162,846 KiB | 177,304 KiB | 165,105 KiB |
| App graphics | 55,540 KiB | 26,620 KiB | 55,660 KiB |

The final format-compatible v10 artifact passes the timing threshold against both fresh baselines:
p95 fell 71–73%, p99 85–86%, and mean about 65%. Its median measured frame counts were
703/707/701, with zero callback drops or invalid/overflow samples. Native decoded pixels were
bounded at 60,788,736 bytes. App PSS rose 12–14 MiB; app graphics fell about 28 MiB. Chromium
renderer memory is excluded, so this is not a claim of lower total resident memory. Earlier v8
trials measured smaller but still passing reductions: p95 49–55% and p99 about 75%.

Generated-only PixelCopy screenshots of the actual Window verified all twelve centred cards,
next/previous, last-to-first wrap, rapid reversal and exit retirement. A previous native build
clipped translated cards; its apparently faster timings were rejected. The corrected renderer
keeps a stationary viewport clip while allowing the translated track to overflow internally.

These are Window callback durations, not delivered FPS or physical touch-to-display latency.
Promotion/admission markers do not independently prove presentation. Thermal, JIT and cache
state were not controlled. The native shadow uses Android elevation rather than a pixel-identical
CSS shadow. The configured-library Shuffle soak below verifies bounded operation and handoffs separately; it
does not establish a performance improvement for every slideshow style or household photo.

## Home hot paths and current result

The retained native Home candidate moves plain Android page wrappers while preserving each
Compose host's geometry, theme and draft state. Its initial 90-cycle A/B/A improved mean by
23–25% and p99 by 22–25%, but improved p95 only 5–12%, below the stated 20% threshold.

A subsequent candidate retained at most three page hardware-layer caches while Home was visible.
Its fresh 90-cycle trial also failed: mean was 10.98 ms against 14.46/13.75 ms, p95 42 ms against
45.75/45.50 ms, and p99 63.75 ms against 61.25/61.75 ms. Its p99 regressed 3–4%.
Native Home therefore remains disabled.

Common label memoization and an asynchronous idle-renewal worker were independently tested with
the established Home renderer. Their fresh 90-cycle baseline/candidate/baseline result was:

| Window callback metric | Baseline before | Candidate | Baseline after |
| --- | ---: | ---: | ---: |
| Mean | 14.57 ms | 14.37 ms | 14.49 ms |
| p95 | 43.50 ms | 45.50 ms | 46.75 ms |
| p99 | 70.25 ms | 70.00 ms | 64.00 ms |

Mean improved only 0.8–1.4%; tails were inconsistent and failed the acceptance gate. These shared
Home changes and their worker were removed from the delivery, with the rejected patch retained
in private diagnostic evidence. The established Home rendering and idle behavior remain in use.
Home's outlier is unresolved; there is no new accepted Home performance improvement in this pass.

## Integration and regression evidence

The latest completed full Android gate has 1,569 tests across 186 suites, zero failures, errors or
skips. The JavaScript gate has 130 passing tests, including 30 native Shuffle adapter cases.

The Shuffle adapter leaves source selection, style rotation, history and microtimers with the
existing scheduler. Native preparation retains the exact selected IDs; preparation failure
falls back to those same IDs. Handoffs retain an opaque predecessor until successor admission,
pause clocks during lifecycle/user pauses, bound native ownership to two stages and a shared
256 MiB pixel pool, and reject stale callbacks after replacement or shutdown.

Handoff deadlines use active time. Lost acknowledgement recovery restores the retained native
stage to opacity before acknowledging restoration; the successor remains owned until that
acknowledgement. If restoration also cannot be acknowledged, both retained owners remain bounded
until stop/reload rather than releasing the only usable predecessor.

The earlier v8 physical Home suite passed all seven scenarios: three tabs, rapid
reversal, search/filter/clear, hidden search-draft retention, long-press menu, and both theme
changes. Full configuration, source groups and settings schema were preserved. This checks
observed control behavior; it does not establish animation overlap or physical touch latency.

The probe maps Android's unmerged fixed labels to their bounded containing action rows. A
benchmark-only override permits checking the retained Home renderer; normal production variants
retain the renderer gate. Fleet authentication, Dev Mode, collector and owned-Window guards
remain. No raw UI text, query text or credentials are exported.

The test harness was corrected for two observed Android behaviors: an approximately 150 ms empty
foreground-Window gap during screensaver launch, and dock occlusion after Apps search input.
Only the bounded admitted launch accepts an empty Window. Search recovery permits one Back after
a confirmed owned focus or edit in the same Home Window, then requires the original dock signature,
fixed search editor and owned query length before further edits. Uncertain receipts are observed
and never replayed. Soak guards stay strict. Parent runners independently restore
only settings that still exactly match their own writes; failed trials remain separate evidence.

Offline harness gates currently pass 97 Home cases, 31 Shuffle cases, 19 lifecycle cases and 28
mixed-aspect fixture cases. These are harness checks, not additional product/device test passes.

The final v10 mixed-aspect and one-photo physical suite passed both cases: six portrait/square/landscape/
panorama cards, forward/reverse navigation, wrap and the one-photo case, using fourteen generated-only
Window screenshots. Configuration, schema and source groups were restored. Its harness now validates
and normalizes only the owned `intervalSec` display derivative; all unrelated schema fields stay strict.

A generated native Shuffle beat was observed in the lifecycle trial, but sleep/wake verification
could not run. A separate bounded capability check confirmed that sleep stayed INTERACTIVE and
logged inactive device administration twice. Device administration was not changed. Actual
screen-off recovery is unverified; the rejected sleep operation is not evidence of a renderer bug.

Compatibility review found that small GIFs can retain animation in the existing WebView path.
The final candidate inspects encoded contents before native decode; animated and unknown formats
fall back to the legacy deck. Opaque bitmap-only SMB fetches also retain the legacy path because
their original format cannot be established. No Google services or new runtime dependencies are used.

The v9 fifteen-minute configured-library Shuffle run passed with the existing thirty-second interval:
WebView-to-native and native-to-WebView handoffs were observed. Its 285 observations ranged from
100,048 to 226,875 KiB app PSS, within the test's memory limits. No household photo pixels or album
metadata were exported; full configuration, settings schema and source groups were restored.

The final v10 format-compatible artifact has passed the Android, JavaScript, fresh A/B/A and
animated-deck fallback gates. The GIF trial verified a fresh legacy Filmstrip WebView marker,
absent active native scene, stable owned Window and restoration of the complete configuration,
schema, source groups and retained APK. The generated GIF has three distinct looping frames,
verified offline. Actual GIF animation pixels on the device were not measured; legacy capture
was not added or broadened. The corrected fallback harness has 22 passing offline guard cases.

The first GIF trial stopped after the verified APK replacement left Chrome foreground, before
any fixture upload, collector start or settings write. The parent restored the retained build
with all snapshots preserved. The harness now requests Home only after the known replacement
is verified, then waits for its owned Window. Photo-case Window guards stay strict.

The v10 Home trial observed all three tabs and rapid reversal, then stopped at search typing
with `ui_ineligible`; it is not a full Home pass. Its parent restored the retained APK and all
configuration/schema/source snapshots. A fresh redacted evidence run reproduced the zero-dock state after acknowledged search focus
and before text dispatch, without an APK/configuration change. The final functional rerun with
bounded pre-edit recovery passed all seven scenarios on the exact v10 APK: tabs, rapid reversal,
search/filter/clear, retained drafts, long-press menu and both themes. It consumed one pre-edit
recovery and one post-edit recovery, with no retained ineligible evidence. The installed hash,
configuration, schema and source groups stayed unchanged, and cleanup finished at Home with
the collector inactive. Home rendering changes remain disabled and
the unaccepted common changes are absent.

The final v10 configured-library Shuffle run also passed for fifteen minutes at the existing
thirty-second interval, without changing photo sources or settings. Its 284 observations ranged
from 134,667 to 214,290 KiB app PSS. WebView-to-native and native-to-WebView handoffs were observed,
and memory returned near or below its starting value after the native beat. Configuration, schema,
source groups and the retained APK were restored. No household pixels or album metadata were exported.

At the delivery checkpoint, the ordinary default build without renderer-property overrides was byte-for-byte identical to the
physically tested v10 APK: SHA-256 `19abed09f4fd88cfa8a0875a7e1af8a2ce8ee4b1f016312649a85417f9c38f65`,
37,133,972 bytes. That build repeated the full 1,569-test Android gate with no failures, errors or skips.
The benchmark-only Home observation override does not enable the native Home renderer.

The final build was deployed over Wi-Fi to Family Area and Living Area before the six-hour
deadline. Both installed APKs match the accepted SHA-256 and byte count above. Full settings,
settings schema and photo-source groups were preserved on both devices; collectors were inactive.
Living Area received deployment verification only, with no duplicate performance profiling.
The first rollout entry stopped before installation when Family had no active foreground Window;
its retained APK was verified and it was returned to Home before retry. A separate Family-only
navigation-helper guard stopped Living before installation; the room-aware deployment path
then completed. Neither stop is a product performance failure.

## Delivery status and remaining work

| Area | Success criterion | Final result | Status |
| --- | --- | --- | --- |
| Filmstrip p95 | At least 20% lower than both fresh baselines | 53.25/50.50 to 14.50 ms; 71.3–72.8% lower | Passed and deployed |
| Filmstrip p99 | At least 20% lower than both fresh baselines | 120.50/125.50 to 17.50 ms; 85.5–86.1% lower | Passed and deployed |
| Filmstrip mean | No regression above 5% | 29.14/28.80 to 10.14 ms; 64.8–65.2% lower | Passed |
| Home performance | Same p95/p99 gate | Latest restored baseline: mean 13.91 ms, p95 45.00 ms, p99 60.75 ms; no accepted Home optimization | Established renderer retained; outlier unresolved |
| Home controls | All seven fixed scenarios | 7/7 passed on exact final APK | Passed |
| Image geometry/navigation | Mixed-size and one-photo cases | 2/2 cases, 14 generated-only screenshots | Passed |
| Shuffle soak | 900 seconds; both handoff directions; app PSS at most 512 MiB and growth at most 256 MiB | 284 observations; 131.5–209.3 MiB app PSS; peak growth 43.4 MiB from start | Passed |
| Regression suites | Zero failures | 1,569 Android and 130 JavaScript tests | Passed |
| Room deployment | Exact accepted APK on both rooms; settings/sources preserved | 2/2 devices | Passed |
| Physical screen-off/wake | Observe actual screen-off and resume | 0 verified cycles; device administration inactive | Unverified |
| GIF compatibility | Whole-deck fallback to existing WebView | Fallback observed; actual animation pixels not captured | Fallback passed; motion-pixel verification unperformed |
| Mobile | Frozen for this bounded outlier pass | 0 new changes or measurements | Unchanged |

The remaining performance priority is Home's p95/p99 outlier. The accepted Filmstrip work does
not establish improvements for every other slideshow style. Actual screen-off/wake recovery
needs a device sleep capability change before it can be physically tested; that capability was
not modified. The final native shadow is not claimed pixel-identical to the old CSS shadow.
No delivered FPS or end-to-end physical touch latency claim is made.


## Final deployed Home measurement

One descriptive block on the exact deployed v10 APK completed thirty Home/Apps cycles with
782 measured frames. Mean was 13.99 ms, p95 45.75 ms, p99 58.00 ms, maximum 80.50 ms;
120/782 frames exceeded the 16.67 ms budget (15.35%). Callback drops, invalid samples and
overflow samples were all zero. Installed bytes, configuration, schema and sources remained
unchanged, and the owned collector stopped. There were no Living Area operations.

The worst recorded frame had 62.01 ms of animation work (77.0% of its 80.50 ms total),
11.83 ms draw and 0.22 ms layout. This narrows the next investigation toward animation-time
composition/semantics work; one worst-frame decomposition does not independently establish
a specific causal code path. This single block describes the shipped Home renderer; it is
not a new paired comparison or evidence of an accepted Home improvement.

Two extra setup attempts stopped before starting collectors. The corrected setup waits for
the owned Home Window and uses each endpoint's actual acknowledgement schema: Home returns
`dispatched`, while Apps returns `ok`/`action`. The final timed cadence stayed unchanged.


## Home attribution and rejected stable-library experiment

A separate sixty-second-cap Main sampler on the shipped APK completed ten Home/Apps cycles:
1,650 samples, 105 outside native polling, 40 containing accessibility-delegate work, 26
semantics work and nine recomposer work. Operation families overlap and are not CPU percentages.
There were 2,368 skipped sampler ticks, four truncated stacks and a reached 256-symbol limit.
Sampling adds overhead; these records are attribution evidence, not timing acceptance. The
installed APK, settings, schema and sources stayed unchanged, with the collector stopped.

Stable Compose UI 1.11.4 contains an upstream semantics optimization. Local binary inspection
confirmed that its SemanticsNodeCopy constructor fetches replaced children once rather than twice.
An optional BOM 2026.06.01 candidate compiled and passed all 1,569 Android tests. It kept the
established Home renderer, animations and input behavior, with native Home still disabled.

The fresh Family-only comparison completed three thirty-cycle blocks without Main sampling:

| Home Window metric | Shipped 1.10.4 before | Candidate 1.11.4 | Shipped 1.10.4 after |
| --- | ---: | ---: | ---: |
| Frames | 785 | 782 | 781 |
| Mean | 13.70 ms | 14.72 ms | 14.23 ms |
| p95 | 38.25 ms | 48.25 ms | 46.00 ms |
| p99 | 46.25 ms | 67.25 ms | 61.75 ms |
| Above 16.67 ms budget | 16.18% | 16.50% | 16.39% |

Both tail thresholds failed; mean also failed against the first baseline. Candidate p95 was
4.9–26.1% worse and p99 8.9–45.4% worse than the two baselines. All 2,348 frames had zero
callback drops, invalid samples and overflow samples. The exact accepted v10 APK was restored,
with settings, schema and sources preserved, Home foreground and the collector inactive.
Living Area had zero operations during this experiment.

The dependency override was removed and the rejected APK/patch retained in private evidence.
A restored default debug/benchmark build repeated the full 1,569-test gate and resolved UI 1.10.4.
The source snapshot matches the accepted source exactly. The rebuilt benchmark is not byte-identical:
classes3.dex and assets/dexopt/baseline.prof differ. It was not installed or promoted; both devices
retain the exact physically accepted 19abed09… APK. No equivalence claim is made for those differing
binary entries. Early source-hash checks used Path-part ordering; the corrected proof uses the
original relative UTF-8 path-string order and matches 76b4079c… .

Independent read-only review found additional offscreen accessibility traversal in 1.11.4,
including scroll-ancestor geometry and region work. This supplies a specific next experiment:
isolate the offscreen semantics change while preserving the current accessible controls and
remote/pinned-app behavior. It is a mechanism hypothesis, not a causal explanation for the
observed regression. There is still no accepted new Home performance gain.


## Isolated offscreen traversal experiment

A follow-up candidate kept Compose UI 1.11.4 but disabled only its new offscreen accessibility
child-traversal flag at process startup, before first UI composition. The existing AndroidX
Startup provider retained ProfileInstaller and lifecycle initialization. The initializer was
included only for the explicit candidate, with the dependency and source-set overrides absent
from normal builds. Three regression tests established flag isolation, startup invocation and
idempotency; the full candidate gate passed 1,572 tests across 187 suites.

A fresh Family-only comparison completed three thirty-cycle blocks, with Main sampling disabled:

| Home Window metric | Shipped 1.10.4 before | 1.11.4 with legacy offscreen traversal | Shipped 1.10.4 after |
| --- | ---: | ---: | ---: |
| Frames | 782 | 782 | 782 |
| Mean | 13.60 ms | 14.49 ms | 14.03 ms |
| p95 | 37.00 ms | 47.00 ms | 47.25 ms |
| p99 | 46.50 ms | 62.75 ms | 64.25 ms |
| Above 16.67 ms budget | 15.60% | 16.50% | 16.50% |

The candidate failed the 20% tail gate against both controls and the 5% mean gate against the
first control. Its p95/p99 were 27.0%/34.9% worse than the first baseline and only 0.5%/2.3%
better than the final baseline. This is not an accepted Home improvement or a causal comparison
against the earlier 1.11.4 trial. All 2,346 frames had zero callback drops, invalid samples or
overflow samples.

The candidate was rejected. Its APK and complete changed sources remain private evidence;
the dependency override and initializer were removed. The exact accepted 19abed09… APK was
restored on Family Area, Home foreground, with the collector inactive and settings, schema and
photo-source groups preserved. Living Area had zero operations. Direct runtime flag inspection
was not performed; compiled policy, initializer packaging and unit invocation are the available
mechanism evidence.

The restored-default build passed all 1,569 Android tests and resolved Compose UI 1.10.4, with
no legacy-offscreen initializer packaged. Its rebuilt APK differs from the accepted APK in
classes3.dex and assets/dexopt/baseline.prof and was not installed or promoted. Both rooms retain
the exact physically accepted artifact. The latest descriptive restored Home block above remains
an unresolved baseline, not a shipped optimization.


## Compact app-tile semantics experiment

A subsequent candidate kept the shipped Compose UI 1.10.4 and established Home renderer,
changing only installed-app tile semantics. Each tile exposed one action node: exact launch
label as content description, full notification count and usage subtitle as state description,
and an accurate tap label. Recent-app taps announced the app-actions menu. The existing
combinedClickable modifier preceded clearing so Compose retained its generated click, long-click
and focus semantics; visuals, layout and callbacks were unchanged. An explicit false-default
build property isolated the candidate. Six helper tests covered exact labels, full counts,
recency, empty state, tile types and Recent-app action meaning. The complete candidate gate
passed 1,575 tests across 187 suites; independent static review found no blocker.

Outside the timed collector, the fixed-control endpoint measured the exposed Apps tree:
68 nodes before, 46 for the candidate and 68 after. Both fixed Browser/Jarvis controls remained
clickable and long-clickable, with the dock and Apps page visible and no truncation. This is a
32.4% external node-count reduction; it does not measure internal operations or CPU cost. No
arbitrary node labels, queries or state descriptions were exported. Physical focus traversal
and dynamic badge/subtitle announcements were not tested; they remain promotion requirements
for any future candidate using this policy.

The fresh Family-only thirty-cycle-per-block comparison, without Main sampling, was:

| Home Window metric | Accepted build before | Compact tile candidate | Accepted build after |
| --- | ---: | ---: | ---: |
| Frames | 782 | 782 | 780 |
| Mean | 13.73 ms | 13.59 ms | 13.91 ms |
| p95 | 38.25 ms | 45.75 ms | 45.00 ms |
| p99 | 47.50 ms | 63.50 ms | 60.75 ms |
| Above 16.67 ms budget | 16.88% | 15.47% | 15.38% |

Mean improved only 1.0–2.3%, while p95 was 1.7–19.6% worse and p99 4.5–33.7% worse than
the two controls. The 20% tail gate failed. All 2,344 frames had zero callback drops, invalid
samples and overflow samples. The node reduction therefore did not resolve the aggregate
Home/Apps outlier in this trial; it is not evidence that all semantics work is negligible or
that every navigation direction behaved the same way. Process replacement, thermal, JIT and
cache state remain uncontrolled, so no RAM gain or specific causal regression is claimed.

The candidate was rejected. Its APK and all four changed source files remain in private
evidence. The flag, UI patch, helper and helper tests were removed from the product source;
the restored snapshot exactly matches the accepted 76b4079c… source. The restored default
debug/benchmark build passed all 1,569 tests across 186 suites with no failures, errors or
skips; Compose UI stayed at 1.10.4 and the tile policy was absent from the APK. Rebuilt
classes3.dex and assets/dexopt/baseline.prof differ from the accepted artifact, so that rebuild
was not installed or promoted.

The hardware protocol independently restored the exact accepted 19abed09… APK on Family Area,
with Home foreground, the collector inactive and settings, schema and photo-source groups
preserved. Living Area had zero operations and retains the same accepted build. Home remains
unresolved. The next diagnostic priority is separating entering Apps from returning Home,
rather than attributing aggregate tail timing to tile complexity alone.
