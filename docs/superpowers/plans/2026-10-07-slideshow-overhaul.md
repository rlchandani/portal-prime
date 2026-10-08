# Portal+ slideshow overhaul implementation plan

**Goal:** Replace the default photo presentation with a bounded native stage,
safe framing, reliable navigation and preparation, while preserving the source,
video, overlay and power-policy integrations.

**Architecture:** Add three native mode IDs without reinterpreting saved legacy
IDs. Reuse `PhotoFrameController` discovery and video adapters. A dedicated
latest-request image worker prepares bounded upright bitmaps; the stage owns its
two composition layers and a single readiness-gated dissolve. Face analysis uses
small independent derivatives and cached normalized boxes, never a visible bitmap
whose lifetime it cannot own.

**Spec:** `docs/design/portal-plus-slideshow-research.md`.

## Constraints and decisions

- API 24 minimum; validate on the connected API-29, arm64 Portal+ at 2160×1440.
- Preserve the existing UX snapshot `2cbb603` on `codex/portal-plus-approved-ux`.
  Implement on `codex/portal-plus-slideshow-overhaul`; leave generated mock
  captures and unrelated untracked files untouched.
- Existing explicit style values remain compatible; new installations default
  to Smart frame. Select Smart frame on the test Portal after validation.
- All global controls go through the settings registry and its remote schema.
- Reuse `MediaCache`, source authentication, video handling, clock/calendar,
  welcome, soundscape, presence and sleep behavior.
- No photo upload for detection. Bundled ML Kit is tested before considering a
  dependency replacement. Whole-photo display remains independent of inference.
- Use Meta charcoal for the matte; no runtime blur or unsupported API-31/33 effect.
- Keep a valid current image during loading. Admit only bounded prepared pixels;
  cancel obsolete queued requests and reject stale completions after exit.
- Manual photo adjustments are non-destructive; deliberate Photos controls do
  not change the Dream's tap-to-exit behavior.

## Parallel write scopes

1. Geometry/decode worker: new `SlideshowGeometry.kt`, `SlideshowDecodeBudget.kt`,
   `SlideshowDecoder.kt`, and their JVM tests. No controller/settings edits.
   `Region(left,top,right,bottom)` uses normalized upright coordinates.
   `Transform(scale,x,y)` maps bitmap pixels into viewport pixels.
   `plan(imageWidth,imageHeight,viewportWidth,viewportHeight,regions,fill,motion,reverse)`
   returns `Motion(start,end,whole)`; `Motion.at(progress)` shares interpolation
   across scale and translation.
   Decoder exposes `file(path,maxEdge)`, `bytes(bytes,maxEdge)`,
   `stream(stream,maxEdge)` returning nullable upright Bitmap.
2. Face worker: new `SlideshowFaceStore.kt`, metadata/cache helpers and tests.
   `regionsFor(assetKey)` returns cached normalized `Region`s or null.
   `analyzeLater(assetKey,bitmap)` owns a small derivative, coalesces pending work,
   publishes only for future compositions; `close()` discards owned pending work.
3. Settings worker: `ScreensaverConfig.kt`, registry entries and their tests.
   New IDs: `smartFrame`, `portraitPair`, `wholePhoto`.
   New snapshot controls: `photoMotion`, `keepPeopleInFrame`.
   Expose `isNativeStage(mode)`; `usesEngine(mode)` excludes Classic and new IDs.
4. Main: controller integration, native stage, bounded work ownership, deliberate
   preview controls/manual adjustment, compatibility-engine correctness,
   instrumented checks and device validation. Avoid the workers' write scopes.

## Execution and verification

- [x] Run existing JVM baseline: 603 tests, zero failures/errors.
- [x] Snapshot approved UX and create implementation branch.
- [x] Write geometry/decode invariants before implementation; verify red then green.
  Cover EXIF bounds, huge dimensions, face/group constraints, full motion path.
- [x] Write cache/settings regressions before implementation; verify red then green.
  Cover stale revisions, no-face versus failure, legacy modes, registry coverage.
- [x] Add stage/worker checks before integration. Cover delayed readiness, rapid
  replacement, canceled work, singleton library and bitmap lifetime.
- [x] Integrate prepared single/pair/framed compositions, native video handoff,
  source readiness, bounded lookahead and explicit native navigation.
- [x] Add compact deliberate-viewer pause/navigation/adjust/exit controls; preserve
  idle Dream and continuation dismissal rules.
- [x] Repair legacy engine navigation/readiness/motion-duration compatibility.
- [x] Run `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:assembleBenchmark`
  and Android-test compilation. Fix failures before deployment.
- [x] Install the same-signed benchmark APK over Wi-Fi without uninstalling or
  clearing data. Keep previous APK for rollback.
- [x] Run deterministic licensed synthetic device fixtures: portraits, groups,
  panoramas, EXIF, corrupt/huge photos, offline fallback and image/video handoff.
  Measure startup/exit, transitions/motion separately, and memory stability.
- [x] Run independent integration review, address material findings, and complete
  a two-hour device soak before reporting stability.

## Progress

The final production build passed 695 JVM tests and built debug, benchmark and
Android-test APKs. Geometry/decode, face-cache, settings, latest-request worker,
navigation, shared admission and framing persistence had failing regressions
before implementation.

Independent reviews identified startup, video readiness, pending navigation,
manifest refresh, editor/pause, stable identity, transport cancellation and
aggregate pixel ownership edges; those findings were addressed. Final device
smoke passed 521 assertions, source HTTP recovery/cancellation checks passed,
and twelve fresh-process startups passed. Group protection is tested with
geometry fixtures; detector inference is verified on the licensed astronaut.

The complete two-hour Smart frame loop passed: 149,470 measured host frames,
p95/p99 9.25/11.5 ms, bounded pixel ownership and no continuing PSS growth.
Normal OS Dream start/owned rendering/window-delivered tap/stop/detach passed.
OS-injected touch wakes before the app receives it on this firmware, so physical
touch routing and unattended presence initiation remain explicit coverage limits.
Exact settings/source restoration and owned-fixture cleanup passed, then Smart
frame was intentionally selected for the original library.

Full measurements and limits:
[`portal-plus-slideshow-native-validation.md`](../../design/portal-plus-slideshow-native-validation.md).
