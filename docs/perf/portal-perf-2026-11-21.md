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

## Screensaver on household photos, 1.306

**Device:** Family Portal+, official 1.306 (versionCode 300) as installed. Household screensaver settings unchanged (`shuffle`, 9 photos); no test source. Same method as Phase A: on Home, meminfo → gfxinfo reset → real dream via SystemUI `Somnambulator` → framestats polled every 2.5 s for 40 s (through the first photo change at ~31 s) → HWUI summary and meminfo while running → tap to leave → meminfo at +5 s and +70 s. 3 runs, back to back. No screenshots. STREAM_MUSIC was set to 0 and restored to 3. Device lock held 04:27–04:34 PST; Home on top at the end.

| Run | Janky % | p90 / p99 ms | Start spikes > 60 ms | Photo change: frames > 60 ms (ms) | Home → running → +5 s → **+70 s after leaving** |
|---|---|---|---|---|---|
| Phase A (1.303) run A | 57 | 32 / 93 | SF gaps up to 300 | (clock-like face) | 255/89 → 386/179 → 342/144 → **308/144** |
| Phase A (1.303) run B | 88 | 22 / 73 | — | 6 + 3 (64–108) | 320/155 → 357/191 → 249/84 |
| 1.306 r1 | 68 | 48 / 250 | 5 (69–272) | 6 + 2 (150, 168, 161, 270, 290, 283; then 104, 61) | 180/48 → 276/137 → 183/48 → **197/61** |
| 1.306 r2 | 22 | 21 / 133 | 1 (135) | 5 (124, 125, 243, 134, 129) | 194/61 → 283/145 → 183/49 → **197/61** |
| 1.306 r3 | 31 | 29 / 200 | 1 (147) | 4 (166, 349, 355, 216) | 195/61 → 283/142 → 187/51 → **201/63** |

- **Memory after leaving is fixed on real photos too.** All 3 runs were back at the Home level 5 s after leaving and stayed there at +70 s (graphics 61–63 MB vs 144 MB in Phase A run A). The log showed `surface closed: renderer caches trimmed` about 4.3 s after each exit.
- **The photo change is not better; it is worse.** Each run had 4–6 frames of 124–355 ms in a ~0.4 s burst at t ≈ 31 s, against Phase A's 64–108 ms. The 2 s lookahead deferral did not remove the change-time cost on household photos. The work that remains at the change (decode, upload or the dissolve's first composite) still needs a trace.
- Between changes all 3 faces drew about 1 frame/s, so none of these shuffle draws had the ~30 fps photo motion seen in Phase A run B.

## Screensaver photo change, 1.306 vs reverts (branch `ss-bisect`)

**Question:** is the slower first photo change on household photos (1.306: 124–355 ms frames) a regression from the 1.306 lookahead deferral or GPU trim?

**Method:** the same as the section above (same script). Real dream through `Somnambulator`, household settings unchanged, no test source, framestats polled for 40 s, meminfo at +5 s and +70 s. No screenshots. STREAM_MUSIC was set to 0 and restored to 3. Every build: install, Home, 60 s idle, one unmeasured warm-up dream, then 3 runs back to back.
- **A**: `main` 1.306 performance build plus `.dm` (`install-dm`).
- **B**: A with only the `SlideshowLookahead.awaitSettle` call removed (plus `.dm`).
- **C**: B with the 1.306 GPU-trim changes reverted too, so it runs the 1.305 screensaver code. Built at versionCode 300 so it installs in place (plus `.dm`).
- **D**: official 1.306 `hearthpane.apk`, plain `adb install -r` (`speed-profile`, `reason=install`, the same state as the OTA install).
- "Change" counts the frames over 60 ms at 29–35 s from the first frame.

| Run | Janky % | p90 / p99 ms | Start: frames > 60 ms (max) | Change: frames > 60 ms (ms) | Change max | Home → running → +5 s → +70 s |
|---|---|---|---|---|---|---|
| A r1 | 68 | 29 / 133 | 2 (142) | 6 (95, 70, 66, 139, 154, 148) | 154 | 200/62 → 273/130 → 190/48 → 203/60 |
| A r2 | 55 | 27 / 121 | 1 (124) | 6 (69, 69, 63, 233, 110, 68) | 233 | 201/60 → 413/213 → 223/71 → 233/80 |
| A r3 | 70 | 29 / 93 | 1 (122) | 5 (95, 90, 96, 62, 64) | 96 | 231/80 → 304/148 → 206/55 → 221/67 |
| B r1 | 68 | 30 / 81 | 2 (117) | 7 (84, 83, 79, 77, 69, 64, 61) | 84 | 197/60 → 277/133 → 189/49 → 202/61 |
| B r2 | 64 | 29 / 150 | 3 (192) | 8 (93, 72, 96, 103, 95, 141, 116, 131) | 141 | 200/61 → 279/133 → 190/50 → 206/62 |
| B r3 | 66 | 28 / 150 | 1 (120) | 7 (105, 104, 101, 174, 216, 124, 95) | 216 | 203/62 → 413/214 → 243/86 → 254/95 |
| C r1 | 69 | 31 / 113 | 2 (150) | 7 (116, 149, 152, 74, 79, 73, 97) | 152 | 200/63 → 282/140 → 190/51 → 204/63 |
| C r2 | 44 | 27 / 97 | 2 (169) | 9 (82, 64, 84, 73, 163, 182, 175, 84, 65) | 182 | 202/63 → 312/169 → 189/48 → 203/60 |
| C r3 | 32 | 26 / 150 | 3 (160) | 7 (160, 200, 191, 64, 160, 150, 181) | 200 | 200/60 → 293/148 → 193/51 → 207/63 |
| D r1 | 67 | 28 / 150 | 2 (213) | 4 (154, 157, 148, 101) | 157 | 200/63 → 291/145 → 190/50 → 204/63 |
| D r2 | 64 | 30 / 109 | 2 (127) | 3 (109, 106, 107) | 109 | 202/63 → 288/143 → 193/52 → 207/64 |
| D r3 | 24 | 69 / 200 | 4 (209) | 1 (232) | 232 | 205/64 → 407/223 → 227/82 → 238/91 |

- **Not a regression.** The change maximum was 96–233 ms on 1.306 (A, D), 84–216 ms with the deferral reverted (B) and 152–200 ms on the 1.305 code (C). The ranges overlap, and C is no better than A. The 124–355 ms in the section above is the same behaviour on different photos and faces. Most runs have a second burst about 1.5–2.5 s after the change on every build, including C.
- **The deferral cannot reach these frames.** The household mode is `shuffle`, and `ScreensaverConfig.usesEngine("shuffle")` is true, so the screensaver is the WebView engine (`assets/screensaver/*.js`). `SlideshowLoader` and `SlideshowLookahead` are only used by Classic and the native stages. The trace has no `slideshow-images` thread.
- **What the trace shows** (one atrace, 26–36 s, gfx/view/dalvik/sched, on B): the change's long frames are `DrawFrame` → `WebViewFunctor::drawGl` on the RenderThread (100, 334 and 107 ms). The main thread's `doFrame` → `draw` waits on it (100 / 322 / 81 ms). During the 334 ms frame, `Chrome_InProcGpuThread` ran 294 ms, which is WebView's own raster and texture upload of the incoming photo. In the 1 s around the change, the renderer's image threads (`CrRendererMain` 221 ms, `ThreadPoolForeground` 199 ms) and `kswapd0` (205 ms, memory reclaim) were busy too. There was no launcher GC or bitmap work in these frames.
- **Memory after leaving** is at the Home level on every build, C included, because the engine's WebView is destroyed when the dream ends. Two runs (A r2, B r3) showed a 413/214 MB running peak with a slower return; D r3 showed the same.
- **Recommendation: keep 1.306.** The deferral is harmless and still applies to the native stages. The GPU trim made no measurable difference to the change. The change cost belongs to the WebView engine: Chromium uploads the next photo at its first draw, even though `preloadDecoded` decodes it beforehand. A fix there (draw the next photo once, off the transition, or smaller derivatives than the 2400 px edge for this panel) needs its own trace and A/B. No code change was kept on this branch.

---

## Control Center Alerts screen cost, 1.308 vs `cc-alerts`

**Device:** Family Portal+. Plain `adb install -r -d` of each build (no `.dm`), no force-stop. Lock held 10:45–10:56,
11:19–11:27 and 11:28–11:40 PST. STREAM_MUSIC 0 during each stint, restored to 3. No screenshots.

**Builds:** official 1.308 (sha256 `bd7f9faa…4588`, matching `hearthpane-latest.json`); branch `cc-alerts`
`assemblePerformance` v1 (Alerts stays recorded + fewer clip layers, `199186ca…`) and v2 (v1 + reused date
formatters, `0e999a37…`). `assembleBenchmark` builds (profileable, R8 mapping applied offline) for simpleperf.

**Method:** as the `cc-switch` A/B: open Control Center (fleet `open_control_center`, Map), one warm-up tap of the
read-only "door is open" row (Map → Alerts) + Back, then 4 measured taps (`gfxinfo reset` → tap → 3 s →
`framestats` of the Control Center window → Back). Official: 4 rounds (one at the start, two mid, one at the end,
interleaved with the branch); v1: 2 rounds; v2: 3 rounds. simpleperf 4 kHz call graphs, 1.4 s around 3 taps per build.

**Where the switch frame went on 1.308 (simpleperf, main thread, worst frame of 3 taps, ~60 ms main):**
- **Draw recording, 18–26 ms** — almost all of it the Alerts slot re-recording every card (text paint, icons,
  backgrounds, the LazyColumn items). Cause: a settled parked screen is `DETACHED` (not drawn), and Android 10's
  renderer deletes the display lists of a render node that leaves the tree (`RenderNode::destroyHardwareResources`;
  Compose then sees `hasDisplayList() == false`). So "their draw lists stay recorded" was not true for Alerts.
- Composition 18 ms: mostly the chrome (rail items, header pills, subtitle) and the map's camera tile reacting to
  `LocalCcScreenActive`; Alerts itself ~7 ms, of which `CcAlertRules.item` ~5 ms — two new `SimpleDateFormat`s per
  card ("Sat 7 Nov" + "2:33 pm"), plus two more in the header subtitle.
- Apply 10–13 ms (modifier-chain structural updates in the rail/slots, effect cancellation), measure 5–8 ms.
- The second long frame is mostly inherited latency plus RenderThread (offscreen rail/header region layers
  re-rendered 4–10 ms) and the semantics diff (5–10 ms, accessibility services on).
- Not significant: vector icon inflation (cached), shadows (dark theme), rounded-clip masks (analytic).

**Changes (`cc-alerts`):**
1. `ControlCenterScreenSlots.kt` `ccSlotDraw` / `CC_KEEP_RECORDED`: Alerts stays `RECORDING` (transparent, in the
   render tree) while parked, like the map, so the switch re-uses its display lists.
2. Alert icon tile, `CcTag` and the toggle thumb draw `background(color, shape)` instead of `clip(shape).background()`
   (no child render node; same pixels — nothing inside overflows, none is clickable).
3. `ControlCenterDateFormats.kt` `CcDateFormats`: one `SimpleDateFormat` per thread / pattern / locale, zone set per
   call; used by `ccTime`, `ccSubtitle` and the alert day label.

| | official 1.308 (16 taps) | v1 (8 taps) | v2 (12 taps) |
|---|---|---|---|
| Frames over 32 ms per tap | 2 ×15, 3 ×1 (33 total) | 2 ×6, 0 ×2 (12) | 2 ×11, 1 ×1 (23) |
| Worst frame, median ms | 64 | 55 | 56 |
| Worst frame range ms | 56–83 | 31–69 | 33–73 |
| Sum of frames over 32 ms, median ms | 127 | 103 | 104 |
| Frames over 16 ms per tap (mean) | 5.8 | 5.6 | 5.8 |
| After leaving: PSS / Graphics MB | 178–183 / 47 (r1 after a dream: 240 / 82) | 180–183 / 46–48 | 180–191 / 47–60 |

- Main-thread draw in the switch frame fell from 18–26 ms to 5–11 ms (simpleperf), and `CcAlertRules.item` from
  ~5 to ~2.5 ms. The worst switch frame drops ~8 ms at the median (64 → 55/56).
