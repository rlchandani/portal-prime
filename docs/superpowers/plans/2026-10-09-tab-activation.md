# Retained-tab activation investigation

Continue the broad performance/UX objective. The web remains mobile-first;
desktop presentation is secondary. This bounded native experiment does not
replace phone-browser testing or the wider quality gates.

## Evidence and proposed boundary

The accepted integer-placement build still exceeds the Home host frame target.
Short leaf/nearest-app sampling finds activation, semantics and refresh work,
but no dominant leaf. These are sampled wall-time observations, not CPU time.

`tvFocusable` and `tvFocusableRow` read the page-active composition local.
The runtime fixture isolates untouched controls in a restartable composable:
after all pages are visited, one Home–Apps–Tools–Home round changes its counts
from Home/Apps/Tools 5/2/4 to 7/4/6. All input checks pass in that RED build.
This proves avoidable activation recomposition in the fixture; it does not
establish the remaining hardware tail's principal cause.

Limit the candidate to a stable per-launcher-page input gate:

- Keep click modifier nodes enabled for retained launcher pages. Reject
  inactive callbacks at invocation; observe activity in focus-properties
  evaluation rather than every unfocused control's composition.
- Track the visibility generation of a held touch. Reject its long press
  and consume stale pointer events after hide/show, including its release.
- Preserve the page's pointer isolation, hidden semantics, focus clearing,
  retained state and 160 ms transition. Keep data-producer lifecycle gating.
- Shared Settings/Tools detail panes use the helper's optional null gate and
  retain their existing disabled-control behavior.

## Verification and decision

1. [x] Preserve prior branch/artifacts and create `codex/portal-tab-activation`.
2. [x] Bound leaf and first-Immortal-method rankings independently; RED/GREEN
   sampler regressions, full 998 JVM tests and review.
3. [x] Deploy diagnostic baseline to both rooms; save finite sample captures.
4. [x] Passive five-minute Fleet check: 20 requests per room, zero failures.
   These are HTTP liveness observations during diagnostics, not idle/UX timing.
5. [x] Establish runtime RED: unchanged input behavior and +2 untouched
   compositions per page after a full navigation cycle.
6. [x] Candidate runtime GREEN: untouched composition count, nested touch/row/
   D-pad focus isolation, held gesture after hide/show, same Window/retention
   and legacy disabled rows; repeat prior tab-placement and profiler checks.
7. [x] Full JVM/debug/test/benchmark builds and independent review.
8. [x] Matched warmed unprofiled baseline/candidate trials on both rooms.
   Preserve every trial/artifact; warmup sampling is not timing evidence.
9. [x] Promote only with functional evidence and repeatable hardware benefit;
   otherwise restore accepted placement build. Verify installed hashes on
   both rooms, stop all owned collectors and discard owned emulator state.

Window FrameMetrics are an Immortal host proxy, not compositor FPS, physical
touch response or another app's website readiness. Keep mean and tails separate;
never pool trial percentiles. A reduced recomposition count alone is insufficient
to claim smoother hardware navigation or completion of the full goal.

## Recorded outcome

Retain the scoped candidate: weighted mean 16.98→16.42 ms in Family and
17.60→16.69 ms in Living. Family tails/miss fraction remain mixed; Living p95
is lower in all three pairs. All fail the absolute host-proxy gate. Full trial
table, runtime regressions, artifact identities and limits are in the
[activation ledger](../../design/portal-plus-tab-activation.md). Both room
installs are verified; collector/sampler resources and the owned emulator are
stopped. The broader goal remains active.
