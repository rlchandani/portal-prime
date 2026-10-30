# Portal music design preview

A silent, functional browser mock for Portal Android 10 / API 29. Open `index.html` directly; no build, packages, external fonts, downloads, credentials or network services are required. Original local SVG artwork and fictional music metadata are bundled.

For a local browser URL, run from this folder:

```sh
python3 -m http.server 8890 --bind 127.0.0.1
```

Open `http://127.0.0.1:8890/`. Routes: `#now`, `#library`, `#playlists`, `#night-clock`.

## Design

Neutral charcoal background `#202124`, surfaces `#292a2e` / `#34353a`, soft white `#f4f4f6`, secondary ink `#b5b6bd`, and keyboard focus `#b8ceff`. Artwork supplies the color. One system sans-serif family keeps player controls familiar; the cover lettering belongs to the art. At 2160×1440 the complete player fits without scrolling, with approximately 648px artwork, 49.5px title type, and 66–110px main controls. Smaller viewports stack the queue under the player. Touch targets are at least 48 CSS pixels; native implementation must preserve at least 48dp after density conversion.

The interaction design uses Apple's clear full-player/MiniPlayer split and a persistent queue, with Material's accessible touch and slider guidance:

- [Apple: music player controls](https://support.apple.com/en-gb/guide/iphone/iph676daac9b/ios)
- [Material: slider guidelines](https://m3.material.io/components/sliders/guidelines)
- [Android: Compose minimum touch targets and semantics](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)

Apple's official search result supplied the MiniPlayer guidance; its full-page fetch was rate limited. Material's guidelines page requires JavaScript; the Android documentation supplied the verified 48dp rule. No assets were copied from these references.

## Interactions and honest boundaries

Transport, seeking, queue selection, library search, album filtering, playlist detail, sample queue replacement, speaker selection, compatible grouping and volume are interactive. Full and compact views share one in-memory state. Play simulates a visible progress indicator and emits no audio; selecting a sample track leaves it paused. Progress stops when the page, full player or Now playing view is hidden. Sample progress stops at the end instead of automatically starting another track.

The footer exercises connected, local-session, live, unknown-duration, empty-queue, not-configured, connecting, offline, authorization, incompatible, interrupted and failed-command states. Offline retains last-known artwork and disables commands. Local sessions offer transport only. Live/unknown-duration samples never fabricate seekable durations. Interruption preserves supported pause/volume and browsing while preventing audio starts, seeks and queue selection. Failed commands preserve fixture playback/position/volume/group state. Connection information is a dialog, not an actual setup form.

All metadata, speaker availability, playback, time and presence are fixtures. This mock proves neither Portal audio nor sensor behavior. It contains no Home Assistant app or configuration. Browser state resets on reload.

## Native implementation mapping

Inspected `MusicPlayerUi.kt`, `MusicPlayerState.kt`, `MusicPlayerHost.kt`, `MusicRoomPolicy.kt` and `MusicMaDispatch.kt` before creating the browser mock. The subsequent native refinement is recorded separately below.

| Mock visual / action | Existing native counterpart and constraints |
| --- | --- |
| Full / compact view | `MusicPlayerLayout.FULL` / `COMPACT`; `onLayoutChange` shares `MusicPlayerController.store`. Layout changes must not start output. |
| Cover, title, artist, album | `MusicTrack`, `MusicCover`, `artBitmap` and coherent source/track IDs. Use `Image`, clipped rounded rectangle, `ContentScale.Crop`; retain bounded artwork decode, not an offscreen full-resolution gallery. |
| Play, pause, skip | `TogglePlayback`, `Next`, `Previous`; `snapshot.can(...)`, pending checks and rendered-action binding. The mock's “simulate” label is specific to the preview. |
| Seek and elapsed time | `Seek(positionMs)`, `MusicProgress`, `visiblePositionMs`; compose `Slider` drafts, commit on release. Preserve unknown/live-duration guards and isolated lifecycle-aware progress updates. |
| Queue list / select track | `MusicQueueItem`, `SelectQueueItem(id)`; `LazyColumn` with stable IDs, available-item and authoritative `queueId` checks. Queue mutation/reordering APIs are not present and no such controls are shown. |
| Speaker selector | `MusicRoom`, `SelectPlayer`; only available native provider players. Selecting a player changes the control target, never promises casting or automatic playback. |
| Compatible group controls | `ToggleRoom`; preserve `musicRoomActions` leader, compatibility, removable-membership, interruption and pending guards. The mock's two available speakers are explicit compatibility fixtures. |
| Volume | `SetRoomVolume(roomId, percent)`; only available and `volumeControllable` rooms with a known 0–100 volume and advertised volume capability. Native UI retains server-confirmed values until refreshed. Mock drafts say “preview”; release commits the fixture only. |
| Availability and errors | `MusicConnection`, `pending`, `problem`, `audioOwnedByOther`, `lastCommand`; reuse `MusicNotice` and capability validation. Do not infer readiness from an open socket. |
| Library / playlists | Current `BrowseLibrary` opens the authenticated provider-owned `WebAppActivity`. The mock's in-page search, cards and playlist-to-queue replacement are **presentation exploration only**, not already-supported native library/playlist APIs. Implement navigation using the existing provider browsing entry point unless a future adapter supplies these models. No playlist CRUD or hidden provider wiring is included. |
| Photos, lyrics, shuffle, repeat, favorites | Existing photo/lyric support is outside this mock. Shuffle/repeat/favorite enum flags have no corresponding exposed `MusicPlayerUiAction` dispatcher here, so controls are omitted. |

Compose construction is ordinary `Row` / `Column` / `BoxWithConstraints`, `Text`, `Image`, `Slider`, `IconButton` and bounded `LazyColumn`. Use default native sans-serif, semantic labels/selected state, palette tokens, and finite panes. No blur, web shader, continuous decorative animation, GMS or downloadable font is needed. All six SVG originals total under 20KB; native production artwork should use the existing bounded cache/decode path. The mock does not measure real Portal frame time or RAM use.

## Night clock route

`#night-clock` matches the parent's `QuietNightClockView` drawing approach, inspected read only after it appeared:

- Black `#000000`; one Canvas; no seconds, blur or animation.
- Clock: light sans-serif, text size `height × .28`, centered; width-fit cap `width × .82`.
- Date: regular sans-serif, size `min(height × .041, width × .038)` and width-fit cap `width × .84`.
- Clock/date group is centered using font ascent/descent; gap `height × .043`.
- Warm RGB `(185,159,135)` or red `(166,85,72)`. Date uses the same RGB at alpha `180/255`.
- 12-hour sample `10:08` and 24-hour sample `22:08`, date `Friday, October 30`.
- Presence dropdown changes a **sample label only**. It does not read sensors, adjust real brightness, route audio, or validate parent hardware behavior.

Browser font metrics differ from Android `sans-serif-light`; exact layout ratios/colors match, glyph rasterization is platform-specific. The native minute-boundary clock is intentionally not duplicated by a browser ticker.

## Verification and evidence

`checks.mjs` uses an existing developer Playwright installation and Chrome, starts its own temporary static server, and writes only to this folder. It is not a runtime dependency:

```sh
PLAYWRIGHT_MODULE=/absolute/path/to/node_modules/playwright node checks.mjs
```

21 functional browser checks cover Portal fit, silent transport/timeline, seek/skip, queue selection, full/compact state, search, playlists, speakers, capability gating, unavailable/error recovery, interruption, command failure, keyboard tabs, reduced motion, Night clock ratios/inks, mobile touch targets, short landscape/tablet layouts, standalone file use and absence of external requests/runtime errors. JS syntax and all SVGs also parse successfully. Screenshot and check evidence is in `evidence/`; `checks.json` records the checks.

## Subsequent native refinement

`MusicPlayerUi.kt` now has the artwork-led hierarchy, single-row header, circular transport,
compact strip, queue covers and shared slender Material seek/volume controls. It preserves
the existing command guards, player ownership and lifecycle-aware progress leaf. It uses
Immortal's established light/dark palette rather than replacing the global theme.
Title and artist are single-line ellipsized labels; full text remains in the native semantics.
The artwork budget includes control/capability-message height and font scale.

`MusicDesignPreviewActivity` and its original 512px cover assets are debug-only. The preview
never creates an audio controller, connects a server or reads household media. It renders the
real native music, night-clock, Gallery print and Gallery spread components with silent sample data.
`MusicBrowseUi.kt` now supplies native library, playlist and collection-detail components.
They accept immutable host-supplied metadata, use lazy grids/lists and bounded local artwork,
and share navigation with the silent debug preview. Empty and unavailable states do not
invent collections. Live provider actions and library data remain deferred; no native
provider integration is implied.

Library and playlist search now filters only host-supplied collection, track and artist
metadata. Queries and lazy-grid positions are hoisted above detail navigation; opening a
collection and returning preserves the original query and complete collection. Clearing
restores the supplied order. Loading and unavailable status stays visible independently
of query results. Search performs no provider or artwork request.

The selected queue row now retains its supplied artist and shows playback status on a
separate quiet line. Confirmed playing/paused states have the corresponding indicator;
idle, stopped, pending, restricted local and offline states do not imply active playback.
Nine JVM policy tests cover those distinctions. Artwork bounds, stable list keys and
command guards are unchanged.

Gallery spread uses the existing bounded native stage, decoder and bitmap ownership.
It contains two whole portraits inside a 3vmin matte with a 2vmin gap, or one whole
landscape when pairing is inappropriate. Overlay changes reposition retained views
during the existing dissolve. Spread omits the photo-adjustment control because its
whole-photo presentation does not use crop positions. Gallery print and the other
styles retain their existing adjustment controls. The normal spread is still and
does not run face inference or background blur.

`native-checks.py` targets only the dedicated `PortalMusic20261030` AVD and refuses a household
device. It captures layout evidence and checks touch sizes, transport visibility, queue/layout
continuity, offline guards, theme/font variants and native clock/gallery rendering.
The acceptance suite includes lazy album scrolling, collection navigation, playlist track
selection, honest empty/unavailable states, 1.8× text, native search continuity, keyboard
visibility, Gallery spread overlay behavior, and the actual viewer controls:

```sh
python3 native-checks.py --serial emulator-5556
```

Results are in `evidence/native/checks.json`; `complete: true` is required before claiming the
whole check passed. The latest integrated run passed **56 checks in 151.09 seconds**.
Open `native-review.html` for the image review; all **39 capture tabs** loaded without page
errors, recorded in `evidence/native-review-checks.json`. The emulator's API 34/GMS
image does not establish Portal API 29 timing, sensor behavior or audio compatibility.
No Home Assistant music deployment belongs to either preview.

## Actual slideshow renderer review

The debug-only `VideoDesignPreviewActivity` separately exercises the production
background MediaPlayer owner using an original silent 1280×720 clip. Its
on-demand position label leaves Android's UI inspector idle between commands.
Run on the same dedicated AVD:

```sh
python3 video-native-checks.py
```

All **11 checks passed in 28.72 seconds**; the result is in
`evidence/video-native/checks.json`. The checks cover pause/seek/resume, 100
rapid replacements and close. They establish functional emulator behavior,
not physical Portal timing or audio. The fixture/activity are debug-only.

`slideshow/serve.py` exposes an allowlist of production slideshow assets, an injected silent
host, two original portrait fixtures, twelve original mixed-aspect gallery fixtures,
and two original panoramic landscapes. It never serves the repository root. Run:

```sh
python3 slideshow/serve.py
```

Open `http://127.0.0.1:8891/`. Diptych, Framed, Ken Burns, Cinematic Push, Gallery Wall,
Hero + Sidebar, Memories, Panorama Drift and Mosaic Wall are rendered by the actual
production JavaScript/CSS. This preview suppresses host clock overlays and does not simulate
ambient sensors. Its **174 browser checks** include real stalled HTTP image requests, pause
cancellation, bounded preparation, delayed dimension recovery, opaque outgoing Diptych
frames, initial readiness without a second fade, pause without the newer animation API,
and cancellation immediately before DOM publication. Diptych preserves its existing
photo composition and source selection. Only the incoming frame fades; both retained
frames are released on cleanup. Chrome results do not establish Portal GPU timing;
source tests separately cover Filmstrip and Gallery geometry.
The renderer checks use developer-installed Playwright/Chrome and `pngjs`; these are
verification tools, not application or preview runtime dependencies.

Living Gallery Wall fits all ten selected photographs inside the viewport, adapts
row height to the screen and centers the shorter final row. Whole static photographs
replace continuous tile motion. Each cell retains two fixed image slots; only the
incoming slot fades over its own opaque matte. Retirement releases the outgoing URL,
and pause freezes a live fade even without the newer WebView animation API.
An asynchronous-decode regression checks that pause/resume preserves one swap cadence.

Hero + Sidebar preserves its larger hero and two portrait panes, showing whole static
photos on matte instead of duplicate blurred fills. Each pane owns two fixed slots
with an incoming-only fade. One-photo libraries expand to a single full-width pane.
Derivative admission and photo publication recheck the current pause state, including
resume/re-pause during an asynchronous load. The review uses the same mixed-aspect
original fixtures as Gallery Wall.

Memories preserves five individual photographs followed by a collage on the sixth
successful beat. Single photos remain whole, with a gentle 4% push only when no cached
face hint exists. Collage frames fit the photographs' proportions and retain one to three
unique, complete static photos, according to the library size. Its neutral heading
does not invent dates, locations or events. Two reusable scene roots own at most four
photo elements during the single/collage dissolve; retirement and cleanup release URLs
and animation hints. Thirty-one actual-source Chrome assertions cover the cadence,
incoming-only fades, old-WebView pause, card proportions, maximum zoom bounds, small
libraries and cancellation. These are structural and functional results, not Portal
RAM or FPS measurements.

Open `http://127.0.0.1:8891/?mode=memories&review=1` for the five-second review pace.
The normal preview remains at thirty seconds. This browser option does not change
any Portal setting.

Panorama Drift retains horizontal transform panning while limiting the moving strip
to two screen widths. Extreme panoramas fit vertically on matte rather than creating
elements many screens wide; fitting landscapes remain whole and still. Cached focal
points hold a static panoramic window around the anchor. The sweep completes within
the photo interval, independent of the scheduler's longer zoom-motion overlap.
The two reusable scenes use an incoming-only 1.8-second fade, current-playback
publication gates, and retirement/cleanup that clear URLs and promotion hints.

Mosaic Wall retains its varied fixed tiles, in-place photo exchanges and roaming
spotlight. Nine tiles own eighteen reusable image slots. The spotlight guides one
photo's motion; a live fade can temporarily retain two moving photos in that tile.
Other photos pause their motion and release explicit transform promotion hints.
Pan strips fit their cross axis and never exceed two tile widths or heights.
Compatible photos stay whole on matte with a gentle 4% push; cached focal points
remain static. Initial decode admission proceeds in batches of two, with the
complete composition gating readiness. Incoming-only 1.1-second fades retain
opaque outgoing pixels until retirement; pause and cleanup retain or release those
owners explicitly. These are bounded rendering contracts, not measured Portal
texture allocations or FPS gains.
Twenty-three actual-source Chrome assertions cover the initial two-decode limit,
nine unique opaque tiles, stable geometry, spotlight coverage, photo motion/hint
limits, atomic two-cell exchanges, retained manual pause, cleanup and cancellation,
small libraries, decoded extreme-aspect bounds and static focal anchors.

Open `http://127.0.0.1:8891/?mode=mosaic&review=1` for the five-second review pace.
Only a strip that actually moves requests transform promotion. Geometry and owned
resource bounds are verified separately from physical Portal GPU memory or FPS.
The preview uses original 3:1 and 8:1 landscapes with matching metadata.
Forty-nine actual-source Chrome assertions cover the full sweep before retirement,
the old-WebView manual pause path, fixed ownership, focal anchors, 24 viewport/aspect
combinations, cancellation, and the rendered right-edge marker pixels.

The rendering choices follow Google's [high-performance animation guidance](https://web.dev/articles/animations-guide)
and [compositor layer guidance](https://web.dev/articles/stick-to-compositor-only-properties-and-manage-layer-count):
animate transforms and opacity, avoid layout/paint properties for movement, and
release unnecessary layer hints. These sources guide the implementation; they
do not establish a Portal benchmark.

Ken Burns now uses the same incoming-only dissolve owner as Diptych, a 3vmin inset
and a quiet charcoal matte. It keeps the original source selection and gentle motion
for photos without a cached face hint. Cached face hints retain the whole photo,
statically, without a permanent compositor hint. Ken Burns has **zero blurred duplicate
photos**, retires old image URLs after the dissolve, and freezes motion as well as opacity
on pause without requiring `getAnimations`. These are rendering-work and lifecycle
changes; they do not establish a physical Portal FPS improvement.

Framed Print now shares that dissolve owner. Each incoming scene includes the same
room gradient, preventing an outgoing print from showing around a differently sized
replacement. Initial readiness is opaque, pause freezes both the breathe motion and
opacity on older WebViews, and retired/closed slots release photo URLs and animation
hints. Cached face hints retain the complete, still print. Its original frame geometry,
matte, lighting and photo interval remain intact. Eight added actual-source browser
checks cover these contracts; the startup check fails against the preceding renderer.

Cinematic Push retains its whole-photo presentation and configured interval, with
a slower 4% push inside a 5% inset and an incoming-only dissolve. It creates no
blurred duplicate fill. Initial readiness is opaque; pause, retirement and terminal
cleanup use the same tested owner. Cached face hints keep the entire image still.
Five additional source regressions fail against the previous implementation and pass
against the refinement. Actual Chrome checks also verify that the complete photo
container stays inside the screen at the final zoom. These results do not measure
physical Portal presentation performance.

The native speaker chooser now marks the selected source independently of its
group leader. It disables redundant selection, shows eligible group actions and
explains temporary or unsupported grouping states. At larger text sizes or narrow
widths, actions stack while retaining 48dp touch targets. The debug-only
`speaker-groups` scenario supplies silent compatible/incompatible/offline players;
selection updates fixture state only, with no provider or household connection.
The native speaker-selection check exposed a tab reset caused by keying UI state to
the music source. Tab state now belongs to the current UI composition, so selecting
a speaker retains the chooser while every pane reads the fresh snapshot.
