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
- Only the existing Home gear's actual startActivity boundary is measured as an observed
  launch. Other entry paths remain explicitly unmatched; no inferred launch latency.

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
