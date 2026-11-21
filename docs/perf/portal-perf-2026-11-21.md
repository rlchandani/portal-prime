# Portal performance survey — 2026-11-21 (perf-9b, Phase A, measure only)

No code changes this round. Shipped 1.303 (`com.immortal.launcher.debug`, versionCode 297), as installed on the device.

**Device:** Family Portal+ (`<family-serial>`, Android 10, 2160×1440).
- Living (`<living-serial>`) was preferred, but it wasn't on adb. fleetctl has no shell, gfxinfo or input, so Living couldn't be driven.
- The device lock was held for 00:19–00:29 PST and then released.
- Quiet hours: STREAM_MUSIC was set to 0 before the session and restored to 3 after it.
- No screenshots. Tap targets came from `uiautomator dump` (a text hierarchy).
- Home was on top at the end of the session.

**Method:**
- Each repeat ran `dumpsys gfxinfo <pkg> reset`, then the action, waited 2–3 s, and read the dump.
- Janky %, p90 and p99 are the HWUI process summary (all launcher windows).
- "> 32" and "max" come from framestats (IntendedVsync → FrameCompleted, flags 0).
- Memory is `dumpsys meminfo` TOTAL PSS / Graphics in MB. "Before" is on Home, idle. "After" is 5 s after returning to Home.
- Warm repeats, 3 per screen unless noted. Values are listed per repeat (r1 / r2 / r3).
- The session started from a real dream (PhotoDreamService), so Settings r1 is the first open after the screensaver.

**Targets:** p90 < 16 ms, p99 < 32 ms, ≤ 1 janky frame on open, no memory growth.

## Results

| Screen | Janky % | p90 ms | p99 ms | frames > 32 ms (max ms) | PSS / gfx before → after leaving |
|---|---|---|---|---|---|
| **Settings open** | **100 / 83 / 89** | **97 / 61 / 61** | **117 / 81 / 65** | **14 / 10 / 7 of 15** (117 / 84 / 43) | 294 / 86 → 250 / 77 (the "before" value was taken just after the dream) |
| Settings scroll (3 flings + 1 back) | 15 / 2 / 2 | 18 / 13 / 13 | 25 / 18 / 16 | 0 (21 / 22 / 18) | (same visit) |
| Music open | 22 / 22 / 14 | 26 / 23 / 28 | 133 / 53 / 44 | 2 / 2 / 1 (135 / 56 / 46) | 253 / 89 → 266 / 94 |
| **Control Center open** | **67 / 77 / 50** | **101 / 93 / 101** | **113 / 105 / 113** | **4 / 4 / 4 of 9–10** (74 / 84 / 79) | 271 / 88 → CC open 284 / 99 → **after 293 / 101** |
| **CC sheet** (door-reminder sheet from the attention row) | 67 / 50 / 45 | 65 / 97 / 61 | 93 / 97 / 77 | 4 / 2 / 2 (95 / 100 / 77) | (same visit) |
| CC Back to Home | 2 of 2 frames janky | 77 / 40 / 61 | — | — | (same visit) |
| Tab → Apps | 15 / 14 / 21 | 46 / 38 / 36 | 77 / 53 / 48 | 2 / 2 / 2 (80 / 56 / 52) | 278 / 101 → 283 / 101 |
| Tab → Tools | 15 / 15 / 15 | 16 / 38 / 38 | 44 / 46 / 48 | 1 / 2 / 2 (46 / 49 / 53) | (same) |
| Tab → Home | 15 / 15 / 15 | 48 / 38 / 48 | 61 / 44 / 57 | 2 / 2 / 2 (63 / 46 / 57) | (same) |
| Screensaver start, first 15 s (no photo change yet), 2 runs | 31 / 29 | 53 / 44 | 73 / 89 | 2 / 3 of 22–25 (55 / 47) | 273 / 101 → 265 / 90; 259 / 90 → 243 / 77 |
| **Screensaver to first photo change (~31 s), 40 s**, 2 runs | 57 / **88** | 32 / 22 | 93 / 73 | run B: 37 frames over 32 ms, 5 of them 97–108 | **run A: 255 / 89 → 386 / 179 running → 342 / 144 after → 308 / 144 at +70 s**; run B: 320 / 155 → 357 / 191 → 249 / 84 |

