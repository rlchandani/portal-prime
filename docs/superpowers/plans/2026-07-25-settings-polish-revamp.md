# Settings Polish Revamp — Implementation Plan

> **For agentic workers:** execute task-by-task. Steps use `- [ ]` checkboxes.

**Goal:** Make the whole Immortal settings surface feel like one polished, consistent app —
unify all typography to the shared `Sizes` scale + `Ios*` palette, and (per user decision)
render every sub-screen INLINE in the two-panel detail panel instead of jumping to a
separate full-screen Activity.

**Architecture:** The two-panel `ImmortalSettingsScreen` (ImmortalSettingsActivity.kt) is the
one polished surface. Sub-sections currently either (a) render inline already, or (b) show a
`SubScreenNavCard` that launches a bespoke `*SettingsActivity`. Convert (b) → inline: extract
each sub-screen's content into a scaffold-free `@Composable` that the detail panel renders
directly when its section is selected. The standalone Activities can remain as thin hosts
(for the phone-remote / deep-link paths) but the on-device Settings never leaves the panel.

**Tech stack:** Kotlin, Jetpack Compose. Shared vocabulary in `SettingsComponents.kt`
(`Sizes`, `Ios*` palette, `Card`, `ToggleRow`, `NavRow`, `Segmented`, `Stepper`,
`SectionLabel`, `IconBadge`, `PickerRow`, `FooterNote`).

## Global Constraints
- LIGHT theme only for settings: `IosBackground` #F2F2F7 page, `IosCard` #FFF cards,
  `IosLabel` #000 titles, `IosSecondary` #8E8E93 secondary text. NEVER `Color.White` text
  on a card (invisible). NEVER `darkTheme = true` for a settings surface.
- All font sizes come from `Sizes` — no bare `.sp` literals for text that has a token:
  titlePage=34, pageSubtitle=19, sectionLabel=16, rowTitle=22, rowSub=16, rowValue=19,
  chevron=22, iconGlyph=20. Row min-height = `Sizes.rowMinHeight` (68dp), row padding 16/10dp.
- All accent/secondary colors from the palette: `IosBlue` (not 0xFF007AFF/0xFF2E6BE6),
  `IosSecondary` (not 0xFF9A9A9A), `IosChevron`/`IosTertiary` (not 0xFF7C7C7C).
- Immutability: config updates return new objects (already the pattern via `*Config.copy`).
- Don't break the phone-remote path: apply still routes through `SettingsDomains.<x>.apply`.

---

## Task 1: Add missing shared tokens + fix source-of-truth literals

**Files:** Modify `SettingsComponents.kt`

Centralize the recurring literals the audit found so consumers stop hand-rolling them.

- [ ] Add to `Sizes`: `val actionButton = 17.sp` (unify all blue action-button labels),
      and note `rowValue`=19 is the canonical stepper-value size.
- [ ] Add palette: `internal val IosRed = Color(0xFFFF3B30)`,
      `internal val IosFocusTint = Color(0xFFE5F0FF)` (the recurring focus/pill pale-blue).
- [ ] Fix in-file literals: `Segmented` (~line 448) fontSize 13sp → keep as a labeled
      `Sizes.segmentLabel` token (add it =15.sp) and use `IosLabel`/`IosTertiary` not raw hex;
      `StepperButton`/`ArrowButton` glyph → `Sizes.chevron`.
- [ ] Build: `./gradlew :app:compileDebugKotlin` → SUCCESS.
- [ ] Commit: `refactor(settings): add shared action/red/focus tokens; de-literal SettingsComponents`

## Task 2: Fix SettingsRenderer.kt (generic path — affects every screen)

**Files:** Modify `SettingsRenderer.kt`

- [ ] Line ~91 enum title: `fontSize = 17.sp` → `Sizes.rowTitle`.
- [ ] `HelpText` (~145): `Color(0xFF9A9A9A)` → `IosSecondary`; `13.sp` → `Sizes.rowSub`.
- [ ] Row/HelpText padding `18.dp` → `16.dp` to match shared rows.
- [ ] Build + `./gradlew :app:testDebugUnitTest` → SUCCESS.
- [ ] Commit: `fix(settings): generic renderer uses Sizes/Ios tokens (enum title, help text)`

## Task 3: Inline the light bespoke sub-screens into the panel

**Files:** Modify `ImmortalSettingsActivity.kt`; refactor `ChimeSettingsActivity.kt`,
`SunriseSettingsActivity.kt`, `SleepSettingsActivity.kt`, `CountdownSettingsActivity.kt`,
`WelcomeSettingsActivity.kt`, `ClockSettingsActivity.kt`, `ScreensaverSettingsActivity.kt`

