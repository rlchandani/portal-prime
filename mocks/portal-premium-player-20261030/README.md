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

Inspected `MusicPlayerUi.kt`, `MusicPlayerState.kt`, `MusicPlayerHost.kt`, `MusicRoomPolicy.kt` and `MusicMaDispatch.kt` before implementation. Native files were read only.

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

No Kotlin, backend, settings or HA configuration was edited by this task. No commit or deployment was performed.
