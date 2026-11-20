# Portal performance track — 2026-11-20 (perf-3)

Family Portal+ (<family-serial>, Android 10, 2160×1440). Performance build + its `.dm`
(`dexopt reason=install-dm` and the APK sha checked before every run). gfxinfo framestats
(IntendedVsync → FrameCompleted, all launcher windows) plus SurfaceFlinger missed frames;
perfetto (gfx/view/dalvik/am/wm/binder) for attribution. "Warm" excludes each run's first repeat.
The Mini was not on USB, so this round has no Mini numbers. The Tools change is the same code
in the compact layouts.

Family self-updated twice during the run (1.273, then main moved to 1.274), so the final
before/after uses builds from main 1.274 (`b4`) and perf-3 merged with 1.274 (`f1`).

## Survey (worst first, warm repeats)

| # | Interaction | What hurts | Cause (trace) |
|---|---|---|---|
| 1 | Tools › Reminders open **and** Back | 2 frames of 85–214 ms on every visit, both ways | Opening a tool disposes the library and composes the workspace; Back does the reverse. ~125 ms of main-thread composition/measure, no JIT involved |
| 2 | Cold start after reboot | Home `Displayed +3.57 s`; first frames 2434 / 1185 / 199 ms; 12 of 16 frames > 32 ms | Not attributed this round |
| 3 | Screensaver start (operator test source) | 297 ms, then 3 × ~500 ms at 2.7 s, 83–293 ms frames at 4.3–5.1 s; each photo change (~30 s) 145–168 ms, then ~4 s of 27–30 ms frames | RenderThread `WebViewFunctor::drawGl`, not the main thread (no main-thread frame > 60 ms in a 32 s trace) |
| 4 | Control Center open / Back | open: 2 frames of 99–136 ms; Back: 84–116 ms; Outdoor floor: max 50–62 ms, sustained ~27 ms | CC's own first frames. CC (sysUI 0x0) also clears Home's HIDE_NAVIGATION |
| 5 | Calls open / Back | Calls' first 2 frames 90–105 / 73–88 ms, overlapping a Home frame of 79–122 ms; Back 56–103 ms | Activity launch: ~45 ms of system_server binder calls (window layout, lock contention); Calls' first traversal ~65 ms; on Back Home recreates its surface and re-records (14 + 18 + 23 ms) |
| 6 | Settings open | Settings' first frame 55–76 ms; Home draws 2 frames as Settings covers it, the second 73–79 ms (the remaining "frame 2") | Home-side work under Settings |
| 7 | Home tab switches | 2 frames of 40–55 ms per switch | one ~35 ms main frame (21–25 ms activation recomposition) + a 33 ms `queueBuffer` stall |
| 8 | Music open | one 42–63 ms frame (102–204 ms on the first open) | — |
| 9 | Night switch | 2 frames of 50–58 ms, then the night clock settles at ~30 fps | mild |
| 10 | Home swipe | warm max 16–19 ms | fine |
| 11 | Jarvis card open | every frame ≤ 14 ms | fine |
| — | Flip's first turn | not measured: the Flip face draws through a WebView, giving one present per minute on its layer and nothing in gfxinfo | needs a WebView-side probe |

Memory (PSS, launcher process): Home idle 190–212 MB (gfx 50 MB); Control Center open
221–262 MB; **after leaving CC 235–254 MB**, with gfx staying at ~75 MB, so CC's graphics
memory is not released. Screensaver running 315 MB (gfx 104 MB). 153 MB 60 s after a cold boot.

## Fix kept

`perf: Tools keeps the library and an open local tool composed across Back` (ba86f190).
The library stays composed, hidden, under an open tool, and a local tool's workspace (alarm,
timers, countdowns, converter, lamp, reminders) stays composed under the library. The hidden
layer follows the launcher's retained-tab pattern: alpha 0, no touch/focus/semantics, page
lifecycle CREATED. Camera, audio and network tools still leave on Back.
Functional check on Family: library → tool, rail switch, Add reminder sheet, sheet-first Back,
Back to the library and Home; the hidden layer is absent from the accessibility tree.

## Before / after (Family, 1.274 base `b4` vs perf-3 `f1`, warm, ms)

