# Portal+ tab placement and main-thread diagnostics

## Scope

This investigation follows the mobile browser control center work and continues
[the broader quality gates](portal-plus-quality-gates.md). It tests one native
Home–Apps animation change. The mobile web layout remains phone-first; see
[browser validation](browser-control-center-mobile-validation.md).

Family Area and Living Area are the same API 29 Portal+ hardware cohort. Both
retain their existing app data, photo sources, widgets and slideshow settings.
Navigation is driven through the authenticated in-app Fleet API because their
ADB ports are unavailable. Each finite capture checks its session ownership,
stops its collector, verifies zero active listeners and requests the owned
photo-frame preview. Accepted preview navigation is not visible-image or
DreamService lifecycle proof.
No household photo bytes are transferred to the workstation.

## Mechanism under test

Compose UI 1.10.4 treats a non-identity graphics-layer matrix as a positional
transformation. RectManager then recursively updates descendant rectangles
through transformed coordinates. A tab translation is such a matrix.

The candidate replaces only the tab translation with a lambda absoluteOffset
and an integer pixel position. The existing alpha display list, 160 ms easing,
visited-page retention, hidden-page lifecycle, input suppression and semantics
suppression remain in the same tab host. Placement rounds to the nearest pixel.
The live symbol captures establish that this path is observed on the baseline;
the controlled timing comparison decides whether changing it is useful.