**Notes on the screensaver runs:**
- The screensaver mode is `shuffle`, so the two 40 s runs drew different faces.
  - Run A showed one SurfaceFlinger present per second for most of the run, which is a clock-like face.
  - Run B ran photo motion at about 30 fps: steady ~32 ms frames after the change, which matches round 6's "~29 ms sustained".
- On start, SurfaceFlinger showed present gaps of 67, 83, 183, 267 and 300 ms in the first second.
- At the photo change (t ≈ 30.8–32.6 s), HWUI showed frames of 83, 75, 64, 106, 101 and 77 ms, then another burst of 108, 99 and 97 ms.
- The screensaver's first frame on screen is therefore the worst single spike: about 300 ms of no new frame.

## Against the targets

- **p90 < 16 ms:** only Settings scroll (13 ms when warm) and one Tools switch meet it.
- **p99 < 32 ms:** only Settings scroll meets it.
- **≤ 1 janky frame on open:** no open meets it.
  - Tabs: 2 janky frames per switch.
  - Music: 5–8.
  - CC: 6–10.
  - Settings: 15–18, i.e. every frame of the open.
- **No memory growth:** two failures.
  1. **Control Center:** +22 MB PSS and +13 MB graphics are still held after leaving. This repeats round 3's "CC gfx memory not released".
  2. **Screensaver, after a photo change:** +53 MB PSS and +55 MB graphics were still held 70 s after returning Home.
     - This doesn't accumulate. The next screensaver run released it (after leaving that run, PSS was 249 / 84).

## Two worst offenders

### 1. Settings open: 83–100 % janky, p90 61–97 ms, p99 65–117 ms, 7–14 of 15 frames > 32 ms

- **Hypothesis:** Settings is a separate activity (`ImmortalSettingsActivity`) in the same process.
  - Its first composition and first traversals run on the main thread and RenderThread that Home also uses. Round 7 traced 27–38 ms traversals, each with a relayout and new-surface binder calls.
  - Home's press-feedback frames are queued in between. Round 7 recorded these as 16–86 ms.
  - The result is that every frame of the ~0.5 s open misses vsync.
  - The first open after the dream is worst (117 ms), which points to a cold Settings tree.
  - The scroll itself is fine. The cost is only in the open.
- **Ruled out:** the diagnostics search index (`PORTAL_SETTINGS_UI_DIAGNOSTICS`) is false in the shipped build.
- **Likely code:**
  - `app/src/main/java/com/immortal/launcher/ImmortalSettingsActivity.kt`: `onCreate` → `setContent { … SettingsHostScreen(...) }` (lines ~174–209), and `SettingsHostScreen` with its `LaunchedEffect(stack.current, stack.control)` (line ~263 on).
  - Also `SettingsLastPage.write(this, stack.current)`, a synchronous write in `onCreate`.
  - `HearthpaneScreens.track` / `SettingsJarvisContext.build` in `trackForJarvis()`.
- **Next steps:**
  - Trace one open with perfetto to split time between the Settings first frame, Home's frames, and binder.
  - Try composing the Settings list pane hidden ahead of time, as round 4 did for Tools, or present Settings as an in-activity route.

### 2. Control Center open + sheet: open 50–77 % janky, p90 93–101 ms, p99 105–113 ms; sheet p99 77–97 ms; graphics memory retained

- **Hypothesis for the open:**
  - On a live Home Assistant home, the MAP screen's first composition holds the main thread together with `onStart` work: `ControlCenter.reloadSettings()` (engine reload plus `RoomSignals.refresh()`) and `setVisible(true)` (live subscription).
  - That gives 4 frames of 50–84 ms on every open.
