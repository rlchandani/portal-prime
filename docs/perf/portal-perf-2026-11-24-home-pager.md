# Home widget pager — slow swipe between the two page sections (2026-11-24)

Owner report: swiping/scrolling between the TWO page sections of the widget Home
screen "feels so slow". Top premium-latency complaint. This doc follows the
Measure → Attribute → Fix → Verify discipline and is explicit about what is
**trace-confirmed** vs **inferred from source** vs **unmeasured**.

Device serials/paths are masked. Family device = `…01C0` (the only adb-reachable
Portal; holds the owner wake build — no installs performed, nothing touched).

---

## 1. MEASURE — what was and was NOT possible here

### Prior triage (coordinator, synthetic)
`dumpsys gfxinfo` after driven horizontal swipes: 114 frames, 16 janky (14%),
P50 9ms / P90 19ms / P95 20ms / P99 27ms — **render throughput is fine**. The
standout was **"High input latency: 99/114"**, plus "Slow UI thread: 6". Caveat
(acknowledged): this used synthetic `adb shell input swipe`, which the perf
playbook flags as distorting the input-latency metric. Treated as a lead only.

### Why a valid Perfetto trace could NOT be captured in this environment
The playbook's primary attribution tool is a zero-install Perfetto system trace of
a **real** swipe. Three hard blockers made a *valid* trace impossible here:

1. **Only a debug build is installed on Family.** `pm list packages` shows
   `com.immortal.launcher.debug` (and `.debug.test`) — no `performance`/`release`
   package. The playbook is categorical: **"Never measure performance on a debug
   build"** (ART disables optimizations; Compose debug is misleadingly slow; R8 /
   baseline profiles not applied). A trace of the debug build is not a valid perf
   signal.
2. **No install window.** Family holds the owner wake build; installing the
   `performance` variant is explicitly off-limits. So the valid (minified) build
   can't be put on this device.
3. **No real finger swipe is possible remotely.** Only synthetic `input swipe` is
   available over adb, and that distorts the exact "input latency" metric in
   question (same caveat as the prior triage). The device was also sitting on
   `PhotoFramePreviewActivity` (photo-frame), not the Home launcher.

Perfetto binary IS present (`/system/bin/perfetto`), API 29 confirmed, so the
tooling exists — only a valid, installable, finger-driven target does not.

**Conclusion:** root-cause attribution here is **inferred from source, not
trace-confirmed.** A device install window + real swipe (or the Macrobenchmark in
§5) is required to confirm on-device.

---

## 2. ATTRIBUTE — source analysis of the active code path

### Active branch
The Portals report `mCurUiMode=0x12` → `UI_MODE_TYPE_DESK` (not `TELEVISION`), so
`ApprovedWidgetArea` runs its **non-TV** branch (custom gesture + offset), NOT the
`HorizontalPager` TV branch. The fix targets the non-TV branch; the TV branch is
untouched.

### Root cause (inferred from source; file: `app/.../HomeWidgetHost.kt`)
In the pre-fix non-TV branch, the swipe gesture did **not track the finger at
all**:

```
detectHorizontalDragGestures(
    onDragStart = { distance = 0f },
    onDragEnd   = { if (abs(distance) >= swipeThreshold) pageChanged(pageAfterSwipe(...)) },
) { change, delta -> change.consume(); distance += delta }   // only accumulates
```

The page position was a separate `Animatable slide`, advanced **only** inside
`LaunchedEffect(page.index)` *after* `onDragEnd` committed a page, via a fixed
`tween(180, EaseOutCubic)`. So during the entire drag the content was **frozen**;
nothing moved under the finger. On release, if the drag passed a 64dp threshold,
the page animated over a canned 180ms; otherwise nothing happened (no velocity /
fling path either).

This matches the symptom precisely: **render times fine, input latency high.**
The input (the whole drag) produces **no visual frame until release** — there is
no direct manipulation, so the swipe "feels slow/sluggish". It is a gesture /
responsiveness bug, **not** a draw-throughput or recomposition-throughput bug.

### Explicitly ruling out the recomposition hypothesis for THIS code
The coordinator's prior hypothesis was "page content recomposes on every offset
change (scroll fraction read too high)". Source review shows that is **not** the
bug here: the page offset is already read inside `Modifier.absoluteOffset { … }`
(the lambda/placement overload, `HomeWidgetHost.kt:192`), i.e. a **layout-phase
deferred read** — changing it re-places, it does not recompose page content. And
in the pre-fix code the position state didn't even change during the drag, so no
per-frame recomposition *or* movement occurred. The playbook's recomposition
signature therefore does not apply. (This remains source-inferred, not
trace-confirmed, per §1.)

---

## 3. FIX — direct finger tracking via the layout phase

Files: `app/src/main/java/com/immortal/launcher/HomePagerGesture.kt` (new, pure),
`HomeWidgetHost.kt` (non-TV branch).

