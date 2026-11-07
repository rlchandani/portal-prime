# Home widget free placement — plan (2026-11-06)

Requested by the human: on any Home page, move a widget into any empty slot
(e.g. row 2) without filling row 1 first. Approved option: **free placement**.

## Today

- Grid: 4 columns × 4 half-rows per page = 2 rows × 4 columns of slots.
  One slot = 1 column × 2 half-rows. Sizes snap to whole slots (commit 0a79e128).
- Layout is **order-only**: `ApprovedWidgetPacking.pack` puts each widget at the
  earliest fitting row, leftmost column. `HomePresentation.page` splits by order
  (page 1: photo hero + 4, later pages: 8). Move = reorder. Gaps impossible.

## Target behavior

1. Each widget may carry a saved slot: `page`, `column` (0–3), `row` (0–1 slots).
2. Placed widgets render exactly there. Unplaced (legacy/new) widgets auto-flow
   into the first free slots, as today, after placed ones are reserved.
3. Customize: drag a widget onto any empty slot (same or another page) → it
   saves that slot. Dropping onto an occupied slot swaps (or reflows) — decide
   in implementation, prefer swap when sizes match, otherwise reject with a
   visible "doesn't fit" state.
4. Resize: grow only if the new footprint is free on that page; otherwise refuse
   (no silent overlap). Shrink always allowed.
5. Migration: none on disk. Legacy widgets have no slot → auto-flow reproduces
   today's screen exactly. The first move/resize in Customize persists slots
   for the widgets on that page (so nothing jumps).
6. Paging: page count = max(saved page + 1, pages needed for auto-flow).
   Photo hero stays page 1 only.
7. Paired browser layout editor / Fleet: must round-trip the new fields
   (unknown-field safe) and not destroy slots when it reorders.

## Out of scope

- No new widget kinds, no visual restyle, no change to sizes allowed.
- No change to the Living Area Portal until Family is accepted.

## Verification

- TDD: pure placement resolver (slots + auto-flow, collisions, out-of-range,
  page growth), serialization round-trip + legacy decode, move/resize rules.
- Full JVM gate + JS gates if remote assets change, optimized build, lint.
- Device (Family Area only): before/after layout capture via fleet-backup;
  confirm unchanged screen after install (legacy = auto-flow), then the human
  tries moving a widget into row 2.

Touchpoint map: see explorer report (to be appended below).
