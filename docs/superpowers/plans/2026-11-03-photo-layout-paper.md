# Paper photo layout implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Implement the reviewed larger whole-photo Mosaic and Framed Print with a quiet Paper doodle surface and slim photo-sized frames.

**Architecture:** Keep the existing decode, double-slot dissolve, pause and retirement owners. Shared bounded geometry selects a free rectangle from measured native overlay bounds, then arranges at most six photographs by their decoded proportions. The native face retains its configured colors with localized backing on Paper; the host publishes immutable geometry to the WebView.

**Tech Stack:** Existing JavaScript/CSS/SVG assets, Kotlin Android views, Node test fixtures and isolated Chromium.

**Spec:** `docs/portal-photo-layout-review-2026-11-03.md`

## Global Constraints

- User's “Continue where you left off” follows the concrete review; use recommended Paper for this local implementation. No new setting or theme selector.
- Existing isolated worktree; root owns writes. Independent reviewer owns read-only review.
- Whole unit, including discovery, verification, cleanup and commit: 04:23:10–04:53:10 UTC November 4. Preserve older issue clocks.
- Android 9/10: no container units, backdrop filters or added dependencies.
- At most six unique Mosaic photos, two slots each, at most two initial concurrent decodes.
- Whole photos remain visible at motion extrema; face hints and the motion-off preference remain respected.
- No device install, Pixel operation, live transfer, account operation or publication in this unit.

## Review Focus

- Replacement with a radically different decoded aspect must not recreate an empty outlined tile.
- A tiny or portrait viewport and a one-photo library must still have positive, bounded geometry.
- Unknown, invalid or excessive native overlay data must fall back without unbounded work.
- Resize during a held decode, paused fade or disposal must not decode again or publish stale work.
- A white native clock in any grid position needs localized contrast on Paper, including Shuffle transitions.

### Task 1: Integrate whole-photo Paper presentation

**Files:**

- Modify: `app/src/main/assets/screensaver/modes.js` — bounded free-area and row geometry.
- Modify: `app/src/main/assets/screensaver/mode-impls.js` — Framed and Mosaic layout only; retain lifecycle owners.
- Modify: `app/src/main/assets/screensaver/styles.css` — mode-scoped Paper and photo-sized frames.
- Create: `app/src/main/assets/screensaver/photo-doodles.svg` — reviewed static original vector.
- Modify: `app/src/main/java/com/immortal/launcher/PhotoFrameController.kt` — immutable measured geometry bridge, layout-driven refresh.
- Modify: `app/src/main/java/com/immortal/launcher/FaceRenderer.kt` — localized Paper contrast and shade restoration.
- Test: `scripts/tests/slideshow_photo_layout.test.mjs` and existing Framed/Mosaic lifecycle/viewport/motion suites.

**Interfaces:**

- Consumes: existing `ctx`, `SS.take`, `SS.slots`, `makeTimers`, `makeSceneDissolve`, measured native `Rect` reservations and style reporting.
- Produces: `SS.photoLayoutArea(ctx)` rectangle `{x,y,w,h}`, `SS.photoLayoutRows(photos,width,height,gap)` rectangles, native `getPhotoLayoutBounds()` JSON `{width,height,occupied}`.

- [x] **Step 1: Write failing behavior tests.** Pin six unique whole-photo Mosaic frames, replacement aspect reflow, one-photo/tiny geometry, larger Framed geometry, valid overlay exclusion and invalid/excessive fallback. Test real mode execution with generated dimensions and inert native snapshots.
- [x] **Step 2: Run RED.** `node --test scripts/tests/slideshow_photo_layout.test.mjs`; expect assertions against existing small fixed cells, small Framed limits and absent reservation support.
- [x] **Step 3: Implement shared geometry and integrate both modes.** Exhaust at most 32 row partitions and at most 32 overlay rectangles / 16 candidates. Reflow admitted sources on resize without selection or decode. Keep photo-sized frames inside invisible layout slots; use spotlight lift rather than cropping pans.
- [x] **Step 4: Integrate native geometry and contrast.** Snapshot on the UI thread using the existing layout observer; WebView bridge reads immutable JSON. Style reports update localized bucket backing; preserve prior shade when leaving Paper. No background polling.
- [x] **Step 5: Run GREEN and existing lifecycle gates.** Update old nine-cell/60%-width and whole-output snapshots only where the reviewed design intentionally changes them; retain all lifecycle and unrelated mode assertions.
- [x] **Step 6: Verify real raster layout and builds.** Isolated Chromium at wide/portrait/Portal+ sizes, varied photo shapes, replacement and resize; full JS CI commands, fresh JVM tests, debug assembly and exact APK asset hashes. Browser proof does not establish Portal hardware acceptance.
- [x] **Step 7: Independent final review and local commit.** Address blocking findings, save receipts and update only the existing photo-layout task's status. Leave native delivery explicitly open.