- **Target (no switch frame over 32 ms) not met**: 2 of 20 branch taps had none over 32 ms; the rest still show the
  two-frame pattern (one long frame, the next inheriting its latency). What remains in the switch frame is mostly
  outside Alerts: chrome recomposition (rail items' conditional `ccCard`, header), composition-local updates for both
  slots, the map's camera tile stopping, the parked/shown semantics swap and the rail/header offscreen layers on the
  RenderThread. Alerts' own composition is now ~6 ms of diffuse work (LazyColumn setup, item derivation).
- No memory retained: GPU/PSS after leaving match 1.308 (one v2 round read 60 MB graphics, within the official spread
  seen after a dream).

## Control Center screen switch: rail, header, slots, 1.309 vs `cc-rail`

**Device:** Family Portal+. Plain `adb install -r -d` of each build, no force-stop. STREAM_MUSIC 0 during each stint,
restored to 3. No screenshots. Ended on official 1.309, Home on top and default, dev mode off.

**Builds:** official 1.309 (sha256 `4e1fe789…617d`, matching `hearthpane-latest.json`); branch `cc-rail`
`assemblePerformance` (`d5d84fce…`). `assembleBenchmark` of unchanged 1.309 and of `cc-rail` for simpleperf.

**Method:** same as the Alerts A/B (Map → Alerts via the read-only "door is open" row, warm-up + 4 measured taps per
round, `framestats` of the Control Center window). 4 rounds each, interleaved (off, v1, v1, off, v1, off, v1, off).
simpleperf 4 kHz, 3 taps per build.

**Where the switch frame went on 1.309 (simpleperf, worst frame of 3 taps, main thread):** composition 29–39 ms, of
which the rail 3–9.5 ms (every `RailItem` re-run; the selected one's chain gained/lost the `ccCard` nodes), the
header 2.75–6 ms (title/subtitle were in `CcHeaderSlice`, so all the pills re-ran), the map's camera tile stopping
2.5–6.5 ms, Alerts 3–6 ms; Apply 4.75–9 ms (incl. cancelled camera effects); measure 5–9 ms.

**Changes (`cc-rail`):**
1. `ControlCenterShell.kt` `CcApp`: the shell no longer reads the screen or floor; rail/header slices exclude them.
2. `ControlCenterSelection.kt` + `ControlCenterChrome.kt` `RailItem` / `ControlCenterCompactChrome.kt` `NavItem`: each
   tab is a fixed tree. The selected card is always composed in its own layer (only its alpha follows the
   selection), icon tint (`CcIcon(ColorProducer)`, Fit/centre like `Icon`) and label colour (`BasicText` colour
   producer) are read while drawing, and `ccSpeakTab` observes a per-tab `derivedStateOf` to update "selected".
3. `ControlCenterHeading.kt`: `CcHeadingInputs` (minute resolution) + `rememberCcHeading`; only the title/subtitle
   composable reads the screen.
4. `ControlCenterScreenSlots.kt`: each slot derives its own `active` (only the two changing slots recompose);
   `ccSlotLive` keeps a just-parked screen live (cameras, motion) for 2 + 2 frames, moving the camera stop off the
   switch frame. Parked screens still leave the semantics tree immediately.

| | official 1.309 (16 taps) | `cc-rail` (16 taps) |
|---|---|---|
| Frames over 32 ms per tap | 2 ×12, 3 ×3, 0 ×1 (33) | 2 ×13, 3 ×2, 1 ×1, 0 ×1 (31) |
| Worst frame, median ms | 57 | 48 |
| Sum of frames over 32 ms, median ms | 103 | 90 |
| Frames over 16 ms, total | 99 | 84 |
| After leaving: PSS MB / Graphics MB | 185 / 47 (r1 after a dream: 222 / 74) | 185–186 / 46–47 |

- simpleperf, worst frames: `CcRail`, `CcHeader` and `CcCameraPicture` no longer appear; `CcHeaderTitle` is 1.5–2 ms;
  Apply fell to 2.5–2.75 ms. Composition is now mostly Alerts itself (re-composed with fresh state, up to ~10 ms)
  plus the LazyColumn items.
- Accessibility (uiautomator dump): same nodes, order and bounds as 1.309; "selected" follows the screen
  (Home → Alerts), header says "Alerts. Saturday · …".
- **Still two frames over 32 ms per tap.** What remains: the semantics swap (`checkForSemanticsChanges` →
  `subtreeSortedByGeometryGrouping`, up to 12.5 ms, inherent to exposing the new screen's tree), Alerts'
  recomposition (`items` keyed on `entitiesVersion`, so any HA update since parking rebuilds every card),
  measure 7–8 ms and RenderThread. Next candidates: give Alerts an equality-skipping slice of what its cards
  show; consider a cheaper semantics shape for the parked/shown swap.

## Control Center screen switch: Alerts slice and deferred semantics, 1.310 vs `cc-alerts-2`

**Device:** Family Portal+ (this unit has no floor plan, so Map is the "Build your floor plan" card plus the side
column). Plain `adb install -r -d`, no force-stop. STREAM_MUSIC 0 during the stint, restored to 3. No screenshots.
Ended on official 1.310, Home on top and default, dev mode off.

**Builds:** official 1.310 (sha256 `819e02ce…f7e21e`, matching `hearthpane-latest.json`); branch `cc-alerts-2`
`assemblePerformance` (`31be9339…`) and `assembleBenchmark` (`caab7747…`) for simpleperf.

**Method:** as before (Map → Alerts via the read-only "door is open" row, warm-up + 4 measured taps per round,
`framestats` of the Control Center window). 4 rounds each, interleaved (off, off, v1, v1, off, v1, off, v1).
simpleperf 4 kHz, 3 taps; the 1.310 attribution is the `cc-rail` benchmark profile (same code).

**Where the switch frame went on 1.310 (main thread, worst frames):** composition up to 27 ms, `CcAlertsScreen` up
to 10 ms of it (every card re-derived: `CcAlertRules.item` scans all entities for garage covers and valves, ~3 ms,
plus the LazyColumn), semantics 10–12.5 ms (`checkForSemanticsChanges` → `getCurrentSemanticsNodes` →
`subtreeSortedByGeometryGrouping`, posted right after the switch frame so it delays the next one), measure 7–8 ms.

**Changes (`cc-alerts-2`):**
1. `ControlCenterState.kt:100` `CcUiState.alertCards` (+ `warm()`): the active alerts' cards are derived once per
   snapshot on the engine thread into `CcAlertCards` (`ControlCenterAlertsScreenLogic.kt:28`, immutable,
   equality-comparable: cards + start of today). `ControlCenterAlertsScreen.kt:54` passes only that slice and three
   banner settings to a skippable `CcAlertsBody`; an HA update or a newer snapshot on switch that changes no card
   compares equal and the body and its keyed cards skip. Resolved cards (`resolvedItem`/`shown`) keep the icon the
   card had while active (`CcResolvedAlert.icon`), so they no longer need the entity map.
2. `ControlCenterScreenSlots.kt:69,178` `ccSlotSemantics`: a parked screen still leaves the semantics tree at once;
   the shown screen joins it 2 frames (~35 ms) after the switch, inside Compose's 100 ms accessibility batching, so
   the tree rebuild + sort runs on a quiet frame instead of stacking on the switch.
- Not done: `mergeDescendants` on cards. Compose sorts the *unmerged* tree, so it would not shrink the sort, and it
  changes the nodes accessibility services see. Traversal groups would split the sort but change TalkBack order.

| | official 1.310 (16 taps) | `cc-alerts-2` (16 taps) |
|---|---|---|
| Frames over 32 ms per tap | 2 ×14, 3 ×1, 0 ×1 (30) | 2 ×11, 1 ×4, 0 ×1 (26) |
| Worst frame, median ms | 48 | 44 |
| Worst frame range ms | 27–64 | 22–57 |
| Sum of frames over 32 ms, median ms | 87 | 81 |
| Frames over 16 ms, total | 89 | 85 |
| After leaving: PSS MB / Graphics MB | 178–205 / 45–64 | 174–181 / 45–47 |

- simpleperf (benchmark build, worst two frames of 3 taps): `CcAlertsScreen` / `CcAlertRules.item` no longer
  appear; switch-frame composition 6 ms (mostly `CcHeaderTitle` 2 ms); semantics in the switch frame ≤1.25 ms
  and 4.5–5.5 ms on the deferred frame. Main thread of the worst frame 13–38 ms (was up to 56).
- Accessibility (full uiautomator dump, Map and Alerts): same nodes, order, bounds, labels and selected/checked
  state as 1.310; only the clock and "Open N min" texts differ (dumps 9 min apart).
- **Target (no switch frame over 32 ms) still not met.** The switch is now RenderThread-bound: 24–47 ms per long
  frame, `renderLayersImpl` ~9 ms (rail/header region layers re-rendered for the selection and title change) plus
  GPU command submission for the whole window (`GrOp::execute` / Adreno draw calls, 8–12 ms) and the first opaque
  draw of the newly shown screen; the second long frame waits on the first's RenderThread. Next: avoid
  re-rendering the region layers on a switch (selection highlight outside the rail layer), and measure what part of
  the window flush is the shown screen versus the whole-window replay.

## Screensaver photo change: decode at the drawn size (branch `engine-upload-2`)

**Question:** the change frame is Chromium rasterising and uploading the incoming photo on its first draw (`Chrome_InProcGpuThread`, 100–300 ms, blocking `WebViewFunctor::drawGl`). Pre-uploading it 4 s early (`engine-upload`, not merged) only moved a 65–147 ms frame onto the still photo. Can each upload be made cheap enough instead, without softening anything?

**Changes** (Portal+ = 2160 × 1440 device px at 180 dpi, so 1920 × 1280 CSS px at dpr 1.125; WebView is Chromium 138):
1. `modes.js:311–368` (`SS.largeEdge` `:321`): a mode that knows how large it draws a photo passes that size (CSS px of the long edge at the top of its motion). The engine asks for `ceil(drawn × dpr)` device px, capped at the copy the request delivered before (the host's former 600 / 1200 / 1800 / 2400 step). So no photo gets fewer photo px per device px than before, and every photo gets at least 1.0 where it had that before. A request without a drawn size (unknown shape, Mosaic, Hero + Sidebar, Cinema, Filmstrip) still gets exactly its former copy. `SS.compactEdge` (the Mini) uses the same rule. Geometry helpers: `containEdge`, `coverEdge`, `largeDrawnEdge`, `polaroidDrawnEdge`, `diptychDrawnEdges`.
2. `mode-impls.js` passes drawn sizes on the Portal+. Ken Burns, Push and Memories singles and pairs (`:567–584`): Ken Burns zoom is 1.15, stopping where a whole photo or a pair touches the screen; the drift is 1.04. Also Diptych cells (`:696–699`), the Framed print (`:734–772`), Gallery Wall tiles and swaps (`:878`, `:899`), Memories collage cards (`:1012–1047`), the Panorama strip (`:1104–1151`) and the Polaroid print box (`:1274`). `scheduler.js:248` routes the Portal+ through `SS.largeEdge`.
3. `EngineScaledCache.kt:64–95, 280–296`: finer copy steps (600, 700 … 1200, 1350, 1500, 1650, 1800, 2000, 2200, 2400; each step ≤ 17 % above the last). The former four steps remain, so existing copies stay valid. A bounded engine load at a finer step never falls back to a ready copy larger than the former step it replaces (`largestFallback`); it prepares the drawn size instead. Before this cap, the first use of a new step could be answered with the 2400 copy. Bridge URL validation and resolution samples follow the same steps (`EngineLocalPhotoRequests.kt:30`, `EngineRemotePhotoRequests.kt:36`, `EngineImageResolution.kt`).
- **Not changed:** Ken Burns, Push and Memories singles that fill the screen. They are drawn at 2160–2484 device px, more than the 2400 copy, so they keep it; a smaller copy would soften them. Transitions, durations, cadence and pre-decode timing are unchanged.

**Texture per change** (decoded copy, RGBA; Portal+ geometry, phone-shaped 4:3 / 3:4 photos; computed with the shipped layout code):

| Mode | Photos | Edge before | Edge after | MB before | MB after | Saved |
|---|---|---|---|---|---|---|
| Ken Burns | 4:3 landscape, fill | 2400 | 2400 | 16.5 | 16.5 | 0 % |
| Ken Burns | 3:4 portrait, whole | 2400 | 1500 | 16.5 | 6.4 | 61 % |
| Ken Burns | 3:4 portrait pair | 1800 | 1500 | 18.5 | 12.9 | 31 % |
| Push / Memories single | 4:3 landscape, fill | 2400 | 2400 | 16.5 | 16.5 | 0 % |
| Push / Memories single | 3:4 portrait, whole | 2400 | 1500 | 16.5 | 6.4 | 61 % |
| Push / Memories single | 3:4 portrait pair | 1800 | 1500 | 18.5 | 12.9 | 31 % |
| Diptych | 3:4 portrait pair | 1800 | 1500 | 18.5 | 12.9 | 31 % |
| Diptych | 4:3 landscape | 1800 | 1800 | 9.3 | 9.3 | 0 % |
| Framed | 3:4 portrait | 1800 | 1200 | 9.3 | 4.1 | 56 % |
| Framed | 4:3 landscape | 1800 | 1650 | 9.3 | 7.8 | 16 % |
| Gallery Wall | 10 tiles (5 + 5) | 1200 | 600 | 41.2 | 10.3 | 75 % |
| Memories collage | 4:3 + 2 × 3:4 | 1200 | 1000 / 800 | 12.4 | 6.5 | 47 % |
| Polaroid | 6 prints (3 + 3) | 1800 | 700 / 800 | 55.6 | 9.7 | 83 % |
| Panorama | 3:2 landscape strip | 2400 | 2200 | 14.6 | 12.3 | 16 % |
| Panorama | 4:3 landscape strip | 2400 | 2000 | 16.5 | 11.4 | 31 % |

Mosaic, Hero + Sidebar, Cinema and Filmstrip are unchanged.

**Method:** the same as the sections above. The household's own `shuffle` screensaver, no setting changes, no test source, real dream through `Somnambulator`, framestats polled for 40 s, meminfo at +5 s and +70 s. Every build was installed with plain `adb install -r` (`speed-profile`, `reason=install`). Each build got Home, 60 s idle, one unmeasured warm-up dream, then 3 runs. STREAM_MUSIC was set to 0 and restored to 3. No screenshots of household photos. O = official 1.311 `hearthpane.apk` (sha256 verified). B = the branch before the fallback cap, the first time the finer steps were used. C = the final branch; it ran after B, so the copies B made were already on disk. "Change" counts the frames over 60 ms at 29–35 s from the first frame. Memory is launcher PSS in MB; this round's meminfo gave only the per-process total, so there is no Graphics column.

| Run | Janky % | p90 / p99 ms | Start: frames > 60 ms (max) | Change: frames > 60 ms (ms) | Change max | Home → running → +5 s → +70 s |
|---|---|---|---|---|---|---|
| O r1 | 33 | 25 / 150 | 2 (136) | 10 (105, 101, 96, 121, 266, 258, 173, 111, 122, 128) | 266 | 203 → 299 → 187 → 203 |
| O r2 | 89 | 25 / 150 | 2 (141) | 6 (173, 307, 299, 369, 228, 228) | 369 | 203 → 306 → 185 → 200 |
| O r3 | 38 | 25 / 81 | 4 (129) | 2 (72, 74) | 74 | 200 → 292 → 191 → 206 |
| B r1 | 71 | 27 / 150 | 3 (248) | 3 (90, 87, 80) | 90 | 218 → 281 → 192 → 207 |
| B r2 | 64 | 29 / 105 | 2 (138) | 4 (61, 72, 84, 76) | 84 | 221 → 296 → 192 → 220 |
| B r3 | 36 | 24 / 81 | 1 (133) | 3 (68, 64, 64) | 68 | 221 → 301 → 205 → 220 |
| C r1 | 72 | 26 / 121 | 1 (137) | 4 (136, 130, 130, 69) | 136 | 198 → 287 → 187 → 202 |
| C r2 | 32 | 32 / 121 | 1 (145) | 6 (75, 80, 74, 103, 122, 118) | 122 | 216 → 288 → 184 → 212 |
| C r3 | 23 | 57 / 150 | 4 (148) | 2 (86, 82) | 86 | 212 → 409 → 226 → 237 |

- **The worst change frames are gone.** On 1.311, 2 of 3 changes had frames of 228–369 ms. None of the 6 branch runs had a change frame over 136 ms (B 68–90, C 86–136). The frames over 60 ms at the change went from 10 / 6 / 2 to 3 / 4 / 3 and 4 / 6 / 2.
- **It is not gone.** Each branch change still has 2–6 frames of 60–136 ms. The photo on screen differs from run to run (shuffle and deck order are random), so the 3-run ranges overlap: O r3 (74) beat C r1 (136). The remaining cost belongs to fill singles, which keep their 2400 copy, and to the modes without a drawn size.
- **Trace** (one atrace per branch build, 26–36 s, gfx/view/dalvik/sched, no framestats polling). B: one 410 ms `DrawFrame` (`drawGl` 403 ms), with `Chrome_InProcGpuThread` busy 366 ms and `kswapd0` 110 ms. 0.3 s before it, the launcher's `ss-scaled-cache` thread decoded an original for a new step (`decodeBitmap` 171 ms). In that first-use state a finer step could still be answered with a larger ready copy, which the fallback cap now prevents. C (final): the change's worst frame was 83 ms (`drawGl` 78 ms, GPU thread 75 ms), the next worst 31 ms, with no derivative decode in the window. One trace per build is not enough to separate the cap from the warmed cache.
- **Memory** is unchanged: running 281–301 MB vs 292–306 MB on 1.311, and back to the Home level at +70 s. The outlier is C r3: 409 MB running and 237 MB at +70 s, the same kind of peak seen on 1.306 (A r2, B r3, D r3 above).
- **Sharpness** (test source only, generated scenes, `performance` builds of `main` and the branch; scratch screenshots compared at 100 % crop and then deleted). Polaroid prints (1600 px scene before, 700 / 800 px copy now) and the Framed portrait print (1600 px before, 1200 px now) show the same edge and caption-text sharpness. Gallery Wall was captured but not compared at 100 %. The shuffled scene order differs between builds, so the same scene was compared where both builds showed it (Polaroid "TEST SCENE 2").
- **Device** at the end: official 1.311 (sha256 verified), Home on top, test source off, dev mode off, STREAM_MUSIC 3.
- **`createImageBitmap`** (Chromium 138 supports resize options and `bitmaprenderer`) was not adopted. An ImageBitmap shown through a canvas is still one full upload at its first composite. Splitting that upload means drawing strips into one accelerated canvas over several frames before the change, which replaces the CSS-background hero (cover crop, face position, pairs) with a canvas. That is a rendering-path change, so it was left out of this round. It is the next step for fill singles, the one case where a large upload cannot be avoided.

## Control Center screen switch: render thread, 1.311 vs `cc-render`

**Device:** Family Portal+. Plain `adb install -r -d`, no force-stop. STREAM_MUSIC 0 during each stint, restored to 3.
No screenshots, no `debug.hwui` props set. Ended on official 1.311 (on-device sha256 `0c357fcd…bbca9`, matching
`hearthpane-latest.json`), Home on top and default, dev mode off.

**Builds:** official 1.311; branch `cc-render` `assemblePerformance` (`aed8a854…`).

**Method:** as before (Map → Alerts via the read-only "door is open" row, warm-up + 4 measured taps per round,
`framestats` of the Control Center window), now also the return (BACK, Alerts → Map) after each tap. 4 rounds each
(off, off, [branch], off, off, branch ×4). RenderThread breakdown from `atrace gfx view` (HWUI's `drawLayer [name] w x h`,
`flush layers`, `flush commands` slices), 2–3 traced taps per build. The house changed between rounds (an alert
cleared, someone came home), so the last official round is the closest comparison for the branch rounds.

**Where the switch frame's render thread went on 1.311 (traced):** three cached textures re-rendered before the
window: the rail (149 × 1440, the strip between the two tabs whose selection changed), the header (2011 × 122, the
title) and the **parked** Map's side column (608 × 1281, the tapped row's ripple still animating on a screen that is
transparent and off-screen; it re-rendered again on later frames). `drawLayer` 0.7–1.9 ms each, `flush layers`
5–6 ms, then `flush commands` (the window) 2–4.3 ms. The renderer re-renders every damaged texture in the tree,
transparent or not.

**Changes (`cc-render`):**
1. `ControlCenterSelection.kt:91` `CcTab` (used by `ControlCenterChrome.kt:102` `RailItem` and
   `ControlCenterCompactChrome.kt:154` `NavItem`): the rail / bottom bar is no longer one texture. Each tab caches its
   resting look (rail colour, secondary tint) as its own small texture; the selected card and the selected look
   (primary tint) sit above it and are shown by layer alpha only; badge + ripple stay in the tappable, speaking box
   on top. A switch flips four layer alphas and re-renders no texture; a rail tap's ripple no longer re-renders the
   rail texture either. Labels in the looks are drawn (`CcTabText`, `BasicText`'s layout/clip/paint via
   `TextMeasurer`) so each tab is still exactly one accessibility node.
2. `ControlCenterRegionLayer.kt:40` + `ControlCenterScreenSlots.kt:73,98,194` `ccSlotCachesRegions`: region textures on
   a kept-alive screen (the Map's side column) are cached only while the screen is shown. Parking drops the texture
   in the switch frame itself (read while drawing), so a hidden screen is never re-rendered; on a return the column
   draws straight onto the window and its texture is rebuilt 4 frames later on a quiet frame. Same pixels either way
   (the region paints its own opaque background).
- Tried, not kept: splitting the header into a title texture and a pills texture. It saved ~0.5 ms of `drawLayer`
  but adds a grouping node to the uiautomator tree (Compose exposes the extra container), so a11y would not be
  identical.

| Map → Alerts | official 1.311 (16 taps) | `cc-render` (16 taps) |
|---|---|---|
| Frames over 32 ms per tap | 2 ×14, 1 ×1, 0 ×1 (28) | 1 ×10, 2 ×1, 0 ×5 (12) |
| Worst frame, median ms | 45 | 34 |
| Worst frame range ms | 26–56 | 27–56 |
| Sum of frames over 32 ms, median ms | 83 | 34 |
| Frames over 16 ms, total | 79 | 53 |

| Alerts → Map (BACK) | official 1.311 (16) | of it, last round (4) | `cc-render` (16) |
|---|---|---|---|
| Frames over 32 ms | 20 | 7 | 29 |
| Worst frame, median ms | 42 | 45 | 42 |

- Traced switch frame on `cc-render`: the only texture re-rendered is the header (title, 0.8–1.1 ms `drawLayer`,
  1.9–2.4 ms `flush layers`, was 5–6 ms); no rail, no side column on any frame after the tap. Render thread of the
  switch frame 11–21 ms (1.311: 17–24 ms traced). The switch frame's main thread is now the larger half (25–30 ms
  traced: composition 13–17 ms, record 7–12 ms).
- The return is not improved: on the branch it is 2 frames over 32 ms per tap like the last official round in the
  same house state (the house change between rounds moved the official return numbers too). The side column drawing
  straight for 4 frames after a return did not show up as a new long frame in the traces.
- Graphics memory after leaving: 46–47 MB on the branch (official 47–78 MB; the parked side column texture is gone).
- Accessibility (full uiautomator dump, Map and Alerts): same nodes, order, bounds, flags and selected state as
  1.311; differences are only the house data (an alert cleared → "Alerts, 3", one fewer attention row, a person
  home, clock).
- **Target (no switch frame over 32 ms) still not met:** 5 of 16 taps have none (1.311: 1 of 16); most taps have one
  frame of 32–40 ms. What remains on that frame is main thread (Alerts composition and draw recording) plus the
  window's GPU command submission (`flush commands` 3–8 ms) and swap; the return switch is unchanged.

## Screensaver photo change: fill photos painted in bands (branch `engine-strips`)

**Question:** after `engine-upload-2`, fill singles (Ken Burns, Push, Memories single) still hand Chromium a 2400 px copy (~16.5 MB) that it rasterises and uploads in one go at the dissolve's first frame. Can that upload be spread so that no frame exceeds ~32 ms, with no visual change?

**Changes:**
1. `lib-engine.js:144–240`: `SS.stripLayout` (`:170`) gives the smallest whole-pixel cover crop of a photo at its `background-position`, where the CSS background would put it, and splits it into row bands of at most 1 MB (`SS.STRIP_BUDGET_BYTES`). A 4:3 2400 px copy on the Portal+ is a 2400 × 1600 crop in 15 bands. `SS.paintStrips` (`:201`) draws one band per frame. Each band is its own `createImageBitmap` crop, closed after it is drawn; the next band is cropped while the current one waits. `SS.fades` (`:189`) counts the dissolves in flight.
2. `mode-impls.js:574–712`: in `prepareLarge`, a `fill` single (`:692`) is painted into a 2D canvas before it is published (`prepareStrips`, `:605`). While it is painted, the canvas sits in a 1 px, 0.001-opacity corner (`.ss-strip-warm`), so each band is composited, and so flushed to the GPU, in its own frame. `paintLarge` (`:713`) then moves the canvas into the hero in place of the CSS background (`showStrips`, `:665`). The hero's Ken Burns or drift animation, the dissolve and its timing are unchanged. Bands wait while any dissolve runs (scene dissolves `:198–260`; the engine's beat crossfade, `scheduler.js:56, 84, 348`) or another photo is being painted, and while playback is paused (`t.publish`). Cleanup, navigation and a dropped beat abandon the bands and zero the canvas. Retired heroes free their canvas (`releaseHeroStrips`, `:656`, in the Ken Burns, Push and Memories release paths). A lost GPU context falls back to the CSS background. Whole photos and pairs are unchanged. One diagnostic console line per beat (`photo_beat`), plan (`photo_plan`) and painted photo (`photo_strips N bands WxH T ms`), tag `ImmortalEngine`, no photo data.
3. `styles.css:226–235`: `.ss-hero.ss-strips` clips to the hero as the background did; the warm corner.
- The change now lands after its bands are done: 76–241 ms (5–7 bands) later on the test scenes, about 0.3 s for a 2400 px copy. Dissolve durations and cadence are unchanged.

**Tests** (`node --test scripts/tests/*.test.mjs`: 493 tests, 474 pass, 19 browser tests skipped, 0 fail). `slideshow_photo_strips.test.mjs` has 18 new tests. They cover position parsing; the 2400 × 1600 / 15-band layout; face-anchored crops; a 3000-case property test (bands cover every row once, in order and within budget, and the crop covers the box by under one source px); one band per frame; no band during a dissolve; the wait cap; abandonment and failed crops or draws closing every bitmap. Through the scene fixture they also check that Ken Burns, Push and Memories show the canvas, never write the photo as a CSS background, and free the retired canvas. Further cases: no band drawn during any dissolve over 20 s; cleanup mid-paint; pause and resume; context loss; whole photos and pairs keep the CSS background.

**Visual check.** In desktop Chromium a CSS `cover` background and the band canvas, placed by `SS.stripLayout` at the same position, were pixel-identical (max channel difference 0 over 960 × 640, at 0.8 scale). On the Portal+ the test source's fill scene drew correctly through the canvas. The official build's scene order differed, so no same-scene 100 % crop comparison was made on the device. Scratch screenshots were deleted.

**Trace: the canvas does not upload again at its composite.** These traces use the test source pinned to `kenBurns` at a 15 s beat (generated 1350–1600 px scenes, smaller than household 2400 px copies), atrace gfx/view/dalvik/sched for 22–32 s, `Chrome_InProcGpuThread` busy summed per 100 ms.
- Branch: two fill changes were caught with their bands inside the trace. Each band window had 21–31 ms of GPU-thread work per 100 ms, spread over 6–7 frames. The 2.2 s dissolve that followed had no 100 ms bucket at 3 ms of GPU work or more. No frame in the 32 s was over 28 ms.
- Official 1.313, same pin: the change frames were 54 ms (GPU thread 46 ms), 40 ms (30) and 35 ms (29), with up to 65 ms of GPU work in a single 100 ms bucket.
- So WebView composites the finished accelerated canvas without re-uploading it. The upload happens where the bands are drawn, a slice at a time.

**Household A/B (method as above):** the household's own `shuffle` screensaver, settings unchanged, no test source, real dream via `Somnambulator`, framestats polled for 40 s, meminfo at +5 s and +70 s. Both builds were installed with plain `adb install -r` (`speed-profile`, `reason=install`). Each build got Home, 60 s idle and one unmeasured warm-up dream, then 3 runs. STREAM_MUSIC was set to 0 and restored to 3. O = official 1.313 (sha256 verified). B = the branch performance build; B3 is the same code with the `photo_beat` / `photo_plan` lines added. "Change" = frames over 60 ms at 29–35 s. Memory is launcher PSS in MB.

| Run | Janky % | p90 / p99 ms | Change: frames > 60 ms (ms) | Change max | Home → running → +5 s → +70 s |
|---|---|---|---|---|---|
| O r1 | 73 | 26 / 81 | 6 (75, 71, 65, 78, 69, 61) | 78 | 178 → 268 → 191 → 208 |
| O r2 | 27 | 69 / 400 | 16 (419, 428, 423, then 13 of 66–153 at 32.8–33.7 s) | 428 | 208 → 282 → 187 → 203 |
| O r3 | 42 | 29 / 150 | 5 (74, 71, 133, 152, 152) | 152 | 203 → 290 → 195 → 210 |
| B r1 | 29 | 81 / 200 | 1 (110) | 110 | 185 → 394 → 206 → 218 |
| B r2 | 70 | 30 / 150 | 6 (79, 74, 68, 180, 183, 72) | 183 | 219 → 378 → 206 → 219 |
| B r3 | 25 | 24 / 117 | 4 (83, 119, 113, 114) | 119 | 177 → 273 → 177 → 192 |

- **The household runs did not test the change.** No branch run painted a fill photo in its window. B1 and B2 logged no `photo_strips` line; that line is logged on every fill attempt, whether it succeeds or fails. B3 logged the beats panorama → mosaic (the change at ~30 s) → cinema → heroSidebar. In this 40 s method the measured change is a shuffle beat to a random mode. On this household's 9 photos it was never a Ken Burns, Push or Memories fill single, so the O / B differences above are run-to-run noise (worst change frame 78–428 vs 110–183 ms).
- **The remaining household change cost belongs to the other modes** (Mosaic, Cinema, Hero + Sidebar, Panorama, the native Filmstrip handoff). A branch trace of one household change (no `photo_strips`) had a 310 ms frame whose time went to `prepareTree` (147 ms) and `flush layers` (96 ms) of a native `FrameLayout` layer, with `kswapd0` busy 97 ms. The GPU thread had 25 ms. That frame is native view work, not WebView upload. Mosaic, Hero + Sidebar, Cinema and Filmstrip still get their former copies (no drawn size), which is the next target.
- **Memory:** running PSS was 378–394 MB in B1 and B2 against 268–290 MB on 1.313, and 273 MB in B3. +70 s was back at the Home level on every run (192–219 vs 203–210). No fill canvas existed in those runs, so the B1/B2 peaks are not band canvases. They match the 409–413 MB peaks seen in earlier rounds (1.306 A r2, B r3; `engine-upload-2` C r3). A painted fill canvas is the 2400 × 1600 crop (~15 MB) in place of the CSS background's decoded image and raster tiles. At most two exist (incoming and outgoing), and a retired one is zeroed at once.
- **Verdict:** the mechanism works on the device. Bands upload a slice per frame, the dissolve composites without an upload, and on the test scenes no frame exceeded 28 ms against 35–54 ms on 1.313. On household photos it is not yet measured, because the 40 s method never landed on a fill single. Measuring it needs a longer window or several changes per run, which is a method change for the owner to approve. Device left on official 1.313.

## Screensaver photo change by mode, long window (branch `engine-modes`)

**Question:** which modes cost what at a household change, measured with a window long enough to see most modes; and what is the native layer in the 310 ms frame of the round-3 trace?

**Method (new: long window).** The household's own `shuffle` screensaver, settings unchanged, no test source, real dream through `Somnambulator`, about 5 minutes per run (~9–10 beats at the household's 30 s interval). Framestats were polled back to back (about 1.3 polls/s) and deduplicated by `IntendedVsync`. Each beat was attributed to its mode by the engine's `photo_beat <mode>` console line (logcat `-v monotonic`, the same clock as framestats). "Change" is the frames from 0.5 s before a beat's log line to 3 s after it. The first beat of each run (the dream start) is left out. Each build got Home, ~50 s idle and one unmeasured 40 s warm-up dream before its run. Installs were plain `adb install -r` (`-d` for the branch, which carries the 1.313 version name). STREAM_MUSIC was set to 0 and restored to 3. No screenshots. Builds: O = official 1.313 (sha256 verified); M = local `main` `assemblePerformance` (= 1.314); N = official 1.314 (sha256 verified; same code as M, a release build); B = this branch, 3 runs (B2 and B3 under `atrace gfx view --async`). 1.313 has no `photo_beat` line, so O is unattributed. Mode order is random, so each mode got 0–4 changes per build.

**Household finding first:** every Ken Burns, Push and Memories beat in 6 runs was a **pair** (`photo_plan pair`; no `photo_strips` line at all). This household's 9 photos never produce a fill single, so round 3's bands never run here. Its change cost comes from the other modes.

**Change cost per mode: worst frame ms / frames over 60 ms, one entry per change**

| Mode | main (M, N) | branch (B1–B3) |
|---|---|---|
| Filmstrip (native handoff) | 264 / 5, 242 / 2 | 177 / 3, 105 / 4 |
| Cinema | 124 / 4, 59 / 0, 119 / 8, 115 / 1 | 63 / 1, 170 / 5 |
| Gallery Wall | — | 240 / 23, 271 / 23, 228 / 19 |
| Polaroid | — | 119 / 1, 139 / 4, 106 / 5, 130 / 7 |
| Framed | 95 / 3, 256 / 5 | 312 / 6 |
| Mosaic | 102 / 6 | 98 / 9 |
| Diptych | 49 / 0, 72 / 4, 114 / 7 | 127 / 7 |
| Panorama | 117 / 7, 147 / 5 | 61 / 1, 173 / 5 |
| Hero + Sidebar | 125 / 6, 71 / 3 | — |
| Memories (pair) | 124 / 6 | 125 / 7, 157 / 8 |
| Ken Burns (pair) | 110 / 4 | 116 / 7 |
| Push (pair) | — | 149 / 6, 190 / 3 |

Official 1.313 (unattributed; 9 changes): worst frame 61–289 ms (median 130), 1–10 frames over 60 ms each. Memory (launcher PSS MB, Home → running → +5 s): O 179 → 459 → 206, M 188 → 469 → 198, N 190 → 433 → 220, B 176–186 → 279–414 → 187–220.

- **Gallery Wall is the most expensive household change**: 19–23 frames over 60 ms and 3.3–3.4 s of frames over 32 ms per change, about 3× any other mode. Trace (B2): two `WebViewFunctor::drawGl` frames of 108 and 161 ms at the beat, then ~1 s of 36–46 ms `drawGl` frames through the 1.6 s beat fade. It is WebView work, not a native layer. Each tile and photo layer paints the tiled `photo-doodles-dark.svg` and a 30 px `box-shadow` (`styles.css:75–80`, `mode-impls.js:919–923`). Not changed in this round.
- Most other modes cost about 100–170 ms worst and 3–8 frames over 60 ms. Between runs of the same mode the spread is as wide as between builds (Cinema 59–124 ms on main), so one or two changes per mode cannot separate main from the branch, except for the native Filmstrip handoff below.

**The native layer in the 310 ms frame** (round-3 trace `prepareTree` 147 ms, `drawLayer [FrameLayout] 1190 × 893` 54 ms, `flush layers` 96 ms): that is the Filmstrip stage, at its first visible frame of the native handoff (`HybridFilmstripHost.promote`). 1190 × 893 is a 4:3 Filmstrip card (0.62 × 1440 tall). Every dimmed card (`alpha .4`, overlapping children) is rendered into its own offscreen layer. Each card's black dimmer (`alpha .45`) got a second layer inside it (`drawLayer [View] 670 × 893`). That frame built 8 + 8 layers and uploaded all the card photos. The stage is added at alpha 0 during preparation, and HWUI skips it until it becomes visible, so all of it lands on the handoff frame. The same frame on main this round: 264 ms (sync 123 ms + draw 101 ms) on M; 242 ms on N.

**Changes:**
1. `FilmstripTextureWarmup.kt` (new) + `FilmstripEngine.kt:91, 219–230`: a prepared deck is handed to the GPU one photo and its backdrop at a time (`Bitmap.prepareToDraw`, RenderThread upload in its idle gap), one step per 34 ms, before the deck is admitted. Abandoned exactly once (bitmaps freed) if the deck is superseded, the engine closes, or the queue rejects a step. `PausableFrameCallbacks.kt:73` `postOwnedDelayed`. 9 photos take ~0.3 s; the JS prepare timeout is 8 s.
2. `FilmstripEngine.kt:262` + `FlatDimmer` (`:476`): the card dimmer draws its fill at its alpha (`hasOverlappingRendering() = false`). One opaque fill at alpha α gives the same pixels as that fill drawn opaque into a layer and composited at α. One fewer layer per card.
3. Cinema (`modes.js:369–379` `SS.cinemaDrawnEdge`, `mode-impls.js:1786–1790`): on the Portal+ a contained photo (up to 2.2:1) asks for its drawn size in the 78 % scope: 4:3 → 1500 px copy (was 2400), 3:4 → 1200. Wide photos crop, drift and follow faces, so they keep their former copy. Compact screens are unchanged.
- **Not changed:** Mosaic and Hero + Sidebar re-justify every photo's box whenever another photo in the composition swaps. A drawn size taken at load time could later be drawn larger, which would soften it, so these keep their former copies. Gallery Wall (above) is the next target.

**Result (trace B3, branch, household Filmstrip handoff):** the 9 card photos were uploaded beforehand as 15 ms `Bitmap#prepareToDraw` slices, spread out. On the handoff frame `prepareTree` took 2 ms, card layers `drawLayer` 5 ms and `flush layers` 18 ms (was 147 + 54 + 96 ms). The next frame still had one 84 ms `flush commands` (the window's GPU submission with the stage fading in over the WebView). Filmstrip handoff worst frame: branch 177 and 105 ms vs main 264 and 242 ms. The remaining 84 ms submission is not explained yet. It may be first-use shader programs (shadows, the vignette gradient, the stage's alpha layer).

**Tests:** `node --test scripts/tests/*.test.mjs scripts/tests/*.test.cjs`: 528 tests, 509 pass, 19 skipped, 0 fail. New: Cinema drawn edge values and a property test that the copy covers the laid-out box for every shape from 0.3 to 2.2:1 (`slideshow_drawn_edge.test.mjs`); the cinema initial request is 1685 px in the resolved-resources fixture. `:app:testDebugUnitTest`: 9178 tests, 0 failures, including `FilmstripTextureWarmupTest` (7: one step per post, then a single admission; empty deck; superseded deck abandoned; close abandons once; rejected post; failed upload hint still admits; paused delayed owned post keeps its remaining delay).

**Device** at the end: official 1.314 (on-device sha256 `e06ce483…`, matching `hearthpane-latest.json`), Home on top and default, test source never used, dev mode off (`fleetctl info`: `devMode: false`), STREAM_MUSIC 3.

## Screensaver Gallery Wall change: pre-rasterised doodle tile (branch `gallery-wall`)

**Question:** a household Gallery Wall change costs ~3× any other mode (round 4: 19–23 frames over 60 ms, ~3.4 s of frames over 32 ms, WebView `drawGl`). Where does it go, and can it be made smooth with no visible change?

**Attribution.** A wall paints the tiled `photo-doodles-dark.svg` 21 times: the wall, each of the 10 tiles, and each tile's opaque photo matte (`styles.css:80` before, `mode-impls.js:919–923` before). A tiled SVG background reaches the GPU as a picture shader that is rasterised again for each paint and raster tile. That is the only thing this branch changes, and the A/B below moves from 4–19 to 0–2 frames over 60 ms per change. The doodle accounts for most of the cost. The 30 px `box-shadow` and the rounded clip are unchanged. The branch trace (below) leaves no frame they could explain.

**Change:**
1. `app/src/main/assets/screensaver/photo-doodles-dark-{480,540,960}.png` (new, 12–30 KB): the same vector rasterised by Chromium at 1x (Portal), 1.125x (Portal+ device scale, 540 × 405 px) and 2x, on a transparent background. They are reproducible with `scripts/generate_doodle_tiles.mjs`, which gives byte-identical output on a rerun.
2. `styles.css:80–84`: the wall and tile doodle is `image-set(… 1x, … 1.125x, … 2x)` at the same 480 × 360 px tile size. `mode-impls.js:913–916` `GALLERY_DOODLE` holds the same value, and the photo matte uses it (`:925`). Chromium decodes and uploads the tile once and draws it 1:1 at each device scale.
- **Not changed:** the box-shadow, the rounded clip, transitions, layout, timing and the photo copies (already drawn-size, `SS.containEdge`). No containment or layer hints were added. The trace showed nothing left for them to fix, and each could change pixels or GPU memory. The other modes that use the dark doodle (Diptych, Cinema matte, Hero + Sidebar, Memories) are not changed. They paint it once per scene, and the same tile would suit them.

**Tests:** `node --test scripts/tests/*.test.mjs scripts/tests/*.test.cjs`: 531 tests, 512 pass, 19 browser tests skipped, 0 fail. New `slideshow_gallery_doodle.test.mjs` (3) checks that each candidate is an RGBA PNG of 480 × 360 × scale; that the CSS rule and `GALLERY_DOODLE` are the same value; and that Gallery Wall no longer references the SVG. Gallery tests that pinned the SVG string now pin the tile value (`helpers/slideshow_scene_fixture.mjs` `GALLERY_DOODLE`). `:app:testDebugUnitTest`: 9178 tests, 0 failures.

**Visual check.**
- Desktop Chromium, real `styles.css` from `main` and from the branch, a 10-tile wall at fractional positions: at dpr 1.125, 99.5 % of channels are identical, max difference 5 / 255 (mean 1.2 where different). At dpr 1, max 1 / 255.
- On the Portal+ (test source pinned to `galleryWall`, generated scenes, official 1.315 vs branch, 3 walls each): tile selection is random per build, so the comparison used the wall background at the same screen position, compared at 100 % crop. Doodle positions, stroke weight and tone match. 95–96 % of channels are identical; the rest differ by at most 14 / 255, on a few edge pixels.
- Scratch screenshots were deleted.

**Method.**
- (1) **Microbenchmark:** test source pinned to `galleryWall` at 15 s, generated scenes, in `PhotoFramePreviewActivity`. A pinned mode never advances beats (`scheduler.js:86`). So each change is forced with a left swipe (`next()`, a new Gallery Wall beat with the full 1.6 s crossfade over the previous wall), 6 per run, 12 s apart. Framestats were polled back to back. Changes were attributed by `photo_beat` (0.5 s before to 3 s after), and the first beat (start) is left out.
- (2) **Household long window:** round 4's method (shuffle, settings unchanged, real dream, 5 min, `photo_beat` attribution).
- Plain `adb install -r`; O = official 1.315 (sha256 verified); B = branch `assemblePerformance`. STREAM_MUSIC 0, restored to 3. No household screenshots.

| Forced Gallery Wall change (6 per run) | O run 1 | O run 2 | B run 1 | B run 2 |
|---|---|---|---|---|
| Worst frame per change, ms | 273, 146, 136, 143, 126, 125 | 145, 136, 110, 142, 121, 158 | 53, 56, 55, 55, 43, 42 | 53, 48, 69, 39, 61, 46 |
| Frames over 60 ms per change | 19, 4, 4, 4, 4, 19 | 19, 19, 4, 19, 5, 17 | 0, 0, 0, 0, 0, 0 | 0, 0, 2, 0, 1, 0 |
| Frames over 32 ms, total | 69 | 99 | 53 | 34 |
| Sum of frames over 32 ms, mean per change | 1156 ms | 1590 ms | 358 ms | 245 ms |

- The official changes with 17–19 frames over 60 ms reproduce the household signature. None of the 12 branch changes has more than 2 frames over 60 ms, and the worst frame is 39–69 ms (official 110–273 ms).
- **Tile swap** (the pin's in-mode swap every 15 s; clusters off the 15 s cadence are a once-a-minute event in the preview and are left out). O: 10 swaps, worst 86–223 ms, 3–4 frames over 60 ms each. B: 9 swaps, worst 41–125 ms. The first three swaps after the install were 65–125 ms with 1–3 frames over 60 ms; the later six were 41–50 ms with 0.
- **Wall build** (test-source start, no crossfade): O worst 96–166 ms (589 ms once), B 53–66 ms (176 ms on the first start after the install).
- **Trace** (branch, one forced change, atrace gfx/view/webview/sched): 2 frames over 30 ms. One `DrawFrame` was 52 ms (GPU thread 48 ms, `kswapd0` 25 ms), the other 39 ms (GPU thread 34 ms). The rest of the 1.6 s crossfade stayed under 30 ms.
- **Household long window:** shuffle picks the mode at random, so 6 runs were taken (O 2, B 4). Gallery Wall appeared in O run 2 and twice in B run 4.

| Household Gallery Wall change | Worst frame | Frames over 60 ms | Frames over 32 ms | Sum over 32 ms |
|---|---|---|---|---|
| O run 2 | 332 ms | 21 | 26 | 3.65 s |
| round 4 (`engine-modes`, 3 changes) | 228–271 ms | 19–23 | — | 3.3–3.4 s |
| B run 4, change 1 | 143 ms | 11 | 32 | 1.76 s |
| B run 4, change 2 | 103 ms | 5 | 36 | 1.69 s |

- On household photos the change costs about half of what it did: worst frame 103–143 ms against 228–332 ms, and 5–11 frames over 60 ms against 19–23. That is less than on the generated scenes. Household copies are larger, and the outgoing mode is heavier.
- About 1.7 s of 33–60 ms frames remain through the fade. They are not traced on household photos yet. The next suspects are the 10 photo uploads at the crossfade and memory pressure (`kswapd0` was busy in the branch trace).
- Other modes on the branch matched official within run-to-run spread, as expected: Gallery Wall is the only mode that uses the new tile.
- Memory (launcher PSS MB, Home → running → +5 s): O 210 → 427 → 221 and 182 → 439 → 198; B 198–213 → 292–414 → 205–211.
- At the start of the last stint the device was on official 1.314 with the Home chooser on top, left by the previous holder. The branch install restored the default Home.

**Device** at the end: official 1.315 (on-device sha256 `229e6741…`, matching `hearthpane-latest.json`), Home on top and default, test source off, dev mode off (`fleetctl info`: `devMode: false`), STREAM_MUSIC 3.

## Control Center return switch (Alerts → Map), 1.313 vs `cc-return`

**Device:** Family Portal+ (no floor plan: Map is the "Build your floor plan" card plus the side column, with one
snapshot camera tile). Plain `adb install -r -d`, no force-stop. STREAM_MUSIC 0 during each stint, restored to 3. No
screenshots, no `debug.hwui` props. Ended on official 1.313 (on-device sha256 `2742f347…`, matching
`hearthpane-latest.json`), Home on top and default, dev mode off.

**Builds:** official 1.313; branch `cc-return` `assemblePerformance` (v1 `cc-return` first cut, v2 final, `1f19e1e4…`;
the Map/Alerts code paths are identical, so their rounds are pooled), `assembleBenchmark` of 1.313 and of the
branch for simpleperf, and one throw-away performance build with `ccTrace` sections (the sections did not appear in
atrace on this build, so it only added frame timings).

**Method:** as before: Map → Alerts via the read-only "door is open" row, BACK to return; warm-up + 4 measured taps per
round, 4 rounds per build (off, off, v1, v1, v2, off, v2, off), `framestats` of the Control Center window. atrace
`gfx view` around 3–4 BACKs per build, simpleperf 4 kHz around 4 BACKs per benchmark build.

**Where the return went on 1.313:**
- The two long frames of a return are one stall: the switch frame's main thread (`doFrame` 27–37 ms: Compose
  `animation` 16–26 ms, `Record View#draw()` incl. measure 8–9 ms) plus its RenderThread (13–15 ms); the next frame is
  short on both threads (5 ms + 10–13 ms) and is long only because its vsync was missed.
- RenderThread of the switch frame: only the header texture re-renders (title, 2011 × 122, `drawLayer` ~1 ms,
  `flush layers` 2–2.5 ms), then `flush commands` 3–4 ms and swap. The Map side column's re-texture 4 frames later
  (`drawLayer` 608 × 1281, 0.8–1.5 ms; `flush layers` 1.4–2.8 ms) lands on a quiet frame (8–12 ms) and is not a
  long frame. The plan is not re-recorded (no plan on this unit).
- Main thread (benchmark simpleperf, worst frame of 4 BACKs, ~33 ms): composition 13–17 ms, of which
  `CcCameraPicture` going live 2.5–4 ms (stream claim + snapshot loop composed in the switch frame), the rest of
  `CcOverviewScreen` re-run with the fresh snapshot 1.5–2.5 ms, `CcHeaderTitle` + `rememberCcHeading` 1.25–3.25 ms;
  semantics 2–6 ms (Alerts leaves the tree in the switch frame; the header description changes); apply 2–2.5 ms;
  measure 1–3 ms. A large share of samples have unwindable short stacks and cannot be attributed.

**Changes (`cc-return`):**
1. `ControlCenterScreenSlots.kt:69,83,103,190` `ccSlotLive(active, awake, lingering)`: a shown screen's cameras and
   loaders (`LocalCcScreenActive`) start 3 frames after the switch (`EXPOSE_FRAMES` + `WAKE_FRAMES`, one frame after
   the semantics join), mirroring the 4-frame linger on leave. A returning tile keeps its last picture meanwhile.
   Animations (`LocalCcMotion`, `ccSlotMoving` `:196`) are unchanged: on at once, off after the linger.
2. `ControlCenterScreenSlots.kt:98` the slot's alpha/draw modifier is one remembered chain that reads its mode while
   drawing (`ccSlotAlpha` `:177`), so shown ↔ parked is only a layer property change.
3. `ControlCenterTeslaHero.kt:45` reads `LocalCcMotion` only (same value as before inside and outside the slots).

| Alerts → Map (BACK) | official 1.313 (16 taps) | `cc-return` (16 taps) |
|---|---|---|
| Frames over 32 ms per tap | 2 ×11, 1 ×2, 0 ×3 (24) | 2 ×12, 1 ×3, 0 ×1 (27) |
| Worst frame, median ms | 42 | 44 |
| Sum of frames over 32 ms, median ms | 79 | 80 |
| Frames over 16 ms, total | 66 | 66 |

| Map → Alerts | official 1.313 (16 taps) | `cc-return` (16 taps) |
|---|---|---|
| Frames over 32 ms per tap | 2 ×7, 1 ×5, 0 ×4 (19) | 2 ×4, 1 ×8, 0 ×4 (16) |
| Worst frame, median ms | 38 | 36 |
| Sum of frames over 32 ms, median ms | 38 | 36 |
| Frames over 16 ms, total | 52 | 58 |

- **No measurable win in either direction; target not met.** The branch benchmark profile confirms the camera
  start is gone from the switch frame (`CcCameraPicture` no longer appears), but the switch frame's main thread is
  still 21–52 ms and its RenderThread 15–30 ms (benchmark build), so the return keeps its two long frames.
- What remains on the return frame (branch, simpleperf): semantics 1.5–15 ms (the parked Alerts leaving the tree and
  the header's spoken title changing, both in the switch frame, each forcing Compose's full tree rebuild + sort),
  `CcHeaderTitle` + `rememberCcHeading` 2–5 ms (46 sp serif title and subtitle re-laid out; `CcDateFormats.format`),
  `CcOverviewScreen` re-run with the fresh snapshot 1–4 ms, measure 2–11 ms.
- Next candidates: move *every* semantics change of a switch (parked screen leaving, header description, rail
  "selected") onto the same deferred frame as the shown screen joining, so the switch frame triggers no tree
  rebuild; keep both titles' text layouts (`TextMeasurer`, drawn like `CcTabText`) so the title swap is a draw only.
  Precomposing Alerts further is not the lever: it is already composed and recorded while parked; its switch-frame
  cost is the header, semantics and measure, not Alerts' own composition.
- Graphics memory after leaving: 44–46 MB on the branch, 43–45 MB official; PSS 172–183 vs 180–186 MB.
- Accessibility (full uiautomator dump, Map and Alerts): same 49 / 71 nodes, order, bounds, flags and selected state
  as 1.313; only the clock text and the camera's "just now / under a minute ago" differ (dumps 2 min apart).
- Device incident: after the last benchmark → official reinstall the HOME intent resolved to the chooser
  (`ResolverActivity`); restored with `cmd package set-home-activity …/HomeActivity` (Home on top, default again).

## Control Center screen switch: semantics off the switch frame, 1.314 vs `cc-a11y`

**Device:** Family Portal+ (no floor plan: Map is the "Build your floor plan" card plus the side column). Plain
`adb install -r -d`, no force-stop. STREAM_MUSIC 0 during each stint, restored to 3. No screenshots, no debug props.
Ended on official 1.315 (released meanwhile, no Control Center code; on-device sha256 `229e6741…`, matching
`hearthpane-latest.json`), Home on top and default, dev mode off.

**Builds:** official 1.314 (sha256 `e06ce483…`, the A/B baseline); `cc-a11y` `assemblePerformance`: v1 = changes 1–2
below (`55b40d55…`), v2 = v1 + the `cc-return` camera deferral cherry-picked (`aacf835e…`), v3 = v2 + change 3
(`6dc7d607…`); `assembleBenchmark` of v2 for simpleperf.

**Method:** as before: Map → Alerts via the read-only "door is open" row, BACK to return; warm-up + 4 measured taps per
round, `framestats` of the Control Center window. Stint 1: off, v1, v1, v2, v2, off; stint 2: v2, v2, v1, v1, off, off
(4 rounds = 16 taps each way per build). Accessibility: full uiautomator dump of Map and Alerts per build, and
`uiautomator events` (6 s) around one tap and one BACK per build. atrace `gfx view` 2 taps + 3 BACKs on v2 and on 1.314;
simpleperf 4 kHz around 4 BACKs and 3 taps on the v2 benchmark build.

**Where the switch went on 1.313/1.314 (from the `cc-return` profile):** switch frame main thread 27–37 ms =
composition 13–17 ms (camera tile going live 2.5–4, Map re-run 1.5–2.5, header title 1.25–3.25), semantics rebuild
2–6 ms (up to 15) triggered in the switch frame by Alerts leaving the tree, the header's spoken title and the rail's
"selected", apply 2–2.5, measure 1–3; RenderThread 13–15 ms.

**Changes (`cc-a11y`):**
1. `ControlCenterNav.kt:94` `CcUi.spokenScreen` (+ `spoke`): the screen accessibility services are told about.
   `ControlCenterScreenSlots.kt:119` `LaunchedEffect(current)` sets it 2 frames after a switch (~35 ms, inside Compose's
   100 ms event batching). Everything a switch changes for accessibility follows it, so it all lands on that one
   quiet frame: the parked screen leaving the tree and the shown one joining (`ccSlotSemantics(spoken)`, one shared
   `UNSPOKEN` clear-semantics modifier so a recomposed unspoken slot keeps an equal modifier), the header heading's
   text and bounds, and every tab's "selected" (`ControlCenterSelection.kt:81` `CcTabSelection(shown, spoken)`; the
   looks still follow `shown` at once, `ccSpeakTab` observes `spoken`; `ControlCenterChrome.kt:67`
   `rememberCcTabSelected`).
