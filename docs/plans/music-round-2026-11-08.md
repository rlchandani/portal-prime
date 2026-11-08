# Music round (2026-11-08): suggestions, history, follow-me, resume, sleep timer, night volume, phone

Owner decisions: search suggests as you type from local data (no YouTube quota per keystroke; Enter
still runs the real YouTube search); a "Recently played" history kept by Hearthpane (YouTube's API
has no watch history); follow-me = "Ask first" by default with a per-room Settings switch to fully
automatic; plus Resume chip on Home, music sleep timer, quieter music at night, and phone search +
history on Portal Toolkit's Music page. One phone app; Portals update over OTA.

## Shared rules

- YouTube rules unchanged: playback only in the visible official player; nothing downloaded.
- History is household data kept on the Portals (and shown on paired phones). Never sent to any
  server. Clearable from Portal Settings ("Clear music history") and the phone.
- Copy says "Hearthpane" (Portal app) and "Portal Toolkit" (phone app).
- Kotlin, small files (<400 lines), immutable state, named constants, JVM tests for logic.

## A. Portal music features (Hearthpane)

1. **Recently played** — record an entry when a queue item actually starts playing (engine reports
   PLAYING for the current entry; ads don't count; replays within 10 min of the same video update
   the timestamp instead of adding). Entry `{videoId, title, artist, thumb, playedAtMs, room}`.
   Keep the newest 200, dedupe by videoId (newest wins) when listing. Broadcast each new entry to
   the household (`HouseholdBus` type `music_played`, signed like other household messages) so
   every Portal's history covers all rooms; merge on receipt.
   Library gets a fourth chip **Recently played** (after Artists): rows with cover, title, artist,
   "Living Area · 2 h ago"; tap = play now (same as a search result tap); ⋯ = Play next / Add to
   queue / Remove from history. Empty state: "Songs you play show up here."
2. **Search suggestions** — as the user types (debounced ~150 ms, min 1 char), show up to 8
   suggestions under the field from local data only: recent searches (last 20, stored), history
   titles/artists, liked songs titles, artist names. Prefix/word-start matching, case/diacritic
   insensitive, ranked: exact prefix > word-start > contains; recent > older. Tapping a song
   suggestion plays it now; tapping a search/artist suggestion runs the YouTube search (quota).
   When the field is empty, show "Recent searches" and "Recently played" (top 5).
3. **Resume chip on Home** — when nothing is playing but a saved queue exists, the Home music chip
   reads "Resume · <title>"; tap opens Music and plays from the saved position.
4. **Sleep timer** — moon button on the Now Playing card: Off / 15 / 30 / 60 min / End of song.
   Shows "Stops in 23 min" on the card; at the end fades volume over 10 s, pauses, restores the
   volume. Cancelled by Pause, Clear, or Move to another room.
5. **Quieter at night** — Settings › Music: "Quieter music during quiet hours" (default ON) with a
   max level (default 6 of 18, slider). During quiet hours, starting playback or raising volume
   (Portal slider, phone, remote) caps STREAM_MUSIC at the max; outside quiet hours no effect.
   Announcements (AnnouncementVolume on STREAM_ALARM) are unaffected.
6. **Phone routes** (extend `/remote/ytmusic`, paired-session auth like today):
   - `GET /remote/ytmusic/history` → `{ok, history:[{videoId,title,artist,thumb,playedAtMs,room}], total}` (newest first, ≤ 200)
   - `POST /remote/ytmusic/history` `{action:"clear"}` or `{action:"remove", videoId}` → `{ok}`
   - `POST /remote/ytmusic/suggest` `{q}` → `{ok, suggestions:[{text, kind:"search"|"song"|"artist", videoId?, artist?, thumb?}]}` (local only, no quota)
   - `POST /remote/ytmusic/search` `{q}` → `{ok, tracks:[{videoId,title,artist,thumb,durationMs?}], quotaLeft}`; 429 `{error:"quota"}` when the day's searches are used up
   - `POST /remote/ytmusic` `{cmd:"playtrack", videoId, title, artist, thumb, playAnyway?}` → same statuses as `play` (done|opening|quiet|declined); `{cmd:"queuetrack", videoId,title,artist,thumb, position:"next"|"end"}` → `{ok,status:"done"}`
   - `POST /remote/ytmusic` `{cmd:"sleep", minutes: 0|15|30|60|-1}` (-1 = end of song, 0 = off) → `{ok,status:"done"}`
   - `GET /remote/ytmusic` player state adds `sleepUntilMs: number|null`, `sleepEndOfSong: bool`, `nightCap: {active, maxLevel}|null`

## B. Follow-me music (Hearthpane)

- Presence comes from `MetaPresence`/`MetaPresenceReader` (Meta's camera presence verdicts via
  logcat): present / absent / unknown per Portal.
- Rooms share state over `HouseholdBus`: each Portal broadcasts `presence` transitions and, while
  its music plays, a periodic `music_live {title, artist, thumb, positionMs, roomAbsentSinceMs}`
  (every 20 s and on change).
- Rule (pure, unit-tested `FollowMePolicy`): a room R that becomes present (absent→present) while
  room S is playing music, S has been absent ≥ 60 s (or becomes absent within 60 s after R's
  arrival), R isn't playing, R isn't in quiet hours/routine/call → follow-me triggers on R. If S is
  present, or more than one room is present-and-eligible, do nothing. Cooldown 5 min per pair.
- Per-room setting (Settings › Music › "Music follows you"): **Ask first** (default) / **Automatic**
  / **Off**. Ask first: R shows a large card "Continue <title> here? · from Family Area" with
  Continue / Not now, auto-dismissed after 30 s. Automatic: R starts the move immediately and shows
  "Music followed you from Family Area" with Undo (moves it back) for 15 s.
- The move itself reuses the existing `MusicHandoff` pull (R recalls from S, i.e. the "Bring it
  back" path), which already handles queue/position and the visible-player rule.

## C. Portal Toolkit (phone) Music page

- Search field at the top of Music (for the selected room): suggestions as you type from
  `/remote/ytmusic/suggest` (debounced 200 ms) plus local recent searches; Enter or tapping a
  search suggestion calls `/remote/ytmusic/search`; result rows: tap = play now on the room (quiet
  hours asks like today), ⋯ = Play next / Add to queue. Quota exhausted → "YouTube searches are used
  up for today on <room>. Try again after midnight."
- "Recently played" section (from `/remote/ytmusic/history`): tap = play now; ⋯ = Play next /
  Remove; "Clear history" with confirm.
- Sleep timer control on the room card (Off/15/30/60/End of song) and "Stops in N min" line.
- Show "Quieter at night: max 6" note when `nightCap.active`.
- Older Hearthpane (404) → existing "Update Hearthpane on <room>…" copy.
