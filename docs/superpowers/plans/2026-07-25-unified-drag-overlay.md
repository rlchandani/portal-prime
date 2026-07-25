# Unified Drag Overlay (grid ↔ dock) — Design

**Date:** 2026-07-25
**Goal:** iPhone/iPad-grade drag: lift any icon and, in ONE continuous motion, drop it in the grid OR the dock; reorder within each; the icon follows the finger smoothly and other icons make space in real time.

## Why the current approach can't do it

- The grid uses `sh.calvin.reorderable` (`ReorderableLazyVerticalGrid`) — smooth in-grid reorder + `animateItem()` reflow. GOOD.
- The dock is a separate `Row` with a hand-rolled drag — sluggish, and NOT the same engine.
- The reorderable library (like all Compose reorder libs) scopes a drag to a SINGLE scrollable. It cannot drag an item from the grid composable into the dock composable. Confirmed via its API: `ReorderableCollectionItemScope` + a single `ReorderableLazyGridState`; no cross-container support; `onDragStarted` gives only the start offset; state exposes no live pointer position (verified by decompiling 3.1.0).
- Current drag-into-dock uses a `lastPointerY` observer that never updates during the library's drag (the library consumes pointer events) → into-dock silently fails. This is the root cause of "outside→dock doesn't work."

## Approach: a self-authored global drag layer (do NOT force the library cross-container)

Model both surfaces as ONE logical, ordered list of "home items" and render a single drag overlay above both. We stop using the reorderable library for the drag *gesture* and own it end-to-end, because only a self-owned overlay can hit-test across two containers. We keep `animateItem()` for the reflow.

### Data model
- Single source of truth: `HomeLayout { grid: List<String>, dock: List<String> }` (package names), persisted (extend `UserLayout` + `DockStore`, or a new `HomeLayoutStore`). One-place invariant enforced by construction: a package is in exactly one of the two lists.
- `dock` capped at `DockStore.MAX (6)`.

### Rendering
- Root `Box` fills the screen. Inside: the existing two-column layout (widgets | grid) + dock at bottom, PLUS a top-level overlay layer for the dragged "ghost".
- Grid = `LazyVerticalGrid` rendering `grid` list; each cell `Modifier.animateItem()` for reflow.
- Dock = `LazyRow` (or Row) rendering `dock` list; also `animateItem()` if LazyRow.
- Each icon records its bounds via `onGloballyPositioned { bounds[pkg] = it.boundsInWindow() }` into a shared map. The dock container records its own bounds too.

### Gesture (self-authored, one detector)
- Each icon (in edit mode) gets `pointerInput { detectDragGestures(... ) }` OR a `DragGestureDetector.Press`-style immediate start (already in edit mode → no long-press).
- On drag start: capture `draggedPkg`, its start bounds, hide the source slot (or leave a gap), show a ghost at finger position in the overlay (follows finger 1:1 using accumulated `positionChange()` — the frame-independent delta that fixed jitter before).
- On drag move: compute the finger's window position; find which container + index it hovers (hit-test `bounds` map). Live-reorder the MODEL (move `draggedPkg` to the hovered index in the hovered container), which drives `animateItem()` so others slide aside — across BOTH grid and dock.
- On drop: commit the model to storage. One-place invariant means moving into dock auto-removes from grid and vice versa.

### Key implementation notes
- Ghost follow must use `positionChange()` accumulation in window coords, not node-local `position` (avoids the jitter bug we already hit and fixed).
- Enforce dock MAX during hover: if dock full, don't accept the hover-insert (visually "bounce"), Toast on drop attempt.
- Auto-scroll grid if dragging near top/bottom edge (LazyGrid) — optional phase 2.
- Exit edit on tap-empty (existing). No Done button (removed).

## Tasks
1. `HomeLayoutStore` (or extend UserLayout+DockStore) unifying grid+dock order with one-place invariant; pure + unit-tested (move-between-containers, MAX enforcement, dedupe).
2. Shared bounds registry + container hit-testing helper (pure function: point + bounds map → (container, index)); unit-tested.
3. Overlay ghost composable + the single drag gesture (immediate start in edit mode, positionChange follow).
4. Live model reorder on hover (grid↔dock) + `animateItem()` on both; commit on drop.
5. Replace the reorderable-library grid drag + the hand-rolled dock drag with this unified path. (Keep animateItem; the library may be dropped for the grid drag, or kept ONLY for in-grid if we special-case — decide during build; simplest is one owned engine.)
6. On-device verification (finger): lift from grid→dock, dock→grid, reorder within each, MAX bounce, smooth follow + reflow.

## Risk
- Highest-risk UI work in the project. Mitigation: build the pure pieces (store, hit-test) TDD-first; get the ghost-follow smooth in isolation before wiring cross-container; verify each step on device.
- Dropping the reorderable library for a self-authored engine risks regressing the now-good in-grid reflow. Mitigation: keep `animateItem()` (that's what makes reflow smooth — it's Compose built-in, not the library); the library only provided the drag gesture, which we're replacing with our own that also feeds the model.

## Global constraints
- Starbright Lab header on new files; Kotlin; minSdk 24; match style; no Play Services. On-device verified by the user (touch gestures can't be validated via adb).
