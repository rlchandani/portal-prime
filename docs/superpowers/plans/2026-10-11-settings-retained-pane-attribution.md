# Settings retained-pane attribution implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Determine whether hidden retained Settings panes perform significant measurement work during category/page switching on Family Area before changing retention.

**Architecture:** Preserve the existing four keyed panes, input/lifecycle gates and Box policy. A stable, benchmark-opt-in layout observer records bounded primitive measurements into the existing expiring Fleet performance capture. It adds no node to ordinary builds and no new setting or HTTP endpoint.

**Tech Stack:** Kotlin, Jetpack Compose UI 1.10.4, Android API 29, JVM/JUnit, authenticated Fleet HTTP diagnostics.

**Spec:** `docs/design/portal-settings-window-matched-validation.md` next bounded investigation; current Settings retention/input behavior in `ApprovedSettingsShell.kt`.

## Global constraints

- Family Area only; Living Area and phone frozen. No GitHub operations.
- Preserve drafts, scroll, focus/input/lifecycle/semantics gates, retention limit and rich-editor disposal.
- Cascading columns remain browser-only pending review.
- Probe identity is a fixed enum; no labels, values, URLs, credentials, Context or Activity references.
- Stable modifier/state identity across activation; no snapshot-state writes from measurement.
- Opt-in benchmark flag false by default; capture deadline and stop guards apply to every observation.
- Inclusive elapsed spans overlap; do not add them or claim CPU/display latency.
- A missing outer callback does not exclude descendant-only work. No speed claim from instrumentation.

## Review focus

1. Activation must not invalidate measurement because the probe element changed.
2. A late span must not publish to a replacement or closed capture.
3. Hidden work and visible work must remain separate after buffer saturation.
4. Unbounded constraints are legal; malformed/inverted/negative constraints are rejected.
5. Functional route/window evidence must distinguish actual category/page switches; attribution builds are not final timing-comparison builds.

### Task 1: Pure bounded collector and span probe

**Files:** Create `SettingsPaneWorkSamples.kt`, `SettingsPaneWorkProbe.kt` and their JVM tests.

**Interface:** Fixed five-pane enum; samples record pane, active flag, elapsed/duration, four constraint integers and thread flag. Snapshot retains at most 512 samples and aggregates each pane/active bucket. Probe captures one measurement on begin and finishes at most once.

- [x] Write and run failing scoped JVM tests.
- [x] Implement bounds, independent aggregates, fractional timing and span ownership/failure handling.
- [x] Verify tests; review no UI references and no unbounded keys/storage.

### Task 2: Stable capture-only integration

**Files:** Create `SettingsPaneWorkModifier.kt`; modify `ApprovedSettingsShell.kt`, `FramePerformanceCapture.kt`, `SettingsUiWorkModifier.kt`, `app/build.gradle.kts`.

- [x] Add benchmark-only `PORTAL_SETTINGS_PANE_DIAGNOSTICS`, false by default.
- [x] Remember a primitive pane observer and stable Modifier; publish active attribution in SideEffect before layout.
- [x] Measure the same child with the same constraints and place it as before.
- [x] Add session-owned optional samples, guarded measurement publication and safe JSON snapshot.
- [x] Run complete unit suite and debug/opt-in benchmark builds; inspect compiled flag values and diff.

### Task 3: Family attribution and decision

**Files:** New ignored/private diagnostic artifacts and a committed result report.

- [x] Freeze current source, compiled signed APK, flags, local driver and protocol.
- [x] Deploy only to Family; save/config-compare settings and photo sources in memory.
- [x] Use verified native routes to exercise warmed categories and scalar pages; separate active/inactive callback records, constraints and durations from root/frame work.
- [x] Attribute observable work honestly; absent boundary callbacks leave descendant-only work unresolved.
- [x] Restore accepted APK and SDK profile unconditionally, verify Home/inactive/config/source state.
- [x] If significant hidden work is observed, separately test active-only measurement without changing retained composition; otherwise reject this hypothesis and target remaining measured cost.
- [ ] Keep instrumentation disabled in any final matched speed comparison.

**Ruling:** Attribution builds keep the existing UI-work observer elements attached outside recording, too. Otherwise START/recomposition could add layout nodes and manufacture hidden-pane work. Ordinary builds retain the prior conditional observer behavior.

**Completed local evidence:** 1,719 unit tests in 198 suites, zero failures/errors/skips; debug and opt-in benchmark builds passed. Actual packaged DEX flags show pane/UI diagnostics false in debug and true in the attribution benchmark. APK signing certificate/package/non-debuggable benchmark checks passed. The tested private APK and source inventory are preserved in `build/fleet/diagnostics/settings-pane-attribution-2026-10-11`. The separate frozen Family retry completed 52 captures (10 warm-up, 30 category, 12 scalar), 173/173 acknowledged inputs and 12 verified Back operations, after correcting a Display-only driver guard in the preserved incomplete run. Retry helpers/analyzer passed 54 tests normally and with Python optimization. Exact accepted APK/SDK profile, configuration/photo sources, source inventory, Home and inactive collector restoration all passed; Living/phone operations remained zero. Category hidden boundary work was 0.108073 ms median (maximum 0.264531 ms). First Appearance visible measurement was 80.200208 ms versus 0.224791 ms median for five repeat visits; first Wallpaper was 34.566771 ms versus 0.438385 ms for five repeat visits. Active scalar attribution matched each route. Inclusive instrumented observations are not CPU/interaction latency or speed improvements, and descendant-only work remains unresolved.

**Decision:** Do not change retained-pane measurement on this evidence. Target first-visible-page construction/measurement/rendering next; preserve existing UX and registry behavior. A final matched speed comparison with instrumentation disabled remains required before promoting any optimization. See `docs/design/portal-settings-pane-attribution-probe.md` for the full trial, limits and provenance.
