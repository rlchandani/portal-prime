# Settings entry timeline diagnosis

Goal: locate the Family Area Settings startup delay before another optimization.
The accepted UI and navigation remain the same; the cascading-columns mock awaits approval.

## Prior experiment disposition

The idle accessibility-window bypass mechanism passed its unit checks but failed the
fresh runtime-verified before/candidate/after timing gate. Largest-frame medians were
85.8414 / 88.2166 / 94.1897 ms; root composition medians were
10.3555 / 11.9680 / 10.2707 ms. Memory passed (+8.1494 MiB against the larger control,
-23.0791 MiB last-three versus first-three). There is no accepted performance gain.

All 90 fresh captures and verified stops completed. Actual installed APK/DM/reference
inventory and structural own OAT admission passed; code 3 was retained as an observed
one-byte cache-size warning. Analysis SHA-256:
aa1657c573d58b9aca664b7c785b0d5c91ca9f5e1bdbfc8426e98a87caceaed8.
The exact rejected patch is archived privately, SHA-256:
e0030023b1f0dacc2ee045a191d6afcf1e8254f79cda545b903814769cdc3cb5.

Both experimental source files were restored to HEAD. The restored 781-file source
digest is 1b89eb349152c1a43995a77a2272cc6d510f8d14e6e3d351fec2036cded2386e.
All 1,636 tests in 192 suites passed after rollback, with zero failures/errors/skips.
Family has accepted APK bc6af47b4184de27f81fc59c6502871bb8a796505c59c1e047ae859dd3f28298
and its compiled SDK profile restored; configuration/schema/source groups are preserved.
Living Area and the phone were untouched.

## Diagnostic design

- Capture-only numeric launch, create, resume, focus, first pre-draw and first frame
  callback timestamps. First pre-draw and callback receipt do not establish presentation
  time or physical touch latency.
- Each entry belongs to the capture identity in its intent. Stale sessions, failed
  launches, expired captures and unknown IDs cannot be silently adopted.
- Bound primitive entries; no persistent settings, content strings or credentials.
  Weak window association prevents callbacks from another Activity being attributed.
- Keep the existing Settings UI work schema and gates unchanged. Add a separate
  settingsEntryTimeline report.
- No additional listener or diagnostic clock outside admitted captures. First pre-draw
  listener removes itself; Activity destruction also removes it.
- The Home header gear and injected Settings app tile have distinct callbacks; both actual
  startActivity boundaries are measured. Other entry paths remain explicitly unmatched;
  no inferred launch latency.

## Execution

- [x] Verify authoritative source rollback and the 1,636-test gate.
- [x] Add capture admission tests before implementation; first run fails on absent APIs.
- [x] Implement pure recorder, capture integration and lifecycle markers.
- [x] Run focused tests, full unit gate and debug/benchmark builds; freeze artifact inputs.
- [x] Review a diagnostic-only runner: 20 warmups + 10 fresh entries, strict collector
  ownership, installed identity and source/configuration guards.
- [x] Capture only Family Area, then unconditionally restore and verify the accepted APK.
- [x] Analyze paired entry stages and existing frame components; select the next actual
  optimization from evidence. Diagnostic instrumentation is not a speed improvement.

## Local validation ledger

- Source frozen at 55497aa570a03a5210bc11428d800a1b136ecddb224ce3f7fd4e60d6f7502356
  (786 files under the established app-source algorithm).
- Full gate: 1,678 tests / 195 suites, zero failures/errors/skips; 42 new tests.
  Debug and non-debuggable benchmark builds passed.
- Review caught ViewTreeObserver replacement during decor attachment. The fix uses a weak
  decor reference, removes from both distinct live observers, clears the report on disposal,
  and is covered by attachment-replacement and capture-stop regression tests. Scoped
  follow-up review found no remaining blocker.
- Ordinary diagnostic APK: c7f45430b7160aadd1b031af8762aa1132fe215b988b2b42ecefdc06e5d6267f,
  37,150,356 bytes. Bootstrap: d07a1143060c4fad0be35f6466373f7e35db028a8d5968227d45a67aa306f98a,
  same bytes, pinned to that ordinary APK. Both match the accepted signing certificate.
