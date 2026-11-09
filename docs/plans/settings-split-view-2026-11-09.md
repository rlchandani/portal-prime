# Settings redesign — "iPadOS-style split view" (owner chose B, 2026-11-09)

Spec: the interactive mock `mocks/settings-redesign-20261109/split-view/index.html` (new tree, every
"where did X go?" mapping, page layouts, search behaviour, light/dark, 1280×800). Current state:
`mocks/settings-redesign-20261109/current/` (54 screenshots, `structure.md`). Perf baseline:
`docs/perf/portal-perf-2026-11-09.md` (each navigation = 2 janky frames).

Defaults chosen for the mock's open questions (owner can change later):
- Profile card shows the Portal's existing name (FleetConfig name, e.g. "Family Area") + model +
  "Hearthpane <ver> · Up to date / Update available" (OtaUpdater state) — no new "room" field.
- No Automatic theme yet (Light/Dark as today).
- Settings reopens on the last page used (persisted page id; first run → Display & Brightness).
- "Advanced" is a per-row tag inside its home page + a small Advanced page for orphans; the old
  global Advanced switch is kept only if removing it would hide rows that have no other home.

## Phase 1 — structure, navigation, search (this build)
1. Split-view shell: fixed left column (profile card, search field, 4 grouped sections of rows
   with coloured rounded-square icons), right column shows the selected page as inset grouped
   cards. Back stack ≤ 3 deep within the right column; no back/forward pill.
2. New tree (4 groups, 18 pages) exactly as the mock: Most used (Display & Brightness, Sounds,
   Photo Frame, Alarms & Wake, Night & Sleep) · Personalize (Wallpaper, Home Screen, Widgets &
   Calendar, Clock Faces, Photo Sources, Welcome Greeting) · Connected home (Home Assistant + MQTT,
   Phone Remote, Intercom, Music + Multi-room audio) · General (About & Health, Portal & Android,
   Advanced). Every existing row moves to its single home per the mock's mapping; no duplicates.
   Reuse existing row composables / settings domains (settings/*.kt) — move, don't rewrite logic.
   Keep every existing setting reachable; nothing is deleted.
3. Search: indexes every row from the settings definitions (not only composed pages), including
   old names ("Slideshow", "Device name"…) as aliases; picking a result opens the page, scrolls
   to the row and highlights it briefly.
4. Perf: route is plain state set in click handlers (no SideEffect write-back); only the active
   page is composed (no hidden panes at alpha 0); pages are lazy lists with contentType per row
   kind. Target: 0–1 janky frames per navigation (measure with gfxinfo like the perf report).
5. Fix the blank Photo Sources tile icons; phone pairing code hidden until "Show pairing code".
6. Visual language: refined Home tokens (ApprovedHomePalette, 1.5 dp outlines, Hearthpane mark),
   ≥64 dp targets, 28 sp row titles, light and dark.

## Phase 2 — previews and polish (next)
Live previews (slideshow transition, clock face, wallpaper on mini Home, greeting overlay,
touch-sound test, chime preview, sleep countdown), remaining Slideshow split details, and
device-tested polish.

## Rules
GitHub: only `main` — work in a local worktree/branch, merge into local `main`, never push
feature branches. Ship via OTA (`tools/scripts/ota-release.sh`) after device testing.
