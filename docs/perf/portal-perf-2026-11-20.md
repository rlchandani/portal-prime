# Portal performance track — 2026-11-20 (perf-3)

Family Portal+ (2221B01C7T01C0, Android 10, 2160×1440). Performance build + its `.dm`
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