- Bundled profile decoded and matched against all DEX inputs: 34,922 SDK method entries,
  5,389 SDK class entries, zero own-app entries. Mapper exit 1 denotes the intentionally
  absent own-app profile, not a decode failure. Actual installed reference admission is
  still required before native diagnostics.
- Fresh read-only Family check confirmed the accepted APK, no active collector, no live
  listeners. No device navigation or candidate installation in that check.
- The native run and paired timing analysis remain pending; no speed improvement claim.

## First native attempt: failed, accepted restoration verified

The original diagnostic protocol/manifest remain immutable:
protocol 882de385ca56bc11f72298256fa90cbcc65cef73c1d880606b7d00b3dd601514,
manifest 0e06544c73f32b805206b0a3560613c4617be2dd07559944d2df6d1aae263d4e.
The candidate and final accepted direct SDK/profile/OAT admission both passed with fresh
cache code 1. The first warmup failed; zero warmups, timed entries or owned stops were
accepted. No timings from this attempt can be used as valid evidence.

Two concrete issues changed the next action:

1. The header gear calls launchSafely directly, bypassing the onLaunch callback used by
   the injected Settings app tile. The first implementation observed the tile only.
   The header is now wired to the measured launch wrapper with the identical NEW_TASK
   intent and failure-swallowing behavior; ordinary UI/navigation stays unchanged.
2. The new runner added full 37 MB APK reads before every POST. An APK read before STOP
   exhausted the 15-second capture deadline; the owned cohort correctly rejected it.
   A separate retry will retain the 15-second deadline and pre/post APK hashes, but use
   acknowledged live capture identity during TAP/STOP. The original trial is not rewritten.
   Failure evidence must retain unobserved/missing-stage numeric timelines instead of
   requiring valid admission when writing a failed warmup.

Native session 64212 exited 1. Cleanup SDK-bootstrap/accepted installation completed,
fresh cache code 1 and exact actual reference/DM/OAT evidence were verified again,
configuration/schema/source groups preserved, Home restored, collectors inactive.
Final accepted admission SHA is
bb531d2a50817ed6e9b9e47347854eb1bc897d2deedaf730b2ae7ac525ca509e.
No performance improvement claim or promotion; Living Area/phone remain untouched.

Header-path retry local gate: 1,678 tests / 195 suites with zero failures/errors/skips;
debug and benchmark builds passed. Frozen source
ce47a5d0207a4d5cfada406ef47b6966ad2cd3a15bcb3da0932198046deaa922 (786 files).
Ordinary APK b4a16bc339c5574a0e08299c43ba41adeacc78c4adde01d4985a74d15693bc1d;
bootstrap 84b96f13447726edc5905c0f3fd287584b09ff8de7131718dd20b1ce1fa4cc47,
pinned to that ordinary APK. Both are 37,150,356 bytes and match the accepted certificate.
Bundled SDK mapping decoded/validated successfully; no own-app profile rules.
The separate retry runner and native capture remain pending.

## Header-path diagnostic: completed, accepted restoration verified

The reviewed retry passed 102 offline contracts (80 runner / 22 analyzer). Native
session 80078 exited 0 with 20 verified warmups, 10 measured entries and 30 verified
collector stops; run 496b172e43e14c01b493491ff576f9cb. The original failed trial remains
failed and immutable. Retry manifest
4474901faf61a855bc8a95cc1e2a677bc5684e2b766bb6e17332ec98343b729b.
Offline analyzer session 68436 exited 0. Analysis SHA-256: 0c7998f2b27a04c6f03bdabeceb370541ce526cfecf25d5a3e997d047a3ba2af.

Median recorder intervals (10 entries): launch-to-create 32.4519 ms; create body
4.6180 ms; resume body 0.6550 ms; create-to-first-pre-draw 71.6288 ms;
launch-to-first-pre-draw 107.6365 ms; launch-to-frame-callback receipt 123.4978 ms;
launch-to-focus 132.5862 ms. Callback receipt/pre-draw are not display presentation
or physical touch latency. Overlapping intervals are not added.