| Interaction | Before: median max (worst), frames > 32 per visit | After |
|---|---|---|
| Tools › Reminders open | 114 (131), 2 | **30 (40), 0** |
| Tools › Reminders Back | 108 (146), 2 | **32 (41), 0** |
| First Reminders open in a session | 145–214 | 145 (unchanged: first composition) |
| Tab Apps / Tools / Home | 53 / 42 / 46 | 48 / 28 / 46 |
| Calls open / Back | 111 / 73 | 94 / 66 |
| Music open / Back | 50 / 25 | 42 / 26 |
| Settings open: first frame / following max | 66 / 75 | 55 / 65 |
| CC Back after a Tools › Reminders visit (A/B, 2 rounds × 6) | median 75 (89) | 61 (109) — no regression |

Pixel diffs (Tools library, Reminders, library after Back, Reminders reopened; header with the
clock blacked out): **100.0000 % identical** on 1.273 and again on 1.274. Full unit tests and
`:app:assemblePerformance` pass on the merged branch.

## Tried, not kept

- Baseline-profile rules for Calls*, CallScreens*, ToolsReminders*, Reminder*, ApprovedToolsEveryday:
  no measurable change (Reminders 64–149 ms, Calls 66–148 ms). With the `.dm` installed the JIT
  is idle in these frames; the cost is real composition.
- Control Center requesting Home's hidden navigation bar (as Settings does): Home's flags stay
  intact (verified in `dumpsys window`), but CC open/Back frames didn't change (100–136 / 89–116 ms).

## Next

1. Cold start: attribute the 2.4 s / 1.2 s first frames (trace from `bindApplication`).
2. Screensaver: the WebView photo transitions (RenderThread `drawGl` 100–500 ms at start and on each change).
3. Control Center: first frames on open/Back, the Outdoor floor's sustained ~27 ms frames, and
   graphics memory kept after leaving.
4. Settings: Home's second frame (73–79 ms) while Settings covers it.
5. Calls: Home's surface recreation on Back; Calls' first traversal.
6. First Reminders open of a session (145 ms): compose the workspace hidden at idle, as the tabs prefetch.
7. Mini portrait/landscape survey when it is on USB.

---

# Round 4 (perf-4)

Family Portal+ (`<family>`), performance build plus its `.dm`. Before every run I checked `reason=install-dm`
and the APK sha. Cold starts are real reboots with no force-stop. Attribution came from:

- perfetto, started at the boot's first adb connection, with `am wm dalvik view gfx binder res` and sched
  blocked reasons;
- `simpleperf --trace-offcpu` on a scratch `profileable` build, for stacks.

The numbers come from untraced reboots. Main moved during the round (1.276 → 1.277 → 1.278), and each
before/after pair uses the same base. While the round ran, debloat-family disabled 5 Meta packages on Family.
The final before/after table below is **debloated on both sides**. The earlier stock A/B runs (1.276/1.277)
show the same direction. The Mini was not on USB.

## Cold start: what the trace showed (1.276, before)

From the launcher's process start to Home's first frame took 2.7 s. The main thread spent most of that
waiting on storage, not computing:

| Main-thread phase | ms | of which I/O wait (DK) |
|---|---|---|
| `bindApplication` (providers, androidx Startup, `Application.onCreate`) | 1792 | 1171 |
| `activityStart` + `activityResume` | 129 | 48 |
| first `Choreographer#doFrame` (first composition; measure 270 ms) | 755 | 183 |
| `FleetAgentService` create, right after the first frame | 237 | – (229 ms asleep in NSD) |

- **The I/O wait is page faults into our own compiled code.** The files are `base.odex` (21.7 MB) and
  `base.vdex` (11.5 MB). simpleperf's off-CPU stacks end in ART's `LinkMethod`, `CodeInfo::Decode` and
  `FixupStaticTrampolines`. After a reboot the page cache is empty, so every class the main thread links
  costs random 4 KB reads from the eMMC.
- **Profile coverage is not the problem.** After boot the runtime profile has only about 1.1 k methods
  that the `.dm` doesn't, and the interpreter frames sit on top of compiled code.
- **`RemoteDiscovery.start` blocks the main thread for about 220 ms** while `NsdManager` makes its first
  connection. This runs right after Home's first frame, so it held Home's second frame.

## Fixes kept