2. `ControlCenterHeadingText.kt` (new) `CcHeadingText`, used by `CcHeaderTitle` (`ControlCenterChrome.kt:188`) and the
   Mini's `CompactTitle`: the title and subtitle are drawn from prepared `TextLayoutResult`s. Layouts for every
   screen are prepared on quiet frames (one per frame after the inputs change); the drawn heading reads
   `ui.screen` while drawing, so the switch frame re-draws the header and composes / lays out no text. Painted as
   `BasicText` paints (`ccPaintText`, shared with `CcTabText`). The node (text, heading flag, bounds = the wider line
   by both heights, as the old `Column`) follows the spoken screen. `rememberCcHeading` removed (unused).
3. Tried, not kept (v3): slots stay placed at the origin and parking is a layer translation (`placeWithLayer`, one
   block per screen reading the shown screen), so a switch moves no node. See the results below.
- Cherry-picked `cc-return` (`ce0272b8`): a shown screen's cameras / loaders start 3 frames after the switch.

| Map → Alerts (tap) | official 1.314 (16 taps) | v1 (16) | v2 (16) |
|---|---|---|---|
| Frames over 32 ms per tap | 3 ×1, 2 ×8, 1 ×3, 0 ×4 (22) | 2 ×1, 1 ×4, 0 ×11 (6) | 2 ×1, 1 ×3, 0 ×12 (5) |
| Worst frame, median ms | 38 | 30 | 28 |
| Sum of frames over 32 ms, median ms | 71 | 0 | 0 |
| Frames over 16 ms, total | 71 | 79 | 73 |

