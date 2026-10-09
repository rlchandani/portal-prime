# Tab-frame placement investigation

Continue the full Portal performance/UX objective. This experiment focuses on one
measured boundary and does not replace the broader acceptance gates.

## Evidence and hypothesis

The deployed control-center build retained slow Home host frames on both rooms.
The existing tabs animate graphics-layer translation, while widget paging already
uses deferred integer placement after an earlier transformed-bounds trace.
The cached Compose UI 1.10.4 RectManager bytecode confirms separate matrix-based
subhierarchy and cached-offset paths. This is a lead, not current hardware proof.

1. [x] Preserve the deployed APK and start codex/portal-tab-frame-performance.
2. [x] Add explicitly opt-in, bounded symbol-only main-thread sampling to /perf.
3. [x] Run meaningful RED request/histogram tests, then GREEN (23 focused).
4. [x] Build/test the diagnostic baseline (973 JVM, zero failures/errors/skips).
5. [x] Verify the baseline retained-tab fixture with real emulator input: taps,
   long press, hidden-page isolation, retention, rapid reversal, same Window.
6. [x] Verify actual sampler delivery, replacement and expiry cleanup in emulator.
7. [x] Capture current Portal tab stacks with profiling overhead labeled.
8. [x] If the evidence supports transformed bounds, prepare exactly one positional
   change: lambda absoluteOffset, retaining visibility/lifecycle/input and duration.
9. [x] Repeat the same fixture, full JVM/build and both-room install verification.
10. [ ] Compare unprofiled same-cadence baseline/candidate runs, preserving matching
    artifacts and original slideshow configuration. Do not use sampled captures
    as timing benchmarks. Reject or revise the hypothesis if tails do not improve.
11. [ ] Record the result, restore an accepted artifact on both rooms, stop owned
    resources and keep wider performance gaps explicit.

Reference: Android's Compose phases guide explains lambda offset reads in the
placement phase, which avoids composition and measurement work:
https://developer.android.com/develop/ui/compose/phases#optimize-state-reads
The current layer lambda already avoids per-frame composition; this hypothesis
concerns descendant coordinate bookkeeping, not the usual eager-state-read bug.

## Collected evidence

- Refined fixed operation counters survive the 256-symbol cap. Focused RED: 25
  tests with 11 expected failures; GREEN: 25 passed. Full JVM: 984 passed.
- Before placement: release-style 24-second captures observed RectManager in
  8 Family and 6 Living samples. After placement: zero observed in both; this is
  bounded wall-time sampling, not proof of zero execution or measured CPU time.
- First unprofiled baseline/candidate: Family p95 67.5/64.5 ms and over-budget
  94/81; Living p95 65.25/57.75 ms and over-budget 94/82. Repeat crossover pending.
- A repeated old runtime probe timed out waiting in startActivitySync while main
  was idle in nativePollOnce. Home is singleTask. The probe now uses an 8-second
  monitor and a fresh standard Settings Activity with NEW_TASK | MULTIPLE_TASK.
- Final corrected runtime guards: 30 sampler lifecycle and 16 retained-tab/input
  assertions including preservation of a matching Settings task. API 34 read-only
  software-renderer emulator; these are interaction/lifecycle, not hardware timing.
- Independent review caught the task-reuse ownership edge; the correction was
  reviewed and passed the actual two-instance marker check.
