# Settings retained-pane work probe

The alpha-zero retained Settings panes may still be measured, but caching can avoid most work. This opt-in diagnostic records actual boundary callbacks before any retention/layout optimization is attempted. It establishes no speed improvement.

## Observation boundary

`PORTAL_SETTINGS_PANE_DIAGNOSTICS` defaults false in ordinary variants and benchmark builds. Only `-PportalSettingsPaneDiagnostics=true` enables the benchmark probe. Each keyed pane remembers one plain active-attribution object and one stable layout modifier; committed SideEffect updates the active Boolean without adding measurement-observed snapshot state. The probe measures the same child with the same constraints and places it at the same relative origin. The four-pane limit, drafts, scroll, rich-editor disposal and focus/input/semantics/lifecycle gates remain unchanged. No native cascading columns are added.

Attribution builds also keep the existing UI-work measurement modifiers attached while no capture is running. This avoids START/STOP or later recomposition adding layout nodes during the observed workload. Ordinary builds preserve the prior conditional UI-work instrumentation behavior. Both probes read no timing clock when no active measurement sink exists.

The optional collector belongs to the existing expiring Frame performance session, with admission/publication guards for current session, closure and deadline. Fixed pane identifiers cover Appearance, Touch, Wallpaper, Quickbar and Other; category overviews and other pages use Other. Active/inactive totals and thread counts are separate, with at most 512 primitive detail records. Aggregates continue after saturation. JSON contains no settings values, labels, search text, URLs, credentials or UI references. Inclusive wall-time spans overlap and are not additive CPU-time or presentation measurements. No new setting or HTTP endpoint is introduced.

An outer callback does not cover descendant-only remeasurement, placement or draw. Zero records cannot prove zero hidden work. Observer overhead and the separately enabled functional geometry/draw probe prohibit using this attribution build as a final timing-performance comparison.

## Local verification

The absent feature first failed the scoped Kotlin test compilation. A separate private stub harness also produced two actual failing assertions before implementation; the standalone collector/probe suite then passed 22 tests. Complete Android unit verification passes **1,719 tests in 198 suites, zero failures, errors or skips**. Debug and opt-in benchmark builds pass. The new tests cover separation/aggregation after buffer saturation, valid/unbounded and invalid constraints, time bounds and fractional nanoseconds, captured measurement ownership, duplicate/concurrent finish, and diagnostic failures.

Actual packaged DEX inspection confirms pane/UI diagnostic flags false in the ordinary debug APK and true in the attribution benchmark. Both keep native Home false, native filmstrip true and self-install metadata false. The benchmark is non-debuggable, package `com.immortal.launcher.debug`, signed with the accepted certificate. A targeted independent source review found no concrete blocker, with runtime/descendant-only limitations retained.

Private build admission, complete source inventory, full unit-report hashes and build log are preserved under the ignored primary-workspace directory `build/fleet/diagnostics/settings-pane-attribution-2026-10-11`. The attribution APK SHA-256 is `5a1d27ebddeb0bc785badc10330fbc5f792fca80820a0d27e587c8009e2b53e3`; the complete source fingerprint is `1c926cbf45f215d75d86e7f705b88cbc9616c597bcabde818d939c0ff488a625`.

## Family native attribution

The attribution APK was installed only on Family Area. The original experiment stopped after 41 verified captures: its reused Back guard admitted only Display and rejected Appearance before sending Back. This was a driver mismatch, not an observed device Back failure. The incomplete run and its successful accepted restoration remain preserved separately.

A separate retry admits only the two protocol scalar pages, verifies the fresh committed route and native/accessibility window pair before one acknowledged Back, then observes the expected parent route/depth. It never retries an uncertain Back. The retry driver, capture controller and offline analyzer pass **54 tests**, both normally and with Python optimization enabled. No Android source or APK was changed for this retry.

The retry completed **52 verified captures**: 10 warm-ups, 30 measured category switches, and 12 scalar-page openings (six each for Appearance and Wallpaper). Every capture used one unique stopped session and the same Settings owner pair. All 12 Back inputs returned to the expected parent; all **173 input mutations were acknowledged**, with no uncertain input. The full 237-input manifest and all capture hashes were verified before and after offline analysis. Scalar active attribution matched the selected page in every capture. No pane records were invalid or discarded, and no frame callbacks were reported dropped.

These measurements are elapsed inclusive time inside the pane's child-measure callback. They are not CPU time, tap-to-display latency or a before/after improvement. A first page visit here means the first visit in this already-running Settings session; each first-visit result is one sample. Repeated detail-page results are medians of the subsequent five visits, not independent cold starts.

| Workload | Captures | Visible pane measurement | Hidden pane measurement |
|---|---:|---:|---:|
| Warmed category switches | 30 | 0.303151 ms median | 0.108073 ms median |
| First Appearance visit | 1 | 80.200208 ms | 0.151041 ms |
| Repeat Appearance visits | 5 | 0.224791 ms median | 0.092396 ms median |
| First Wallpaper visit | 1 | 34.566771 ms | 0.125261 ms |
| Repeat Wallpaper visits | 5 | 0.438385 ms median | 0.069948 ms median |

Category-switch hidden observations ranged from 0.059270 to 0.264531 ms. First Appearance's observed largest Settings frame was 227.641656 ms; first Wallpaper's was 172.043657 ms. These are instrumented frame-duration samples, not interaction latency. Android's frame component named `animation` includes work scheduled through that phase and does not establish that a visible slide animation remains. Composition, pane measurement and frame spans overlap and must not be added.

The accepted APK (`bc6af47b4184de27f81fc59c6502871bb8a796505c59c1e047ae859dd3f28298`) and its exact SDK-only compilation profile were restored. Installed bytes/hash, fresh-cache status, canonical method/class/flag sets, unchanged configuration/photo sources/source inventory, Home and inactive collector state all passed. Living Area and phone operations remained zero.

Private evidence is preserved in the ignored directory `build/fleet/diagnostics/settings-pane-attribution-retry-2026-10-11`; analysis SHA-256 is `ddc0392a4c996eb8d0d0601e2f9bf7ae3612ec5d5468ba89c80f049e1b3c96fa`, and frozen manifest SHA-256 is `988eb8583c359505083b65586511f40ed5c9c5e2af3a9d7058ed8a8c3f62795e`. The failed experiment remains in the original directory. Neither run is a candidate promotion.

## Decision and remaining work

The observed hidden-boundary cost does not justify changing retained-pane measurement or sacrificing retained drafts/scroll. Descendant-only work remains outside this probe, so this decision deprioritizes that optimization rather than claiming hidden work is zero. The next bounded target is the first opening of a visible detail page: its initial construction, lazy row measurement and rendering. A source review found small snapshot/list/enum allocation candidates, but none is proven to explain the first-visit delay; they are not promoted as a speed fix.

Any resulting optimization must preserve the registry, settings behavior, navigation, drafts/scroll and existing UX, and pass a separate matched timing comparison with pane/functional observers disabled. Cascading columns remain browser-only pending human review. No new speed improvement is claimed, and the full performance objective remains incomplete.