Pattern per screen: extract the `SettingsScaffold { body }` CONTENT (the `body` lambda) into
an `internal @Composable fun <Name>Content()` (no scaffold, no Back button, no page title —
the panel's `DetailTopBar` already shows the section name). Keep the Activity as a thin host
that wraps `<Name>Content()` in `SettingsScaffold` for the remote/deep-link path.

- [ ] Extract `ChimeSettingsContent()` from ChimeSettingsActivity; in the panel replace the
      `selectedSection == "Chime"` `SubScreenNavCard` with `ChimeSettingsContent()`.
- [ ] Same for Sunrise & Wake → `SunriseSettingsContent()`.
- [ ] Same for Sleep Timer → `SleepSettingsContent()`.
- [ ] Same for Countdown → `CountdownSettingsContent()`.
- [ ] Same for Welcome Greeting → `WelcomeSettingsContent()`.
- [ ] Same for Clock Face sub-page (if separate) → `ClockSettingsContent()`.
- [ ] Screensaver already renders inline; confirm it uses the same content composable.
- [ ] While extracting, collapse hand-rolled rows onto shared `ToggleRow`/`NavRow`/`Stepper`/
      `PickerRow`, and swap all action-button labels to `Sizes.actionButton`, stepper values to
      `Sizes.rowValue`, reds to `IosRed`, focus tints to `IosFocusTint`.
- [ ] Build + visually verify each section renders inline (screenshot on device).
- [ ] Commit per screen or as one: `feat(settings): inline light sub-screens into detail panel`

## Task 4: Convert the 5 dark sub-screens to light + inline

**Files:** Modify `ImmortalSubScreens.kt` (theme flags), `ImmortalSettingsActivity.kt`
(the `MultiRoomScreen`, `MqttScreen`, `WorldClockScreen`, `DeviceHealthScreen`,
`BootAppsScreen` composables + `MultiRoomNavRow`)

- [ ] `ImmortalSubScreens.kt`: change `darkTheme = true` → `false` on all 5 Activities
      (MultiRoom, Mqtt, DeviceHealth, WorldClock, BootApps).
- [ ] Rebuild each `*Screen` composable on `SettingsScaffold`/`Card`/`ToggleRow`/`NavRow`/
      `FooterNote`: replace bg `Color(0xFF101012)` → remove (scaffold paints `IosBackground`);
      back-pill `Color(0xFF1C1C1E)` → `SettingsBackButton`; all `Color.White` text → `IosLabel`;
      greys `0xFF9A9A9A`/`0xFF7C7C7C` → `IosSecondary`/`IosChevron`; title 34sp → `Sizes.titlePage`;
      accents `0xFF2E6BE6` → `IosBlue`. Status-pill colors (DeviceHealth, MQTT auth) keep
      semantic green/red but source from `IosGreen`/`IosRed`.
- [ ] Fix `MultiRoomNavRow` (renders on light panel, currently white text) → use `NavRow`.
- [ ] Inline them: replace their `SubScreenNavCard`s with `<Name>Content()` in the panel.
- [ ] `WallpaperSection` (renders on light panel, currently `Color.White` text) → `IosLabel`/
      `IosSecondary`, `Sizes.rowTitle`/`rowSub`.
- [ ] Build + screenshot each on device to confirm light + legible.
- [ ] Commit: `fix(settings): convert 5 dark sub-screens to light iOS theme + inline`

## Task 5: Sidebar + DetailTopBar token alignment

**Files:** Modify `ImmortalSettingsActivity.kt`

- [ ] Sidebar nav label 21sp → `Sizes.rowTitle`; icon glyph 19sp → `Sizes.iconGlyph`;
      group header 14sp → `Sizes.sectionLabel`; selection `Color(0xFF007AFF)` → `IosBlue`.
- [ ] `DetailTopBar` malformed `Color(0xF2F2F2F7)` → `IosBackground`; title 26sp → decide a
      token (propose `Sizes.titlePage`=34 or a new `Sizes.titleBar`=28) and apply consistently.
- [ ] Search result rows: 18sp/14sp → `Sizes.rowTitle`/`rowSub`.
- [ ] Replace remaining `Color(0xFF9A9A9A)`/`Color(0xFF7C7C7C)` with palette; hand-rolled `›`
      26sp → `Sizes.chevron` + `IosChevron`.
- [ ] Build; full device walkthrough screenshot of every section.
- [ ] Commit: `refactor(settings): sidebar + top bar use Sizes/Ios tokens`

## Task 6: Final verification

- [ ] `./gradlew :app:assembleDebug` + `:app:testDebugUnitTest` → SUCCESS.
- [ ] Install; scroll through EVERY section on device; confirm: no full-screen jumps, no
      white-on-white, uniform fonts, icon badges on rows, consistent headers.
- [ ] Whole-branch code review of the revamp diff.
