# Hearthpane on-device performance test: Family Area Portal+ (2026-11-09)

- **Device:** Meta Portal+ "Family Area" (`<family-serial>`): QCS605 (8 cores, Adreno 615), Android 10, 2160×1440 at 180 dpi, 2.8 GB RAM (about 700 MB available).
- **Build:** `com.immortal.launcher.debug` 1.239 (debug build, so expect release builds to be somewhat faster).
- **Method:** `dumpsys gfxinfo <pkg> reset` before each flow, then `dumpsys gfxinfo <pkg> framestats` after it.
  - Input came from `adb shell input`: taps, 180–300 ms swipes for flings, and 1.5 s swipes for slow drags.
  - The phase breakdown comes from the framestats of frames over 16.7 ms.
  - Logcat ran for the whole session (about 17 min).
- **Not done:** I changed no settings, played no music, and never captured Home.

## Headline

| Area | Result |
|---|---|
| Scrolling Settings pages | **Smooth.** Flings run at 0.2–3 % jank, p90 9–13 ms, p99 13–21 ms |
| Opening any Settings page or category | **Always janky.** Every navigation renders exactly 2 frames, both missing the deadline: p50 57 ms, up to 129–250 ms |
| Home widget-page swipe | Acceptable. 4–10 % jank, p90 13–15 ms, p99 20–32 ms. The slow frames are the first frames of each transition |
| Music → Library → Liked songs scroll | **Worst flow: 33–35 % jank**, p50 13–14 ms, p90 24–26 ms, p99 36–48 ms. Repeated twice with the same result |
| Music screen, first open | **One 850–1000 ms main-thread stall.** The second open is fine (p99 81 ms) |
| Memory (PSS) | 213 MB with Settings open → **307 MB** after Music: Graphics 80 → 112 MB, Native 20 → 46 MB, 1 WebView |
| CPU | Idle on Home about 0.1 % of one core. During page swipes about 13 % of one core (1.8 s of CPU over about 14 s) |
| Logcat | No StrictMode (not enabled), no ANR, no "Skipped N frames", no crashes. Background GC about every 2 min, freeing 7–10 MB (about 100 ms, concurrent; not a storm) |

## Baseline (Settings open, before test)

- **gfxinfo since process start:** 22,275 frames, 37.7 % janky, p50 13 ms, p90 23 ms, p95 25 ms, p99 61 ms, 20,822 "high input latency". That input latency count is inflated by adb-injected input and is not meaningful.
- **meminfo:** TOTAL PSS 212.7 MB.
  - Java 15.4 MB, Native 20.0 MB, Code 66.1 MB, Graphics 80.4 MB.
  - 2 activities, 3 ViewRootImpls.
- **top:** 0 % CPU, RES 154 MB.

## Settings: page open (first frame after tapping a row)

| Page | Frames | Worst frame (ms) | Notes |
|---|---|---|---|
| Display | 4 | 81 | |
| App appearance | 2 | 129 | |
| Wallpaper | 4 | 93 | decodes the built-in photo thumbnails |
| Photos & screensaver overview | 2 | 73 | |
| Slideshow | 3 | 150 | LazyColumn page |
| Photo clock faces / Digital clock | 2 | 73 / 85 | |
| Weather / Calendar / Quick buttons / Device name / Diagnostics | 2 | 57 each | the floor cost of any navigation |
| Alarm / Music | 2 | 93 / 93 | |
| Sleep timer | 2 | 77 | |
| Intercom | 2 | **250** | compose 99 ms + draw 41 ms |
| Welcome to Hearthpane (HelpActivity) | 4 | 150 | UI thread busy for 113 ms before the first frame |
| Sidebar category switching (8 categories ×2) | 32 | p50 57 / p90 77 / p99 101 | **100 % janky.** Each switch = 2 frames |

Mean phase cost per slow frame during sidebar switching:
- UI thread busy before the frame starts: 19.7 ms (max 70)
- Input + animation, i.e. composition: 17.2 ms (max 49)
- Draw/record: 11.6 ms (max 45)
- GPU: 6.7 ms

## Settings: scrolling

| Page (scroll type) | Frames | Jank % | p50 | p90 | p95 | p99 | Notes |
|---|---|---|---|---|---|---|---|
| Slideshow, first slow scroll through 5 screens | 731 | **20.0** | 11 | 22 | 24 | 31 | LazyColumn composes rows as they enter |
| Slideshow, fling ×12 (run 1 / run 2) | 650 / 695 | 1.7 / 3.2 | 7 / 8 | 12 / 13 | 13 / 15 | 21 / 20 | slow frames are GPU-bound (13.6 ms issue+swap) |
| Welcome greeting, slow scroll | 528 | 3.0 | 8 | 13 | 15 | 19 | non-lazy Column |
| Welcome greeting, fling ×12 (×2) | 611 / 616 | 0.2 / 0.3 | 6 | 9–10 | 11 | 13–14 | |
| Start on boot (≈25 app rows with icons), slow | 519 | 7.3 | 10 | 15 | 19 | 25 | |
| Start on boot, fling ×12 (×2) | 626 / 626 | 0.8 / 0.5 | 7 | 11 | 13 | 15–16 | |
| Photo sources / Home dashboard / Sounds / HA / Android settings / About / Managed | 258–415 | 1.0–4.8 | 8–9 | 11–14 | 12–16 | 15–22 | |

