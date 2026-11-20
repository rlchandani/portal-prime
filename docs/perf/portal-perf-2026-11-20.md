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
