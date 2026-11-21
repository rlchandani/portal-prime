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