1. `perf: warm the page cache with our compiled code on a cold start` (e8eda424).
   - `ImmortalApp.attachBaseContext` starts one daemon thread that reads `base.odex` and then `base.vdex`
     sequentially.
   - In the trace it reads 33 MB in about 0.5 s, and `bindApplication` falls from 1.8–2.6 s to about 1.15 s.
2. `perf: start remote discovery off the main thread` (4ed16144).
   - Start and stop run on one process-wide thread, so they stay in order.
   - `FleetAgentService` create falls from 237 ms to 14–39 ms.
3. `perf: Tools composes the Reminders workspace hidden ahead of its first open` (830426bb).
   - After the Tools tab's own idle prefetch, Tools waits 1 s, then at the next idle composes the Reminders
     workspace as the hidden kept layer. This is the same layer a first visit leaves behind.

## Before / after (Family, debloated, 1.278 base vs perf-4 merged with 1.278, 3 reboots each, median (range))

| Interaction | Before (1.278) | After (perf-4) |
|---|---|---|
| Home `Displayed` after reboot | 2321 ms (2047–2330) | **1783 ms (1710–1809)** |
| First frame, gfxinfo (IntendedVsync → completed) | 1396 ms (1255–1505) | **976 ms (855–1002)** |
| Second frame | 891 ms (838–993) | **520 ms (470–631)** |
| First frame on screen, after process start | 2018 ms (1739–2020) | **1514 ms (1453–1534)** |
| First Reminders open in a session (1 reboot each, stock, 1.277) | 139 + 95 ms | **36 + 33 ms** |

The stock runs on 1.276/1.277 point the same way:

- `Displayed`: 2.37–4.00 s before, 1.96–2.12 s after (one outlier at 3.3 s).
- First frame: 1.49–2.35 s before, 0.97–1.14 s after.

On the targets:

- **Home usable under 2 s: met.**
- **First frame under 800 ms: not met.** Its IntendedVsync still falls during `Application.onCreate`, because
  something there requests a Choreographer frame, so the gfxinfo number includes about 0.5 s of
  `bindApplication`.

Frames over 32 ms in the first 20 s stay at 10–14. The later ones are designed idle work and are not on the
first-frame path:

- the Apps/Tools tab prefetch (100–300 ms at about +3.5 s);
- Music's WebView prewarm (+5–6 s).

Pixel diffs (Tools library, Reminders, library after Back; header with the clock blacked out) are
**100.0000 % identical**. All 7423 unit tests pass (816 classes). `:app:assemblePerformance` passes on the
merged branch.

## Tried, not kept

- **Starting the prefetch earlier from an `appComponentFactory` and reading `shared_prefs` first.**
  - SharedPreferences loader I/O fell from 216 ms to about 40 ms, and the thread started about 80 ms
    earlier.
  - Across reboots, though, `bindApplication` and the first frame didn't change measurably (1150–1162 ms
    either way).
  - Not worth overriding androidx's component factory. A relative class name there resolves against the
    `.debug` applicationId and silently falls back.

## Findings without a change

- **Control Center memory (1.278).**
  - Home idle: HWUI GPU cache 8.3 MB, GL 5.7 MB. In CC: 17.9 MB. After Back (+10 s and +60 s): **17.9 MB,
    9.7 MB of it purgeable**, GL 14.5 MB, native +7 MB.
  - So CC leaves about 10 MB of Skia GPU cache plus a few MB of native heap behind, not 40 MB. Most of the
    PSS gap in round 3 was Java/native heap, which GC returns.
  - `onTrimMemory` can't help. `RUNNING_MODERATE` frees nothing in HWUI, and the platform won't send
    `UI_HIDDEN` while Home is in front (`Unable to set a background trim level on a foreground process`).
  - Releasing the 9.7 MB would need the hidden `WindowManagerGlobal.trimMemory`, which the codebase
    doesn't use yet, and the next CC open would pay a re-upload. Left for an owner decision.
- **Screensaver (shuffle, operator test source).**
  - Start: about 30 s of frames that are all over 16 ms, plus 134–303 ms frames at +1–2 s.
  - Each change: 3 frames of 476–486 ms, then about 3 s of 70–270 ms frames.
  - The long frames are `WebViewFunctor::drawGl`, during which **Chromium's in-process GPU thread runs
    411 ms of a 470 ms frame**. The main thread only waits on the sync.
  - `atrace webview` gives no sub-slices on this WebView, so the GPU work (image upload, raster of the
    incoming layer) needs Chromium tracing to attribute.
  - Moving the incoming layer's raster ahead of the fade would only move the stall: the outgoing Ken Burns
    motion is still running at that point.
