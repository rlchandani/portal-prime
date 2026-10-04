# Screensaver polish — diagnosis brief (2026-09-19)

Mission: the photo-frame screensaver on the Portal (`com.immortal.launcher`) works but is
**not polished** — it fails the quality bar set by the mock. Diagnose ALL issues end-to-end,
find root causes, then polish. The mock at `mocks/screensaver/unified/index.html`
(served at http://localhost:8777/unified/index.html) is the **quality reference**.

## Reported symptoms (user, on-device)
1. **"Randomly runs over multiple images"** — advances too fast / multiple advances at once.
2. **"Two images coasting/ghosting"** — two images try to come in together (crossfade overlap).
3. General: "works but not a quality product," "the mock looks premium, the portal port sucks."

## Architecture (as read)
`PhotoFrameController.kt` (2163 lines — oversized) has **two render paths**:
- **Native "classic" path**: two `ImageView` layers `photo`/`photoBack`, alpha crossfade,
  Ken Burns via `AnimatorSet` (`kenBurns`/`kenBurnsBack`), stale-decode guard via `gen` counter.
  → prime suspect for the **coasting/ghosting** bug.
- **Engine path** (`startEngine`, line ~524): a **WebView** loading `app/src/main/assets/screensaver/`
  (index.html + lib-engine.js + scheduler.js + modes.js + mode-impls.js + photos.js + styles.css).
  Renders the 12 "premium" modes. `ScreensaverConfig.usesEngine(mode) = (mode != "classic")`.

`ScreensaverConfig.kt`: modes = classic, shuffle, + 12 engine modes (kenBurns, diptych, framed,
galleryWall, push, memories, panorama, polaroid, mosaic, filmstrip, cinema, heroSidebar).
Default mode = **shuffle** (engine). Default fit = fill. interval clamp 5..600s.

## Mock vs shipped divergence (KEY)
- Mock `mocks/screensaver/unified/`: `engine.js, index.html, mode-impls.js(20KB), modes.js(4KB), styles.css(9KB)`.
- Shipped `app/src/main/assets/screensaver/`: `index.html, lib-engine.js, scheduler.js, photos.js,
  mode-impls.js(26KB), modes.js(8KB), styles.css(10KB)`.
- The port **split engine.js into lib-engine.js + scheduler.js** and grew every file. Regressions /
  new bugs likely introduced in `scheduler.js` (beat timing/double-advance) and the rewritten modes.

Mock scheduler (`unified/engine.js`): single `beatTimer = setTimeout(advance, BEAT[mode])`,
BEAT ≈ 24–32s/mode. `modes.js` has orientation **appetite** system (portrait/square/landscape/pano
buckets) so the scheduler only picks a mode the pool can satisfy — REQUIRES real photo w/h.

## Live device state (serial FAMILY_PORTAL_SERIAL, debug build com.immortal.launcher.debug)
`immortal_screensaver.xml`:
- mode = **shuffle** (engine active), fit = **fit**, interval_sec = **30**, shuffle = true
- source = **folder**, folder_path = **/storage/emulated/0** ← entire storage root (junk, screenshots)
- ambient_dashboard = true, welcome_enabled = false, show_now_playing = false, enabled = true

Foreground activity when observed: `PhotoFramePreviewActivity` (debug preview).
Screenshot: portrait photo, pillarboxed with muddy **blurred-fill** background, clock "07:06"
bottom-left + "Sat, Sep 19 ☀ 60°". Debug beetle bottom-right.

## Immediate red flags (pre-diagnosis)
- **Source default / device value `/storage/emulated/0`** = scans ALL device images (screenshots,
  downloads, thumbnails) → un-curated, un-premium regardless of transitions.
- **Near-zero runtime instrumentation** — two logcat passes captured nothing from the launcher.
  Diagnosis + future support are blind. Add structured logging.
- **fit = fit** on device (blurred pillarbox) while config default is fill — muddy portrait bg.
- Engine appetite scheduler needs real image dimensions; if the Kotlin side feeds photos without
  true w/h, mode selection (diptych/gallery/pano) misfires. VERIFY.

## Diagnostic dimensions (workflow fan-out)
1. engine-js: shipped assets vs mock; scheduler/lib-engine/modes/mode-impls/styles/index bugs & regressions.
2. native-path: controller classic crossfade + Ken Burns + advance/next + `gen` races (coasting, multi-advance).
3. timing-lifecycle: all timers/handlers both paths; presence gating; dashboard/welcome/ambient; WebView bridge lifecycle & leaks; stop/cleanup.
4. source-curation-framing: LocalMedia scan, exclude junk, real w/h to appetite, ordering/dedup, fit/fill, portrait blur-fill, EXIF orientation, decode resolution.
5. research-polish: benchmark polished frames (Apple Aerial, Nest Hub/Photos ambient, Aura/Nixplay/Skylight) → transitions/intervals/framing/ordering/overlays/controls taxonomy + defaults.
6. settings-coverage-defaults: SettingsDomains screensaver specs vs taxonomy; missing controls; bad defaults.

Output: prioritized polish plan appended below by synthesis.

## Live evidence captured (main session, on device FAMILY_PORTAL_SERIAL)

CORRECTION: the premium **engine IS running** (WebView present as anonymous subclass `wv$1` in the
view hierarchy — an earlier grep for "WebView" missed the synthetic name). Screenshots prove it:
polaroid-scatter and hero+sidebar **collages** appear, which the native path cannot render. The two
`ImageView` base layers sit empty behind the WebView. So the user is seeing the engine, not classic.

Quantitative:
- **Jank: 54.19%** of frames (200220/369510). 50th pct 17ms, 90th 26ms, 99th 48ms. Motion visibly stutters.
- **Graphics memory ~393 MB** (GL mtrack 210MB + EGL 37MB). Strong sign textures/bitmaps are near
  full-res, not downsampled to the 2160x1440 panel → GPU pressure → jank and mid-transition smearing.
- **Zero launcher logcat** in 3 capture passes; WebView console not forwarded → runtime is a black box.

Visual (45-frame sequence @1s, /tmp/ss_seq; diff analysis):
- **Cadence far too fast** — frame-to-frame diffs are large (20–63) for ~35s straight (f06–f41) then
  settle. Modes appear to change every few seconds, not the 24–32s BEAT in modes.js. Matches user's
  "runs over multiple images."
- **Same photos repeat constantly** — the "heart-hands woman" and "splash-pad dad" recur in nearly
  every collage (f27–f35). Dedup / deck-cursor rotation bug or too-small pool.
- **Collages look cluttered** — polaroid scatter is messy/overlapping/edge-clipped, not tasteful.
- **Un-curated source** — random snapshots (kid on floor, lamp close-ups) from /storage/emulated/0.
- **No w/h to engine** — folder path calls `startEngine(urls)` with empty `dims` (PhotoFrameController
  line ~338), so modes.js appetite buckets can't match photo orientation → wrong mode picks.

Artifacts: /tmp/ss_seq/f*.png (45 frames), /tmp/ss_montage.png (f24–f35 contact sheet), /tmp/ss_shots.

</content>

## SYNTHESIS — prioritized polish plan (workflow wsfhzn4hx, 13 agents, verified)

### Prioritized fixes

1. **[critical/S] Stop resize→advance() from tearing down beats and stacking crossfades**  
   files: `app/src/main/assets/screensaver/scheduler.js:110-118`  
   Restore the mock's no-op resize behavior. If a first-layout recovery is genuinely needed for the tiny-viewport boot case, gate it behind a one-shot flag that fires at most once and only while the viewport is still degenerate (grew from a sub-full size), and never while pendingFade!=null. This is the single highest-impact visible fix — it directly eliminates the startup 'jumps to a new mode' and the stacked half-faded 'coasting' layers.

2. **[critical/M] Populate real per-photo dimensions host-side + remove the 24-photo measure cap + NaN-guard bucket()**  
   files: `PhotoFrameController.kt (folder branch ~:334-339, startEngine dims ~:526/578-592), assets/screensaver/photos.js:48-50,73, index.html:72, assets/screensaver/modes.js:13-19`  
   On the Kotlin side, for each folder file run a bounds-only decode (inJustDecodeBounds, off-UI) + ExifInterface to get ORIENTED w/h (swap for ROTATE_90/270/TRANSPOSE/TRANSVERSE), cache keyed by path+mtime, and build dims[url]=(w,h) before startEngine. For remote sources capture natural size on fetch/cache. As backstop: raise/remove premeasureAll(24) (bounded-concurrency measure of the whole deck before first beat) and make bucket() treat NaN/0 as a safe default (square/landscape), not pano. This restores the exact shape-intelligence that makes the mock feel premium.

3. **[high/M] Thread interval_sec into the engine and derive beat + internal step timing from it**  
   files: `PhotoFrameController.kt bridge :596-607 (+ startEngine opts), assets/screensaver/scheduler.js:101, modes.js:49-53 (BEAT), mode-impls.js (per-mode step literals 9000/8000/8800/14000/13000)`  
   Add Android.getIntervalSec() (return settings.intervalSec) and pass an intervalMs into startEngine opts. Drive each single-photo mode's internal step from interval (hold one photo ~interval), and set each shuffle BEAT = interval * N so a mode shows a small, composed N (~2-3) of photos before rotating instead of a fresh photo every 9s. This is the real cure for 'runs over multiple images'.

4. **[high/M] Curate the local source: hidden-dir skip, screenshot/cache/min-resolution filter**  
   files: `LocalMedia.kt enumerate() :83-98 (esp. :85), classify() :51-58`  
   In enumerate(), mirror summarize()'s dot-dir skip (:123) so .thumbnails/hidden caches never leak; skip known junk paths (Android/, .trashed, cache, and Screenshots unless explicitly chosen); reject images whose short side is below a threshold (~512px) using the bounds decode from fix #2; optionally bias DCIM/Camera + Pictures. Removes the screenshots/junk that make the frame feel cheap.

5. **[high/S] Make the beat crossfade re-entrancy-safe (coalesce advances during a fade)**  
   files: `app/src/main/assets/screensaver/scheduler.js:69-71,86-98`  
   If pendingFade is non-null when advance() is called, either ignore the new advance or queue it to run in flushFade's completion, rather than force-flushing a half-visible layer. Hardens the crossfade against any future rapid trigger (album refresh, resize, gesture).

6. **[high/S] Change the default/allowed folder off /storage/emulated/0**  
   files: `ScreensaverConfig.kt setFolder :364-365, defaults ~:157-163; SettingsDomains.kt folder spec; PhotoFrameController.kt local scan`  
   Default folder to DCIM/Camera (or force an explicit pick) and reject/warn when the picker returns the storage root or a non-media tree. Stops the frame from surfacing the entire device.

7. **[high/M] Add per-mode enable/disable and pass enabledModes through the bridge**  
   files: `SettingsDomains.kt (new multi-select spec), ScreensaverConfig.kt (persist set), PhotoFrameController.kt bridge, index.html startEngine opts; consumed at scheduler.js:72`  
   The scheduler already honors opts.enabledModes (scheduler.js:72) — only the wiring is missing. Add a persisted set of enabled mode ids, expose Android.getEnabledModes(), and pass it into startEngine so users can drop modes that feel gimmicky. Directly improves the shuffle 'feel'.

8. **[high/M] Fix the native classic-path timer/crossfade races**  
   files: `PhotoFrameController.kt: webNext/webPrev :1658-1673, rotate :1186-1192, loadFresh :1675-1686, show() :1726-1772, startKenBurns :1785-1788, stop() :641-666`  
   In webNext/webPrev call ui.removeCallbacks(rotate)+postDelayed(rotate,intervalMs()); add a gen capture/compare to loadFresh mirroring showLocalImage(:1264); in show() cancel photo.animate()/photoBack.animate() and flip photoBackIsFront synchronously at the start (not in the deferred endAction), and cancel the outgoing layer's Ken Burns. Fixes classic-mode ghosting/multi-advance and stops interrupted transitions leaving stale layers.

9. **[medium/S] Gate native navigation/ticks behind engineActive**  
   files: `PhotoFrameController.kt: next()/prev() :1223-1237, onTouch :244-245, gestureWave :308, engineActive :639`  
   When engineActive is true, have next()/prev()/gesture early-return or forward to the engine (webView.evaluateJavascript to advance the beat) instead of falling through to webNext→loadFresh, which today spins a hidden native crossfade + Picsum download behind the opaque WebView. Kills wasted CPU/network and state divergence in engine mode.

10. **[medium/M] Honor fit in the engine and de-muddy the portrait blur-fill**  
   files: `PhotoFrameController.kt bridge (getFit), assets/screensaver/styles.css:15,36,98, mode-impls.js fill usage`  
   Expose Android.getFit() and let letterbox/contain modes present the whole photo on a controlled matte when the user chose 'fit'. Lighten blur-fill brightness from 0.4-0.6 to ~0.7-0.8 (or use a color-sampled/gradient matte for portraits) so a portrait no longer sits on a muddy dark wash.

11. **[medium/M] Replace binary shuffle with weighted ordering (recency/dedup) and sort before the cap**  
   files: `LocalMedia.kt enumerate() :73-98, PhotoFrameController.kt :334, engine.js:19; SettingsDomains.kt shuffle spec :267`  
   Sort candidates by File.lastModified before the 2000 cap so the newest are kept; dedup obvious duplicates (name/size); drop the redundant second shuffle (or make deck order deterministic from a single weighted shuffle that avoids recently-shown photos). Add an ordering EnumSpec (Shuffle/Newest/Oldest/On-this-day) renamed 'Photo order' to disambiguate from the 'Shuffle' style mode.

12. **[medium/S] Compose the opening beat once at the real viewport**  
   files: `assets/screensaver/index.html:82-86, scheduler.js:105; PhotoFrameController.kt engineParams :616-622`  
   Defer the first advance() until innerWidth/innerHeight match the real screen (wait for the first resize/rAF at full size, or pass known dims from the host), so the opening mode isn't composed against the 256px/tiny boot viewport and then immediately superseded.

13. **[medium/M] Add a transition-speed / crossfade-duration control both renderers honor**  
   files: `SettingsDomains.kt (new spec), PhotoFrameController.kt :1754 (classic 1500L) + :1827, scheduler.js XFADE_MS :31 via bridge`  
   Expose Slow/Medium/Fast (or a ms IntSpec, default ~1000ms, cap ~2s) and thread it to XFADE_MS and the classic crossfadeDuration. Research: ambient dissolves ~800-1500ms with ease-in-out read as calm; never linear.

14. **[low/M] Fix engine/native lifecycle leaks**  
   files: `PhotoFrameController.kt: layout listener :624-628, stop() :641-666, album refresh :1466-1468; DreamPolicy.kt:184-191; PhotoFramePreviewActivity.kt:120-121`  
   Keep a reference to the layout-change listener and removeOnLayoutChangeListener in stop() and before recreating the WebView on album refresh (attach to the WebView so it dies with it); in stop() cancel photo/photoBack/welcome/dashboard ViewPropertyAnimators; serialize the dream→continuation-activity handoff so only one engine WebView renders at a time.

15. **[low/L] Add night-dim, caption, and global motion controls (premium polish)**  
   files: `SettingsDomains.kt overlay/night specs (:342/354-387), ScreensaverConfig.Settings :130-273, faceRenderer caption path`  
   Add a Night dim (ambient-light + schedule, min-brightness floor, cross-dim ~1-2s, clock-only-at-night) distinct from full screen-off; an optional auto-hiding caption (date/location/people, off by default); and a global 'Motion (pan & zoom)' intensity toggle. Matches the Nest Hub / Apple TV / Aura bar.


### Defaults to change

- folder_path: the live device is set to /storage/emulated/0 (entire storage root → screenshots/thumbnails/junk). Default to DCIM/Camera (or force an explicit pick) and reject/warn on the storage root in setFolder (ScreensaverConfig.kt:364-365, which stores raw with no validation)
- fit: device has fit=fit, which is a NO-OP under the engine (bridge sends no fit). Either honor fit in the engine or, once framing is subject-aware, default to Fill (subject/face-aware crop) with matte fallback only for extreme aspect ratios. The shipped code default FIT_FILL (ScreensaverConfig.kt:157) is correct — the device value is the problem
- interval: once interval is actually honored in the engine, target ~10-12s/photo (research sweet spot) with single-photo modes composing ~2-3 photos per beat; today the engine ignores interval_sec entirely and changes a photo every ~8-9s
- blur-fill brightness: raise from 0.4-0.6 (styles.css:15/36/98) to ~0.7-0.8 (or color-sampled matte) so portraits don't sit on a muddy dark wash
- ordering default: move from raw double-shuffle to a weighted shuffle that dedupes near-duplicate bursts and avoids recently-shown photos; keep the newest N when capping at 2000 (sort by lastModified before the LIFO cap in LocalMedia.kt:73-98)
- bucket() fallback for unmeasured photos: currently NaN→'pano' (modes.js:13-19); default to a real orientation (square/landscape) so shape-aware modes don't misfire on cold/unmeasured photos

### Settings to add

- Time-per-photo (interval): make intervalSec visible in engine modes too (currently gated !usesEngine at SettingsDomains.kt:266) or replace with a Relaxed/Balanced/Lively enum, and actually thread it through the bridge
- Per-mode enable/disable: multi-select persisted as a set, passed as enabledModes (scheduler.js:72 already supports it)
- Photo order enum (Shuffle / Newest first / Oldest first / On-this-day) replacing the bare 'shuffle' bool at SettingsDomains.kt:267; rename to 'Photo order' to disambiguate from the 'Shuffle — all styles' mode
- Source curation: 'Skip screenshots' (default ON), min-resolution filter, optional Camera/DCIM-only scoping
- Transition type + duration/speed control (Crossfade/Push/Cut; Slow/Medium/Fast) honored by both renderers
- Framing/fit enum honored in the engine (Fill subject-aware / Fit / Matte-blur), since fit is currently dead in engine modes
- Night dim: brightness floor + clock-only-at-night, distinct from the existing full screen-off / overnight window
- Optional caption overlay (date/location/album/people), off by default, auto-hiding in a bottom/corner safe zone
- Global 'Motion (pan & zoom)' intensity toggle (Off/Subtle/Standard/Strong) applied across modes
- Surface Memories / On-this-day toggle (default on) to feed the memories mode with emotional content

### Quick wins

- scheduler.js:110-118 — make the resize handler a no-op (match mock unified/engine.js:59) or one-shot gate. Single highest-impact visible fix for both ghosting and startup mode-jumps
- modes.js:13-19 — guard bucket() against NaN/0 (return 'square'/'landscape' instead of falling through to 'pano'). One-line change that immediately un-breaks shape-aware mode selection for all unmeasured photos
- scheduler.js:69-71 — bail out of advance() (or queue) when pendingFade!=null so a re-entrant advance never flushes a half-faded layer
- index.html:72 — raise premeasureAll(24) toward the full deck (bounded concurrency already exists in photos.js:73-79) so more than 24 photos carry real dimensions
- styles.css:15/36/98 — lighten blur-fill brightness (0.4-0.6 → ~0.75) to kill the muddy portrait wash
- ScreensaverConfig.kt:364-365 — reject/warn when setFolder receives /storage/emulated/0; change the shipped default folder to DCIM/Camera
- LocalMedia.kt:85 — add the dot-dir skip that summarize() already has (:123) so hidden .thumbnails/cache dirs stop leaking into the live feed
- PhotoFrameController.kt webNext/webPrev :1658-1673 — add ui.removeCallbacks(rotate)+postDelayed(rotate,intervalMs()) so manual web nav resets the auto-advance clock (classic-mode multi-advance)

### Open questions

- Interval model: should the engine honor one global interval, or derive per-mode beats as interval*N so single-photo modes still show ~2-3 composed photos before rotating? (Recommend the latter to preserve the 'composed, calm' feel while respecting the user's dwell.)
- Dimension pipeline cost: measuring oriented w/h for up to 2000 files via BitmapFactory bounds + ExifInterface — acceptable to do on io before first beat, or should it be cached to disk (keyed by path+mtime) and warmed for the NEXT run like FaceFocusStore already does for faces?
- Does the device actually have DCIM/Camera content, or is /storage/emulated/0 the only place photos live? Confirm before changing the default folder so we don't point at an empty tree.
- Should the native 'classic' crossfade path be maintained at all given the engine is the default (usesEngine = mode!=classic)? Two independent crossfade systems doubles the bug surface; consider deprecating classic or reusing one path.
- Memories/On-this-day and captions need capture dates — folder sources only reliably have File.lastModified (not true EXIF capture date). Is EXIF date extraction in scope, and for which sources?
- Is the face-focus data (fx/fy/pw from FaceFocusStore) actually populated on the device, or always cold on first run (warmUp targets the NEXT run at :593)? If cold, mosaic/hero face-aware framing is inert on first sessions.
- Is the dream→continuation-Activity double-engine overlap (DreamPolicy.kt:184-191 → PhotoFramePreviewActivity.kt:120-121) observable on-device as a transient double frame, or benign enough to defer?
- Should burn-in / overlay-shift protection be added now (Portal is LCD, low risk per research) or deferred — the fixed bright clock is the only real residual risk element?

### Research-backed defaults (quality bar)

- **Transitions:** CROSSFADE/DISSOLVE (default): recommend ~800-1500ms (1000ms default), both layers eased (ease-in-out) - longer than functional-UI fades (~200-300ms per Material) so ambient change feels calm rather than like a UI event. These durations are recommendations extrapolated from Material's guidance, not values Material specifies. KEN BURNS: continuous slow pan+zoom spanning the full dwell; recommended zoom ~108-115% (Subtle ~8%, Standard ~12%, Strong ~15%), pan a few percent; near-constant velocity with eased slow-in/slow-out start and stop so no acceleration is perceptible; needs dwell >=8s (10-12s ideal) to read as elegant. PUSH/SLIDE: ~600-900ms with ease-out for the push mode. HARD CUT: reserved for group/album boundaries, photo<->video edges, and diptych recomposition; avoid as a general transition since it breaks the ambient mood. EASING: never linear (web.dev - robotic); ease-out for entrances, ease-in-out / cubic-bezier(0.4,0,0.2,1) for symmetric crossfades, keeping ease-in-out moves within roughly 300-1000ms. NIGHT: cross-dim brightness/warmth over ~1-2s rather than stepping. All numeric transition targets except the web.dev easing guidance are design recommendations, not sourced figures.
- **Framing:** Premium frames avoid letterboxing a portrait with black bars. Three acceptable treatments, in order of quality: (1) PAIR two portraits into a diptych - the approach marketed by Aura (Portrait Pairing) and used by Google Nest Hub; a thin gutter or subtle matte separates them. (2) MATTE - center the fitted portrait inside a gallery mat, optionally tinted to a color sampled from the image (Aura's Paper-like matting; Nest Hub's Ambient EQ similarly color/light-matches to the room). (3) BLURRED-FILL done well - duplicate the SAME image behind the sharp fitted copy, blur heavily (large radius), darken it noticeably, and slightly desaturate so it becomes an ambient wash rather than a competing image. The version to AVOID is a low-radius, full-saturation, stretched/zoomed background that still shows recognizable detail and reads as a rendering bug. Crop-to-fill a portrait to landscape only as an explicit opt-in, with subject/face detection so the subject is never beheaded. For landscapes near the screen aspect, fill edge-to-edge with a subject-aware crop; fall back to matte only for extreme ratios (true panoramas warrant a dedicated panorama treatment). Exact darken/desaturate amounts are tuning choices, not sourced values.
- **Ordering:** Default to WEIGHTED SHUFFLE, not pure random: exclude images shown in a recent window, collapse near-duplicate bursts (multiple shots of one moment), and de-prioritize auto-detected clutter (screenshots, documents, blurry) - mirroring Google Live Albums auto-curation. Layer optional RECENCY weighting so newer photos appear somewhat more often. Surface MEMORIES / ON-THIS-DAY as a first-class content stream (Apple Memories; Apple/Google On-This-Day) for emotional payoff and to feed a memories mode. GROUP related photos by event/date/location/people so a session reads as a small story and multi-image modes (diptych, gallery) compose coherent sets rather than clashing pairs. Offer explicit Newest-first / Oldest-first for chronological playback.
- **Default profile:** Ship a calm, universally-flattering default profile. Mode = kenBurns; interval 10s per photo; crossfade at 1000ms with ease-in-out; Ken Burns intensity = Subtle (eased start/stop). Portraits default to diptych pairing with a color-matched blurred-fill fallback (never black bars, never crop-to-behead). Landscapes fill edge-to-edge with a subject/face-aware crop, falling back to matte only for extreme aspect ratios. Ordering = weighted shuffle that dedupes near-duplicate bursts and avoids recently-shown images, with Memories/On-this-day surfacing on. Overlays: subtle clock + weather in a bottom/corner safe zone over a soft scrim, captions on subtle-auto, overlay slowly repositioning over time. Auto-dim/Night mode on, driven by ambient light plus a night schedule, with a minimum-brightness floor and clock-only-at-night; brightness Auto. Burn-in protection on as cheap insurance even though Portal is LCD (low risk per Wikipedia). Videos/motion photos on but muted and length-capped, hard-cutting in/out so they don't break the dissolve rhythm. These defaults track the Apple TV / Nest Hub / Aura quality bar while staying predictable; every control is exposed for power users, but the out-of-box experience needs no tuning. Numeric targets (10s, 1000ms, zoom %) are design recommendations, not values stated by the cited sources.

## IMPLEMENTED + VERIFIED (2026-09-19, on device FAMILY_PORTAL_SERIAL)

Changes (all in the engine assets + PhotoFrameController + LocalMedia):
- **Cadence (fix #1/#3/#5):** `scheduler.js` resize→one-shot boot recovery (was: advance() on any >5%
  viewport change → mode storms); advance() coalesces during an in-flight crossfade (was: flushed a
  half-faded layer = "coasting"); threaded `interval_sec` via new `Android.getIntervalSec()` bridge →
  `ctx.photoMs`/`ctx.microMs` so all 12 modes honor the user's interval (was: hardcoded ~8–9s steps).
- **Shape intelligence (fix #2a):** host computes ORIENTED per-photo w/h (BitmapFactory bounds +
  EXIF swap) and passes `dims` to the engine; `modes.js` `bucket()` NaN-guarded. Folder pool bounded
  to ENGINE_MAX_PHOTOS=600, sub-512px junk filtered.
- **Perf/memory (fix #2b):** `startEngine` file:// interceptor decodes local photos to MAX_EDGE + bakes
  EXIF orientation, re-encodes JPEG → WebView uploads small textures. Serialized decode (heap safety).
- **Curation (fix #4):** `LocalMedia.isJunkDir()` — enumerate/summarize skip dot-dirs, Android/, cache,
  nested Screenshots.
- **Framing (fix #10a):** blur-fill brightness 0.6/0.55/0.4 → 0.75/0.72/0.6 (de-muddy portraits).
- **Instrumentation (Batch G):** WebChromeClient forwards engine console → logcat "ImmortalEngine".

Measured before → after:
- Cadence: photos changed ~every 1–9s (continuous churn) → scenes dwell 15–20s+, **62% static frames**.
- Ghosting: stacked half-faded layers at transitions → clean single compositions (none observed).
- Graphics memory: **322MB → 164MB** (GL 188→69MB).
- Worst-case frame hitch (99th pct): **53ms → 29ms**. Median ~18–20ms (old Portal GPU animation floor).
- Orientation: portraits now upright (EXIF baked in during downsample).
- Unit tests: green. Debug APK installs + runs; engine boots <10s with the bounded dims pass.

Remaining (optional, ranked in SYNTHESIS above): Batch E settings (per-mode enable, transition speed,
photo order, night dim, expose interval in engine UI), Batch F native classic-path races + lifecycle
leaks (low user impact — engine is default), default-folder off /storage/emulated/0 for NEW installs,
filmstrip mode sparse layout (black lower half), engine `fit` honoring.

## Face-clipping fix (2026-09-20) — "guaranteed" whole-photo framing

User reported faces clipped in the hero + sidebar (cover-cropped cells). Findings + fix:
- ROOT: hero/sidebar/diptych cover-crop to fill the cell, slicing faces. The engine HAS face-aware
  focal points (fx/fy) but the ML Kit face detector never populates the cache on this Portal —
  `warmUp()` enters then STALLS before building the detector (no exception; likely an ML Kit/native
  incompatibility on Android 9/10). Traced with added logging; left the face-aware hooks in place for
  if/when detection works, but did NOT rely on it.
- GUARANTEED FIX (no ML Kit): heroSidebar + diptych now render the WHOLE photo (`contain`) over a
  soft blurred fill of itself (reuses `.ss-cell.contain` + new `.ss-pane`), so a face is never
  clipped. Verified on device: sidebar shows full photos with blurred letterbox fill, not crops.
- Also biased the faceless cover default (`.ss-img` 38%→30%) for the modes still using cover
  (galleryWall/mosaic), and made those face-aware (faceBg) for when fx/fy exists.
- LATENT BUG FIXED: the WebView cached engine JS/CSS by URL, so `file:///android_asset/` assets went
  stale after an app update (updates silently ignored). Now `wv.clearCache(true)` on each engine start.
- NOTE: the test Portal has only 9 photos (Pictures/PortalInstaller) — apparent "repetition" is the
  9-photo pool, not a bug; real variety needs the user's library on the device.
- Startup: cold start (force-stop → relaunch) is ~14-20s of black — inherent WebView/Chromium
  cold-init in a fresh process (reducing the photo pool 600→300→seed-80 didn't move it); irrelevant
  to the always-on frame which warm-restarts. Not a regression.

## Fix-all pass (2026-09-20) — implemented + device-verified

- **Interval honored everywhere** (scheduler.js microMs=intervalMs; ambientMs for pure motion; mosaic
  split spotlight(ambientMs)/swap(microMs); polaroid fills MAX at intro). Verified: 79% static frames,
  scene holds 18-39s at a 30s interval (was ~1.5-7.5s). Residual per-frame motion is gentle Ken Burns.
- **No face clipping in ANY mode** (framing audit found 5 offenders): galleryWall uniform-scale fill
  (was height-only distort -> cover crop, then sparse); cinema contain for landscapes (was ~34% crop);
  panorama aspect-sized pan element (no vertical crop); mosaic MISMATCH 1.35->1.15 (more pan-through);
  memories collage tiles; global cover top-bias 30%->25%. Verified by forcing each mode.
- **memories crash fixed**: showCollage referenced undefined `single.root` -> ReferenceError every 6th
  beat (mode froze). Now `singles.forEach(...)`. Verified mode renders.
- **Cold-start**: removed per-start wv.clearCache(true) (was wiping cache mid-cold-start) -> version-query
  cache-bust (?v=versionCode); premeasureAll 24->6. First photo ~10-11s (was ~15-20s). Rest is
  irreducible Chromium cold-init.
- **Background-thread stall / OOM**: DISPROVEN as OOM (the "Killing immortal" logs were our own
  force-stops; real lowmemorykiller hit a Facebook process). Added largeHeap as margin. Face detection
  still doesn't populate (likely CPU starvation) but is no longer needed — clipping is fixed structurally.
- **Not done (optional / environmental)**: transition-speed control (feature add); memory ~330MB (a
  memory-safe disk-cache downsampler needs the stalling bg-thread path); ~50fps jank (old GPU); only 9
  photos on device (needs the user's real library).

## Performance pass (2026-09-24 → 2026-10-03) — implemented + device-verified

User report: "everything is jittery; tapping to exit takes a while to get back to Home."
Profiled with `dumpsys gfxinfo <pkg> reset` + 30s read on the heaviest mode (mosaic, 9 animated tiles).

| mosaic, 30s window      | before | pass 1 | pass 2 (texture cache) |
|-------------------------|--------|--------|------------------------|
| janky frames            | 96.5%  | 55%    | **20%**                |
| median frame            | 20ms   | 17ms   | **13ms**               |
| 95th percentile         | —      | —      | 20ms                   |

Pass 1 (CSS/JS, low risk): blur radii 46/60/40px → 20/24/18px (cost ~r²); outgoing beat frozen
(`.ss-beat.freeze` → `animation-play-state: paused`) so only the incoming mode animates through the
1.6s crossfade; galleryWall 18 → 10 tiles; ~40% of gallery/mosaic tiles static; mosaic spotlight is
transform-only (animating `filter` re-rasterized the whole wall); `stop()` pauses the WebView
(`onPause()+pauseTimers()`) FIRST and moves TTS shutdown + soundscape stop off the main thread.

Pass 2 (`EngineScaledCache.kt`, new): every photo on the device is 12MP (4000×3000 → 46MB decoded
texture, re-decoded on each appearance because 9 of them overflow Chromium's image cache). The cache
scales each oversized local photo ONCE to a 2400px long edge (EXIF baked, JPEG q88, ~0.4–1.3MB) on a
MIN_PRIORITY thread starting 20s after engine start, keyed by path+size+mtime, trimmed oldest-first
to 160MB. The engine asks `Android.scaledUrl(url)` (pure stat, never a decode) and gets the copy when
ready — nothing is ever decoded on the load path, so the earlier "buffering" cannot recur. Verified:
9/9 copies written in <60s, 1800×2400 upright, renders identical, Home redraw no longer competes
with a saturated GPU. `will-change: transform` now only on tiles that actually animate.

Also checked: remote `longEdge` caps (#7) are moot — `photoUrl()` ignores the hint; the app already
sizes/proxies remote images. Unit tests: `EngineScaledCacheTest` (8, pure math + resolve()).

Residual: ~20% janky in mosaic is the aging GPU compositing 9 moving layers; single-photo modes are
at/under budget. Options if more is wanted: fewer animated mosaic tiles, or a transition-speed setting.