Reference: [Compose phases](https://developer.android.com/develop/ui/compose/phases#optimize-state-reads).
The old graphicsLayer lambda already avoided per-frame composition. This change
concerns geometry bookkeeping, not an eager composition-state-read bug.

## Diagnostic bounds

The existing /perf endpoint accepts an optional Boolean sampleMain. It defaults
to false. Opted captures must last 10–60 seconds; ordinary frame captures retain
the existing 10–900-second bounds. Stop accepts only the action field.

The sampler reuses the collector HandlerThread, reads the main stack outside its
session monitor and skips missed 10 ms cadence slots. It inspects at most 64
frames, retains at most 256 class.method identities of at most 512 characters,
reports two top-24 rankings and accepts at most 6,000 ticks. The VM's transient
stack array can exceed inspected depth; it is never retained.

Seven fixed operation-family counters are evaluated before dynamic symbol
admission: RectManager, NodeCoordinator geometry, performRecompose, ComposerImpl,
layout/measure delegates, accessibility delegate and semantics work. Each family
is counted once per observation, publishes its exact matching rule and remains
visible with a zero count after symbol saturation. A zero describes only sampled
observations within inspected depth. It does not prove the operation never ran.

Counts are wall-time observations with profiling overhead. They are neither CPU
usage nor delivered FPS. Native-poll leaf observations are separated, while
outside-poll observations may still be waiting. Truncation, missed ticks, empty,
error and cancelled reads are reported. Sampler stop, replacement, expiry and
collector cleanup share the session's ownership boundary. No profiler thread or
listener runs by default.

## Verification

- Request/histogram initial focused GREEN: 23 tests. Fixed-family extension RED:
  25 tests with 11 expected failures; GREEN: all 25 passed.
- Full JVM suite: 984 tests, zero failures/errors/skips. Debug, benchmark and
  Android test APK builds passed. The benchmark is non-debuggable, has the same
  debug application ID/signing key and inherits release settings without the
  experimental R8 flag.
- Final runtime checks: 30 sampler delivery/lifecycle assertions and 16 retained
  tab/input/ownership assertions, including tap, long press, rapid reversal,
  hidden-page isolation, retained instances and the same Window.
- The read-only API 34 software-renderer emulator is interaction evidence, not
  Portal performance evidence. It was closed without saving an AVD snapshot.
- The repeated old sampler probe hung in startActivitySync, with main idle in
  nativePollOnce and the instrumentation thread waiting for a reused singleTask
  Home Activity. Both fixtures now use an eight-second monitor wait and a fresh
  standard Settings Activity with NEW_TASK | MULTIPLE_TASK. A two-instance marker
  check verifies the earlier Settings content remains intact. Independent review
  caught the NEW_TASK task-reuse edge; the correction was reviewed and exercised.

## Artifacts and interpretation

Private local captures and preserved rollback APKs are under
build/fleet/diagnostics/tab-frame-placement-2026-10-09; they are not committed.
The unmodified deployed debug APK is preserved as deployed-control-center-baseline.apk.

- Diagnostic release-faithful baseline: 75c52d18020a6ca4d27789ec545690315cc0c4e26b27e80d6a07f879063faabb.
- Placement release-faithful candidate: 264699779f485a013c846f77781bcda4591714f820cdd988eebd3d57903f90cf.
- Candidate debug used by runtime guards: d751295c66049d68204ec165d1c8a5c7c11232207f7b01bbabe4e1d0680a3304.

Each production APK installation was verified by installed hash on both rooms.
Timing captures use 20 Home–Apps cycles, two requests per cycle, with 0.8 seconds
of settlement after each request and a fixed three-second cycle cadence. Each
release-style install receives the same separate eight-cycle sampled warm-up.
Sampling is off for the subsequent 60-second timing run. Profiling-on timings
must not enter the comparison table.

Reported frame durations belong to the Immortal Window host. They are not
compositor FPS, physical touch latency or a browser-ready measurement. Quantiles
use 0.25 ms histogram bins. The 16.67 ms host frame budget is still the acceptance
proxy, not a claim that all broader quality gates passed. These runs do not
control thermal state or ART compilation sufficiently for certification.

## Unprofiled A–B–A–B results

| Room | Variant / trial | Frames | Over budget | Mean ms | p50 ms | p95 ms | p99 ms | Max ms |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| Family Area | baseline A | 519 | 94 | 19.06 | 11.75 | 67.5 | 80.5 | 94.98 |
| Family Area | candidate A | 520 | 81 | 17.22 | 10.25 | 64.5 | 85.75 | 111.16 |
| Family Area | baseline B | 520 | 90 | 17.62 | 11 | 62.25 | 75.75 | 81.60 |
| Family Area | candidate B | 519 | 81 | 16.77 | 10 | 66.25 | 74.5 | 82.48 |
| Living Area | baseline A | 521 | 94 | 17.35 | 10.5 | 65.25 | 81.75 | 91.49 |
| Living Area | candidate A | 521 | 82 | 15.91 | 9.25 | 57.75 | 71.5 | 85.47 |
| Living Area | baseline B | 521 | 94 | 18.51 | 11.25 | 69 | 77.75 | 89.42 |
| Living Area | candidate B | 521 | 83 | 16.02 | 9.5 | 63 | 74.75 | 85.91 |

All timing runs have zero callback drops, first-draw frames and attach failures.
Family aggregated mean falls from 18.34 to 17.00 ms (7.3%); Living from 17.93 to
15.96 ms (11.0%). Over-budget rates fall from 17.71% to 15.59% and 18.04% to
15.83%, respectively. These are observed cohort summaries, not confidence bounds.

Family p95 is mixed (candidate 64.5/66.25 ms versus baseline 67.5/62.25 ms);
no Family tail improvement is established. Living p95 improves in both trials
(57.75/63 ms versus 65.25/69 ms). Family's first candidate max/p99 is worse;
second p99 is slightly lower and max is similar. The full table preserves this.

The fixed RectManager counter observes baseline 8/4 samples in Family and 6/10
in Living, versus candidate 0/1 in both. Recomposition and accessibility work
remain visible. The sampled times are excluded from the timing comparison.

## Decision and remaining work

Keep the placement change as a limited average/over-budget-frame improvement.
Revise the initial hypothesis: transformed descendant bookkeeping is avoidable
work, but it is not the main explanation for the remaining tail stalls.
The tested non-debuggable candidate remains installed on both rooms with its
installed hash verified. Default profiling is off; every finite capture verifies
collector/listener cleanup and requests the photo-frame preview.

The 16.67 ms proxy gate is still failing. Next attribution work should distinguish
activation/recomposition and semantics/accessibility invalidation, using the
bounded diagnostic separately from unprofiled timings. Do not disable accessibility
to obtain a faster measurement. Broader Settings, tool bodies, physical app/browser
journeys, compositor delivery, RTL/provider-wide interactions and visible locked
Pixel testing remain governed by the broader quality ledger; this experiment does
not certify them.

Final read-only status confirms both agents reachable on API 29, version 1.64/58,
profiling off, collectors stopped, zero active listeners and READ_LOGS capability
preserved. Presence screen state reported OFF; that feed does not certify visible
preview recovery. No screenshot containing household photos was downloaded.
