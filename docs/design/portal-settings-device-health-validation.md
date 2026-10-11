# Device health: off-thread checks and refreshed status

The Device health pane no longer queries Android permission services during Compose composition.
It loads one immutable snapshot on Dispatchers.IO and refreshes once when its lifecycle resumes.
Leaving the pane cancels publication of an in-flight result. Loading and query failure are explicit
states; neither displays a healthy result. There is no idle polling.

The snapshot reuses the administrator result for both the health row and the uninstall control.
This reduces administrator reads from two to one per refresh and moves the pane's five capability
queries out of UI composition. Confirmed administrator deactivation also runs on IO. The existing
two-tap confirmation is retained, resets on resume, and rejects repeated input while an operation
is running. The registry, preferences and wire settings are unchanged.

## Verification

- Full JVM gate: 1,742 tests, 200 suites, zero failures, errors or skips.
- Four new loader tests cover off-thread execution, failed permission queries, cancellation and
  preventing publication after a departing screen cancels a blocked query.
- Two diagnostic regressions cover fixed health status visibility and exclusion of editable,
  password and arbitrary permission details. Status markers do not enable input targets.
- Final Family Area native continuation: six checks passed in 87.41 seconds. Device overview,
  health, Android/Portal settings, Advanced and two health revisits used the same Settings window.
  All three health entries loaded successfully; Back returned to Device overview.
- The previous revision passed 28 navigation checks before stopping on a missing diagnostic
  marker. Its incomplete report is preserved. The corrected build differs only in the read-only
  marker whitelist and its tests; product permission/loading UI source is byte-identical.
  These checks are not reported as one fresh full sweep of the final APK.
- Family Area configuration and photo-source snapshots were unchanged. Home was restored and
  the diagnostic collector was inactive. Living Area and the phone received no operations.

The first native attempt exposed my marker mapping error: Settings JSON has its own fixed-key
whitelist, and the health markers were initially added to the wrong map. A failing regression
reproduced that mistake before the corrected whitelist passed the full gate and native checks.
No permission query slowness was inferred from that missing marker.

## Installed artifact

Final APK SHA-256: `79736ccd6b4d4b97eedc2baa9bb57bbb198bede66eeaa357c8aec6bc193f8763`.
Bootstrap SHA-256: `27e4abeeec87fceab2a35ebf262eedc595633d8206f10a5c7f378ca02c555f54`.
The benchmark artifact keeps the existing package/signature, is not debuggable, uses native R8,
and has one DEX. Its profile resolves 8,614 methods / 2,558 classes, including 264 application
methods / 69 application classes. The bundled/reference profiles match the installed APK;
own OAT metadata reports speed-profile / install-dm. This does not prove every individual
profile method is compiled.

Local build, unit, source/artifact admission and native postflight records are under
`build/fleet/diagnostics/settings-device-health-marker-2026-10-11/` in the primary workspace.
The earlier incomplete report is under `settings-device-health-2026-10-11/`.

## Limits and current work rule

No new frame-time percentage, query duration or universal 60 FPS result is claimed. This is a
bounded Device health correctness/UI-thread fix, not a claim that Settings optimization is done.
Physical permission changes and administrator deactivation were not performed in native QA.
The earlier six-hour performance deadline was missed.

Following the user's instruction, each subsequent issue has a maximum of 30 minutes total for
investigation, a fix and verification. At that limit, close the attempt with its actual result,
retain the verified build, record an unresolved blocker and move to another issue. Do not extend
an unsuccessful experiment into repeated open-ended profiling.