- **Settings open (1.278).** In 2 traced opens, the main thread's largest frames were 78 ms (Settings' first
  frame) and 41 ms. Home's frames can't be separated from Settings' in the same process without window
  attribution. Not fixed.

## Next

1. **First frame under 800 ms.**
   - Find what posts a Choreographer frame during `Application.onCreate`: the stale IntendedVsync costs
     about 0.5 s on the metric.
   - Shorten the remaining `bindApplication`: androidx Startup EmojiCompat + ProcessLifecycle take about
     120 ms of I/O, and `Application.onCreate` installs take about 0.5 s.
2. **Screensaver:** Chromium-side tracing of the GPU thread at a photo change, then image tier/upload work.
3. **CC:** decide whether ~10 MB of reclaimable GPU cache is worth a hidden-API trim and a slower reopen.
4. **Settings and Calls Back:** per-window attribution of Home's frames.
5. **Mini survey** when it is on USB.

---

# Round 5 (perf-5)

Family Portal+ (`<family>`), debloated as in round 4, performance build plus its `.dm`. Before every run I checked
`reason=install-dm` and the APK sha. Cold starts are real reboots with no force-stop, and the numbers come from untraced
reboots. Main moved from 1.279 to 1.280 (split-home) during the round. The first A/B uses 1.279, and the final table
uses main 1.280 against perf-5 merged with 1.280. The Mini was not on USB.

## Cold start: what the trace showed

- **What requested the stale first frame: `ProfileInstallerInitializer`.** At +456 ms, inside `bindApplication`, it
  calls `Choreographer.postFrameCallback`. In the perf-4 trace that is a `scheduleFrameLocked` slice under
  `ProfileInstallerInitializer`, and it lands on exactly the first frame's IntendedVsync. Home's traversal joined that
  frame, so gfxinfo timed the first frame from inside `Application.onCreate`.
- **EmojiCompat and ProcessLifecycle** spent 132 ms in `Startup`, 121 ms of it I/O wait. App code reads neither: the
  dex shows only androidx-internal references. EmojiCompat can't load a font on a Portal anyway, because there is no
  font provider and no GMS.
- **`Application.onCreate` installs** took about 590 ms. The largest were DinnerCall 92, HouseholdList 63, Calling 45,
  HouseholdBus 38, MediaSessionReader 30, HearthpaneWake 29, SettingsSync 28, MusicHandoff 27, SharedTimers 26 and
  OtaUpdater 26 ms. Most of that time was page faults.
- **After both fixes** (traced 1.279 + perf-5):
  - `bindApplication` takes 794 ms, down from 1150.
  - The first doFrame (535 ms) still spends about 120 ms asleep waiting on SharedPreferences loader threads, and
    another 88 ms before it. Home's first composition reads about 17 prefs files from a cold eMMC.
  - The second frame's stale IntendedVsync comes from a frame request inside that long first doFrame. Its own doFrame
    takes 21 ms.

## Fixes kept

1. `perf: drop androidx Startup initializers from the cold-start path` (c87076e7).
   - Removes the InitializationProvider.
   - `BackgroundProfileInstall` writes the baseline profile from its own background-priority thread 10 s after
     startup. This keeps OTA/sideload installs compiled, with no frame request.
2. `perf: defer Application setup that Home's first frame doesn't need` (13a72589).
   - About 30 installs run after the first frame any of our activities draws, in their original order, one
     main-thread message per step (`DeferredStartup`). A 3 s fallback covers a process with no activity.
   - Still synchronous: CrashGuard, Fleet route registrations, guest mode, DreamPolicy, PresenceHub, Who's there,
     Hey Jarvis (its activity counter must see Home), rotation, status bar, and every alarm/timer re-arm.
   - FleetAgentService (sticky restart), both dreams, TimerReceiver and the Jarvis tool provider call
     `ensureStarted()` first.
   - Every boot-critical service still starts. Fleet, OTA, the household bus, doorbell and Control Center alerts start
     about 1 s after Home's first frame, and the fleet service was created at +1.95 s in the trace.

