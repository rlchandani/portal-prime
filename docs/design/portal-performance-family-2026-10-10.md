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

The ordinary default build, without renderer-property overrides, is byte-for-byte identical to the
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
| Home performance | Same p95/p99 gate | Common trial p95 45.50 ms, p99 70.00 ms; both gates failed | Established renderer retained; outlier unresolved |
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
