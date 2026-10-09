# Portal+ retained-tab activation — 9 October 2026

The retained-tab input change is kept on `codex/portal-tab-activation`, built
from the accepted integer-placement branch at `118b43e`. Both Family Area and
Living Area have the verified non-debuggable benchmark APK below. The wider
performance/UX goal remains active. Web design and validation remain phone-first.

## What changed and why

Short diagnostic captures showed activation, semantics and refresh work, with no
dominant leaf. Dynamic inclusive rankings can saturate before reaching app code.
The opt-in sampler now has independent bounded rankings for the literal leaf
(excluding exactly native MessageQueue polling) and the first Immortal method
within the inspected depth. They retain class/method identities and counters.
Leaf/nearest-app limits are 128/96 symbols, 512 characters per identity, 64
inspected frames and 24 reported entries. The sampler remains off by default.

The source showed every `tvFocusable` and `tvFocusableRow` reading page visibility
in composition. A generated runtime fixture proved the cost: after visiting every
page, a Home–Apps–Tools–Home round changed untouched control counts from
Home/Apps/Tools 5/2/4 to 7/4/6. Other input checks passed in that RED build.

Retained launcher pages now provide a stable input gate. Click and long-click
callbacks check permission when invoked; focus properties observe permission
outside composition. Unfocused unchanged controls stay at 1/1/1 throughout the
same round. Existing producer lifecycle gating, hidden semantics, focus clearing,
integer placement and the transition duration are preserved.

The root remembers the visibility generation of a held touch. An interrupted
gesture stays cancelled after hide/show; stale pointer events, including release,
are consumed before clearing the gesture. Shared Settings/Tools detail panes
keep the helper's null-gate behavior and their existing disabled controls.

## Correctness evidence

- Leaf-ranking focused RED: 39 tests, 14 expected failures. GREEN: 39 passed.
  Full final JVM gate: **998 tests, zero failures/errors/skips**.
- Debug, AndroidTest and benchmark builds passed. Final restored-source build:
  `/private/tmp/portal-tab-activation-verified-final-build.log`.
- Final runtime checks: **27 activation + 16 placement + 30 profiler assertions**,
  all passed on the owned API-34 read-only software-renderer emulator.
  These cover nested touch/row/D-pad isolation, hidden-child focus rejection,
  retained state, same Window, rapid reversal, legacy disabled rows, and
  explicitly owned sampler replacement/stop/expiry/window destruction.
- Independent review found a feedback regression: enabled `combinedClickable`
  requested haptics after a rejected long-click wrapper. A generated haptic
  provider recorded **three requests for one accepted long press**. Gated controls
  now disable automatic haptics and request feedback only after an accepted action;
  the final runtime result is **one request**. Actuator behavior was not measured.
- Review also caught a cached-release-coordinate weakness. The short-press
  fixture now waits for settled bounds, rereads the control, verifies the original
  down point remains inside and releases before the long-press timeout. An
  emulator-only fault build omitting stale-release consumption failed that exact
  check. Production source was restored exactly before final builds/deployment.
- Final review found no remaining actionable issues in the scoped code/fixture.
  The owned emulator exited with snapshots disabled. No GitHub operations ran.

Runtime logs: `/private/tmp/portal-tab-activation-verified-{tabActivation,tabPlacement,framePerformance}.log`.
The fixture checks unchanged controls; changing data and focused controls can
legitimately recompose. It does not establish complete Portal UI/accessibility
acceptance or physical touch latency.

## Matched hardware trials

Each trial used the same package/signing key, existing room configuration,
8-cycle/24-second sampled warmup followed by a separate **unprofiled 20-cycle,
60-second** Home–Apps capture. Cadence was one cycle every three seconds,
with 0.8 seconds after each selection. Trial order was A–B–A–B–A–B per room.
Baseline A also had earlier diagnostic warmup history; B/C installed each
artifact again before the prescribed warmup. Cache/thermal state was not
independently frozen, and no statistical significance claim is made.

All twelve unprofiled room/trial captures had zero first-draw frames, callback
drops, invalid samples and attachment failures. Sampling warmups have disclosed
overhead and are excluded from timing comparisons.