| Alerts → Map (BACK) | official 1.314 (16) | v1 (16) | v2 (16) |
|---|---|---|---|
| Frames over 32 ms per tap | 2 ×10, 1 ×3, 0 ×3 (23) | 3 ×1, 2 ×4, 1 ×8, 0 ×3 (19) | 1 ×9, 0 ×7 (9) |
| Worst frame, median ms | 42 | 34 | 33 |
| Sum of frames over 32 ms, median ms | 75 | 34 | 33 |
| Frames over 16 ms, total | 68 | 72 | 80 |

- Graphics memory after leaving: 46–47 MB on every build (1.314 45.6–47.1, v1 46.4–48.0, v2 46.3–47.1).
- **The tap is fixed for most taps** (12 of 16 with no frame over 32 ms on v2, 4 of 16 on 1.314). The return went from
  two long frames per tap to at most one, and the camera deferral is what makes the difference on the return
  (v1 19 → v2 9 frames over 32 ms): with the semantics moved, the camera start was the next-biggest item on the
  return's switch frame. Frames over 16 ms do not drop: the work moved to the following frames, it did not vanish.
- simpleperf (v2 benchmark, per frame of 4 BACKs and 3 taps): **no semantics work in the switch frame any more**
  (0 ms in all 7). But the frame right after the switch still has a semantics rebuild of 4–8.5 ms, and the spoken
  frame a second, smaller one (2.5–4 ms). The first is not a semantics change: Compose's
  `onLayoutChange` marks the accessibility tree stale for **any** node that moves, and the slots are parked by
  placement (the shown screen moves from the park offset to 0, the parked one away), so the bounds loop rebuilds and
  re-sorts the tree after the switch frame. That is what v3 tried to remove.
