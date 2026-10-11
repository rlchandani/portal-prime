# Family Area: bounded Settings focus comparison

A Settings-only candidate replaced per-row `collectIsFocusedAsState` with
`onFocusChanged` before `clickable`, preserving focus, semantics, and inactive-pane
input guards. Decorative switches created no interaction source or collector;
interactive switches observed their toggleable focus target directly. Compose
1.10.4 source review confirmed those contracts. Visual resources were unchanged.

The candidate built successfully with **1,736 unit tests in 199 suites**, zero
failures/errors/skips. Nine offline execution-policy checks passed. The native
comparison completed **15 captures**: two warm entries and five measured entries
per control/candidate/control block. Each detail opened in a fresh Settings
window; its selection stayed in that window. Main-thread and Settings rendering
samplers were disabled. Each APK's own compilation profile was verified.

| Median/worst largest frame per opening | Control before | Candidate | Control after |
|---|---:|---:|---:|
| Median | 37.871 ms | 35.268 ms | 33.227 ms |
| Worst | 41.747 ms | 46.693 ms | 42.813 ms |

The existing gate required at least 10% median improvement against both controls,
tail within 10% of both, and control drift within 15%. Median and tail gates failed;
control drift passed. **The patch was rejected.** There is no claimed speedup.
This measures the worst frame per opening, not physical tap latency or delivered
FPS. The planned full native navigation regression was not run after rejection.

The sequence stopped after **181.6 seconds**. It restored the exact accepted APK
`c8625fa9a4461626a3825a325d97b0da98508cb4a584ad48cb1914f17083a81a`,
unchanged configuration/photo sources, Home, and an inactive collector. The app
source was restored exactly to accepted revision `41e42ce`; the rejected patch
and built artifacts remain private in
`~/portal-prime/build/fleet/diagnostics/settings-focus-cost-2026-10-11/`.
Living Area operations, phone operations, setting edits, and repeated comparisons
were zero. `postflight.json` remains `completed:false` because candidate acceptance
failed; `comparisonExecuted:true` records that all three measurement blocks finished.

This result does not show that row collectors dominate the remaining frame cost.
No further experiment was launched in this turn. The prior accepted R8 comparison
showed 36.99–42.37% median improvement; its historical 27.779 ms value belongs to
that earlier APK and is not relabeled as this revision's performance.

Official API references:
- https://developer.android.com/develop/ui/compose/touch-input/focus/react-to-focus
- https://developer.android.com/reference/kotlin/androidx/compose/ui/Modifier
