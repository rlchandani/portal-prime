# Portal+ Tools overhaul implementation plan

> **For agentic workers:** Use parallel implementation with disjoint file ownership, main integration, independent review, and device verification.

**Goal:** Implement the approved Tools library, shared workspace and all twelve tool flows on the connected Portal+, preserving existing data and measuring responsiveness.

**Architecture:** Retain the launcher header/dock and one Tools host. Keep at most four keyed local utility panes to reduce reconstruction during switching, use saveable state per tool for drafts, pause hidden pane lifecycles/input/focus/accessibility, and use contextual sheets for child controls. Audio, network and camera owners are transient. Reuse existing configuration and hardware services; only explicit camera viewing, lamp display and alarm ringing leave the workspace.

**Tech stack:** Kotlin, Compose, Android API 29, existing JVM tests and Portal instrumentation; no new runtime dependencies.

**Spec:** `mocks/portal-plus-tools-redesign/README.md`, `tools.js`, `tools.css`, and `docs/design/portal-plus-tools-overhaul-inventory.md`.

## Global constraints

- Keep `codex/portal-plus-approved-ux`; preserve unrelated dirty files and current settings/data.
- Match 1920 × 1280 dp / 2160 × 1440 px geometry, Roboto, dark palette, 68 dp controls.
- Speed test requires Advanced. All eleven other tools remain discoverable.
- Main controls build, installation, device input, backups and profiling.
- Tools forms use existing Config APIs, never direct SharedPreferences.
- Main-thread work must not perform network or scans; no idle animations or hidden polling.
- New source uses Starbright Lab 2026 MIT header.
- Tools page and rail expose the existing launcher wallpaper; retain readable card,
  selected-row and sheet surfaces. This incorporates the user's later design refinement.
- Re-selecting the active bottom Tools tab returns to the library, preserving
  filters/scroll. Remove the redundant return button above the left tool list.

## Review focus

- Nested picker Back must return to the draft; setup Cancel stays in its tool.
- Revisit retains drafts without resuming recording, narration, intercom or a bandwidth transfer.
- Invalid dates/durations/RTSP addresses never persist; cancel/delete affect only the requested item.
- All actions stay reachable with keyboard, scrolling, default text size and 1.3× text.
- Timers/alarm scheduling continues after tool/tab exit; fullscreen returns restore their origin.

## Task 1 — Shared library, workspace and controls (main)

Files: `ToolsActivity.kt`, new `ApprovedToolsCatalog.kt`, `ApprovedToolsUi.kt`,
`ApprovedToolsDiscovery.kt`; `ApprovedToolWorkspace.kt` only if needed.

- [x] Test catalog groups, search/Advanced filtering and route policy before implementation.
- [x] Implement grouped three-column library, search/categories and compact left rail.
- [x] Implement shared panels, fields, selection sheets and confirmations.
- [x] Retain per-tool saveable state, library scroll, and active lifecycle boundaries.
- [x] Integrate worker bodies with fixed heading and bounded scrolling.

Shared worker interfaces in `ApprovedToolsUi.kt`:

```kotlin
@Composable fun ToolsPanel(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit)
@Composable fun ToolsTwoPane(left: @Composable ColumnScope.() -> Unit, right: @Composable ColumnScope.() -> Unit)
@Composable fun ToolsText(text: String, secondary: Boolean = false, modifier: Modifier = Modifier)
@Composable fun ToolsButton(label: String, primary: Boolean = false, enabled: Boolean = true, destructive: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit)
@Composable fun ToolsField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, numeric: Boolean = false, multiline: Boolean = false)
@Composable fun ToolsSheet(title: String, onDismiss: () -> Unit, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit)
@Composable fun ToolsChoice(title: String, value: String, options: List<String>, onSelect: (String) -> Unit)
@Composable fun ToolsStatus(text: String, active: Boolean = false)
@Composable fun ToolsConfirm(title: String, message: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: () -> Unit)
```

All interfaces are internal in `com.immortal.launcher`. Bodies receive bounded
workspace area and use `ToolsTwoPane` for normal scrolling; no body repeats the
workspace heading or Tools return.

## Task 2 — Everyday utilities (worker A)