- atrace (v2 BACK): switch frame main thread 16–23 ms (composition 5–13 ms, traversal 10–11.5 ms), RenderThread
  17.5–29 ms: the header texture (2011 × 122, the title) re-rendered (`drawLayer` 1.4–3.4 ms, `flush layers` 2.4–5.6 ms),
  `flush commands` 5–10 ms (the window with the returning Map), swap 2–3.3 ms.
- Accessibility (full uiautomator dumps, Map 50 nodes and Alerts 76, each build vs 1.314): same nodes, order, classes,
  flags, selected state and bounds; the only differences are the clock in the heading ("3:58 pm" → "4:03 pm", the
  heading's right edge moving 1–3 px with the digits) and "Open 26 min" → "Open 30 min" on the open-door row.
- Accessibility events (`uiautomator events`, one tap and one BACK each): the same events arrive on both builds in
  the same order: `VIEW_SELECTED` (the tab), `WINDOW_CONTENT_CHANGED` with `CONTENT_DESCRIPTION` (the heading),
  and the `SUBTREE` changes (1.314: selection, then the heading 40 ms later; branch: selection, then the heading
  22–28 ms later). So TalkBack still hears the newly selected tab and the new heading after every switch.
- **v3 (parking by layer translation) did not help; not kept.** Measured after the house changed (an open door
  closed, nobody home), with one official round in that state. Frames over 32 ms per tap: v3 tap 9 / 16 (0.56 per
  tap), BACK 12 / 16 (0.75); official in the same state tap 4 / 4 taps, BACK 8 / 4 (2 per BACK); v2 tap 5 / 16,
  BACK 9 / 16. The frame after the switch did get short (15–21 ms, v2 27–32 ms) but the switch frame got longer
  (BACK 38–47 ms, v2 33–38 ms), so no long frame was removed. Its accessibility dump matched the official one in the
  same house state.
- Device incident: one reinstall of 1.314 left the system chooser (`ResolverActivity`) on top although the HOME
  intent still resolved to Hearthpane; the stint stopped there (the script then only checked the resolution), and the
  next stint set the home activity and pressed Home first. Every install now checks both.

**Against the target (no switch frame over 32 ms either way): not met, but close on the tap.** v2 (merged here):
tap 12 of 16 taps with no frame over 32 ms (1.314: 4 of 16), worst frame median 28 ms; return 7 of 16 with none
(1.314: 3 of 16), at most one long frame per BACK (1.314: two), worst median 33 ms (42).

**What is left on the return's long frame (v2):** main thread 16–23 ms (Map's composition with the fresh snapshot,
measure) plus RenderThread 17–29 ms: the header texture re-rendered for the title, the window's `flush commands` with
the returning Map, swap. Next candidates: draw the title outside the header's cached texture (a layer inside it still
re-renders the texture; the heading is now one drawn node, so drawing it elsewhere need not add a node), and find
what of the returning Map's `flush commands` is avoidable.

