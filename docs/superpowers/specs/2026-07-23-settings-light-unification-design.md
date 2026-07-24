# Settings — Light-theme unification & Large sizing

**Date:** 2026-07-23
**Status:** Approved (mockups reviewed in Chrome)
**Device target:** Meta Portal+ (`cipher`), 2160×1440, density 180, touch screen viewed at room distance.

## Problem

The main settings screen (`ImmortalSettingsActivity`) is a light iOS two-panel layout
(grey `#F2F2F7`, white cards, blue/green accents) built on the shared vocabulary in
`SettingsComponents.kt`. But **all 18 sub-page Activities are dark** (`#101012` / `#111111`
/ `#1A1A1A`) with their own inconsistent title conventions (34sp SemiBold vs 30/26sp Bold)
and back affordances (mostly invisible gesture-back; `FolderBackButton` is a dead no-op;
only `ScreensaverDismissAppActivity` draws a real back button). Tapping into any sub-page is
a jarring light→dark jump, and each dark page disagrees with the others.

Additionally, text/touch targets are too small for a wall touchscreen, and the "Settings"
title is centered.

## Goals

1. **Every sub-page becomes a full-screen LIGHT page** using the same tokens as the main
   screen (grey bg, white cards, blue/green accents, real "‹ Back", left-aligned title +
   subtitle).
2. **Large sizing scale** across settings (main + sub-pages).
3. **Sidebar "Settings" title** left-aligned and large (biggest text on the main screen).
4. **Remove redundant per-section headings** on the right panel (the selected sidebar item
   is the context). Keep uppercase *group* labels that organize multi-group screens.

## Design decisions (locked)

- Sub-page model: **Full-screen light page** (not sidebar-preserving).
- Sizing: **Large** — see scale below.
- Title: **Top of sidebar, left-aligned**; no redundant right-panel section heading.

### Large sizing scale (single source of truth → `SettingsComponents.kt`)

| Token | Old | New (Large) |
|---|---|---|
| Sidebar "Settings" title | 17sp centered | **32sp, left-aligned, Bold** |
| Sub-page H1 title | 34/30/26sp | **34sp SemiBold** (kept; already left-aligned) |
| Sub-page subtitle | 16sp | **19sp** |
| Section/group label | 13sp | **16sp** |
| Row title (body) | 16-17sp | **22sp** |
| Row subtitle | 11-13sp | **16sp** |
| Right-side value | 15sp | **19sp** |
| Icon badge | 30dp | **40dp** (radius 9dp, glyph 20sp) |
| Row min height | 54dp | **68dp** |
| Chevron | 15sp | **22sp** |
| Card radius | 12dp | **14dp** |
| Switch | default | thumb 26dp / track 56×32dp |
| Stepper button | 32×30dp | **40×38dp** |

New shared constants (add to `SettingsComponents.kt`, an `object Sizes`):
`titleSidebar`, `titlePage`, `pageSubtitle`, `sectionLabel`, `rowTitle`, `rowSub`,
`rowValue`, `iconBadge`, `rowMinHeight`, `chevron`, `cardRadius`.

## Sub-page archetypes (18 dark pages → 5 light patterns)

A shared **`SettingsScaffold`** composable provides the light full-screen shell for every
sub-page: grey bg, `verticalScroll`, `widthIn(max = 1100.dp)`, a real **`SettingsBackButton`**
(replaces the no-op `FolderBackButton`), left-aligned `titlePage` H1 + `pageSubtitle`. Back is
wired through the existing `onPreviewKeyEvent { Key.Back }` path AND the visible button.

| Archetype | Screens | Reuses |
|---|---|---|
| A. Selectable list | ScreensaverSources, FacePicker (list part), ScreensaverDismissApp | `Card`, `SelectableRow`, `SectionLabel` |
| B. Connect form | Immich, Smb, Dav, AlbumUrl, WebUrl, CalendarUrl | `SettingsScaffold` + light `OutlinedTextField` + primary button |
| C. Thumbnail picker | FolderPicker, FacePicker (gallery) | light thumb grid, blue selection ring |
| D. Feature settings | Chime, Sunrise, Sleep, Countdown, Welcome, Clock, ScreensaverSettings | `Card`, `ToggleRow`, `Stepper`, `Segmented`, `NavRow` |
| E. Status | DeviceHealth | green banner + check rows (already light-ish; restyle to tokens) |

## Main screen changes (`ImmortalSettingsActivity`)

- `IosNavBar`: title left-aligned, `titleSidebar` (32sp Bold). Remove centered arrangement.
- Sidebar rows: badge 34→ use `Sizes.iconBadge`-ish (sidebar badge stays 34dp; keep distinct
  from content badges), label 15→19sp.
- Right panel: **drop the leading `SectionLabel(selectedSection)`** for single-group sections
  (Weather, Appearance, etc.). Keep group labels only where a section renders >1 card group
  (e.g. System). Verify each `if (selectedSection == …)` block.
- Bump the in-file `MqttScreen` / `MultiRoomScreen` / `WorldClockScreen` / `BootAppsScreen` /
  `DeviceHealthScreen` (currently dark `#101012`) to the light scaffold too — they are
  sub-pages in the same navigation and must match.

## Hard rules honored

- **No prefs read/written from UI** beyond existing patterns; sizing is pure presentation.
- **One nav model — Activities**: no new in-file toggle sub-screens; scaffold is a composable
  used *inside* each Activity.
- Copyright header: `Starbright Lab` on any new file. Match existing style; edit over rewrite.
- Dark theme: sub-page Activities currently call `PortalPrimeTheme(darkTheme = true)` → switch
  to `darkTheme = false` (matching the main screen).

## Testing / verification

- `./gradlew :app:assembleDebug` after the shared foundation, and again after each batch.
- Install to the connected Portal (`FAMILY_PORTAL_SERIAL`) and screenshot the main screen + one
  page per archetype to confirm the light treatment renders on-device.
- `SettingsDomainTest` must stay green (no spec/registry changes here — presentation only).

## Out of scope

- No new settings, no registry/`SettingSpec` changes, no behavior changes.
- Orphaned sub-pages (13 not reachable from main) are still restyled for consistency, but
  wiring them back into navigation is a separate task.
