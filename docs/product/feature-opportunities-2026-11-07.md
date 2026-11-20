# Feature opportunities — 7 November 2026

Product-management read of where Immortal earns a place on the shelf next. Source
checked: `docs/features/*`, the 216-task roadmap index, the music goal, the
6–7 November Home/music work, `app/src/main/java/com/immortal/launcher` and Portal
Toolkit's README/manifest. Nothing below is shipped or on the roadmap. These are design hypotheses, not
findings from user research.

## 1. Who uses a Portal here, and when

| Moment | Who / where | What Immortal already does | The gap |
| --- | --- | --- | --- |
| **Weekday morning, 07:00–08:30** | Parents and kids, Family Area (kitchen) | Sunrise wake-light, welcome greeting, Weather/Calendar/Timers/To-do/Alarm widgets, YouTube Music | "Do we need coats, will it rain at drop-off, what's missing from the list?" means walking over and reading several widgets |
| **After school / bedtime** | Kids, either room | Bedtime story (TTS), Lamp with red night mode, countdown chips, soundscapes, sleep timer, one-way intercom/baby monitor | Kids can't read a list or a clock face. Bedtime takes 4–5 separate taps across Tools |
| **Evening** | Whole family, Living Area | Photo frame/screensaver, ambient music card, multi-room audio, pinned HA dashboard, overnight night clock | Music and messages stay in the room where they started. "Dinner's ready" is still shouted |
| **Away / on the phone, plus guests** | Parent on the Pixel; visitors | Toolkit: photo upload, remote, scenes and schedules, settings. Browser remote | The phone can only *manage* the Portal. You can't share a photo or note into it from another app. Guests ask for the Wi-Fi password |

## 2. Opportunities

Effort: S ≤ 1 week, M 1–3 weeks, L > 3 weeks.

**O1. Shared household list (both Portals + phone).** Make the existing To-do list the
household's single list. *Problem:* `TodoStore` is device-local SharedPreferences with no
remote route, so the kitchen and living-room lists drift and nobody can add "milk" from
the shop. *Effort M.* Reuse `TodoStore`'s pure `withAdded/withToggled` helpers, a new
`/remote/list` route beside `RemoteCompanionRoutes`, and the revision/stale-save pattern
already used for scenes. Use `RemoteRoster` for peer sync and add a Toolkit list tab.
*Risks:* merge conflicts between rooms (use last-writer-wins per item with tombstones),
and the list must work offline.