## Settings kept alive, and Control Center's last-known states (branch `settings-alive`)

Both changes were owner decisions from the two "Next" lists above.

**Changes:**
- **Settings stays alive between opens** (`SettingsRetention.kt`, `ImmortalSettingsActivity.kt`, manifest).
  - Settings now has its own task (`singleTask`, excluded from recents). Back at the top, and Settings' Home
    button, move it behind Home (`moveTaskToBack`, with no transition, matching the old finish) instead of
    finishing it.
  - A reopen is `onNewIntent` plus a resume. A reopen after Back gets a light reset before its first frame: the
    last page at depth 1, the list pane, search, dialogs, the Jarvis pulse and every scroll position.
  - A reopen after any other exit (a Home press, the screensaver, a sub-screen left on top) composes the tree
    afresh.
  - A parked Settings is finished after 10 min unopened, or on memory pressure (`RUNNING_LOW`, `RUNNING_CRITICAL`,
    background levels). It is never finished while one of its sub-screens is on screen.
  - The first open after process start is the same cold launch as before.
- **Control Center opens fully drawn** (`ControlCenterLastKnown.kt`, engine).
  - When CC is hidden, the background subscription still drops the plan's non-alert entities. The engine now
    keeps them as last known, in memory (at most 600), instead of discarding them.
  - An open restores them at once, marked unconfirmed, and publishes, so the plan models are built on the engine
    thread. The foreground subscription's full answer then confirms them in place, and drops any that HA no
    longer has. An unchanged entity keeps its instance.
  - Unconfirmed entities never raise alerts.
  - Safety-critical entities (locks, the alarm, garage doors and gates, leak/smoke/CO/gas sensors) show the
    in-flight look while unconfirmed. Actions on them are refused, in both the UI gate and the engine, until HA
    confirms them.
  - Nothing is persisted to disk.

**Device and method:**
- Family Portal+, lock held 20:16–20:23 PST. STREAM_MUSIC was 0 during the session and restored to 3. No
  screenshots; the tap targets came from `uiautomator dump`.
- Before: official 1.317 (OTA install, long-running process). After: the branch `assemblePerformance` build,
  installed with a plain `adb install -r`, then 25 s idle.
- Settings: `gfxinfo reset`, tap Settings, 2.5 s, `framestats`, Back, 4 s. Control Center: the same with Home
  Control and 3 s; only open and Back were used.
- One unmeasured warm-up open before each series. Medians of 5. "Span" runs from the first frame's intended vsync
  to the last frame's completion.
- At the end, official 1.317 was reinstalled (sha256 `4db51aa3…2f2a`, matching `hearthpane-latest.json`). Home
  was the default and on top, and dev mode was off.

| | Frames (all windows) | Janky | p90 / p99 ms | Frames > 32 ms | Max ms | Span ms |
|---|---|---|---|---|---|---|
| Settings open, official 1.317 | 18 (Settings 16) | 16 (89 %) | 61 / 69 | 8 | 72 | 389 |
| Settings first open after install, branch (cold, n = 1) | 18 (Settings 16) | 18 | 93 / 105 | 15 | 105 | 661 |
| **Settings reopen, branch** | **3 (Settings 2)** | 3 (100 %) | 69 / 69 | 3 | 70 | **152** |
| CC open, official 1.317 | 13 | 9 (69 %) | 133 / 150 | 4 | 141 | — |
| CC open, branch | 11 | 8 (73 %) | 150 / 150 | 4 | 171 | — |

**Settings:**
- A reopen is a resume: 2 Settings frames instead of 16, and the open settles in 119–153 ms instead of
  369–466 ms. The first reopen after the cold open took 268 ms.
- Every frame is still janky. Settings' first frame recreates the window surface and re-records the whole tree
  (66–70 ms; 158 ms on the first reopen), and Home draws its hide frame at the same time. The targets
  (≤ 1 janky frame, p90 < 16 ms) are still not met, but the open is about 2.5 × shorter.
- The single cold open ran on a fresh install (not `speed-profile`), so it isn't comparable with the
  long-running official process.
- Logcat showed `Settings reopened (LIGHT)` on all 5 reopens. Back returned to Home each time.

**Memory cost of a parked Settings:**
- Launcher PSS was 249.1 MB with Settings parked, and 234.7 MB about 6 s after
  `am send-trim-memory RUNNING_LOW`. Logcat showed `Releasing retained Settings (trim 10)`, and no Settings
  activity record remained.