- **Hypothesis for the sheet:** its first frames compose the modal's content. They are 70–100 ms, not a steady animation cost: the other sheet frames are 11–25 ms.
- **Hypothesis for the retained memory:** after leaving CC (activity finished), graphics stays about 13 MB higher. Likely causes are camera snapshot thumbnails or blurred plan layers still referenced from process-wide state, or HWUI caches that are never trimmed.
- **Likely code:**
  - `app/src/main/java/com/immortal/launcher/ControlCenterActivity.kt`: `onStart` (`ControlCenter.reloadSettings`, `ControlCenter.setVisible`), and `CcApp(initialScreen = MAP)` in `setContent`.
  - `ControlCenter.kt:47` (`reloadSettings`).
  - `ControlCenterModal.kt` (scrim and card, lines ~72–90).
  - `ControlCenterCameraFeed.kt` (snapshot bitmaps).
  - `ImageCacheTrim.kt` doesn't trim any CC image cache.

### Runner-up: screensaver photo change

- The photo change shows 3–6 frames of 64–108 ms and ~300 ms of no frame at start.
- Graphics memory up to +55 MB stays held after exit.
- **Likely code:** `SlideshowLoader.kt`. Its `Warm` and `Ready` bitmaps call `prepareToDraw()` (lines ~153 and ~194), which uploads them as textures. They aren't in `ImageCacheTrim`, and they may outlive the screensaver activity until the next load or close.

## Next (Phase B candidates)