| Room | Pair | Build | Frames / over budget | Mean ms | p50 ms | p95 ms | p99 ms | Max ms |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Family | A | baseline | 521 / 85 | 16.500 | 10.50 | 58.00 | 69.50 | 85.65 |
| Family | A | candidate | 520 / 86 | 17.010 | 10.75 | 62.00 | 71.50 | 82.34 |
| Family | B | baseline | 521 / 83 | 17.386 | 10.00 | 67.50 | 83.75 | 93.29 |
| Family | B | candidate | 522 / 85 | 16.715 | 10.25 | 59.25 | 73.75 | 95.71 |
| Family | C | baseline | 521 / 82 | 17.050 | 9.75 | 65.25 | 82.00 | 88.69 |
| Family | C | candidate | 521 / 82 | 15.540 | 9.75 | 57.00 | 63.50 | 86.03 |
| Living | A | baseline | 520 / 85 | 18.004 | 10.50 | 70.50 | 79.50 | 91.08 |
| Living | A | candidate | 521 / 84 | 16.937 | 10.50 | 60.25 | 73.00 | 84.80 |
| Living | B | baseline | 520 / 87 | 17.659 | 10.00 | 66.50 | 80.75 | 96.92 |
| Living | B | candidate | 521 / 82 | 16.502 | 10.25 | 61.75 | 71.75 | 83.54 |
| Living | C | baseline | 521 / 84 | 17.135 | 9.50 | 67.25 | 83.75 | 92.51 |
| Living | C | candidate | 521 / 86 | 16.624 | 10.00 | 61.50 | 78.75 | 93.40 |

Weighted means use frame counts, not averaged percentiles:

- **Family:** 16.98 → 16.42 ms mean (3.3% lower); proxy over-budget 15.99% → 16.19%.
- **Living:** 17.60 → 16.69 ms mean (5.2% lower); proxy over-budget 16.40% → 16.12%.

Family p95 improved in two of three pairs; Living p95 improved in all three.
Family's over-budget fraction did not improve. Living's fraction fell modestly.
The evidence supports a limited activation/mean-cost improvement; it does not
establish a universal tail or deadline-miss fix.

Window FrameMetrics report the Immortal host pipeline. Delivered compositor
frames, physical input-to-visible response, external app/website readiness and
full DreamService recovery remain unmeasured here. Every trial still exceeds
the 16.67 ms p95 screening target and the 1% over-budget target. Broader acceptance
remains pending in the [quality gates](portal-plus-quality-gates.md).

## Artifacts and cleanup

Private artifacts and raw captures:
`build/fleet/diagnostics/tab-frame-placement-2026-10-09/` in the primary checkout.

| Artifact | SHA-256 |
| --- | --- |
| Diagnostic placement baseline benchmark | `b803352b0a7cdebd6d68429d6ec7a2a2c1f3c3ad4f7e7011644ecb25c37a5e05` |
| Accepted activation benchmark | `12a97cb15dd132cd3738407a919f008850cc0f0acd2f3ce02ed0aec470053c02` |
| Accepted activation debug | `e427290498fb48bb6ab171c9ff030165c855e149f2553c653c2148c0d20a3211` |

Both installed benchmark hashes were verified. These artifacts retain the
`.debug` application ID and debug signing key; benchmark is non-debuggable with
the existing release-style configuration, without the R8 experiment. It is
not proof of an independently validated shipping release configuration.

Raw timing files are `{family-area,living-area}-activation-{baseline,candidate}-{a,b,c}.json`.
Warmup files add `warm-` before the trial letter. No household photo bytes or
credentials were exported for these tests. No source/layout settings were changed.

A separate finite five-minute Fleet liveness check made 20 requests per room
with zero failures (Family median/max 34.69/61.06 ms; Living 44.17/61.96 ms).
It ran during diagnostic activity and is HTTP liveness evidence, not an idle
benchmark or browser-ready time.

Final agents were reachable at app 1.64 / code 58 with READ_LOGS capability
preserved, profiling inactive, worker stopped and zero active listeners.
Each trial's preview-navigation request was accepted; visible recovery is
not certified. Keep investigating activation/refresh/rendering tails and
the distinct browser, Settings and slideshow gates.
