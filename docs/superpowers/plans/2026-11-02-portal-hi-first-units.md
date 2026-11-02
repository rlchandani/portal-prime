# Portal HI first units Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Demonstrate the updated Portal OS HI workflow with two small, independently verifiable fixes on Family Area.

**Architecture:** Preserve the existing Compose cards, shared settings registry and alarm scheduler. Correct a route at its existing call site; move alarm time arithmetic into a pure tested helper and refresh only while Home is active. Main coordinates/integrates/device-tests; one engineer implements the disjoint timing helper/tests; an independent QA reviewer checks integration.

**Tech Stack:** Kotlin/Compose, JVM JUnit, existing optimized performance APK, wireless ADB, existing Fleet Agent.

**Spec:** Started under `~/.codex/attachments/f82ee58d-dddc-451f-a985-d29c016c523d/goal-objective.md`; the user subsequently replaced it with `~/.codex/attachments/4832b00e-0492-44a6-a521-a970bf2ea968/goal-objective.md`. These bounded units also serve that whole-product objective. Source-grounded widget and settings audits supply bug evidence. The preceding broad widget-redesign goal is superseded, not silently declared complete.

## Global Constraints

- API 28/29 Portal hardware; minSdk 24; no Google services/root/new generated media assets.
- Each independent issue has a 30-minute cap for implementation and verification; unresolved outcomes are parked with evidence.
- Family Area is primary test hardware. Final accepted software reaches Living Area; do not double every performance experiment.
- No copied prefs or new alarm scheduling/storage; reuse `AlarmConfig.nextTrigger` and existing lifecycle-aware Home state producer.
- No GitHub authentication, push, hooks or public release artifact claim. Work on `codex/portal-hi-widget-fixes-20261102`.
- Treat zero jitter/lag and 100% coverage as acceptance ambitions requiring evidence. Report measured scope, missed frames and uncovered code honestly; sample success does not prove a universal guarantee.

## Review Focus

1. Slideshow disabled: Photo Memory editing still opens source selection.
2. Config unchanged while time advances: alarm countdown and next occurrence must update.
3. Less than a minute remaining: show one minute instead of premature zero.
4. Background/other tab: clock work must stop and refresh immediately on Home return.
5. Daylight-saving changes: preserve the scheduler's local-time calculation rather than invent a second algorithm.

### Unit 1: Photo Memory source ownership

**Files:** Modify `app/src/main/java/com/immortal/launcher/HomeActivity.kt` in `ApprovedWidgetCell` only; evidence under `docs/` or a private device-capture directory.

**Interfaces:** Existing edit action calls `openApprovedHomeSettings`; change only `KIND_PHOTO_MEMORY` destination from `screensaver` to `sources`. Primary action remains photo preview; registry and provider/timer handling remain intact.

- [x] Reproduce by source trace and device edit action; record current destination.
- [x] Change the existing destination to `sources`.
- [x] Verify actual edit action opens Photo sources, Back returns correctly, and source controls are available with idle slideshow off. Restore any temporary test configuration. No source credentials or selection were changed.
- [x] Commit the bounded fix (`d9fce1fb`); build/test, route evidence and limitations are recorded in `docs/portal-widget-fixes-release-2026-11-02.md`.

### Unit 2: Current alarm timing

**Files:** Create `HomeAlarmWidgetTiming.kt` and `HomeAlarmWidgetTimingTest.kt`; modify only `ImmortalAlarmWidget` in `HomeActivity.kt` during main integration.

**Interfaces:** `homeAlarmRemainingMinutes(config: AlarmConfig.Config, nowMs: Long, timeZone: TimeZone = TimeZone.getDefault()): Long?`; `homeAlarmWidgetRefreshDelayMs(nowMs: Long): Long`. Reuse the existing schedule. Remaining minutes round up; disabled/no valid occurrence returns null; refresh delay is 1..60000 ms at the next wall-clock minute.

- [x] Write failing deterministic tests for elapsed time, sub-minute rounding, disabled/invalid days, passed occurrence, selected weekday, DST and minute-boundary delay.
- [x] Implement pure helpers and run focused tests.
- [x] Integrate a minute-boundary producer using `produceHomeState`; no minute loop when disabled; refresh immediately after Home becomes active.
- [x] Verify live saved enable changes and add a preference observer through the existing snapshot helper, scoped to active Home. Existing 12-hour rendering observed; separate native 24-hour/time-edit cases remain unverified and are outside this minute-caption acceptance.
- [x] Verify native armed/off/countdown behavior and repeated Home/Apps/return; keep the actual alarm disabled except during a controlled observation and restore original config.
- [x] Commit (`d9fce1fb`); build, required tests, independent review and evidence are recorded.

### QA and release gate

- [x] Read Family Area CPU/GPU/RAM/display capability; record hardware-limited acceptance methodology. No thermal-stress claim.
- [x] Run existing relevant tests and required `:app:testDebugUnitTest --no-daemon`; assemble debug for the repository gate, optimized performance variant for device acceptance.
- [x] Verify APK identity/signature and preserve the installed rollback APK before replacing it.
- [ ] Equivalent-scenario before/after performance comparison remains unverified. Existing samples and memory snapshots differ in scenario/process age and are explicitly not improvement claims. Do not extend this unit into parked Home/Apps or screensaver investigations.
- [x] Bump the accepted local iteration's version, produce release notes, install/verify Family Area, then deploy final accepted artifact to Living Area if reachable. Both exact APK identities verified.
- [x] Record what was built/tested/released and any failed/unverified gate. Post-release user feedback remains future evidence. No fabricated adoption, escape-rate, velocity, jitter or code-coverage percentage.