Measured search preparation median was 0.3589 ms, shell composition 10.9174 ms and
shell measurement 10.8357 ms. Across each entry's component maxima, median layout
was 50.6401 ms and unknown/main-thread delay was 69.2058 ms. Those maxima need not
belong to the same frame. Reusing the search index is not selected: its observed
cost is too small to explain this delay. No speed improvement or promotion.

Candidate cache code 3 retained its one-byte size heuristic warning; fresh actual
reference/DM and structural OAT evidence passed independently. Accepted cleanup
restored cache code 1 with fresh direct SDK admission
8a75b7aca0ca29b57381ae333f17a33d629937ae1acc2118e632fb7cf4a4e753.
Accepted APK identity, configuration/schema/source groups, Home and inactive
collectors were verified; Living Area and the phone were untouched.

## Next bounded experiment: immediate Settings window handoff

Source inspection found a separate configured 160 ms Android window alpha fade
for ImmortalSettingsActivity. The earlier detail-pane transition removal does not
remove this window animation. This is a UX handoff cost, not a causal explanation
of the measured FrameMetrics components; do not subtract 160 ms from the recorder
results or claim measured display latency.

The candidate adds a Settings-specific theme inheriting the existing preview and
window policy, with windowAnimationStyle null. Only ImmortalSettingsActivity uses
it. No settings, persistence, route, layout or proposed cascading columns change.
Rich editors and other Activities retain their current policy.

- [x] Validate debug/benchmark resource linkage and run the unit gate.
- [x] Freeze candidate/build inputs in a separate experiment directory.
- [ ] Verify Settings/Home/search/back on Family Area, including first-draw handoff.
- [ ] Compare fresh runtime evidence and accept only verified improvement with
      preserved behavior; otherwise restore the accepted APK and exact source.

Local candidate validation completed: 1,678 tests / 195 suites, zero failures,
errors or skips; debug and benchmark builds passed. Compiled manifest selects
Theme.SampleApp.Settings resource 0x7f110111, whose compiled parent remains the
existing Navigation theme and whose windowAnimationStyle is a null reference.
The benchmark is non-debuggable, has the accepted signing certificate, and
retains the established filmstrip/Home/diagnostics/background-startup flags.

Frozen candidate app source: 01786a3daf2d0defc1442686d1ec872e1d94dc568f512ed2d94d8c514dba592d
(786 files). Ordinary APK: 8407fd11adbd394d1b1a8cf60944757eb8826ebeb0f16419c72af77fa8f006e1;
bootstrap: a46acf04a4631709e6e43d7a2b6e4f181f28e9a4fc15647e1cae97d119fbfd44,
both 37,150,452 bytes. Bootstrap metadata is enabled and targets the ordinary
SHA. Bundled SDK profile decoder/DEX validation passed with zero own-app entries
(expected mapper exit 1); actual installed admission remains a native-test step.

The current API 29 fleet has no compositor screen-stream or general screenshot
backend. FrameMetrics, lifecycle markers and compiled window policy cannot prove
an instantaneous compositor handoff; other transition participants/system policy
may still animate. Native behavior and rendering regressions remain untested for
this candidate. No acceptance or performance gain is claimed. Family remains on
the restored accepted APK. A separate diagnostic-only native driver is being
prepared; the original failed trial and completed header-path results stay immutable.

## Null-animation native attempt: transport failure, accepted restored

The reviewed driver passed its 102 offline contracts. Input manifest
f8c2aaac38e6ca981aa245030f5d5f4d28e1620471e63b94cd1db0ec3d136c86
was frozen before native operations. Native session 20000 exited 1; run
67b465b79dde460394642f3a9d16cf98 verified six warmups/six owned stops and
zero timed entries. Entry seven failed before START, during the inactive full APK
hash read preceding settled_home's Home request. The exact Fleet Retryable arises
from OSError/HTTPException at room-fleet.py:394 (connection interrupted). No timing
sample or performance acceptance is admitted from this incomplete cohort. The
failed files are immutable.