Files: new `ApprovedToolsEveryday.kt`, optional new pure validation/model and JVM tests.
Own Alarm/Timers/Countdowns/Converter. Exports composables
`ToolsAlarmContent`, `ToolsTimersContent`, `ToolsCountdownsContent`, `ToolsConverterContent`.

- [x] Read current Config/service APIs and approved mock; preserve existing data.
- [x] Test strict countdown date and duration validation before implementation.
- [x] Implement two-panel controls and nested selection sheets with atomic drafts.
- [x] Six timer presets, active list, custom duration and cancellation confirmation.
- [x] Single alarm/time/day controls; explicit ringing preview routed to existing activity.
- [x] Countdown add/icon/month/day/year gating and removal.
- [x] Converter six families, all native units, live result and Swap.
- [x] Pause timer display on hidden lifecycle; scheduled timers continue.

## Task 3 — Cameras and lamp (worker B)

Files: new `ApprovedToolsHome.kt`; owns edits in `CameraViewerActivity.kt`,
`LampActivity.kt` only. Exports `ToolsCamerasContent`, `ToolsLampContent`.

- [x] Preserve native RTSP player and existing lamp brightness result boundary.
- [x] Camera list/placeholder/add/validation/remove sheet; View alone opens fullscreen.
- [x] Lamp controls/numeric sheets/red warmth gating and explicit fullscreen display.
- [x] Align native fullscreen return/buttons to approved design and restore window flags.
- [x] Cancellation always closes child UI first; no unintended collection changes.

## Task 4 — Notes, intercom and stories (worker C)

Files: new `ApprovedToolsAudio.kt`; owns `BedtimeStoryActivity.kt` only if required.
Exports `ToolsNotesContent`, `ToolsIntercomContent`, `ToolsStoriesContent`.

- [x] Preserve one note/memo; retain note draft with explicit Save and delete confirmations.
- [x] Reuse AudioNote and LanAudioSessions; microphone permission and error states.
- [x] Stop audio on hidden lifecycle, tool switch and disposal; ignore stale callbacks.
- [x] Implement all five stories, same-workspace reader, explicit narration and readiness/completion/error states; the device smoke run verifies the reader, and an additional automatic check verifies speech-engine Start/Stop state. Aural voice quality is not certified.
- [x] Back from reader returns to picker before Tools; teardown releases TTS.

## Task 5 — Discovery, speed and alarm edge (main)

Files: `ApprovedToolsDiscovery.kt`, `ApprovedToolsSpeed.kt`,
`ApprovedToolsSpeedRunner.kt`, `AlarmReceiver.kt`, `AlarmRingActivity.kt` and regression tests.

- [x] ISS/Aurora two-panel view, cached real data and in-place refresh; IO off main.
- [x] Speed progress/results/explicit Cancel and no hidden transfers.
- [x] Fix one-shot snooze delivery with a distinct scheduled intent, tested policy.
- [x] Match alarm fullscreen style; preview has no sound or actual snooze scheduling.

## Task 6 — Integration, quality and performance (main + reviewer)

Files: native probe extensions and `docs/design/portal-plus-tools-native-validation.md`.

- [x] Review worker changes against approved mock, source inventory and lifecycle contracts.
- [x] Build debug/dev/release-equivalent APK and run full JVM suite.
- [x] Save installed APK/private relevant settings backup before replacing the app.
- [x] Install in place, verify same signing/package and retained configuration.
- [x] Exercise all tool entries, representative nested Save/Cancel/Back, invalid-input cases and explicit fullscreen returns. Preserve configurations and document untested hardware cases.
- [x] Capture native screenshots; compare representative views to 2160 × 1440 references.
- [x] Measure warmed tool switching, idle work and repeated-cycle memory on the non-debuggable benchmark variant.
- [ ] Quantify sheet-opening/scroll frame timing and meet the stricter 16.67 ms p95 screening target; current switching runs exceed it.
- [x] Investigate outliers, optimize, rerun affected functional and performance checks.
- [x] Independent code review and resolution of concrete findings.
- [x] Finish final artifact/device evidence and publish the validation gallery, including the unmet frame-time screening target and hardware limits.

## Decisions

The user approved the design and explicitly authorized implementation/device testing.
Existing parallel-agent authorization persists. No additional plan approval is required.
No push, merge or release publication is part of this task.
