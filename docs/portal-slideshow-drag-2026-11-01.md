# Family Area slideshow crop dragging — November 1, 2026

**Delivered on Family Area only.** Framed-photo dragging now follows the finger's distance, and reversing at a crop edge responds immediately. The original image remains unchanged. The dedicated night clock and automatic transitions are unaffected by this correction.

| Native check | Before | After |
|---|---:|---:|
| Requested framed movement | 80 × 60 px | 80 × 60 px |
| Rendered view movement | 73.667 × 55.250 px | 80 × 60 px |
| Independent captured marker movement | 73.501 × 55.038 px | 80.000 × 60.000 px |
| Full-screen reverse after reaching edge | 0 × 0 px | −80 × −60 px |
| Assertions | 21 failures / 73 | 73 / 73 passing |
| Generated native scenarios | 5 | 5 |
| Isolated exceptions | 0 | 0 |
| Native work | 3.620 s | 3.643 s |

The same ordered assertions cover full-screen 2×, framed 2× and 3×, a smaller framed viewport, and the unzoomed whole photo. Each exercises forward, reverse, edge-crossing and reversal after an edge. The whole photo remains centered. Assertions confirm the editor is actually open, input starts inside the stage outside the toolbar, and each gesture retains the same image view and bitmap. The test injects software MotionEvents through the native Activity; physical finger input and panel frame rate are not claimed.

## Correction

`SlideshowViewerControls` formerly converted touch distance using a full-stage transform. A framed print is rendered at a smaller scale. `SlideshowStage.draggedAdjustment` now resolves the active edited image's drawable identity, live scale, translation and holder dimensions for each move. It clamps the resulting translation before deriving persisted focus. That prevents invisible overshoot from making reverse gestures stick. Resizing can replace the image view; no reference is cached across moves.

The existing `adjust` fast path still updates only view properties. It does not acquire or decode a new image, recreate the holder, start a dissolve, or write settings. No performance/FPS improvement is inferred from these correctness checks. Invalid or unavailable image geometry is rejected. Gallery spread retains its existing whole-photo policy and does not expose crop editing.

## Verification and deployment

Fresh debug, optimized performance and instrumentation builds pass. **3,764 JVM tests across 395 suites** pass with zero failures/errors/skips; every report is fresh. The full build took **3m 48s**. Fixture-only cleanup refinements were subsequently rebuilt in the debug and instrumentation APKs. Native assertions run against the debug build; the optimized artifact's installed identity and restoration are verified separately. The instrumentation fixture is debug-only and uses generated bitmap pixels with a non-persisting save callback, bounded work waits and owned-session teardown.

Optimized APK `658f61d5871f2f8520994f2aed938a0a1b1da78b2d21eded72754e2e6f9ae1a1` was verified installed over wireless ADB **13 minutes 52 seconds** into the original **16:17:11–16:47:11 UTC** slot. Family's settings, sources, face-cache bytes, accessibility services, pin, brightness policy, test APK and original Dream are restored. The system-server PID is unchanged; no instrumentation or performance collector remains active; owned device evidence was removed. Living Area was untouched. Household settings, credentials and rollback APKs remain outside Git.

The first narrower run is retained privately (12 failures / 37 assertions). The expanded red run and final green run use the same 73 assertion labels. Minor harness cleanup safeguards added after red do not change gesture/assertion cases.

## Evidence and remaining scope

[Original native assertions](../mocks/portal-premium-player-20261030/evidence/family-slideshow-drag/baseline-checks.json) · [Corrected native assertions](../mocks/portal-premium-player-20261030/evidence/family-slideshow-drag/checks.json) · [Fresh unit gate](../mocks/portal-premium-player-20261030/evidence/family-slideshow-drag/unit-gate.json) · [Deployment/restoration proof](../mocks/portal-premium-player-20261030/evidence/family-slideshow-drag/verification.json)

Native captures: [original before](../mocks/portal-premium-player-20261030/evidence/family-slideshow-drag/original-before.png), [original after](../mocks/portal-premium-player-20261030/evidence/family-slideshow-drag/original-after.png), [corrected before](../mocks/portal-premium-player-20261030/evidence/family-slideshow-drag/corrected-before.png), [corrected after](../mocks/portal-premium-player-20261030/evidence/family-slideshow-drag/corrected-after.png).

The natural room-light fade/comfort check remains unavailable; do not treat it as passed. The brief black Dream-to-Home handoff remains parked under its expired issue cap. Cold startup, SMB establishment, provider/audio and panel performance are outside this accepted correction.