1. Settings open: a perfetto trace of one warm and one post-dream open, then try a pre-composed list pane.
2. CC: move `reloadSettings` / `RoomSignals.refresh` off the open path, and find what holds the extra ~13 MB of graphics after `onStop`.
3. Screensaver: release `SlideshowLoader` bitmaps on activity or dream stop, and check that PSS returns to the Home baseline.
4. Tabs: still 2 frames of 40–80 ms per switch (round 3's activation recomposition plus the `queueBuffer` stall).

## Control Center (Phase B, branch `cc-open`)

**Device:** Family Portal+, the same session method as above (gfxinfo reset → tap Home Control → 3 s → framestats; then the read-only "door is open" attention row → 3 s; Back twice). No screenshots. STREAM_MUSIC was set to 0 and restored to 3.
- A/B builds: a `main` performance build and the branch performance build, each installed with its own dex metadata (`install-multiple` apk + dm, so both were `speed-profile`, as an OTA is), then 25 s idle and one unmeasured warm-up open. 3 repeats each.
- The HWUI cache is the "Total GPU memory usage" line of `dumpsys gfxinfo`.

### What the trace showed (official 1.303)

- Open: the tap reaches `activityPause` about 75 ms later (system_server). CC's first frame is one 61 ms traversal: ~16 ms attach + composition, 15 ms measure, 12 ms draw.
  - The frame after it is janky only because its vsync passed during the first one.
  - Home's window draws a layout-changed frame at the same vsync, queued behind CC's. It counts as a second janky frame of about 90 ms.
  - About 230 ms in, the foreground subscription's snapshot arrives: 6.5 ms of recomposition and 17 ms of draw recording, plus the frame queued behind it.
- `onStart` work is not on the main thread. `reloadSettings`/`setVisible` post to the engine thread, and `RoomSignals.refresh` is a few preference reads. It wasn't the cause.
- Sheet: the tap frame costs ~23 ms of recomposition and ~26–56 ms of draw recording. A branch experiment composed the sheet page off screen over several frames (a paused precomposition plus premeasure) before the slide started. The long tap frame stayed. That frame is the plan's room highlight (`openRoom` sets `highlightRoom`, so the plan canvas recomposes and re-records its layers), not the sheet. The experiment didn't improve the sheet numbers (sheet frames over 32 ms: base 3 / 6 / 5, experiment 4 / 4 / 4), so it was dropped.

### Changes

1. `ControlCenterGpuTrim.kt` (new): `CcGpuTrim`. Once no Control Center window has been on screen for 4 s, it runs the renderer's UI_HIDDEN trim (`WindowManagerGlobal.trimMemory(TRIM_MEMORY_UI_HIDDEN)`, a hidden API that Android 10 allowed). An open within 4 s cancels the trim, so a quick re-open keeps its cache.
   - Wired in `ControlCenterActivity.onStart` / `onStop`.
   - 5 JVM tests in `CcGpuTrimTest`.
2. `ControlCenterActivity.onStart`: `setVisible(true)` now runs before `reloadSettings()`. The reload's publish is then a visible one, so the plan models are built on the engine thread. Before, a hidden engine published empty models and the map built them on the main thread.
   - This had no measurable effect on the open (see below). It is kept because it removes a main-thread build and doesn't change the result.

### Before / after (performance builds; frames over 32 ms from framestats)

The branch columns are build `5daab363`: the final changes plus the sheet experiment, which was dropped afterwards. An earlier run of the final code alone (`ce514f53`) gave the same picture:
- Open: 3 / 3 / 3 frames over 32 ms (66 / 72 / 52).
- Sheet: 4 / 4 / 4 (102 / 102 / 74).
- The GPU-cache rows are from the final code.


| | main r1 / r2 / r3 | branch r1 / r2 / r3 |
|---|---|---|
| CC open: janky %, p90 / p99 ms | 67 / 78 / 58; 57–89 / 65–101 | 62 / 46 / 67; 65–101 / 69–101 |
| CC open: frames over 32 ms (max) | 2 / 3 / 3 (40 / 54 / 59) | 3 / 2 / 3 (50 / 69 / 63) |
| Sheet: frames over 32 ms (max) | 3 / 6 / 5 (91 / 101 / 98) | 4 / 4 / 4 (95 / 82 / 95) |
| HWUI GPU cache, Home before → CC +10 s → Home +2 s → **Home +6 s** → +30 s | 15.5 → 15.5 → 16.4 → **16.4** → 16.4; then 16.4 → 17.3 → 17.3 → **17.3** → 17.3 MB | 10.1 → 15.5 → 15.5 → **10.1** → 10.1; then 10.1 → 17.3 → 17.3 → **10.1** → 10.1 MB |
| Re-open 30 s after leaving (after the trim): frames over 32 ms (max) | 4 (60); 3 (62) | 3 (73); 3 (54) — the same as an untrimmed open |

- **Memory:**
  - With the trim, the renderer's cache returns to Home's own 10.1 MB (1.9 MB purgeable) about 4 s after leaving.
  - Without it, it stays at 16.4–17.3 MB (8–9 MB purgeable) and grows with each visit.
  - meminfo "Graphics" moves in steps of about 12 MB (46 ↔ 58 MB) on both builds whether or not CC was opened, which is one 2160×1440 buffer of Home's window. TOTAL PSS / Graphics alone can't show the CC difference in a fresh process. The HWUI cache line can.
- **Open / sheet: not fixed.**
  - The open still has 2–3 frames over 32 ms: CC's first composition, Home's layout-changed frame queued behind it, and the snapshot redraw about 230 ms in.
  - The sheet still has the plan-highlight frame.
  - The targets (≤ 1 janky frame, p90 < 16, p99 < 32, no sheet frame over 32 ms) are not met.

### Next

1. Sheet: make the plan's room highlight cheap. Draw it as its own layer, so the plan's other layers keep their recordings when `highlightRoom` changes. To attribute first, trace the `cc:*` sections with a profileable (benchmark) build. They don't appear in performance builds, because Android 10 enables app tracing only for profileable apps.
2. Open: the first frame is one composition of the whole map (~45 ms of main-thread work). Making it smaller needs either a lighter first composition or keeping CC alive behind Home, which trades against the memory result above.
3. The +230 ms redraw happens because the background subscription drops the plan's non-alert entities. Keeping their last states would avoid the redraw, but would show stale states for ~200 ms. That is an owner decision.

### Follow-up: the room highlight as its own layers (branch `cc-highlight`)

**Attribution (main 1.304, profileable benchmark build, 3 atrace captures of the read-only "door is open" row, after one warm-up open).**
- No `cc:record:*` or `cc:drawCtx` section appears on a repeat open: the plan re-records nothing. The draw object and
  the recorded passes for that focus are still in their LRUs from the warm-up, so the earlier "re-records its layers"
  holds only for the first open of a room.
- The tap frame is main-thread work: `Recomposer:recompose` 9–28 ms (`applyChanges` 2–7, `onForgotten` 1–2.4),
  then a traversal of 12–19 ms (measure/layout 4–7 ms, the rest draw recording). The UI frames were 21–44 ms.
- RenderThread: 16–28 ms for the same frame, of which `flush layers` 4.8–8.4 ms (the plan's offscreen textures and the
  sheet's texture being rastered).
- So a repeat open pays for the highlight in recomposition plus re-rastering, not in recording.

**Change (commit on `cc-highlight`).** The highlight moved out of `CcPlanDraw` into `CcPlanFocus`
(`ControlCenterPlanFocus.kt`): tint, veil, edge, which labels fade. `CcPlanDraw` and `CcDrawKey` no longer depend on it.
- The floors, washes, glows, walls, openings and markers keep one draw object and one recording across every highlight.
- Only these change: the tint and veil layers (a few rects), the labels (`CcPassKey` keyed by the highlighted room),
  and the per-frame animation layer.
- The draw order is unchanged: tint between floors and washes, veil between openings and markers, edge after the pulses.
  `OVER` is split into `OPENINGS` + `MARKS` to make room for the veil. `MAX_LAYERS` went from 16 to 20.
- JVM tests: `ControlCenterPlanFocusTest` (6) and `ControlCenterPlanLayerCacheTest.onlyTheLabelsAreRecordedPerHighlightedRoom`.

**Highlight on / off (main, profileable build; atrace of the sheet's tap frame, repeat opens).** A temporary
`debug.cc.nohl` switch opened the same sheet without setting `highlightRoom`.

| Build | UI frame ms | recompose ms | traversal ms | RenderThread ms (flush layers) |
|---|---|---|---|---|
| main, highlight on (n = 2) | 48.6 / 46.8 | 30.5 / 23.7 | 17.9 / 22.9 | 17.1 / 17.2 (4.8) |
| main, highlight off (n = 3) | 35.2 / 29.8 / 32.8 | 18.4 / 13.0 / 15.8 | 16.4 / 16.6 / 16.8 | 24.1 / 18.9 / 25.6 (5.6–7.7) |
| **branch**, highlight on (n = 3) | 29.8 / 35.4 / 39.2 | 10.9 / 19.7 / 24.0 | 18.7 / 15.5 / 14.9 | 18.2 / 19.1 / 18.9 (4.7–5.6) |

- The highlight cost about 13 ms of the tap frame's main thread, almost all of it recomposition: a new draw object
  meant every plan layer's lambda changed. On the branch the tap frame costs about what it costs with no highlight.
- With or without the highlight, the tap frame still does 30–35 ms of main-thread work. That is the sheet itself:
  composing the room sheet (13–18 ms) and measuring and recording it (15–17 ms).
- The app's own `cc:*` / temporary `cc:x:*` sections never appeared, although the dex calls `Trace.beginSection` and
  Compose's sections from the same thread do. That is unexplained; the attribution above uses Compose's and HWUI's sections.

**Before / after (performance builds, apk + dm; framestats of the Control Center window, 3 s from the tap).** Two
rounds per build, 3 opens per round, after 25 s idle and one warm-up open.

| | main r1 / r2 | branch r1 / r2 |
|---|---|---|
| Sheet: frames over 32 ms per open | 2, 2, 2 / 2, 3, 2 | 2, 2, 2 / 2, 2, 1 |
| Sheet: worst frame ms | 64, 53, 53 / 56, 59, 43 | 54, 61, 53 / 52, 64, 43 |
| Sheet: frames over 16 ms per open | 7, 6, 6 / 2, 8, 6 | 6, 6, 4 / 6, 9, 8 |

- **Not fixed on the user-visible measure.** The worst sheet frame stays at 43–64 ms on both builds. The tap frame and
  the frame queued behind it are still both over 32 ms. framestats measures from the intended vsync, so 13 ms less
  main-thread work inside a ~50 ms frame doesn't move it below the 32 ms line.
- The change is kept. It removes the highlight's recomposition (and, on a first open of a room, the re-record of
  every plan pass), it is pixel-for-pixel the same draw order, and it costs nothing.
- Targets (no sheet frame over 32 ms, p90 under 16 ms) **not met**.

**Next.** The remaining tap frame is the room sheet's own first composition. Two options:
- compose its page ahead, off screen, the moment the tap lands, and slide it in one frame later;
- or keep the last room's sheet composed (hidden) so a reopen is a re-place.

The `cc-open` experiment precomposed the sheet while the highlight was still in the tap frame, so it measured both at
once. Rerun it on this branch.

---

# Phase B: Settings open (settings-open, 2026-11-21)

No code change kept. Two candidates were measured on the device and neither met the bar; the attribution below
replaces the Phase A hypothesis.

**Device and builds:**
- Family Portal+ (`<family-serial>`), last Settings page Display & Brightness. Lock held 00:33–00:46, 00:50–00:56 and
  01:10–01:21 PST. STREAM_MUSIC 0 during each stint, restored to 3. No screenshots; the tap target came from
  `uiautomator dump`.
- A/B builds are `assemblePerformance` from main 1.303 plus its `.dm` (`reason=install-dm` checked every install).
  Each install waits 25 s and discards one warm-up open.
- At the end the official 1.303 APK (sha256 `2e058554…a365`, matching `hearthpane-latest.json`) was reinstalled.
  Home was on top and dev mode was off.

**Method:** the survey's: `gfxinfo reset`, tap Settings in Home's header, 2.5 s, `gfxinfo framestats`, Back, 4 s.
Medians per build. "Settings frames" counts framestats rows of the Settings window (flags 0 and 1).

| Build | n | Janky (of total) | Settings frames, janky | p90 ms | p99 ms | frames > 32 ms | max ms |
|---|---|---|---|---|---|---|---|
| Official 1.303, installed by OTA (long-running process) | 5 | 15 of 18 (83 %) | 13 of 16 | 48 | 53 | 5 | 54 |
| **Before:** performance build of main | 6 | 11.5 of 18 (64 %) | 9.5 of 16 | 52.5 | 61 | 4 | 63 |
| Candidate 1: the page's deferred entries shown as one wave | 8 | 11.5 of 18 (64 %) | 9.5 of 16 | 53 | 61 | 3.5 | 63 |
| Control: unobfuscated, profileable (for the next row) | 8 | 11.5 of 18 (65 %) | 9.5 of 16 | 45 | 55 | 3.5 | 56 |
| Candidate 2: Compose accessibility diffing off (reflection, not shippable) | 8 | 9 of 17 (56 %) | 7.5 of 15 | 53 | 59 | 3 | 60 |

- The official build runs about 2 janky frames worse than a fresh performance install of the same code. That is
  a property of the long-running process, not of the build: the A/B compares fresh installs only.

## Where an open goes

**Frame structure.** One open draws 15–16 Settings frames over about 350 ms. Every vsync gets a frame:
1. The first frame (window layout, flags 1): 26–57 ms.
2. About 6 frames build the hidden master column (`SettingsMasterReveal`, 3 entries per frame): 7–31 ms each.
3. The page frame: doFrame about 25 ms, 15 ms of it in measure.
4. About 7 tail frames: the deferred page entries, the Display snapshot, then a run of RenderThread-only redraws
   (record 0.1 ms) as the page's controls settle. 9–36 ms each.

**Why p90 and p99 don't move.** They are set by 3 frames that are not Settings content:
- Home's press-feedback frame: 26–44 ms.
- Home's hide frame (flags 1, a relayout as Settings covers it): 63–68 ms.
- Settings' first frame: about 57 ms.

The last two share one vsync. The launch transaction delays it by 31–33 ms. This matches round 7's Home-under-Settings
finding.

**Why a cheap frame is still janky.** The pipeline is main thread, then RenderThread, then GPU. In atrace the
RenderThread spends about 5 ms per frame in `waiting for GPU completion`, and 7 ms in each of 8 `waiting for HWC release`
(dequeueBuffer). So a frame with 8 ms of main-thread work still lands at 17–25 ms of latency.

**Main-thread CPU per open** (simpleperf, 4 kHz, 5 opens on the profileable control):

| Work | ms per open |
|---|---|
| Activity launch (`handleLaunchActivity` and resume, including `installDecor` 8, `setTheme` 3, binder `getActivityDisplayId`) | 33–40 |
| **Compose accessibility diff** (`checkForSemanticsChanges` → `sendAccessibilitySemanticsStructureChangeEvents` → `getAllUncoveredSemanticsNodes` / `subtreeSortedByGeometryGrouping`) | **33–50** |
| Text layout (`TextStringSimpleNode.measure`) | 18–28 |
| Vector icons (`VectorPainter.onDraw`, plus `painterResource`) | 6–14 |
| The search field (`BasicTextField` / `CoreTextField`) | 3–8 |
| Code running interpreted (`art_quick_to_interpreter_bridge`, callers in Compose runtime and lifecycle) | about 10 |

**The accessibility diff is the largest Settings-side cost, and it is new.**
- Compose diffs the whole semantics tree after every frame whose semantics change. The check is posted between frames,
  so it delays the next frame's start.
- Compose turns this on whenever any accessibility service is enabled. On the Portal four are always on: Meta's
  KeyEvent and Presence services, plus Hearthpane's BarWatchService and InstallConfirmService. None of them is a
  screen reader.
- Staged opening multiplies the cost. The largest checks come once the page is composed (17 ms in one run).
- Turning the diff off saved about 2 janky frames per open (candidate 2). Compose has no supported switch for this.
  The reflection used for the measurement depends on a private field (`_enabledServices` in Compose UI 1.10.4) and
  stops Compose's semantics events for every consumer, so it is not shippable.

**Ruled out:**
- `SettingsLastPage.write` in `onCreate`: a no-op for an unchanged id, and the preferences are already in memory.
- `trackForJarvis()`: a lifecycle observer. `SettingsJarvisContext.build` runs only when Jarvis asks.
- Baseline profile: the `.dm` holds 5,229 classes, 537 of them Settings, and the status is `speed-profile`. The
  interpreted residue (about 10 ms) sits in callees that simpleperf can't name on Android 10.

## Tried, not kept

1. **The page's deferred entries shown as one wave** (`SettingsCompositionScheduler` holds measured slots until the
   queue drains, at most 5 frames).
   - Hypothesis: frames that only precompose would draw nothing, and one accessibility pass would replace several.
   - Result: no change. Frames stayed 16, janky 64 % → 64 %.
   - The tail frames are driven by the page's own settling redraws, not by the reveals. The change also altered
     transitional visuals, so it was reverted.
2. **Compose accessibility diffing off** (scratch reflection): 65 % → 56 % janky, Settings janky 9.5 → 7.5. Not
   shippable (see above).
3. **Sharing VectorPainters across openings:** not tried.
   - It would save about 6–14 ms of main-thread time per open, but it contradicts the documented `SettingsIconCache`
     contract ("values are immutable icon data, never UI owners"; "each composition owns its painter").
   - It needs an owner decision first.

## Against the targets

The targets (≤ 1 janky frame, p90 < 16 ms, p99 < 32 ms) cannot be reached without an architectural change or an
owner trade-off:
- p90 and p99 are set by Home's own frames and by the launch frame.
- With every vsync producing a frame, each one is at least 17 ms of pipeline latency on this device.
- A one-frame open would be a single 150 ms frame. That is the stall the staged opening (`af1fb81c`) replaced.

## Next (needs owner decisions)

1. **Keep Settings composed between opens:** retain the activity and its window rather than finishing it.
   - This is the only route to about 1–2 frames per open.
   - Costs: it retains the Settings tree in memory, a reopen needs an explicit reset hook (as the calendar panel
     has), and task/Back semantics change.
2. **Accessibility diff:** decide whether a shippable way to scope Compose's semantics diffing is acceptable, given
   that the Portal's always-on services include no screen reader. Or watch for a Compose release that throttles
   `checkForSemanticsChanges`.
3. **Shared Settings tile painters:** a contract change to `SettingsIconCache`.
4. **Interpreted residue:** name the roughly 10 ms of interpreted callees with a debuggable build and extend
   `baseline-prof.txt`.

## Screensaver (branch `ss-photo-2`)

**Device:** Family Portal+. The operator test source, pinned to `smartFrame` at a 30 s beat, so only generated scenes were on screen. It opens the photo frame activity, not the dream. Each run: on Home, gfxinfo reset → enable → 44 s of polled framestats → disable (the frame goes back to Home) → meminfo at +5 s and +70 s. No screenshots. STREAM_MUSIC was set to 0 and restored to 3.
- Before: official 1.305. After: the branch performance build, installed with its dex metadata (`speed-profile`, `install-dm`). 2 runs each.
- Memory is TOTAL PSS / Graphics in MB.

### Changes

1. `SlideshowLookahead.kt` (new), used in `SlideshowLoader.load`: the next photo's decode and `prepareToDraw()` upload now wait 2 s (`SETTLE_MS`, longer than every day dissolve). Before, they ran while the published scene was dissolving in. A newer request cancels the wait at once. The lookahead is only a cache, so nothing visible changes. 6 JVM tests in `SlideshowLookaheadTest`.
2. `CcGpuTrim` is now shared by the Control Center, the dream (`PhotoDreamService` attach / detach) and the photo frame activity (`onStart` / `onStop`). Once none of them has been on screen for 4 s, it runs one collection off the main thread and then the renderer's UI_HIDDEN trim. The collection is needed because the departed frame's photos are unreachable but nothing collects them. 1 new JVM test (one shared count).

### Before / after

| Run | Start, 0–25 s: frames > 32 ms (max) | Photo change, 25–38 s: frames > 32 ms of 14 (max) | Still photo, 38–44 s | Home → running → +5 s → **+70 s after leaving** |
|---|---|---|---|---|
| 1.305 r1 | 10 (47) | 2 (35) | 1 frame/s | 211/70 → 306/115 → 285/114 → **271/126** |
| 1.305 r2 | 11 (51) | 7 (46) | 1 frame/s | 269/126 → 318/127 → 288/114 → **273/126** |
| branch r1 | 11 (69) | 5 (35) | 1 frame/s | 208/49 → 289/85 → 239/84 → **240/82** |
| branch r2 | 10 (49) | 4 (45) | 1 frame/s | 237/82 → 302/97 → 240/84 → **225/67** |

- **Memory after leaving is fixed.** On 1.305, graphics stayed at 126 MB 70 s after leaving (+56 over the first Home reading). On the branch it was 82 / 67 MB at +70 s, back to the Home level. The log showed `surface closed: renderer caches trimmed` 4 s after the frame left.
- **The photo change itself didn't reproduce here.** With generated scenes, the change had no frames over 46 ms on either build. The 64–108 ms frames in the round above came from household photos in `shuffle` mode with photo motion, which this test can't show without real photos on screen. The lookahead deferral is kept because it moves the next photo's texture upload out of the dissolve. Its effect on real photos is not measured yet.
- **No redraws during a still photo.** With the test scenes the frame drew about once a second (the clock) between changes. The steady ~32 ms cadence in the round above is the photo motion (Ken Burns property animators run for the whole dwell when motion is on). That is by design, so it was left unchanged.