## Before / after (Family, debloated, 3 reboots each, median (range), ms)

First A/B on 1.279 (b0 = main, c1 = fix 1, c2 = fixes 1 + 2):

| | 1.279 | + initializers | + deferred setup |
|---|---|---|---|
| Home `Displayed` | 1859 (1736–2115) | 1792 (1700–1922) | 1627 (1558–1725) |
| First frame, gfxinfo | 896 (876–981) | 504 (447–628) | **474 (384–518)** |
| First frame on screen, after process start | 1489 (1475–1828) | 1657 (1484–1662) | **1371 (1233–1406)** |

Final, on 1.280 (main vs perf-5 merged with 1.280):

| | Before (1.280) | After (perf-5) |
|---|---|---|
| Home `Displayed` | 1745 (1673–1778) | **1638 (1606–1684)** |
| First frame, gfxinfo | 918 (875–918) | **367 (254–443)** |
| Second frame | 525 (444–535) | **413 (338–550)** |
| First frame on screen, after process start | 1480 (1390–1509) | 1397 (1306–2038) |

**First frame under 800 ms: met** (367 ms median on 1.280).

Pixel diffs (main 1.280 vs perf-5; header with the clock blacked out):
- Settings, Tools library, Reminders, library after Back, Reminders reopened, and Calls: **100.0000 % identical**.
- Music: identical outside the live sign-in code, which changes on every open.
- Control Center (cc_debug dry-run plan, whole frame blurred so no names are stored): no pixel differs by more than
  4/255 outside two live areas.

All 7431 unit tests pass (817 classes), and `:app:assemblePerformance` passes on the merged branch.

## Tried, not kept

- **Reading `shared_prefs` first in the startup prefetch.** This targets the 210 ms of SharedPreferences waits.
  On 1.280 (3 reboots), `Displayed` improved to 1552 ms, but the first frame rose to 406 ms (vs 367) and one run put
  its first frame on screen at +2.6 s. Not a measurable win. It matches round 4's appComponentFactory result.
- **Screensaver CSS probes**, injected live over DevTools on a scratch debuggable build: outgoing scene not frozen
  during the fade, and doodle SVG backgrounds removed. Neither was measurably better on natural beats, and keeping
  the outgoing scene running was worse (440 ms frames).

## Findings without a change

- **Screensaver photo change (Chromium trace, scratch debuggable build, test source only).**
  - The long frames are the RenderThread's `DrawFn_DrawGL` waiting in
    `BlockingSequenceRunner::Sequence::RunAllTasks::Wait` on the in-process GPU thread. That thread is in
    `RasterDecoderImpl::DoEndRasterCHROMIUM::Flush`, i.e. GPU raster of the new scene's tiles.
  - Image decode is cheap: `GpuImageDecodeCache::DecodeImage` totals 12 ms on a worker. `img.decode()` already runs
    before every promotion (`preloadDecoded`), so decode-before-swap is in place.
  - The 3 × ~480 ms pattern doesn't reproduce reliably. One natural beat gave 421/410/408 ms. Four more minutes of
    beats on the same build peaked at 108–181 ms. Beats fall about 30–60 s apart in shuffle, so A/B needs a pinned
    mode or a shorter interval.
  - Forced `immortalSlideshow.next()` calls are not a valid substitute. They queue behind a running fade and once
    stalled the engine.
  - The framestats buffer keeps only 120 frames, so a change has to be sampled by polling (`bursts.py`), not by a
    single dump.
- **Settings open (per window, 6 opens).** Home draws 2 frames as Settings covers it: 20–57 ms and then 25–69 ms
  (median about 64). Settings' own first frame is 44–149 ms.
- **Calls (per window, 6 visits).**
  - Back: Home's first frame takes 64–88 ms (median about 75), then 27–43 ms.
  - Open: Home keeps drawing 20–25 frames, mostly 8–20 ms, under Calls' opening. Isolated 56–195 ms frames happen
    in 3 of 6 opens.
  - Home's composition reloads `ImmortalSettings`/`WelcomeConfig`/`FleetConfig` on every `ON_RESUME`
    (`appsRefreshKey`), and the apps chrome bumps its revision on resume. These are the first candidates for the Back
    frame, but they need a traced attribution before any change, because they are how Home picks up Settings edits.