Postflight SHA: 8ed682885c1a6851b0993764c651b1dbb14a0e786aa6c2ab24ac484d514e1171.
Count-trace SHA: 3ee7945d3e9aa20ec936d392725a3e1c68321ebdb2f57f37a546493c943da3b9.
Fresh final accepted SDK admission:
cac6f8eb58148f7051473c681d638128098cacfdc73827610f511dc3c6049bb1.
Accepted APK bc6af47b4184de27f81fc59c6502871bb8a796505c59c1e047ae859dd3f28298
and SDK cache code 1 were restored. Main independently rechecked frozen inputs,
app source and the complete actual reference/DM/structural OAT restoration proof.
Configuration/schema/source groups, Home and inactive collectors passed; Living
Area and the phone were untouched.

Public Android 10 source follow-up confirms that getCachedAnimations returns null
when LayoutParams.windowAnimations is zero; loadAnimationAttr subsequently returns
null for an absent animation resource. This supports the compiled theme policy,
without establishing compositor/display timing on Meta firmware. Source:
https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-10.0.0_r1/services/core/java/com/android/server/wm/AppTransition.java#554

Next action is a fresh sibling trial with the exact same candidate/boot/source
pins. Only the driver will change: at most two full-hash attempts, transport
Retryable only, inactive/session checks before either attempt and after success,
bounded inactive-read time; no capture deadline expansion, active TAP/STOP retry,
mutation retry or identity-mismatch retry. All existing artifact/SDK/OAT/ownership
and restoration gates stay required. The failed trial remains failed.


## Bounded inactive-read retry: completed and analyzed

The separate sibling trial used the unchanged null-window-animation candidate,
source and SDK artifacts. The only driver change was bounded transport retry
at inactive full-APK identity reads. It passed 117 offline contracts (95 runner,
22 analyzer). Native session 36034 exited 0; analyzer session 12353 exited 0.
No transport retry was needed in the successful run.

Run db1a74e8f7f4495f8cf6e8e45473bd1c: 20 verified warmups, 10 measured entries, 30 verified collector
stops, zero capture failures. Manifest 95026b0b70ff4041717f96eb96cfd7d8669e3d893b731f25ad94edcc8a85d2d9.
Postflight efffc07b19b9447efe961a07405141efa02b38f615db928018b5e7b9af3e5691.
Count trace 4b4ffabc1d1d1a2914daecc31b47f0576c3a56b1dd3845ba9f8ef3bcaeec4819.
Analysis 37a40f27540a607c1650e3744dc7d52c74dfa89c50a399058eedf506a890a099.

Median recorder intervals over ten measured entries: launch-to-create
30.2227 ms; create body
5.0874 ms; resume body
0.4871 ms; create-to-first-pre-draw
73.7870 ms; launch-to-first-pre-draw
106.9500 ms; launch-to-frame-callback receipt
126.3924 ms; launch-to-focus
136.4178 ms. First pre-draw ranged from
81.6008 to
124.7041 ms. These are recorder/callback
intervals, not physical tap or compositor presentation latency.

Across each entry's frame-component maxima, median queued delay was
65.4547 ms and layout
56.6912 ms. They may
belong to different frames and are not added. Median per-entry largest Settings
frame was 92.3633 ms. The historical header
dataset is not a fresh A/B control. No causal speed-improvement, performance
acceptance, instantaneous handoff, or automatic candidate-promotion claim.

Candidate cache code 3 retained the one-byte size warning; fresh complete actual
SDK reference/DM equality and structural own OAT admission passed independently.
Accepted APK bc6af47b4184de27f81fc59c6502871bb8a796505c59c1e047ae859dd3f28298
was restored with fresh cache code 1 and final direct admission
6248ae8290622fbd1d088d63d398056e4425b86c99a3734310c5b0930c6971dd.
Configuration/schema/photo-source groups, app source, Home and inactive
collectors were verified. Main independently rechecked frozen source inputs
before recording this result. Living Area and the phone had zero operations.
All prior failed and completed trial roots remain immutable.

This test verifies repeated Settings startup/first-draw capture and Home
recovery. Category selection, search, Back and compositor appearance still
need their own native functional evidence; the broader checklist above remains
open. Ordinary scalar details already use local ApprovedSettingsHistory routes
inside the same Activity with immediate pane replacement. The browser cascade
mock remains awaiting human signoff and no native cascading columns were added.
