# Portal+ approved UX implementation

Approved contract: `mocks/portal-plus-complete/IMPLEMENTATION-HANDOFF.md`,
revision 04; source baseline `caf8f350cabd00ab1b4aa2951e512bf6e351a702`.
Branch: `codex/portal-plus-approved-ux`.

## Goal

Implement the approved native Compose experience on the connected Portal+ and
verify the upgrade, navigation, settings, conditional controls and home layout
on its 2160 × 1440 display at density 180 (1920 × 1280 dp).

## Latest authorized steering

- Home, Apps and Tools form one continuous dark launcher in `HomeActivity`,
  with local tabs and 160 ms horizontal transitions. All Home entries into
  Apps or Tools select those tabs. Photos opens full-screen.
- Widgets remain draggable and resizable, with layouts saved through the
  existing `HomeWidgetStore` and its persisted model.
- Settings navigation from Appearance to Display stays in the same detail
  pane and window. Keep the shared sidebar and header fixed; animate only
  the detail content for 160 ms.
- Rich editors, system screens and third-party handoffs remain Activities.
  Rich settings editors continue to use registry `NavSpec` entries, following
  the repository settings rule.

## Constraints

- Keep the frozen 67 mocks and 795 references intact; no browser edits.
- Preserve widget identities, configured services, photo sources and credentials.
- Keep validation and side effects in the existing settings domains.
- Advanced is presentation only; disabling it must not disable configured features.
- Keep rich editors as Activities and retain Android/third-party handoffs.
- Use native Roboto, solid surfaces and native Compose controls.
- Never publish private device data or signing material.

This checkpoint is documentation only: edit this plan alone. Do not edit
`docs/design/portal-plus-native-validation.md` or
`docs/superpowers/plans/portal-plus-performance-audit.md`, change code or mocks,
operate the device, or stage, commit or otherwise modify Git state.

## Native architecture and reported progress

The following records the latest supplied implementation status; this
documentation update does not independently run the app or repeat its test gate.

- `ApprovedLauncherTabs.kt` retains visited Home/Apps/Tools tabs. Hidden pages
  have lifecycle state `CREATED`, stopping refresh, recording and Back handling.
  Horizontal motion uses animated graphics-layer offsets without per-frame
  composition.
- `AppSwitcherActivity` and `ToolsActivity` expose embedded screen APIs for
  the launcher host. All Home Apps/Tools entries now select local tabs.
- `ImmortalSettingsActivity` owns simple settings routes through
  `ApprovedSettingsRoute` and `LocalApprovedSettingsNavigate`. The shared shell
  keeps the sidebar and header fixed; `AnimatedContent` wraps only the detail
  pane with a 160 ms transition. Rich editors and external handoffs retain
  their Activity boundaries.
- The widget editor retains the existing persisted model and drag/resize
  behavior. Performance changes retain a bounded three-widget-page working
  set and cache data according to the Home-tab lifecycle and refresh deadlines.
  Actual gesture and end-to-end verification remain ongoing.
- The latest reported JVM gate passed **478 tests**. This does not establish
  native interaction acceptance or performance completion.
- **Performance is not complete.** The previous installed debug artifact
  measured Home-page p95 at **62.88 ms**. This is historical evidence for that
  artifact, not certification of the latest implementation; current frame
  evidence remains ongoing.
- The frozen **67 mocks / 795 references** remain unchanged, with no browser
  edits. Native acceptance must be established on the implemented screens.

## Parallel work and interfaces

1. Home worker: `HomeActivity.kt` and independent home layout models/tests.
   Bounded widget area, compact favorites, non-overlapping in-flow navigation,
   paging, editing, existing live data and app/widget behavior; retained local
   Home/Apps/Tools tabs, lifecycle gating and 160 ms graphics-layer transitions.
2. Settings worker: main settings Activity, scaffold, renderer and shared rows.
   Seven everyday categories plus Advanced; grouped native controls and editors;
   simple routes in one fixed shell with detail-only 160 ms transitions.
3. App/tool worker: store, tools, app switcher and APK browser.
   Expose embedded Apps/Tools screens for the Home host; preserve native install
   paths, catalog and third-party launch behavior.
4. Integrator: config and registry presentation metadata, persisted Advanced and
   appearance preferences, shared theme, remote renderer, tests and device QA.

Shared interfaces:
`ImmortalSettings.Settings.advancedSettings: Boolean = false`,
`settingsTheme: String = "light"`; registry keys use those same names.
`SettingsPresentation.isAdvanced(domainId, key)`,
`conciseHelp(domainId, key, fallback)` and `isAdvancedPage(page)`.
Theme palette uses `ui.theme.LocalApprovedPalette`.

## Acceptance and remaining tasks

Implementation progress above is recorded separately from acceptance. The
following checks remain open; do not mark the overall UX or performance complete
until actual native evidence supports them.

- [ ] Verify continuous dark Home → Apps → Tools navigation in one
  `HomeActivity`, including every Home entry, retained visited-tab state,
  160 ms horizontal transitions and full-screen Photos.
- [ ] Verify hidden pages at `CREATED` stop refresh, recording and Back
  handling, and resume the intended behavior when selected again.
- [x] Exercise actual widget drag and resize gestures end to end; verify
  `HomeWidgetStore` saves and restores layouts while preserving widget identities
  and configured services.
- [x] Verify Home → Apps → Tools → Home, embedded Converter/Back, Apps
  search/Back and full-screen Photos return retain the intended host/theme.
- [x] Verify Appearance → Display → Back and cached scalar-pane revisits
  keep the same Activity/window. Revisit after more than four routes; verify
  Display's Night Shift parent gate and restore the original values.
- [ ] Verify Appearance → Display navigation stays in the same Settings
  detail pane/window, with a fixed sidebar/header and only the detail content
  animating for 160 ms. Exercise Back and rich-editor/system/third-party handoffs.
- [ ] Validate the bounded three-widget-page working set and lifecycle/deadline
  data caching through real Home navigation and refresh behavior.
- [ ] Collect current artifact frame evidence and complete the performance
  assessment. Do not use the previous debug p95 of 62.88 ms or the 469-test
  gate as performance certification.
- [ ] Finish native upgrade, settings, conditional-control and navigation QA
  below, recording remaining differences against the frozen references.

## Validation

Baseline unit tests passed before changes; the latest reported gate passes
478 tests. After further implementation changes, re-run the full JVM unit-test
gate and debug APK build. Subsequent implementation/QA work must upgrade the
installed debug package in place after saving a private rollback backup, then
exercise Home/Apps/Tools tabs, full-screen Photos, all settings categories,
Appearance → Display continuity, Advanced off/on/search, source/connection
cancellation, app/tool/store navigation, actual widget editing and
sleep/permission handoffs. Compare actual native screenshots with frozen browser
references and collect current frame evidence; record remaining differences
explicitly instead of claiming parity or performance completion from a successful
build or unit-test gate. Device operations and these implementation checks are
outside this documentation-only checkpoint.