## Next

1. **Cold start:** the SharedPreferences waits (about 210 ms on the main thread) are now the largest block before
   Home's first frame. Find which files the first composition reads and either read them before `setContent` off the
   main thread or batch them. The second frame's stale vsync comes from a frame request inside the 535 ms first
   doFrame.
2. **Screensaver:** pin a mode (or shorten the interval) for a repeatable A/B. Then attribute the GPU-thread raster
   per mode with the CDP tracer (`disabled-by-default-gpu.service`).
3. **Calls Back / Settings:** trace Home's 75 ms Back frame and its second frame under Settings with app tracing
   (profileable scratch build), then gate the resume reloads on an actual settings change.
4. **Mini survey** when it is on USB.

---

# Round 6 (perf-6)

Family Portal+ (`<family>`), debloated, performance build plus its `.dm`. Before every run I checked `reason=install-dm` and
the APK sha. Cold starts are real reboots with no force-stop, and the numbers come from untraced reboots. Main moved from
1.282 to 1.283 to 1.284 during the round. Each A/B pair uses one base: 1.282 for the first cold A/B and the Home and
screensaver surveys, 1.283 for the final cold and galleryWall runs. perf-6 is merged with 1.284, and its full unit tests and
`:app:assemblePerformance` pass there. The Mini was not on USB.

## Fixes kept

1. `perf: start cold-start SharedPreferences loads in parallel from attachBaseContext` (165ce191).
   - The first composition opened about 20 prefs files one after another. Each load started only on its first read, so
     the main thread waited on them in series.
   - `StartupPrefsWarmup` starts one background thread in `attachBaseContext`, before the Application's own setup. It
     calls `getSharedPreferences` for the 22 files that setup and Home's first frame read.
   - Their platform loader threads then run in parallel with `bindApplication`, and the main thread's later calls get
     the same cached instances. Nothing is read or parsed by the warm-up itself.
   - This is not round 5's rejected prefetch, which read the files' bytes on the odex thread.
   - A unit test checks that every name in the list is still a prefs file with an owner.
2. `perf: operator test source can pin one screensaver mode at a fixed interval` (8dcbeb38, plus db6717f1 for the split
   `FleetActionApi`).
   - `screensaver_test_source` takes `{"mode":"<mode>","intervalSec":5..600}`. While the test source is active, the frame
     uses that mode and interval instead of the household's.
   - The pin expires with the source, and a plain enable or disable drops it. A bad mode or interval returns
     `400 bad_pin`. Release builds are unchanged.
   - Tooling: `perf-6/tools/sspin.sh <build> <tag> <mode>...` runs 75 s of polled framestats per mode at a 10 s beat,
     after a 12 s start.

## Cold start: before / after (3 reboots each, median (range), ms)

| | 1.282 | + prefs warm-up | 1.283 | + prefs warm-up |
|---|---|---|---|---|
| Home `Displayed` | 1641 (1611–1683) | **1488 (1351–1506)** | 1649 (1560–1654) | **1547 (1328–1559)** |
| First frame, gfxinfo | 458 (433–475) | **324 (280–374)** | 421 (401–516) | 444 (386–534) |
| Second frame | 543 (505–555) | **399 (361–427)** | 454 (432–546) | 505 (354–539) |
| First frame on screen, after process start | 1379 (1359–1382) | **1217 (1129–1229)** | 1372 (1324–1381) | 1256 (1067–2320) |

- Pooled over all 6 reboots per side: `Displayed` 1645 → **1497 ms**, first frame 446 → **380 ms**, second frame
  524 → **413 ms**, first frame on screen 1376 → **1240 ms**.
- `Displayed` improved on every reboot.
- The 1.283 pair is noisier on the first and second frames, and one reboot put the first frame on screen at +2.3 s
  (round 5 saw the same kind of outlier).
- Traced (1 reboot each, 1.282): the first `Choreographer#doFrame` falls from 446 to 393 ms, and its sleep time from 165 to
  104 ms. `bindApplication`'s sleep falls from 86 to 39 ms.

Pixel diffs (1.283 + pin vs perf-6 on 1.283):
- Tools library, Reminders, library after Back, Reminders reopened: **100.0000 % identical**.
- Settings: identical outside the OTA status line. 1.284 was released between the two captures, so it changed from
  "Up to date" to "Update available".
