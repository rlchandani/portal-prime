# Slideshow destination picker: share Home's app artwork

The inline "Open when you tap to exit" editor previously queried all launcher activities and
rendered their icons again on every visit, despite Home/Apps already maintaining a package-aware
app inventory. It also independently decoded the Home Assistant icon.

`ScreensaverDismissContent` now calls `loadScreensaverAppInventory` on IO. The default reader uses
`loadApprovedInstalledApps`, retaining the existing curation, installed components and rendered
`AppEntry`/icon objects from Home's shared cache. The filtered list container can be newly
allocated; there is no second picker cache. Its old private query/bitmap loop is removed.
When Home Assistant is in that inventory, its existing curated icon is reused. An installed HA
app without a curated launcher entry still gets the existing icon fallback. The one/two HA
`getPackageInfo` availability probes remain on IO; this change does not remove every
PackageManager call.

The picker observes the existing inventory revision through a read-only `StateFlow`. Package
changes invalidate the shared cache and reload the editor. Choices and the public ready marker
are only exposed for the loaded current revision, so stale entries are unavailable during
refresh. Leaving the rich pane cancels result publication. Cancellation is rethrown; other
load failures show a manual retry rather than an automatic polling loop. The underlying
synchronous PackageManager batch is not individually cancellable.

No setting, persistence format, remote schema, destination selection or launch behavior changed.
The approved single sidebar/detail pane, title, history and chooser-dialog ownership are retained.
The category/detail names remain "Photos & screensaver" / "Slideshow" and "Home & widgets" /
"Home dashboard", matching the browser mock.

## Evidence

| Requirement | Result |
| --- | --- |
| Controlled warm reuse | 20 visits share one initial cache scan; no additional scans or HA fallback decodes; entry/component/icon identities retained |
| New JVM regressions | Warm reuse, package invalidation, HA fallback, absent HA, failed inventory read before HA lookups |
| Red check | 5 tests ran; cached-HA-icon assertion failed before implementation |
| Debug and optimized benchmark builds | Passed |
| Full JVM gate | 1,791 tests / 205 suites; zero failures, errors or skips |
| Offline native-runner guards | 38 passed |
| Family Area native sequence | 9 checks passed in 72.46 seconds, including deployment/profile verification |
| Loaded destination / actual choices | 2 / 3 verified visits |
| Dialog navigation | 2 dialogs opened and closed without selecting a target |
| History / ownership | Original Settings window after dialog close, Back to Slideshow and Forward to the inline editor |
| Known still-closing dialog | Observed 1 time; bounded wait required the original Settings owner to return |
| Setting edits / Living Area / phone operations | 0 / 0 / 0 |
| Final state | Candidate retained; source/configuration snapshots unchanged; Home restored; collector inactive |

The controlled warm-cache counts are JVM fixture measurements. This native sweep verifies the
loaded UI and continuity; it does not export device package-query counts or measure picker
latency, FPS, total heap or long-run stability. Cold standalone loads may prepare Home's
additional shortcut artwork. HA fallback decode remains when no shared launcher icon exists.

## Artifact

Candidate: `31f8aa47658ea262569c5fe7ae5a99d5a4dc9e8a7723fd28d106916ea056e080`.
Bootstrap: `b3489a6896e5855cfe920b106b36b99357fad4adb40a973657d025cd2737b1ca`.
Source: `3653f48f107937a80cfe369bb70bbea06f8dcf2fac4ddd7f5e34b1156f1f68c4`.
Postflight: `1939fce7230976bcf617f55fba2081a90fa223744ddaba50b4137c010fd741dd`.

The APK is 25,402,496 bytes, one DEX, the existing signed
`com.immortal.launcher.debug` package, and a non-debuggable optimized benchmark build.
Exact SDK/bundled/reference profile sets and DEX mapping passed. The profile contains
8,649 methods / 2,569 classes,
including 285 app methods / 75 app classes.
Individual app-method compilation is not proven. The legacy six-symbol offline mapper gate
retains stale R8 names and is not used as the admission gate; the complete resolved sets are
verified instead.

Evidence: `~/portal-prime/build/fleet/diagnostics/settings-dismiss-inventory-2026-10-11`.
This issue began at 22:19:55 UTC and was fixed and verified within its 30-minute bound.
The broader performance goal remains incomplete.