- Replaced the `Animatable slide` with a plain `scroll: Float` state that the drag
  writes **synchronously each frame**, so the pages now move 1:1 with the finger.
  `scroll` is read only inside the existing `absoluteOffset { }` placement lambda,
  so finger tracking runs in the **layout phase — no per-frame recomposition**
  (exactly the playbook's recommended fix: "push frequently-changing values into
  layout/draw phase with lambda modifiers: `Modifier.offset { }`").
- On release, `HomePagerGesture.targetPage(...)` decides the page from **drag
  position + fling velocity** (via `VelocityTracker`): a fast flick commits one
  page in its direction; otherwise it snaps to the nearest page. Replaces the old
  "must cross 64dp or nothing happens" rule, so quick flicks now register.
- The settle animation now starts from wherever the finger left `scroll` and its
  duration scales with the remaining distance
  (`HomePagerGesture.settleDurationMillis`, 90–220ms): drag 90% and release → a
  near-instant settle instead of a fixed 180ms replay.
- A `LaunchedEffect(…, dragging)` suspends the settle while a drag owns `scroll`
  and animates to the target (or snaps back) when the finger lifts — no position
  jump at hand-off. The deferred neighbor-composition / page-work window
  (`HomePagerWindow`, `settled`) is preserved unchanged.

Pure, side-effect-free geometry lives in `HomePagerGesture` so it is unit-testable
without Compose.

### Mini (fleet-only, not run here)
The change is purely gesture→offset math in the shared non-TV branch and uses the
measured viewport width (`viewportWidthPx`), so it is orientation- and
size-agnostic: it should help the Mini in both portrait and landscape identically
(more responsive tracking, cheaper settle) and cannot regress it — there is no new
per-frame composition and no fixed pixel assumption. **Unmeasured on Mini
(fleet-only).**

---

## 4. Gate

- `./gradlew :app:testDebugUnitTest --tests HomePagerGestureTest --tests
  ApprovedHomePresentationPagingTest` → **BUILD SUCCESSFUL** (new gesture tests +
  existing paging tests pass).
- `./gradlew :app:assemblePerformance` → **BUILD SUCCESSFUL** (R8-minified release-like variant compiles with the fix).
- Builds run serialized, `--no-daemon --max-workers=1`, one variant, after
  confirming no other Gradle processes.

New tests: `app/src/test/java/com/immortal/launcher/HomePagerGestureTest.kt`
(8 tests: finger-tracking proportionality, clamp/no-overscroll, missing-viewport
guard, nearest-page snap, fast-flick-before-halfway, fling range clamp, empty
pager, settle-duration scaling).

---

## 5. VERIFY — needs a device install window (not done here)

A trace-confirmed before/after is impossible without (a) a valid installed build
and (b) a real swipe. Recommended, in priority order:

1. **Macrobenchmark `swipePager`** on a **spare Portal** (not Family). There is no
   `:macrobenchmark` `com.android.test` module today (only `:app`, with
   `performance`/`benchmark` buildTypes). Adding a module touches shared
   `settings.gradle`; it was **intentionally not added here** to avoid a risky
   structural change while other build agents are active. Recommended test shape
   (to add in a `:macrobenchmark` module and run on a spare device):

   ```kotlin
   @Test fun swipePager() = benchmarkRule.measureRepeated(
       packageName = TARGET_PACKAGE,
       metrics = listOf(FrameTimingMetric()),
       iterations = 10,
       startupMode = StartupMode.WARM,
       compilationMode = CompilationMode.Partial(),
       setupBlock = { startActivityAndWait() },
   ) {
       val pager = device.findObject(By.desc("Home widgets")) // existing semantics label
       pager.setGestureMargin(device.displayWidth / 5)
       pager.fling(Direction.RIGHT)
       pager.fling(Direction.LEFT)
   }
   ```
   Target the **`performance`/`benchmark`** (R8-minified, profileable,
   non-debuggable) build, compare **P95/P99 `frameDurationCpuMs`** before vs after.
   Note: `frameOverrunMs` and Perfetto FrameTimeline are API 31+ → unavailable on
   API 29; read raw `doFrame`/input slices instead.

2. **Zero-install confirmation** once a `performance` build is installable on a
   spare device: `record_android_trace … sched gfx view input am wm binder_driver`
   during a **real** swipe; confirm each `Choreographer#doFrame` now moves the page
   (input→frame latency collapses) and is not recomposition-dominated.

3. If first-swipe JIT jank remains after the gesture fix, evaluate a Baseline
   Profile covering startup + the pager swipe (API 29 = Partial AOT via
   profileinstaller; non-Play caveat applies).

---

## Claim ledger (no overclaim)

- **Trace-confirmed:** none (no valid build installed + no real swipe possible).
- **Source-confirmed (code cited):** active branch is non-TV (`UI_MODE_TYPE_DESK`);
  pre-fix gesture never moved content during the drag; offset already read in the
  layout phase (`absoluteOffset {}`), so the recomposition hypothesis does not fit.
- **Expected, unmeasured (needs device install window):** the responsiveness
  improvement (1:1 finger tracking + velocity fling + scaled settle); Mini
  portrait/landscape behaviour.
- **Gate-verified:** unit tests + Performance assemble (see §4).