- So the cost is about 14 MB, almost all Java heap (35.5 → 21.8 MB). Graphics was unchanged at 51.0 MB, because
  a stopped window's surface is already freed.
- This is an upper bound: the trim also runs the app's other trim handlers.

**Control Center:**
- No measurable change in jank (frames over 32 ms: 4 vs 4). The CC window draws about 9 frames instead of 11.
- The open is still dominated by its first two frames, 115–167 ms each: CC's first composition and Home's frame
  queued behind it. The redraw when the snapshot arrives was 20–54 ms before, and is not the bottleneck on this
  household.
- The change is kept for what it shows: the plan is fully drawn from the first frame, with no pop when the
  states arrive. It is not kept for frame time.

**Tests:**
- New JVM tests: `SettingsRetentionTest` (10), `CcLastKnownTest` (10) plus `CcLiveUnchangedTest` (1), and
  `CcEngineLastKnownTest` (6).
- `cc_engine_golden.txt` was re-recorded. Only `entitiesVersion` counts changed, because identical updates no
  longer bump the version.
- Full suite: 9,216 tests, 0 failures.

## Home tab switch (Home / Apps / Tools), 1.317 vs `home-tabs`

**Device:** Family Portal+ (2160×1440, accessibility services on). Plain `adb install -r -d`, no force-stop.
STREAM_MUSIC 0 during each stint, restored to 3. No screenshots or recordings (Home shows family photos), no debug
props. Only the tab bar was tapped. Ended on official 1.317 (sha256 `4db51aa3…`), Home on top and default, dev off.

**Builds:** official 1.317; `home-tabs` `assemblePerformance` v1 (changes 1–3), v2 (v1 + change 4), v3 (v2 + change
5, built, unit-tested, **not measured**: device time ended); `assembleBenchmark` of main and of v1/v2 for simpleperf.

**Method:** per round: warm-up cycle, then 4 × (Home → Apps, Apps → Tools, Tools → Home), `gfxinfo reset` before each
tap, `framestats` of the HomeActivity window 2.5 s after. 2 rounds per build (8 switches per direction). atrace
`gfx view` on 1.317, simpleperf 4 kHz around 6 switches per benchmark build.

**Where a switch went on 1.317:** one stall: the switch frame's main thread 32–82 ms (atrace `animation` 31–52 ms,
`Record View#draw()` 9–12 ms) and RenderThread 9–24 ms; the next frame is long mostly because its vsync was missed.
Main thread (simpleperf): the whole `LauncherScreen` recomposed because it took the tab as a parameter (7–15 ms incl.
header, background, `rememberLauncherForActivityResult`), the dock 2–8.5 ms (every tab re-composed, icons re-resolved
through `painterResource`), the pages' activation (`LocalApprovedLauncherPageActive` readers, lifecycle
RESUMED/CREATED, Apps' grid, producers restarting) 2–10 ms plus apply 1–20 ms (side effects, lifecycle observers),
and the page swap in the semantics tree (rebuild 1–6 ms on the next frame).

**Changes (`home-tabs`):**
1. `HomeActivity.kt:62` passes a tab reader; `HomeLauncherScreen.kt:94` reads it only in the header's own scope
   (`HomeRecomposeScope`, `:380`; Customize comes and goes with Home), the tab host (`ApprovedHomeUi.kt:84`) and the
   dock (`:611`), and the per-switch resets run from a `snapshotFlow` (`:219`). A switch no longer recomposes the screen.
2. `ApprovedHomeDock.kt` (new; the dock moved out of `ApprovedHomeUi.kt`): each tab is a fixed tree (CcTab pattern):
   fill, icon tint (`ApprovedDrawnTintIcon`) and label colour (`BasicText` colour producer) are read while drawing.
   Same nodes, bounds, role and text as before.
3. Accessibility off the switch frame (CcUi.spokenScreen pattern): `rememberHomeSpokenTab` / `followAfterFrames`
   (`ApprovedHomeDock.kt`) set the spoken tab 2 frames after a switch; the dock's "selected" (`homeTabSelected`,
   observed, no recomposition) and which page is in the semantics tree (`ApprovedLauncherTabs.kt`
   `launcherPageSemantics`, one shared hidden-page modifier) follow it.
4. Deferred page wake (heavy starts after the switch): `ApprovedLauncherTabs(wakeFrames)`; Home passes
   `HOME_TAB_WAKE_FRAMES` = 3, one frame after the accessibility swap. Lifecycle, producers, cameras and
   `LocalApprovedLauncherPageActive` readers follow the woken tab; input closes on the switch itself and opens once
   the page is awake. Other callers (device checks, preview) keep immediate activation.
5. (v3, unmeasured) The page that slid out stays awake until its slide settles (`launcherPageAwake`,
   `LauncherPageWake`), so the incoming wake and the outgoing sleep no longer share frame +3 (on v2 benchmark that
   frame was 40–73 ms on Home → Apps: Home's widgets sleeping plus Apps' grid waking).

| Frames over 32 ms (8 switches each) | official 1.317 | v1 | v2 |
|---|---|---|---|
| → Apps: total / worst median ms / switches with none | 15 / 74 / 0 | 18 / 61 / 0 | 14 / 42 / 0 |
| → Tools | 12 / 47 / 0 | 10 / 38 / 1 | **2 / 24 / 6** |
| → Home | 15 / 63 / 0 | 16 / 68 / 0 | 14 / 43 / 1 |
| Sum of frames over 32 ms per switch, median ms (Apps / Tools / Home) | 126 / 61 / 104 | 117 / 38 / 124 | 78 / 0 / 81 |

- Memory after leaving: unchanged (PSS 228 MB / graphics 50.8 MB on both).
- v1 alone barely moved Apps and Home: with the screen and dock no longer composing, the pages' activation
  dominated the switch frame. v2 moves it: Tools is fixed (6 of 8 switches with no frame over 32 ms, worst median
  24 ms); Apps and Home keep a long switch frame but the worst median fell 74 → 42 and 63 → 43 ms.
- What is left (v2 benchmark): **Home ←** switch frame main thread ~41 ms: re-recording the header (Customize
  appears, every header button's vector icon is re-drawn, 9 ms), measure 9 ms (Customize's text laid out afresh,
  `TextStringSimpleNode.measure` ~5 ms), composition 8 ms; RenderThread 18–32 ms per slide frame (two full pages
  drawn, no cached layers). **→ Apps:** the wake frame (+3, both pages changing state), addressed by change 5.
- Accessibility (uiautomator dump of Home, Apps, Tools on v1/v2 vs 1.317): same nodes (124 / 106 / 151), order,
  classes, bounds, selected and clickable flags; differences are the clock, the temperature and the R8-renamed
  ComposeView class. Events (`uiautomator events` around each switch): the same types (VIEW_SELECTED, a
  WINDOW_CONTENT_CHANGED, then SUBTREE batches); VIEW_SELECTED now arrives ~25 ms later, after one SUBTREE batch from
  the outgoing page's motion (it stays in the tree for two frames).

**Against the target (no tab-switch frame over 32 ms): met for Tools (v2), not for Apps and Home.** Next: measure v3;
keep Customize composed (zero-width, unplaced off Home) so its text is not re-laid out; give the header buttons
their own layers so a header re-layout does not re-record every icon; cache each page as a layer during the slide
(RenderThread). Portal Mini (800×1280): same code paths (`LauncherScreen`'s compact header is in the same
`HomeRecomposeScope`, same dock and tab host); not measured.

### v3 measured: official 1.322 (= v2) vs `home-tabs-v3` (1.322 + change 5), 2026-11-22

**Device:** Family Portal+ (2160×1440), 03:23–03:31 PST. Plain `adb install -r`, no force-stop, STREAM_MUSIC 3 → 0
→ 3, framestats only (no screenshots, recordings or traces), only the tab bar labels tapped. Ended on official 1.322
(on-device sha256 `b3140a96…` = `hearthpane-latest.json`), Home on top and default, dev off.

**Builds:** official 1.322 (contains v2, sha `b3140a96…`); `home-tabs-v3` = local main `b713403d` + cherry-pick of
`ca7cb42f` (`assemblePerformance`, sha `b1d09cfc…`; `testDebugUnitTest` green, `LauncherPageWakeTest` 5 tests).

**Method:** as above (warm-up cycle, 2 rounds × 4 × (Home → Apps, Apps → Tools, Tools → Home), `gfxinfo reset`
before each tap, HomeActivity `framestats` 2.5 s after; frame = FrameCompleted − IntendedVsync, Flags 0), run in
the order official, v3, v3, official so each build has one pass on either side: 16 switches per direction per build.

| Frames over 32 ms (16 switches each) | official 1.322 (v2) | v3 |
|---|---|---|
| → Apps: total / worst median ms / switches with none | 28 / 42 / 3 | **15 / 35 / 6** |
| → Tools | 2 / 25 / 14 | **0 / 22 / 16** |
| → Home | 29 / 48 / 1 | 30 / 44 / 0 |
| Sum of frames over 32 ms per switch, median ms (Apps / Tools / Home) | 81 / 0 / 89 | **35** / 0 / 86 |

Per pass (8 switches; frames over 32 ms Apps / Tools / Home): official 16 / 2 / 16 and 12 / 0 / 13; v3 9 / 0 / 16 and
6 / 0 / 14. The official baseline reproduces the 1.317 → v2 column above.

- **→ Apps: v3 halves the long frames** (28 → 15; per-switch over-32 ms time 81 → 35 ms median, in both passes):
  splitting Home's sleep and Apps' wake onto different frames removes the +3 wake-frame stall. Not yet at target
  (worst median 35 ms, 6 of 16 switches clean).
- **→ Tools:** unchanged within noise (already at target on v2; v3 had no frame over 32 ms in 16 switches).
- **→ Home:** no change (29 vs 30 frames, worst median 48 vs 44 ms): its remaining cost is the switch frame (header
  re-record and Customize measure, above), which change 5 does not touch. No regression in any direction.

**Verdict:** v3 is a clear win for → Apps with no regression; ship it. Next for → Home: the header items listed above
(keep Customize composed, header button layers), then page layers during the slide.

## Screensaver doodle tiles in every mode, and Gallery Wall upload warm-up (branch `ss-tiles`)

**Status: partial.** Device work was stopped early at the owner's request: the test source had been on the Family Portal too long. The doodle-tile part is measured. The Gallery Wall warm-up part has one household change and an untraced remainder.

### 1. Every doodle is a pre-rasterised tile

**Users found.** The tiled SVG doodles were still painted by Diptych (`.ss-frame`, `styles.css:38`), Framed (`.ss-room` / `.ss-art`, light, `:56`), Mosaic (`.mosaic-wall`, light, `:104`), Panorama (`.panorama-drift`, `:147`), Polaroid (`.ss-table::before`, `:156`) and Cinema (`.ss-scope`, `.cinema-layer`, `.ss-bar`, `:198`). Hero + Sidebar and Memories never paint a doodle, so they are unchanged.

**Change.**
- Each rule above now uses the Gallery Wall `image-set` tile (dark), or new light tiles `photo-doodles-{480,540,960}.png` (12–30 KB, transparent, from `photo-doodles.svg`).
- `scripts/generate_doodle_tiles.mjs` now renders both themes. A rerun gives the existing dark tiles byte for byte.
- Colours, tile size (480 × 360 CSS px), repeat, position and layout are unchanged.

**Tests.** `node --test scripts/tests/*.test.mjs scripts/tests/*.test.cjs`: 540 tests, 521 pass, 19 browser tests skipped, 0 fail. `:app:testDebugUnitTest` passes.
- Each affected mode's golden hash (`slideshow_photo_motion.test.mjs`) is unchanged once each tile value is mapped back to the SVG it replaced (`vectorDoodleSnapshot`). So the doodle source is the only thing that changed.
- `slideshow_gallery_doodle.test.mjs` adds two tests. The light candidates are RGBA PNGs at 480 × 360 × scale. No style or mode references a doodle SVG, and all 7 doodle rules use one of the two tile values at 480 × 360.
- `slideshow_framed_lifecycle.test.mjs` pins the light tile.

**Visual check** (desktop Chromium, real `styles.css` from `main` vs branch, each doodle-bearing rule full screen; channel values; device scale = dpr 1.125):

| Mode | dpr 1.125 identical / max diff | dpr 1 | dpr 2 |
|---|---|---|---|
| Diptych | 99.4 % / 6 | 99.2 % / 1 | 99.7 % / 3 |
| Framed | 99.0 % / 5 | 99.3 % / 1 | 99.6 % / 3 |
| Mosaic | 99.3 % / 6 | 99.2 % / 1 | 99.6 % / 3 |
| Panorama | 99.4 % / 6 | 99.2 % / 1 | 99.7 % / 3 |
| Polaroid | 99.4 % / 7 | 99.2 % / 1 | 99.7 % / 4 |
| Cinema | 98.8 % / 6 | 99.2 % / 1 | 99.6 % / 4 |

- The differing values are doodle-stroke edges: mean difference about 1 / 255, and ≤ 0.025 % of values differ by more than 2.
- Cinema at dpr 1.125 is 98.8 %. Its scope starts at 11 % of the height, a fractional device-pixel phase, so the tile is resampled where the vector was rasterised in place.
- No device screenshots were taken. Scratch renders were deleted.

