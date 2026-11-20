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
  or after it hid itself (`MusicHomeCardRules`): a card left paused hides after 10 minutes (only
  while paused) and returns when music plays again.
- 788×256 dp: the player at the Music stage's 384×216 dp, then title/artist (tap opens Music),
  minimize, previous / play-pause / next (64–76 dp) and the speaker volume (quiet-hours capped).
  Colours come from the song's artwork palette, light or dark like the Music stage.
- **Draggable** (`MusicHomeFloatingPlayer`, `MusicFloatMath`): drag it by its chrome (the player
  keeps its own touches) anywhere in Home's content area, between the header bar and the
  favourites strip / dock, with a 24 dp side and 16 dp top margin. A drag may stretch 12 dp past the
  bounds; the release springs it back inside and snaps onto an edge within 32 dp. It only moves the
  same view (`Modifier.offset`): no re-attach, no reload. First appearance: bottom-right.
- **Minimize, not close**: the old ✕ (now a collapse glyph, "Minimize player, music keeps playing")
  turns the card into a video-only tile: the same player at 208×208 dp (YouTube minimum 200), and
  beside it — never over it — a 64 dp rail with expand and a drag grip. The player keeps playing
  (still visible, uncovered), so the header chip keeps controlling it. Expand restores the card.
  The player is always the card's first child, so switching modes only resizes the same view.
  Only Pause (chip, card, media keys, a phone) or the tile's ✕ stops the music.
- **The tile's ✕ = pause and close** (`MusicTileCloseRules`, `MusicTileClose`; owner, 2026-11-17: "if I
  can close it, it should not stop the music" — YouTube allows no background playback, so closing
  must pause, and this was offered as the default). A small ✕ (22 dp glyph, 64 dp target) tops the
  rail, above expand. A tap pauses (queue and position saved: a pause, not a stop) and hides the
  tile; the header chip then offers "Resume · <title>" for the same song, `/remote/ytmusic` reports
  `paused`, and other Portals' follow-me sees the room not playing, exactly as after Pause.
  "Paused · Undo" (Home's inverted toast pill, Undo a 64 dp target) shows for 5 s on whichever tab
  it was closed; Undo brings the tile back and resumes if it was playing. Any request to play — the
  chip's Play, a phone's Play, Music, a move here from another Portal — also brings the tile back
  (also lifts the 10-minute paused auto-hide, which a phone's Play could not before). Closing does not
  show the one-time "Music paused" note: that note now keys off `wantsPlay` (the user's intent), not
  the lagging player status. The tile's size is unchanged, so the Mini's compact layouts (800 dp
  upright, 740 dp tall turned) fit it as before.
- The card and the tile each remember their own spot on this Portal
  (`MusicFloatPositionStore`, SharedPreferences `immortal_music_home_card`, `v1:<fx>,<fy>` fractions
  of the free travel, so a spot survives another tab or a size change).
- A playing card or tile keeps the screen awake and blocks the screensaver, like the Music screen.

## Header chip

`MusicV3HomeChip` is compact and fixed at 198 dp: the song's cover (tap opens Music), Play/Pause
and Next (64 dp). No title text; the cover's accessibility label names the song. Play brings the
card back (the player must be on screen to play) and starts the song there; after a restart it
first loads the saved queue.

`HomeHeaderBudget` keeps every round header button at 68 dp: below the needed width optional pieces
drop in order — mini player, "Connected" text, room name, wordmark, and last the chip. The room
name also ellipsizes as a weighted child. At 1920 dp (the Portal) everything but the legacy mini
player fits; at 1280 dp the chip and all buttons stay, the text goes.

Checked on the Family Portal (2026-11-17, 1920×1280 dp): chip 223 px (198 dp), every header button
77 px (68 dp), paused or playing.

## What may cover the player (`MusicCoverRules`)

- **Pause while shown, resume after:** Call everyone / dinner, Timer done, the doorbell card, the
  follow-me "Continue here?" ask (all full-screen scrims), and Home's dialogs and sheets. Music
  pauses while one shows; when the last closes it resumes only if it was playing when covered. Any
  pause meanwhile, or leaving the screen, cancels that. The sleep timer is kept.
- **Avoid it** wherever it was dragged (`MusicCardAvoidance`, fed by the card's resting rectangle):
  Home's bottom toasts centre in the wider space beside the card or tile when it reaches their
  220 dp band; a full-width MQTT notification takes the screen edge away from it (bottom → top while
  it rests in the lower half, top → bottom in the upper half). The follow-me banner is a top strip
  above the content area and does not reach it.
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
