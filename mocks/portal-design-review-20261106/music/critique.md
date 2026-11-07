# Portal music player: critique and redesign

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
- **One Now Playing surface, built only from session truth.** Artwork, then status, title and artist (56/30 CSS px), then progress, then previous, play/pause and next. The background is a quiet wash of the artwork's colour (Android Palette, computed natively). If the session doesn't advertise seek, the knob disappears and the bar becomes a plain indicator (`#playing&noseek`).
- **Say plainly where everything else lives.** One button, "Queue and library", opens YouTube Music. There are no disabled tabs. Queue, lyrics, shuffle, repeat, favourites and rooms are gone. They come back only if a Music Assistant-class route returns (cancelled, so future work).
- **Starting music.** *First run* (no YouTube Music session ever seen on this Portal) is a two-step sequence: sign in with Google in the browser, then choose something, with one "Open YouTube Music" button. *Nothing playing* (a session has been seen before) is one sentence and the same button. We can't detect "signed out" from a media session, so we don't pretend to. "Has ever seen a session" is honest, automatic state, not a setting.
- **Across the room: the frame keeps the room.** Over the photo screensaver, the existing card grows to art 120 px, title 36 px and artist 24 px, with a 3 px progress line as the only "live" signal. No box and no equaliser animation. *Paused* desaturates the art, drops the line and reads "Paused, Artist".
- **Walking up: same card, now touchable.** A tap on the card (the Dream is already `isInteractive = true`) gives it a material and a row of previous, play/pause, next and open player. Nothing else on the frame moves. A tap elsewhere keeps today's dismiss behaviour.
- **Palette** evolves `quick-controls-review.css` toward warm neutrals: `#1b1915` ground, `#f4eee4` ink, `#a89f90` secondary, and one accent, brass `#d9b26a`, used only for progress and focus. Light theme included. The settings shell is unchanged.

## States that matter
| State | Across the room | Walking up |
|---|---|---|
| First run | Not shown on frame | Two steps and "Open YouTube Music" |
| Nothing playing | Card absent, plain frame | One line and "Open YouTube Music" |
| Playing | Card: art, title, artist, progress line | Card gains transport |
| Paused | Card dimmed, "Paused" | Play is primary |
| Session lost (browser closed) | Card fades out, no stale "playing" | Falls back to Nothing playing |

## Decisions for the human
1. Retire the Full, Compact and Photos modes in favour of Now Playing plus the screensaver card? (Recommended. The frame *is* "photos beside music".)
2. Should a tap on the card open walk-up controls, or go straight to Now Playing? This needs a hit-region exception in `PhotoDreamService`'s tap handling.
3. How long can the screen stay paused before the card leaves the frame (suggested: 10 min)?
4. Volume: rely on the hardware buttons (as mocked), or add a slider using `AudioManager`?
5. Risk to verify before building: background or screensaver playback continuity of YouTube Music in Chromium, and whether its session publishes a non-blank title on Android. Both are unproven per the 2 November readiness review, and the whole design rests on them.
