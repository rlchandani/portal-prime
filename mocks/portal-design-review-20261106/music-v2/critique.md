# Portal music player v2: split Now Playing

v1 (kept in `../music/`) fixed what the old player got wrong about honesty, but the human was right that it wasted the Portal. A 416 px album cover took up about a third of a 1080×720 CSS screen, and the space beside it held almost nothing. This second version is for **the person standing at the Portal**. The photo frame (across the room, or someone walking up) is unchanged from v1. It was already restrained, and it should stay that way.

## What changed and why
1. **Art becomes an identifier, not the subject.** The cover is now 200 px (it was 416). The screen goes to what people actually use: the next song, their library, search.
2. **Split layout.** On the left (~45%) is our native Now Playing: room line, art, title at 44 px over two lines (it was 56 px and squeezed), artist at 28 px, progress, transport, and a **volume slider** (Portal speaker volume via `AudioManager`; true, because the audio plays on this device). On the right (~55%) is **the real YouTube Music page in an embedded view**, with native tabs: Queue (YouTube Music's own Up next on its player page), Library (`/library`) and Search (`/search`). The pane is clearly framed and labelled "YouTube Music page (embedded)" with its URL, so nobody mistakes it for our UI. Its content in the mock is generic: neutral rows and generated thumbnails, with no branding.
3. **"Playing on Family Area".** This is a truthful room line: the device name, with a status dot that is brass when playing and grey when paused. No other rooms are offered, because none exist on this route.
4. **Long titles** take two lines at 44 px, then truncate. The artist takes one line. Checked in `#paused&long&noseek&light`. With seek unavailable, the bar turns into a plain indicator.
5. **Sign-in fallback (`#blocked`).** If Google refuses sign-in inside the embedded view, the right pane says so in one sentence and offers one button: "Sign in on the Portal browser". The tabs dim, and the left side keeps working from the media session.

## Biggest risk: the split only works if one page both plays and shows the queue
- If YouTube Music plays in the **Portal browser** and we embed a **second** copy, that second copy has its own separate queue. It wouldn't control or reflect the playing one. Android WebView and Chromium also don't share cookies, so the embedded copy would be signed out.
- So the honest build is this: **YouTube Music runs inside Immortal's own retained WebView**, the right pane *is* that player, and the left side reads its session. Today `WebAppActivity` destroys its WebView on close, so that would have to change. The WebView must also keep playing while the screensaver is up, which hasn't been tested.
- **Google may refuse sign-in in embedded WebViews.** Its own support pages describe this restriction, and the readiness review cites them. If it does, Library, Search and Queue in the pane are lost. The fallback is v1's model: play in the Portal browser, show what's playing on the left, and give the pane over to the `#blocked` message. That means the queue is visible only in the browser.
- Nothing native is proposed for building now. The human has parked native music work.

## States (hash)
`#split` (default; Queue tab, playing), `#paused`, `#library`, `#search`, `#blocked`, plus v1's `#start`, `#idle`, `#ambient`, `#ambient-paused` and `#walkup`. Flags: `&light`, `&noseek`, `&long`.

## Decisions for the human
1. Accept running YouTube Music inside Immortal's WebView (needed for a real queue pane), and prove sign-in works there before any design commitment.
2. If sign-in is blocked: should the split stay, with the right pane holding the fallback message, or fall back to v1's single-column layout?
3. Volume step granularity: Portal's 15 hardware steps (as mocked), or a smooth slider?
4. The open questions from v1 still stand: tap-to-control on the frame card, and how long the card stays on the frame while paused.

---
## Carried over from v1
## Verdict
The existing player (`portal-premium-player-20261030`, native captures in `evidence/family-music-native/`) is well made, but it was made for a product that no longer exists. Every structural decision in it depends on Music Assistant: the Queue, Lyrics and Players tabs, Library and Playlists, rooms, volume and the "Music Assistant" subtitle. That route was cancelled. The route we have today is YouTube Music running in the Portal's browser, read through the Android media session. That gives us title, artist, artwork, progress, play/pause, next/previous and seek, and nothing more. Shipping the current layout over that route would leave half the screen as permanently empty or disabled panels. That is dishonest, and it doesn't need to be.

## What works (keep it)
- Artwork leads, and the artwork supplies the colour. The neutral chrome defers to it.
- Large transport with a primary play button that is clearly bigger than the rest. Touch targets are generous.
- The careful work on unseen states: offline keeps the last-known artwork, live and unknown-duration tracks never fake a seek bar, and failed commands roll back. That discipline carries straight over to media-session truth.
- Photo continuity (the paused photo is held exactly across the music layouts) and the existing screensaver `NowPlayingSpec` card.

## Problems, ranked
1. **It shows capabilities the product doesn't have.** The Queue, Lyrics and Players tabs, Library, Playlists and room volume can't be backed by a media session. Disabled tabs tell the family "broken", not "elsewhere".
2. **There is no way to start music.** Nothing on the player says where music comes from. The first-run problem (sign in once in the browser, then choose something) isn't designed at all, and this is the moment that decides whether the feature ever gets used.
3. **It ignores the room.** The Portal is a frame on a shelf. Most of the time music is heard while the screensaver is up. The player is designed as an app you sit in front of. What the person across the room sees, and what the person walking up touches, are afterthoughts (one card setting).
4. **The chrome is noisy.** Four unlabelled round icons (compact, photos, settings, close), a "Music / Music Assistant" header, a "Browse library" link and three modes (Full, Compact, Photos). Each one is a decision handed to the family.
5. **The type is too small for 1 to 4 m.** The title is about 25 CSS px and the metadata about 16 px. The hierarchy is fine at arm's length and gone from the sofa.

## Redesign (see `index.html`)
