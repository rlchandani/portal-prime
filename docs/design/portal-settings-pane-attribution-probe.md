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

## Remaining work

Native callback/activation validation and the frozen Family Area category/page workload remain pending. No APK from this probe has been installed on a device, and no new device speed improvement is claimed. Living Area and the phone remain frozen. Preserve the accepted APK/SDK/configuration restoration path when running the native trial. The full performance objective remains incomplete.
