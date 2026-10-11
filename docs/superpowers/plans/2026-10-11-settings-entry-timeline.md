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
- [ ] Review a diagnostic-only runner: 20 warmups + 10 fresh entries, strict collector
  ownership, installed identity and source/configuration guards.
- [ ] Capture only Family Area, then unconditionally restore and verify the accepted APK.
- [ ] Analyze paired entry stages and existing frame components; select the next actual
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