- Music: identical outside the live sign-in code.

## Screensaver: per-mode survey (1.282, pinned, 10 s beat, 75 s, generated scenes only)

| Mode | frames > 32 ms | > 100 ms | max | jank (Σ over 16.7 ms) |
|---|---|---|---|---|
| **galleryWall** | 49 | **26** | **332** | 7.3 s |
| heroSidebar | 50 | 7 | 120 | 5.6 s |
| memories | 134 | 2 | 113 | 35.5 s |
| diptych | 43 | 1 | 103 | 8.1 s |
| kenBurns / framed / push / panorama / polaroid / mosaic / cinema | 37–237 | 0 | 65–93 | 4–40 s |
| filmstrip (native) | 0 | 0 | 30 | 0.04 s |

The continuous-motion modes (kenBurns, framed, memories) sit at about 29 ms per frame for the whole run, i.e. ~34 fps
motion. That is their sustained cost, not photo-change spikes. Gallery Wall is the worst on photo changes: each swap
crossfades one cell, and it produces 4–5 frames of 100–330 ms.

## Tried, not kept

- **Gallery Wall: a permanent compositor layer per cell** (`will-change: transform` on `.gallery-wall .ss-gcell`).
  - A/B (2 rounds each): frames over 100 ms fell from 29/20 to **12/7**, jank from 7.98/7.71 s to **3.04/2.82 s**, and the
    median frame from 28 to 13 ms. GPU memory didn't rise (gfx 130–139 vs 142–143 MB).
  - **Not pixel-identical.** In headless Chromium renders of the same layout, shadows and photos move by 1–4/255 over
    about 11 % of the wall (max 7/255 in 10 px). This is layer resampling.
  - Left for an owner decision: `scratch-tmp/perf6/not-kept-gallery-permanent-layers.patch`.
- **Gallery Wall: a layer only while the cell dissolves.** A settled wall is bit-identical, but this gave no gain
  (26/24 frames over 100 ms vs 26/25). So the cost is rastering the incoming cell and its layer, not repainting the root.
- **Home frames.** simpleperf on a profileable `benchmark` scratch build (2 kHz, dwarf, main thread, 3 Calls Backs).
  Per Back, the main thread spent:
  - about 12 ms in `BarWatchService` → `readPinAppWindow` → `AccessibilityWindowInfo.getRoot()`. On API 29 this prefetches
    descendants, so Home's own main thread builds node infos for its Compose tree on each `TYPE_WINDOWS_CHANGED`;
  - about 5 ms recomposing `LauncherScreen` (the resume `appsRefreshKey`);
  - about 3 ms in `HearthpaneWake.evaluate`;
  - about 1.5 ms in `use24HourClock`.

  Two fixes removed those costs but didn't move the frames:
  - naming our own focused window without a root lookup while one of our activities is resumed;
  - a structural-equality resume snapshot (settings, greeting, room, clock format) that writes nothing when nothing
    changed.

  Per-window results, 6 visits each (1.282 / + own window / + both):
  - Calls Back, Home's first frame: medians 61 / 71 / 71 ms.
  - Under Settings, Home's max: 60 / 73 / 58 ms.

  Both are within noise. The saved CPU falls outside the measured frame. Patches:
  `scratch-tmp/perf6/not-kept-own-window/`, `scratch-tmp/perf6/not-kept-home-resume/`.

## Next

1. **Home Back frame (~60–75 ms):** the main-thread work above isn't inside it. Next is RenderThread / surface attribution
   (Home's surface recreation and re-record on Back, from round 3) with perfetto `gfx` plus `--trace-offcpu`.
2. **Gallery Wall:** the owner decides on permanent cell layers (2.7× less jank, not bit-identical). Otherwise pre-raster
   the incoming cell before its fade, e.g. a hidden layer one beat ahead.
3. **Continuous-motion modes** (kenBurns, framed, memories: ~29 ms frames throughout): check the Ken Burns layer size and
   the doodle tiling under motion.
4. **Cold start:** prefs loads now overlap. Next is the ~390 ms first doFrame itself (measure 270 ms in round 4).
5. **Mini survey** when it is on USB.