**O2. "Share to Portal" from any phone app.** *Problem:* Toolkit's manifest only has a
`MAIN` intent filter. Getting a photo from WhatsApp onto the frame means saving it, opening
Toolkit and picking it again. *Effort S–M.*
Add an `ACTION_SEND` target (image/*, text/plain) to Toolkit with a room picker. Images go to
the existing confirmed `/remote/photo-upload`, text to a note, URLs to `/remote/launch`.
*Risks:* large files on a slow LAN (the existing per-file ledger already reports this),
and accidental sends to the wrong room. The room has to be confirmed.

**O3. Fresh-photo moment.** Newly shared photos jump the queue and show "New from
Rohit · just now". *Problem:* an upload disappears into a shuffled library of thousands.
Nothing in `SlideshowLoader`/`RemoteCompanionPhotos` prioritises recent arrivals. *Effort S.*
Reuse `PhotoCaption` badge rendering (the amber "years ago today" badge) and the import ledger
for sender/time. *Risks:* the sender's name has to come from the phone pairing name, never
from account data.

**O4. "Will it rain before I'm out?" strip.** *Problem:* the hourly forecast doesn't
answer "in the next 40 minutes". *Effort S.* `Weather.kt` requests only `hourly=weather_code,temperature_2m`.
Open-Meteo's keyless `minutely_15=precipitation` adds a one-line nowcast to the Weather widget
and the screensaver. *Risks:* the nowcast is less accurate in some regions, so label it
"likely", not "will".

**O5. Departures widget.** *Problem:* the school run starts with "when's the next bus?".
*Effort S–M.* `Transit.kt` (Transitous/MOTIS, keyless) already exists **with no callers**.
Expose it as a "More" widget kind in `HomeWidgetPicker`. *Risks:* Transitous coverage
for the household's stops has to be checked before design starts. Hide the widget if
coverage is empty.

**O6. Call everyone to dinner.** One tap: every Portal chimes, shows "Dinner's ready" full
width and speaks it. *Problem:* the intercom is live push-to-talk (someone has to listen
right then), and Ping is a bare tone with no meaning. *Effort S.* Extend the `PingService`
UDP payload with a preset message ID. Render it with `NotificationOverlay`, speak it with
`OneShotSpeechOwner`/TTS and respect quiet hours from `ChimeConfig`. Add the same buttons
to Toolkit ("Home in 10 min"). *Risks:* spoofing on the LAN. Sign the payload with the
roster token and keep messages to fixed presets.

**O7. Follow-me music.** "Move to Living Area" on Now Playing. *Problem:* walking from the
kitchen to the sofa means pausing in one room and searching again in the other. *Effort M–L.*
`YtMusicBridge` already does play/seek. It needs a track identity plus position, sent
peer-to-peer, and the target's `YtMusicWebHost` resumes it. *Risks:* YouTube Music lets an
account play on one device at a time, so the handoff must pause the source first. Each
Portal needs the human sign-in, which is still pending on the music goal. Don't start
until the music acceptance gates close.

**O8. Picture routines for kids.** Morning and bedtime checklists made of large picture
cards ("teeth, shoes, bag") with a shrinking time bar to departure. *Problem:* pre-readers
can't use To-do and can't read the clock. *Why Portal:* it sits at kid height in a fixed
place and runs every day. *Effort M.* Reuse `TodoStore` (one list per routine), `TimeProgress.kt`,
a scheduled trigger from `RemoteScenes`, and the Countdowns emoji model. Parents edit in
Toolkit. *Risks:* gamification creep. Keep it to cards, a tick and a cheer.

**O9. One-tap Bedtime.** A built-in "Bedtime" action: story → red Lamp → soundscape →
screen off at the end of the sleep timer. *Problem:* today that's five tools.
*Effort S.* The scene engine exists, but its steps are nav/app/settings/photo/wait.
Add Lamp/soundscape/story steps or a native composite. *Risks:* overlap with user scenes.
Ship it as a preset users can edit, not as new machinery.

**O10. Speak my day.** A tap or a wave reads time, weather and nowcast, the next two calendar
events and open list items. *Problem:* elders and kids can't read small widgets from 3 m.
*Effort S–M.* Reuse the welcome-overlay speech path, `TodayCalendar`, Weather and
`GestureCamera` wave detection. No speech recognition is needed. *Risks:* calendar titles
are spoken aloud to anyone in the room, so add a per-calendar privacy toggle.

**O11. Guest card.** A "Guests" tile showing a Wi-Fi QR code plus house basics (door code
optional, checkout time), with personal photos and calendar hidden while it's on. *Effort S.*
`lanSetupQr(text)` already renders arbitrary QR codes (it can encode `WIFI:` strings).
Hide photos and calendar with existing source/visibility settings. *Risks:* the Wi-Fi
password is stored in plain preferences. Keep it out of snapshots and backups, the same as
existing source credentials.

**O12. Celebration day.** On a countdown's day, the screensaver shows a banner and a
gentle chime ("Happy birthday, Ana 🎂") and the welcome greeting uses it. *Problem:* countdown
chips stop at "0 days" and nothing happens on the day. *Effort S.* `CountdownConfig`
dates plus a `PhotoFrameController` overlay and `ChimePlayer`. Dismissible.

**O13. Large-text household mode.** One switch raises type size and contrast and enlarges
touch targets on Home, Tools and the screensaver clock. *Problem:* grandparents visiting.
The Ive-lens persona already demands 1–4 m legibility. *Effort M.* It's a settings
registry key plus Compose scale tokens, and every widget needs visual QA at both resolutions.
*Risks:* the 2×4 slot grid overflowing. It needs a design pass for each widget.

## 3. Top 5 (value × frequency × feasibility)

**#1 O1 Shared household list.** It's used every day by everyone, and today it's
broken by design: two lists that never sync. It reuses existing stores and
revision patterns.
- An item added on either Portal or in Toolkit appears on the other two within 5 s on a healthy LAN.
- Ticking or removing in one place is reflected everywhere. A concurrent add and remove never loses the add.
- If a room is offline, it shows the last list with a "Not synced" hint and catches up on reconnect.
- The widget at 1×2 shows the top 4 open items. Tapping opens the full list with a large add field.

**#2 O2 + O3 Share to Portal with a fresh-photo moment** (design them together).
Sharing photos is the reason a family keeps a frame. This removes four steps and makes
each share feel received.
- Toolkit appears in Android's share sheet for images and text.
- A room picker defaults to the last room and allows "Both".
- The upload confirms only after the Portal's ledger acknowledges it. Failures name the file.
- The shared photo shows within the next slideshow turn with "New from <phone name> · <time>" for that turn only.
- Shared text becomes a note on Home, attributed and dismissible.

**#3 O6 Call everyone to dinner.** It happens several times a day, and the effort is S
on top of the existing UDP ping, overlay and TTS.
- Six fixed presets plus "Ping", available on Home Quick controls and in Toolkit.
- Every other Portal shows a full-width card for 30 s, plays a chime and speaks the message once.
- Quiet hours block sound and speech but still show the card.
- An unsigned or unknown-sender packet is ignored.

**#4 O4 Rain nowcast** (add O5 if coverage passes). The morning's most-asked
question; S effort, keyless, extends an existing widget.
- The Weather widget adds one line, for example "Rain likely from 08:20", or "Dry for the next 2 h".
- The same line shows on the screensaver under the weather when rain is due within 2 h.
- No line appears when data is missing. It never shows a stale nowcast older than 30 min.

**#5 O8 Picture routines for kids.** High value for the households Portals were bought
for. It turns the kitchen Portal into the morning coach, and it reuses To-do, schedules
and TimeProgress.
- A parent creates a routine (cards with an emoji/icon and a label, start time, deadline) in Toolkit.
- At start time, Home shows the routine full screen with a time bar to the deadline.
- A card is ticked with one large tap. When all are done, a cheer plays and Home returns.
- It skips weekends or holidays per the routine's weekday selection.
- At its smallest size, the cards stay readable from 3 m (≥ 64 sp labels on 2160×1440).

## 4. Rejected ideas

- **Video calling between Portals.** It needs WebRTC and camera ownership beside Meta
  Calls, which only works through Meta Home as trusted caller. That means a high privacy
  risk and large effort.
- **Speed-dial "Call Grandma".** Meta Contacts requires a trusted caller, so a contact
  deep link isn't possible without root.
- **Room camera peek from the phone.** A camera feed out of a family room is a trust
  breaker. The audio baby monitor already covers the real need.
- **Generic voice assistant or LLM chat.** Jarvis is an external app whose provider and
  voice health are unestablished. Don't build on it until the bridge is proven.
- **Push notifications from phone apps to the Portal.** There's no Play Services or FCM
  on the Portal, and HA MQTT notify already covers automations.
- **Doorbell camera pop-up.** It mostly exists already: MQTT notify supports image, sound
  and tap target, plus the RTSP viewer.
- **"On this day" memories.** It already exists as the frame badge and the Photo memory widget.
- **Chores and allowance tracking.** Low frequency for this household, it needs
  per-person identity the device doesn't have, and it would grow into an app.
- **Presence-based personalisation (who is standing here).** Meta's presence and face
  signal is signature-protected, and face recognition at home is a privacy line.