**Microbenchmark.** Test source pinned per mode at 15 s, generated scenes, 6 changes forced by a left swipe (`next()`), `photo_beat` attribution (0.5 s before to 3 s after), start beat excluded. O = official 1.317 (sha256 verified), B = branch, one run each.

| Mode | O worst frame per change, ms | B worst | O frames > 60 ms (total) | B | O sum > 32 ms per change (mean) | B |
|---|---|---|---|---|---|---|
| Diptych | 43–56 | 25–61 | 0 | 1 | 137 ms | 68 ms |
| Framed | 36–61 | 25–37 | 1 | 0 | 130 ms | 46 ms |
| Mosaic | 34–51 | 22–51 | 0 | 0 | 129 ms | 78 ms |
| Panorama | 31–72 | 24–48 | 2 | 0 | 127 ms | 129 ms |
| Polaroid | 58–94 | 70–91 | 15 | 16 | 282 ms | 302 ms |
| Cinema | 55–81 | 33–59 | 10 | 0 | 234 ms | 135 ms |

- Cinema paints the doodle on three full-width layers, and it gains the most: no frame over 60 ms, against 10 on official.
- Diptych, Framed and Mosaic roughly halve their sum of frames over 32 ms.
- Panorama is within noise.
- Polaroid is unchanged. It paints the doodle once on a full-screen pseudo-element, so its change cost is elsewhere (most likely the 64 px print shadow; not traced).
- These are single runs. The household long-window A/B was not run.

### 2. Gallery Wall on household photos: upload warm-up

**Trace** (household shuffle, real dream, atrace gfx/view/webview/sched/freq/memreclaim, branch build whose Gallery Wall equals 1.317). At the wall's first draw, two `WebViewFunctor::drawGl` frames took 75 and 125 ms (`DrawFrame` 80 and 131 ms). `Chrome_InProcGpuThread` ran 25 / 40 / 65 / 57 ms in four consecutive 100 ms buckets, about 190 ms of raster and upload for the ten tile photos.
- In the same window `kswapd0` ran ≤ 4 ms per 100 ms, and there were two direct reclaims of under 1.5 ms each. Memory pressure is not the cause.
- The box-shadow was not implicated: no frame was long after the upload burst inside the 0.6 s the trace covered.

**Change** (`cf8f02b4`).
- `lib-engine.js:225–270` adds `SS.warmImages`. Before a wall is published, each photo is drawn once, one per 2 frames (`SS.WARM_FRAMES_PER_IMAGE`), at its contain size (`containBox`, `mode-impls.js:918`).
- The photos are drawn in a 1 px window at 0.001 opacity, below one 8-bit step (`.ss-warm`, `styles.css:241–243`, the same window as the unmerged `engine-upload`). So the wall's first frame finds them uploaded.
- In Gallery Wall (`mode-impls.js:1008–1018`) the warm window goes at the first tile swap or at cleanup (`:924`). The wall publishes about 20 frames later. Transitions, timing and layout are unchanged, and the Gallery golden hash is unchanged.
- New `slideshow_gallery_warm.test.mjs` (7 tests): one photo per 2 frames at its drawn CSS size; release mid-way; abandonment; no body; the hidden-window CSS; warm before render, released at swap and cleanup; `containBox`.

**Result so far.**
- Household, one Gallery Wall change on the branch: worst frame 87 ms, 7 frames over 60 ms, 29 over 32 ms, 1.49 s summed. On 1.316 (`gallery-wall` B run 4) the two changes were 143 / 103 ms, 11 / 5, 32 / 36 and 1.76 / 1.69 s.
- So the 100+ ms first-draw frames are gone in that one sample. The sustained 33–87 ms frames through the fade remain.
- Pinned Gallery Wall micro (generated scenes, 6 forced changes): official 1.317 worst 45–81 ms, 7 frames over 60 ms, 429 ms mean summed over 32 ms. Branch 41–69 ms, 6 over 60 ms, 466 ms. That is within noise: the generated scenes are small and cheap to upload.
- Both builds had 2 of 6 changes with 19–22 frames over 32 ms. The sustained-frame signature reproduces on generated scenes, so the next trace can use the pinned micro and needs no household dream.

**Not done.** The 1.5 s of 33–87 ms frames through the fade is not traced. Two household trace dumps lost their buffer (a 24 MB circular buffer that overflowed, and a dump that came too early). A pinned-micro trace was started and then stopped when device work was halted. The memory-release question (is the old wall freed before the new one uploads?) was not measured. The warm-up should not merge until a trace shows where the sustained frames go and a repeat household A/B confirms it.

**Device** at the end: official 1.317 (on-device sha256 `4db51aa3…`, matching `hearthpane-latest.json`), Home on top and default, test source off (`active: false`), dev mode off (`fleetctl info`: `devMode: false`), STREAM_MUSIC 3, lock released.


> Note: only the doodle-tile part (56652441) of `ss-tiles` shipped; the Gallery Wall warm-up (cf8f02b4) is not merged.

## Polaroid print shadow as a 9-slice bitmap (branch `polaroid`, desktop only)

**Status:** desktop measurement, then a short on-device A/B (see *On device* at the end of this section).

**Attribution** (headless Chromium 1223, real engine assets from `main`, Polaroid pinned, 12 generated scenes, seeded `Math.random`, viewport 1920 × 1280 CSS at dpr 1.125 = 2160 × 1440 device px, CPU throttle 6×, software raster; 6 changes forced with `next()` 2.5 s apart; trace `cc` / `devtools.timeline`; `RasterTask` time summed over the 6 changes; 4 interleaved rounds):

| Variant | Raster ms, 6 changes (median, range) | Per change |
|---|---|---|
| Current (`main`) | 983 (761–1098) | ~164 ms |
| No print shadow (`box-shadow: none`) | 403 (355–493) | ~67 ms |
| Shadow kept, no tilt (`transform: none`) | 405 (393–505) | ~68 ms |
| Branch: 9-slice bitmap shadow | 587 (508–697) | ~98 ms |

- The cost is the blurred shadow **on a tilted print**. Either one alone is cheap. An upright blurred rounded rectangle uses Skia's cached nine-patch. A rotated one misses it and is blurred again in full (64 px blur, about 96 px reach) on every raster of every tile it touches. Each change re-rasters the tiles under the incoming and outgoing prints, so the neighbours' shadows are blurred again too.
- The doodle is a minor part: with `.ss-table::before` hidden, raster is 756 against 949–957.
- Keeping every print on its own layer (`will-change: transform`) cuts raster to about 245 ms. But it gave 71–98 frames over 32 ms against 0–1 for `main` (7 composited layers under throttle), and the layer memory is large. Rejected.
- Main-thread `Paint` is 5–20 ms per run in every variant. The cost is raster, not recording.

**Change.**
- `styles.css:157–170`. `.ss-polaroid` drops `background` and `box-shadow`, and gets `isolation: isolate`.
- `::before` (`z-index: -1`) draws `polaroid-shadow-{480,540,960}.png` as a `border-image` (`40% / 192px stretch`, no `fill`, because the paper covers the middle slice) over the box-shadow's reach: −80 / −80 / −50 / −110 px. It has `opacity: .75`.
- `::after` is the paper (`#fbfaf6`, radius 4 px). The photo and caption are unchanged and sit above both.
- The tiles are made by `scripts/generate_polaroid_shadow.mjs`. Chromium casts the same shadow at full strength (`rgba(0,0,0,1)`) from a 320 × 320 print 10000 px off-tile, so the capture also covers the part under the print. A rerun gives the same files byte for byte. They are RGBA PNGs of 21–52 KB.
- Applying the 0.75 as layer opacity, not baking it into the tile, matches the live shadow's rounding much better: 97.4 % against 86.8 % identical values.
- `mode-impls.js:1296–1298, :1334`. A print under 224 px on a side keeps the live box-shadow (`.ss-live-shadow`). At that size the shadow can no longer hold both 96 px blur ramps, and a 9-slice would scale its corners. On the Portal+, prints of ordinary photos are 296–640 px, so all of them use the bitmap. Very wide or tall photos (10 : 1, 1 : 40) and the Mini's portrait prints (~203 px at 800 × 1280) keep the live shadow.
- Transitions, timing, selection and layout are unchanged.

**Visual check** (desktop Chromium, `main` vs branch, the same seeded layout, full-screen screenshots at rest and after one change; channel values):

| dpr | At rest: identical / max diff / mean | After a change |
|---|---|---|
| 1.125 | 97.4 % / 3 / 0.027 | 97.2 % / 2 / 0.028 |
| 1 | 97.0 % / 3 / 0.030 | 96.7 % / 3 / 0.033 |
| 2 | 96.5 % / 3 / 0.035 | 96.2 % / 3 / 0.039 |

- No value differs by more than 3, and 0.000 % differ by more than 2. The differing values are thin contour lines in the shadow ramp, where a rotated, resampled 8-bit ramp crosses a rounding step at a slightly different place than Skia's live blur.
- This is below the 99 % the doodle tiles reached, because the shadow covers about a third of the screen with a smooth gradient, where the doodle was sparse strokes. An exact match would need the live blur.
- Scratch renders were deleted.

**Tests.** `node --test scripts/tests/*.test.mjs scripts/tests/*.test.cjs`: 537 tests, 518 pass, 19 browser tests skipped, 0 fail. `:app:testDebugUnitTest` passes.
- New `slideshow_polaroid_shadow.test.mjs` (4 tests): no live shadow or paper on the print rule; the 9-slice geometry, opacity and three RGBA candidates; the exact live fallback rule; the fallback class matches the < 224 px rule across 5 screen sizes, and no Portal+ print falls back.
- The Polaroid golden hash (`slideshow_photo_motion.test.mjs`) is unchanged once the print's declarations are mapped back to the reviewed paper and box-shadow (`printShadowSnapshot`). So only the shadow and paper source changed.

**Caveats.** Desktop raster here is software. The Portal+ WebView uses GPU raster, where a rotated blurred rounded rectangle also misses the analytic or cached path, but the size of the saving there is not measured. The machine was shared with another Gradle build, so absolute numbers vary by about ±25 % between rounds. The ratios held in every round: the bitmap removed 62–74 % of the shadow's raster cost.

**On device** (Family Portal+, 2026-11-22 01:11–01:15 PST, device lock held 4 min 28 s). The operator test source was pinned to `polaroid` at a 15 s beat, so only generated scenes were on screen. 6 changes were forced by a left swipe (`next()`), 12 s apart. framestats were polled throughout, and each `photo_beat` was attributed from 0.5 s before to 3 s after. The start beat is excluded. Flags 0 frames, IntendedVsync → FrameCompleted. O = official 1.320 (`hearthpane.apk`, sha256 `867264f6…`, verified on the device). B = the branch rebased on `main` 1.321, a `performance` build. 1.320 → 1.321 changes no screensaver asset or code. Each build got Home and 15 s idle before its run. One run each: B first, then O. No audio was played.

| Per change (6) | O worst frame, ms | B worst | O frames > 60 ms | B | O frames > 32 ms | B | O sum > 32 ms | B |
|---|---|---|---|---|---|---|---|---|
| 1 | 110 | 79 | 3 | 3 | 5 | 4 | 399 | 259 |
| 2 | 81 | 82 | 4 | 3 | 4 | 3 | 290 | 221 |
| 3 | 79 | 65 | 3 | 2 | 5 | 3 | 298 | 181 |
| 4 | 77 | 74 | 3 | 3 | 5 | 5 | 287 | 278 |
| 5 | 74 | 72 | 3 | 3 | 5 | 4 | 295 | 237 |
| 6 | 62 | 76 | 1 | 3 | 4 | 4 | 219 | 260 |
| **Total / range** | 62–110 | 65–82 | 17 | 17 | 28 | 23 | mean 298 | mean 239 |

- The summed time over 32 ms per change drops by about 20 % (298 → 239 ms), and 5 fewer frames go over 32 ms. The worst frame no longer reaches 100 ms (110 → 82).
- Frames over 60 ms are unchanged (17 and 17). Each change still has 2–3 long frames. So the 62–74 % raster saving measured on desktop software raster does not carry over in full to the Portal+'s GPU raster. The rest of the change cost is elsewhere (likely the photo upload and the rotated layer composite; not traced).
- O matches the earlier Polaroid run on 1.317 (58–94 ms, 15 frames over 60 ms, 282 ms).
- These are single runs, about 6 changes each. A difference of about 20 % in the summed time is within the spread seen between rounds elsewhere in this doc. The household long-window A/B was not run.

**Visual check on device** (branch, one screenshot, taken only after confirming that `PhotoFramePreviewActivity` was on top, the test source was active and pinned to `polaroid@…`, and the frame showed "TEST SCENE … generated"; 2160 × 1440, inspected at 100 % crop, then deleted). The shadow ramps are smooth on every print, with no 9-slice seams, no stretched or doubled corners, and no hard edge where the ramp meets the table. The tilt is drawn cleanly. The paper corners are rounded and the shadow follows them. The bottom row's paper looks greyer than the top row's. That is the global `#chrome-scrim` bottom gradient (`styles.css:232–233`), which this branch does not change. No screenshot of official was taken, so this check is against the desktop comparison above, not against a device capture of `main`.