## Home and launcher tabs

| Flow | Frames | Jank % | p50 | p90 | p95 | p99 | Notes |
|---|---|---|---|---|---|---|---|
| 5 left then 5 right swipes | 29 | 17.2 | 9 | 22 | 53 | 57 | Only about 2 transitions happened: there are 2 pages and swipes past the end are clamped |
| 10 alternating swipes, run 1 | 125 | 9.6 | 8 | 15 | 21 | 32 | about 9 transitions |
| 10 alternating swipes, run 2 | 141 | 4.3 | 8 | 13 | 16 | 20 | |
| Apps tab open (intent), 1st / 2nd | 14 / 14 | 35.7 / 14.3 | 12 / 10 | 69 / 29 | 77 / 31 | 77 / 31 | first time includes 1 slow bitmap upload; the grid fits on one screen, so it didn't scroll |
| Apps → Tools tab, 1st / 2nd | 14 / 14 | about 30 / about 7 | | | | 133 / 31 | first visit composes the Tools page |
| → Home tab | 13–14 | 7–8 | 8–10 | 14–16 | 32–38 | 32–38 | |
| `am start -W HomeActivity` (warm, already resumed) | | | | | | | WaitTime 12 ms, delivered to the existing instance |

The slow Home swipe frames cost about 7–8 ms in composition, 5 ms waiting for the UI thread, and 6–7 ms on the GPU. They fall on the first frames of each 180 ms slide.

## Music screen (no playback)

| Flow | Frames | Jank % | p50 | p90 | p95 | p99 | Notes |
|---|---|---|---|---|---|---|---|
| Open (intent `approved_home_music`), cold | 25 | 24 | 9 | 40 | **850** | **1000** | one 847 ms main-thread stall before a frame |
| Open, 2nd time | 25 | 24 | 11 | 21 | 53 | 81 | stall gone, so it is one-time init (YouTube WebView/engine) |
| Library tab (Playlists grid) | 16 | 50 | 27 | 85 | 150 | 150 | one GPU frame at 150 ms on first draw of the grid art |
| **Liked songs, 4 flings each way, run 1** | 744 | **33.3** | 13 | 26 | 32 | 48 | 48 slow-UI-thread frames |
| **Liked songs, run 2 (repeat)** | 752 | **35.4** | 14 | 24 | 28 | 36 | composition 8.3 ms + GPU 6.9 ms per slow frame |
| Queue tab switch | 14 | 64 | 21 | 28 | 29 | 29 | |
| Search tab switch | 5 | 60 | 22 | 85 | 85 | 85 | |

After the Music session:
- PSS was 307 MB: Graphics 112 MB, Native 46 MB, Java 23 MB, 1 WebView, 1 activity.
- Playback state was unchanged (no media session became active).

## Top 5 hotspots (measured, then traced to code)

### 1. Music track lists recompose on every scroll frame

- **Symptom:** 33–35 % jank on Liked songs, about 8.5 ms composition per frame.
- **Cause, `MusicV3TrackLists.kt:96-101`:**
  - Items are keyed by `"$i:${t.videoId}"` and have no `contentType`.
  - Each row builds a new `MusicV3RowModel` plus a fresh `listOf(MusicV3RowAction…)` with new lambdas on every composition. Rows therefore never skip, and items cannot be reused across types.
- **Cause, `MusicV3Kit.kt:101-104`:**
  - Each `MusicV3Thumb` runs `produceState` → `MediaArt.deliverUri` when its row enters the screen.
  - The art is fetched and decoded at `MAX_EDGE = 384` px (`MediaArt.kt:21`) but drawn at 72 dp (81 px). That is about 22× the pixels, and each one is uploaded as a GPU texture on first draw.
- **Background cost:** while the screen is open, the root `collectAsState` of the whole `YtEngineState` (`MusicV3PlayerScreen.kt:70`) recomposes the layout on every engine tick.

### 2. Every Settings navigation costs 2 janky frames (57–250 ms)

- **Symptom:** 100 % janky on sidebar switches and on every page open.
- **Cause, `ApprovedSettingsShell.kt:424-433`:** the pane list is written to state inside `SideEffect { panes = displayed }`. That forces a second recomposition right after the first, which is the reason it is always exactly 2 frames.
- **Cause, `ApprovedSettingsShell.kt:442-460`:** up to 4 visited panes stay composed behind `graphicsLayer { alpha = 0f }`. Flipping `LocalApprovedLauncherPageActive` recomposes every retained pane, and `tvFocusable` reads it during composition (`TvFocus.kt:61`).
- **Cause, `ApprovedSettingsShell.kt:467-474`:** non-lazy pages compose and measure every row up front.
- **Measured breakdown:** UI thread already busy for about 20 ms, composition about 17 ms, and draw/record up to 45 ms (the first record of a whole page).

