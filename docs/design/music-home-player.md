# Music beyond the Music screen: Home card and header controls

Owner request (2026-11-17): music paused when going Home, and there was no control for it outside
the Music screen. YouTube's rules stay the hard constraint: playback only in the official player
while it is visible, at least 200×200 CSS px, never covered, overlapped or tinted; no hidden or
audio-only playback.

## One player, two hosts

There is still exactly one official YouTube player (one WebView, `YouTubePlayerHost`). It is
re-parented between hosts, never recreated, so the page does not reload and playback continues:

| Host (`MusicPlayerHostKind`) | Where | Counts as on screen |
|---|---|---|
| `MUSIC_SCREEN` | the full Music stage | composed and Home RESUMED |
| `HOME_CARD` | Home's Now Playing card (Home, Apps, Tools tabs) | composed and Home RESUMED |

`MusicPlayerHosts` (pure) picks the best-ranked host that is composed and active. "Visible" =
some host shows it; that drives `YtEngineState.foreground`, so the media keys, the phone remote,
"Move to…", the sleep timer and the quiet-hours cap all keep their existing meaning. Host swaps are
not atomic (one surface leaves composition a frame before the other attaches), so a hide waits
`MusicHostHandoff.SWAP_MS` (250 ms) before pausing. The player is released 30 s after no host shows it.

Logs (`ImmortalYtEngine` / `ImmortalYtPlayer`): `host A -> B: same player, no reload`,
`player re-parented (same WebView, page kept)`, and `player page loading` only when a player is
really (re)built.

## Home's Now Playing card

- Shown whenever a song is current and the Music screen is closed, except while customizing Home
  or after ✕ (`MusicHomeCardRules`). ✕ pauses and hides; the card returns when music plays again.
  A card left paused hides itself after 10 minutes.
- Bottom-right, above the favourites strip, floating over the widget area (the saved Home layout is
  untouched). 788×256 dp: the player at the Music stage's 384×216 dp, then title/artist (tap opens
  Music), ✕, previous / play-pause / next (64–76 dp) and the speaker volume (quiet-hours capped).
  Colours come from the song's artwork palette, light or dark like the Music stage.
- A playing card keeps the screen awake and blocks the screensaver, like the Music screen.

## Header chip

`MusicV3HomeChip` gains Play/Pause and Next (64 dp). Play brings the card back (the player must be
on screen to play) and starts the song there; after a restart it first loads the saved queue.

## What may cover the player (`MusicCoverRules`)

- **Pause while shown, resume after:** Call everyone / dinner, Timer done, the doorbell card, the
  follow-me "Continue here?" ask (all full-screen scrims), and Home's dialogs and sheets. Music
  pauses while one shows; when the last closes it resumes only if it was playing when covered. Any
  pause meanwhile, or leaving the screen, cancels that. The sleep timer is kept.
- **Avoid it:** Home's bottom toasts centre in the space left of the card; a bottom-anchored MQTT
  notification moves to the top while the card shows the player. The follow-me banner is a top
  strip and does not reach it.
- Customize hides the card (it would sit on the widgets being arranged), so the music pauses as
  when leaving the screen.

## Floating window (picture-in-picture): not available on the Portal

Checked on the Family Portal (Android 10, 2026-11-17):

- `pm list features` / `cmd package has-feature android.software.picture_in_picture` → true, and
  the PinnedStackController reports a 588×331 px default window (would be 523×294 CSS px).
- But inside the app `PackageManager.hasSystemFeature(FEATURE_PICTURE_IN_PICTURE)` returns
  **false** (Meta's framework hides it from apps).
- The home Activity can't float anyway (Android refuses picture-in-picture for the home type), so
  a separate standard Activity with `supportsPictureInPicture="true"` and its own task was started
  over the opened app (Material Files) while music played. Its `enterPictureInPictureMode(16:9)`
  returned **false**; the task record showed `mSupportsPictureInPicture=false`.

So the experiment was removed. Opening another app from Home pauses the music (250 ms after Home
leaves the foreground) and Home's card shows it paused on return, with the one-time "Music paused"
note. A future firmware that exposes the feature would need the Activity reinstated.
