# Wi-Fi Performance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Measure physical Portal frame performance over Fleet and stop obsolete legacy analysis work.

**Architecture:** A dormant Window observer and bounded histogram collector back an explicit timed Fleet capture. Legacy analysis remains a separate, cancellable source owner. Main implements diagnostics; the authorized parallel worker changes only legacy face code/tests.

**Tech Stack:** Kotlin, Android API 24+ FrameMetrics, existing Fleet HTTP API, org.json, JUnit.

**Spec:** `docs/superpowers/specs/2026-10-09-wifi-performance.md`

## Global Constraints

- Android minSdk 24, Portal API 28/29, no Google services requirement.
- Capture duration 10–900 seconds; default 60; no persistence.
- Existing Fleet bearer authentication and developer-mode start gate.
- Maximum 128 hosts, 24 weakly tracked live windows; 0.25 ms bins through 500 ms.
- No per-frame I/O/JSON/array allocation and no UI-thread joins.
- Keep existing photo cache format and native slideshow analysis.

## Review Focus

- An API start with wrong numeric types must reject rather than silently coerce.
- An expired/replaced capture must reject queued old callbacks.
- A Dream attached before capture begins must still be measurable.
- A 600 ms frame must not be reported as an exact 500.25 ms percentile.
- Cancelling inference must not recycle pixels still owned by ML Kit.

### Task 1: Pure bounded metrics and capture request contract (main)

**Files:** `FramePerformanceHistogram.kt`, `FramePerformanceRequest.kt`, matching JVM tests.
**Interfaces:** `FramePerformanceHistogram(budgetNs).record(totalNs, dropped, firstDraw): Boolean`; `component(index, nanos)`; `snapshot(): JSONObject`. `FramePerformanceRequest.parse(JSONObject): FramePerformanceRequest?`.

- [x] Write tests for valid/invalid totals, percentile overflow, component sums, callback drops and integer/action/phase validation.
- [x] Run `:app:testDebugUnitTest --tests '*FramePerformance*Test'`; verify red for missing contracts.
- [x] Implement the bounded contracts, using LongArray(2001) and explicit type/range checks.
- [x] Run the same test filter and verify green.

```kotlin
val h = FramePerformanceHistogram(16_666_667L)
h.record(600_000_000L, 0, false)
check(h.snapshot().isNull("p95Ms"))
check(h.snapshot().getDouble("p95AtLeastMs") == 500.0)
```

### Task 2: Window capture and Fleet lifecycle (main)

**Files:** `FramePerformanceCapture.kt`, `ImmortalApp.kt`, `PhotoDreamService.kt`, `FleetRoutes.kt`, dedicated Android regression helper.
**Interfaces:** `init(Application)`, `track(Window, host)`, `untrack(Window)`, `start(request): Boolean`, `stop(): Boolean`, `snapshot(): JSONObject`.

- [x] Register lifecycle tracking with weak Window references; keep it dormant when stopped.
- [x] Attach listeners only for an active immutable session and detach on stop/destroy.
- [x] Guard expiry/queued callbacks using elapsedRealtime and closed session ownership.
- [x] Expose GET /perf and validated POST start/stop behind existing auth; dev gate on start.
- [x] Compile APKs and test actual emulator Window frames, replacement, explicit stop and expiry.

```json
{"action":"start","durationSec":10,"phase":"home"}
```

### Task 3: Cancellable legacy face analysis (parallel worker)

**Files:** `FaceFocusStore.kt`, `PhotoFrameController.kt`, focused legacy face JVM tests.
**Interfaces:** Existing face bridge and cache values stay compatible; owned job has nonblocking cancellation.

- [x] Demonstrate stale cache writes and stopped-worker ownership with failing focused tests.
- [x] Implement cancellation/checkpoints and latest cache merge/ownership guards.
- [x] Run focused tests; main runs the integrated full gate after review.

### Task 4: Physical Portal validation and local commit (main)

**Files:** Fleet docs and a new validation record; private diagnostics under ignored build/.

- [x] Run `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`.
- [x] Review both slices, fix concrete findings and rerun affected checks.
- [x] Deploy with existing room-fleet.py to Family Area, verifying installed APK SHA-256.
- [x] Capture actual slideshow/Home and remote-key AppSwitcher frames through /perf; verify timed expiry and stop.
- [ ] Measure physical Settings, retained Home tabs, normal OS Dream initiation, and external browser/website performance in the continuing quality effort.
- [x] Record measurements, exclusions and remaining broad-goal gaps; commit focused changes.