### 3. Music first open blocks the main thread for about 850 ms

- **Symptom:** an 850–1000 ms frame on the first open only.
- **Cause:** the YouTube player `WebView` (`YouTubePlayerSurface.kt:47-51`, inside `MusicV3NowPlayingCard`) and the engine are created on demand, on the main thread, in the first composition. Chromium init on a QCS605 costs hundreds of ms.
- **Memory:** +32 MB Graphics and +26 MB Native after Music, and a WebView stays alive.

### 4. The lazy Slideshow page janks on first scroll (20 %)

- **Symptom:** 20 % jank on the first pass, versus 0.2–3 % for the non-lazy pages and for flings once it has been visited.
- **Cause:** this is the only LazyColumn detail (`ScreensaverSettingsActivity.kt:105`). Its about 35 heterogeneous rows (switches, choosers, the calendar section) compose on first entry. Every switch thumb also has `.shadow(2.dp)` and `.alpha()` (`ApprovedSettingsRows.kt:121,129`), which shows up as GPU-bound slow frames (13.6 ms issue+swap).

### 5. First visit to the Apps and Tools tabs, and Home page swipes

- **Apps:** p99 77 ms on first open, with a slow bitmap upload of the icon grid. The cause is the inventory and badge load, which starts in the same frame as the 160 ms slide (`ApprovedLauncherTabs.kt:150-174`, `AppSwitcherActivity.kt:218-243`).
- **Tools:** 133 ms on first visit.
- **Home swipes:** the slow frames are the first 1–2 of each transition.
  - `HomeActivity.kt:959-961` flips `active` on two pages at frame 0, restarting the page-lifecycle effects (photo `repeatOnLifecycle`, tickers).
  - The pages move via `absoluteOffset` with no `graphicsLayer` of their own, so each frame re-places them and re-records the parent.

## Fix suggestions, ranked by impact

1. **Music lists:**
   - Key by `videoId` (de-dupe the suffix only) and add `contentType = { "track" }`.
   - `remember(t.videoId)` the row model and actions, and hoist stable lambdas.
   - Request row art at about 128 px: pass the edge into `MediaArt.deliverUri`, and keep 384+ only for the 460 dp cover.
   - Add an in-memory LRU of decoded thumbnails.
   - Collect narrow slices of the engine state: queue, status and position, each with `distinctUntilChanged`.
   - Expected result: Liked songs from about 34 % jank to under 5 %.
2. **Settings navigation:**
   - Compute `displayed` without writing state back in `SideEffect` (derive it, or update it in the click handler), so each navigation is 1 frame.
   - Retain 1–2 panes instead of 4.
   - Make `tvFocusable` read the active gate at click time rather than in composition.
   - Use `LazyColumn` with `contentType` for long domain pages, or pre-compose the next page after the first frame.
   - Target: under 32 ms per navigation, from 57–129 ms today.
3. **Music cold open:**
   - Pre-warm the WebView once after Home's first draw, off the critical path: `WebView(context.applicationContext)` in an idle handler, or the `WebViewCompat`/`loadUrl("about:blank")` warm-up.
   - Alternatively, show the player shell first and attach the WebView in a later frame (`LaunchedEffect { withFrameNanos{}; attach }`).
   - Release the WebView when Music closes and nothing is playing, to win back about 50 MB.
4. **Settings rows:**
   - Replace the per-thumb `.shadow()` with a pre-drawn ring or `drawBehind`.
   - Apply alpha only when disabled.
   - Give the Slideshow LazyColumn items `contentType`s (switch, chooser, section label) so their compositions are reused.
5. **Tabs and Home pager:**
   - Defer the `active` and lifecycle flip until `animateTo` finishes (keep only the input gate immediate).
   - Put a `graphicsLayer {}` after each page's `absoluteOffset`, or animate `translationX`.
   - Start Apps inventory loading at idle after Home's first draw, not on tab activation.
6. **Hygiene:**
   - Enable `StrictMode` (disk and network on the main thread) in debug builds; there is none today.
   - Move the roughly 25 synchronous `ImmortalApp.onCreate` installs and schedulers to after the first frame.

## Caveats

- This is a debug build. Input came from adb, so the "high input latency" counts are not meaningful.
- Settings is not exported, so `am start -W` cannot measure its cold time-to-first-frame. The per-page open cost was measured through gfxinfo instead.
- Home has 2 widget pages. Home does not scroll vertically, and the Apps grid fits on one screen, so neither was scrolled.
- The Queue list was not scrolled, because it is reorderable by drag. Lamp, Phone remote and the credential Activities were not opened.
- `top` CPU sampling was unreliable on this device. CPU was measured from the `/proc/<pid>/stat` utime+stime delta instead.
- Raw data (gfxinfo dumps and logcat) was kept only in `/tmp/portalperf/data/` on the host and is not committed.
