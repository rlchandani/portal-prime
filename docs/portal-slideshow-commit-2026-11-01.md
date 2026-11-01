# Family Area crop Save restores the slideshow composition — November 1, 2026

**Delivered on Family Area.** Saving a position in a portrait pair now restores both photos instead of leaving the temporary single-photo editor on screen. The first photo gets its saved crop; the companion keeps its original bitmap, pose and animation time. Cancellation retains the original composition. No image download, decode, provider or extra photo allocation is introduced by finishing the editor.

| Native check | Original | Corrected |
|---|---:|---:|
| Original assertion set | 8 failures / 53 | 53 / 53 pass |
| Final assertions, including 20 added rendering/animation checks | — | 73 / 73 pass |
| Generated completion scenarios | 5 | 5 |
| Pair photos after Save | 1 | 2 |
| Moving-pair photos after Save | 1 | 2 |
| Captured companion pixels, fixed ROI | 1,069,000 → 0 | 1,069,000 → 1,069,000 |
| Companion animation time after Save | companion absent | 3,200 ms retained |
| Existing crop-drag regression | earlier scoped pass | 73 / 73 pass |
| Isolated native exceptions | 0 | 0 |
| Crop completion native work | 3.683 s | 3.744 s |

## Root cause and correction

`SlideshowStage.adjust` expands the first photo into a temporary editing composition. The old `finishAdjustment(true)` restored the normal layout only for Gallery spread, leaving portrait pairs with one photo. Its internal presentation also stayed marked as editing.

Completion now rebuilds the original normal composition, replacing only photo 0's saved adjustment. It retains the original companion, fill/motion/gallery policy, dwell and metadata. Cancellation restores every captured pose. Save keeps the first photo's new crop and restores only companion poses; normal Gallery spread continues to ignore saved crops.

Snapshot animation times now use photo indices. Indexing by animator-list position is insufficient: saving a manual crop removes the first photo's animator, so the remaining animator could otherwise receive that first photo's elapsed time. The native moving-pair case sets the original animations to distinct **800 / 3,200 ms** positions, verifies the companion retains **3,200 ms**, then resumes and observes **3,235 ms**. The saved pose is captured before Resume and matches the original exactly. Paused/hidden hosts stay paused while the retained pose is restored. The existing viewport-dimension guard and API 24/25 hold behavior remain.

The operation recreates the normal holder views once when the user finishes editing; it retains the same image allocations. It adds no per-frame work, observer, timer or decode. These checks establish correctness and retained bitmap ownership, not CPU/FPS savings or complete GPU residency.

## On-device verification

The debug-only generated host uses the real crop toolbar and stage, with a synchronous non-persisting Save callback. Five cases cover paired Save, paired Cancel, moving-pair Save, single-photo Save and Gallery print Save. Every case checks retained bitmap identity, saved first-photo adjustment, pause state, and a second edit/cancel round. The final matrix preserves all **53 baseline labels** and adds **20 rendering/animation assertions**; the fixture also resets stage pause state between cases for consistent isolation. Button clicks use native `performClick`, not a physical finger.

The first paired Save has an independent **2160 × 1440 Android 10** Window pixel check. Its generated blue companion occupies the same ROI and bounds after the corrected Save. The original Save removes all its blue pixels. The ROI is rows **100–1099**, RGB blue >160, red <90, green <130. This generated classifier does not classify arbitrary household photos. No UiAutomation observer or real photo source is used.

Because the changed completion path also serves Cancel, the final debug build additionally runs the existing **73-assertion crop-drag matrix**. All pass. Fresh debug/performance/instrumentation builds and **3,765 unit tests / 395 suites** pass, with zero failures, errors or skips, in **4m 18s**. The strengthened native assertions and cleanup were subsequently rebuilt in the instrumentation APK; production source and the optimized artifact were unchanged. Native checks run on debug; the optimized artifact's identity is checked separately.

## Deployment and remaining acceptance

Optimized APK `28735244a70246675f4deed8557703f9e6781f60718adec80f54633c64d76188` was verified installed over wireless ADB **14 minutes 43 seconds** into the original **16:51:37–17:21:37 UTC** slot, whose deadline was never reset. Family Area's **97 settings**, sources, face-cache bytes, accessibility, pin, brightness policy, test APK and original Dream were restored. The system-server PID is unchanged. Owned evidence is removed; no instrumentation or performance collector remains. Household data, connection tokens and rollback APKs remain outside Git. Living Area was untouched.

[Original assertions](../mocks/portal-premium-player-20261030/evidence/family-slideshow-commit/baseline-checks.json) · [Corrected assertions](../mocks/portal-premium-player-20261030/evidence/family-slideshow-commit/checks.json) · [Drag regression](../mocks/portal-premium-player-20261030/evidence/family-slideshow-commit/drag-regression.json) · [Pixel proof](../mocks/portal-premium-player-20261030/evidence/family-slideshow-commit/pixel-proof.json) · [Unit gate](../mocks/portal-premium-player-20261030/evidence/family-slideshow-commit/unit-gate.json) · [Restoration](../mocks/portal-premium-player-20261030/evidence/family-slideshow-commit/verification.json).

All eight independent [post-deployment health checks](../mocks/portal-premium-player-20261030/evidence/family-slideshow-commit/post-health.json) pass. Native assertions are unchanged in the final report-only rerun; its sampler now records the saved pose before the intentional Resume step.

Natural room-light fade/comfort acceptance remains pending. The brief black Dream-to-Home handoff and prior capped performance experiments remain parked. This correction does not claim provider/audio, persisted-position I/O, physical-finger, every resize/API variant or panel-performance acceptance. The full platform goal remains incomplete.
