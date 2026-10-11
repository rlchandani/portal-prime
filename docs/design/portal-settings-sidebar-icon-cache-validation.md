# Settings sidebar vector reuse — 11 October 2026 UTC

## Change and scope

The shared Settings shell previously parsed its eight small sidebar vectors through each
fresh composition's painterResource cache. It now reuses only immutable ImageVector data
across Settings openings. Each composition creates its own painter in the existing IconBadge.
Dimensions, white tint, badge background, decorative semantics, navigation, settings and
resource geometry are unchanged. The motion fix remains independent of this optimization.

SettingsIconCache admits a copied fixed allowlist, retains one detached configuration epoch,
invalidates all entries on configuration changes and leaves failed loads retryable. The typed
sidebar consumer retains at most eight ImageVectors and a Configuration copy. It stores no
Activity, Context, Resources, Theme, Painter or loader closure. Literal, single-variant XML
resources permit theme=null; two resource tests guard that prerequisite. First use and
configuration changes still parse resources. No background work or dependency was added.

## Verification

The deliberate RED executed eight cache tests, all failing with assertions, zero errors or
skips. An earlier fixture compile attempt encountered Android stubs lacking newer JDK XML
constant fields; the fixture now uses the standard parser property URIs. The full GREEN gate
passed **1,602 tests in 191 suites**, zero failures/errors/skips; debug and benchmark APKs
built. Independent source review found no verified blocker. Packaged DEX inspection confirms
that the sidebar consumer invokes SettingsIconCache.get. Native Filmstrip remains enabled,
native Home and WebView background startup disabled, benchmark DEBUG false.

## Matched Family Area comparison

Three blocks used accepted baseline, candidate, then accepted baseline again. Every block
reinstalled its exact APK, including when already installed, then ran twenty warmups and ten
timed fresh Settings Activity entries in the existing process. The final-control wrapper only
extended pre-install artifact admission to the independently verified candidate hash; its
entry/capture protocol stayed unchanged. No ART/profile/thermal reset was requested.

Each timed entry had three Settings frame callbacks and two FIRST_DRAW flags. Thirty timed
entries therefore contain ninety frame callbacks and sixty first-draw flags; those flags are
not unique displayed frames or delivered FPS. Stack sampling was disabled. Final stopped
frame, search and UI-work records exactly matched their pre-stop snapshots in every timed
entry. No callback drops or invalid frame samples occurred. Every timed entry composed eight
sidebar rows once. The table contains medians of ten per-entry observations in milliseconds.

| Observation | Baseline before | Candidate | Baseline after |
| --- | ---: | ---: | ---: |
| Largest Settings frame | 114.653 | 99.107 | 116.815 |
| Shell composition | 20.449 | 13.302 | 22.936 |
| Shell child measurement | 12.718 | 11.794 | 12.703 |
| Eight sidebar composition spans, sum | 9.422 | 1.810 | 8.842 |

Candidate largest-frame medians were 13.56% and 15.16% lower than the two controls. Sidebar
composition sums were 80.79% and 79.53% lower; shell composition medians were 34.95% and
42.00% lower. Candidate largest frame was 124.387 ms versus 129.143 and 138.609 ms controls.
These repeated warmed observations support keeping the narrow cache. They are not physical
tap-to-visible latency, cold-process startup, CPU percentages or completion of Settings tuning.

Sidebar spans are nested within shell composition; child measurement is inside root
measurement. These values must not be added into a critical path. Fixed block order, ten
samples per block and unmeasured thermal/JIT state limit generalization.

Adverse observations remain: candidate child-measure maximum was 26.956 ms versus 15.300
and 22.323 ms controls, despite its lower median. Sampled PSS medians were 105.36 MiB versus
96.03 and 99.59 MiB controls; ranges were 99.47–109.78, 93.73–102.01 and 96.16–101.91 MiB
respectively. This short protocol cannot establish a memory cause, leak freedom or long-uptime
behavior. The bounded data cache is not evidence that every memory metric improved.

## Navigation, cleanup and decision

Candidate checks passed six category-position actions, covering cached returns and four-pane
eviction, plus the first two Appearance subpages and the first Photos subpage with Back.
Detail composition was observed and the Settings window identity remained unchanged. Exact
visible labels, first pixels, draft/scroll persistence and physical focus were not independently
captured. Source preserves the existing layout/semantics and pane lifecycle gates.

Effect marker spans were fourteen 0 ms observations and one 1 ms observation for focus
clearing. An initial analysis incorrectly required exact-zero millisecond marker pairs; that
assumption was rejected and preserved privately. These markers do not measure animation.
The candidate does not reintroduce the removed detail-panel slide or fade.

All ninety cohort captures and two navigation captures closed. Full configuration, settings
schema and photo-source groups remained unchanged, Home was restored and installed APK
hashes were independently verified at each boundary. Living Area and the phone had zero
performance/UI-test operations. The control leaves Family Area on the accepted baseline until
final candidate rollout; Living rollout is deployment verification only.

Accepted candidate benchmark: 37,150,356 bytes, SHA-256
`bc6af47b4184de27f81fc59c6502871bb8a796505c59c1e047ae859dd3f28298`.
Debug SHA-256: `186db703aa17313cd598087ea63a4015345d317ff7769ea0d2f648dd16e9e458`.
App-source digest: `e13615c2bee4128c1a1a937ddf0e3492c0fc34658b46936f7daf0a0e247697d8`.
The prior a564577a... artifact remains saved for rollback. Evidence stays private under ignored
build/fleet/diagnostics/settings-icon-cache-2026-10-11, including XMLs, APKs, protocol versions,
raw timed records, analysis and navigation checks. No household images, screenshots, UI text,
setting values or credentials appear in this public report.

This accepts one Settings work reduction. The broad performance/quality objective stays open.
