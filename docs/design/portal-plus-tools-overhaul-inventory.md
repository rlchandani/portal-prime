# Portal+ Tools overhaul: native inventory

Read-only source audit, October 6, 2026. Scope: all twelve Tools and their opening,
setup, picker, error and return flows. This document supports browser mocks; it does
not change Android or establish that hardware, feeds or audio worked on a device.
No device commands, network requests or private configuration were used.

**Existing** below means implemented in the audited source. **Proposal** means the
chosen browser redesign: retain a categorized Tools library alongside one tool
workspace, keep setup in an in-context sheet/pane, allow explicit fullscreen only
for camera viewing, lamp preview and alarm ringing, and keep the story reader
immersive inside the host. Categories and simultaneous library/workspace visibility
are proposals: native currently replaces an uncategorized grid with the workspace.

## Critical boundaries for the mock controller

- **Two timer systems:** Tools uses `TimerConfig` for several start/cancel timers.
  Home uses `TimerStore` for one pause/resume/reset timer. Opening the Tools timer
  body from Home does not unify them. Do not imply shared pause/resume or shared data.
- **One alarm:** `AlarmContent` exposes enabled, time and repeat days, but preserves
  a stored label without offering its editor. No multiple alarms or sound picker.
  Static edge: a one-shot fire disables the configuration; Snooze schedules
  `AlarmReceiver`, whose enabled check can then suppress that snoozed ring.
- **Collection editors add/remove:** cameras and countdowns have no existing edit,
  reorder or duplication action. A mock edit flow would be new functionality.
  Countdown validation checks numeric ranges, not valid month/day combinations.
- **External prerequisites:** cameras need a supplied compatible RTSP stream;
  Intercom needs another reachable Portal broadcasting on the LAN; voice recording
  needs microphone permission. Neither uses the protected far-field microphone
  array. Cameras here are external feeds, not the Portal's onboard camera.
- **Location is shared IP geolocation:** ISS/Aurora call `Weather.coordinates`.
  They have no location picker, GPS flow or manual coordinates in their tool bodies.
  Missing location and feed failures are not independently identified by their APIs.
- **Incomplete feedback:** story TTS has no ready/error/completion callback exposed
  to its UI; Intercom status does not track later socket/audio failure; Speed can
  report completion with only partial metrics. Distinct diagnostic mock states must
  be labeled as proposals where native cannot report them.
- **Docs can lag code:** `docs/features/tools.md` omits Alarm and describes
  Intercom as push-to-talk; actual code starts/stops a continuous one-way stream.
  Its claim that other tools have no persistent settings overlooks alarms, timers
  and notes. Use the source inventory below for capabilities.

## Existing opening and return map

| Entry | Existing destination and return |
| --- | --- |
| Home dock Tools, or Home's synthetic Tools app entry | Selects retained Home Tools tab; renders `ApprovedToolsScreen(embedded = true)`. Opening a tool preserves the grid scroll. Workspace Back returns to Tools list; Back at list selects Home. |
| Direct `ToolsActivity` wrapper | Same Tools screen in `ApprovedAppsShell`; Back at list finishes the wrapper. No individual tool deep-link argument is implemented. |
| Tools library tile | Sets saveable `ToolPage`; maps all twelve bodies below into `ApprovedToolWorkspace`. Fixed Tools return and title; bounded scrolling body, except stories own their scrolling. |
| Settings routes/search and existing wrappers | Alarm, Countdowns and Intercom share bodies in the Settings pane; Cameras and Lamp launch their Activity settings wrappers. Standalone Alarm/Countdown/Intercom wrappers remain available to existing callers. Return uses the caller's Settings back callback or Activity finish. |
| Home Alarm widget/body or widget settings | Opens Settings `alarm`; widget also toggles the same `AlarmConfig`. This is not currently a direct Tools detail opening. |
| Home Timers widget heading or widget settings | Opens `ApprovedHomeTimersPage`, a dialog hosting `TimersOverlay` with Tools shell chrome. Android Back dismisses to Home; explicit Tools return dismisses and selects Tools tab. Inline widget controls/custom picker still use `TimerStore`, separately. |
| Settings Advanced → Speed test | Opens a Settings row that selects Tools tab; user must choose Speed test. No direct speed detail route. Tools tile is visible only with Advanced enabled. |
| Camera View / Lamp Preview | Explicit Activity handoff from their controls; Back returns to the originating controls. Lamp returns changed preview values via Activity result. |
| Scheduled alarm | `AlarmReceiver` starts sound and alarm notification/fullscreen intent, with a best-effort Activity launch. Dismiss/Snooze finish the ring Activity; this can occur outside Tools and need not return to a Tools workspace. |
| Direct `BedtimeStoryActivity` | Legacy dark card over scrim: outer scrim/Back exits; reader Back first returns to story picker. Tools itself embeds the same body rather than launching this wrapper. |

`ApprovedLauncherTabs` retains visited tab compositions and gives hidden pages an
inactive lifecycle. Tools route and list scroll are retained; most tool drafts use
plain `remember` and disappear when their body is disposed by returning to the list.
Lamp controls use `rememberSaveable`, but are not persistent preferences. Notes,
Intercom and TTS stop/release their sessions on lifecycle stop/disposal; Speed
cancels transfers on stop and needs an explicit new run.

Home-style `Scrim`/`ToolCard` fallbacks exist in the shared overlays; their outer
scrim consumes taps and does not dismiss. Inside `ApprovedToolWorkspace` those
frames and duplicate titles are omitted. Nested `ToolDialog` and
`ApprovedSettingsDialog` remain real dialogs and dismiss through their own
callback. Native form Cancel for cameras/countdowns returns to the Tools list;
Cancel inside a picker/remove confirmation returns to the parent form.

## Per-tool inventory

### 1. Alarm (`alarm` → `ToolPage.ALARM`)

**Existing:** one persistent wake-up alarm. Enabled switch; hour 0–23 wraps by one;
minute wraps by five (preserves any existing minute offset); seven repeat-day
toggles Monday–Sunday; next occurrence text or “Alarm is off.” Default off, 07:00,
no repeat days. Changes save and reschedule immediately. No selected days means
one-shot. Stored `label` defaults to “Alarm” and is preserved, not editable here.

**States/prerequisites:** fully local; device clock/time zone and Android scheduling
determine firing. No empty alarm list. Scheduling falls back from exact to inexact
idle alarms, with no visible scheduling error. Ringing wakes/shows over lock screen,
displays live clock and label, plays sound, and offers Dismiss / Snooze 9 min.
One-shot snooze limitation is noted above; no promise of exact delivery from this audit.

**Nested/return:** time steppers and weekdays are inline, with no picker dialog.
Tools Back → library; standalone/Settings Back → caller. Ring Dismiss/Snooze stops
sound and finishes; destruction also stops sound.

**Proposal/feasibility:** keep one alarm's controls and status in the workspace,
optionally present existing time/day controls in a setup pane. Only ring is
fullscreen. A new label editor, extra alarms, selectable snooze duration or sound
selection is outside existing UI parity. Reframing existing controls is feasible;
repairing the one-shot snooze edge would require Android work.

Sources: `AlarmSettingsActivity.kt::AlarmContent`, `AlarmConfig.kt`,
`AlarmScheduler.kt`, `AlarmReceiver.kt`, `AlarmRingActivity.kt`.

### 2. Cameras (`camera` → `ToolPage.CAMERAS`)

**Existing:** saved list of `id`, name and RTSP URL; View and Remove per camera.
Add form: optional Name, RTSP URL initialized to `rtsp://`, Add and Cancel.
Blank name becomes “Camera.” Validation is the literal `rtsp://` prefix and length
greater than eight; URLs are trimmed on save. No separate credentials form,
discovery, thumbnails, editing, PTZ, recording or onboard-camera control.

**States/prerequisites:** empty collection says “Nothing added yet. Add an item
below.” Invalid address shows inline error. View needs a reachable RTSP source
compatible with Android `VideoView`/MediaPlayer; LAN viewing need not use internet.
No dedicated connection-loading indicator. Player error → unavailable dialog with
Retry/Done; missing saved ID → unavailable with Done. URLs may contain credentials;
mock fixtures must be fabricated/redacted, not read from saved configuration.

**Nested/return:** Remove opens Cancel/Remove confirmation. Add saves and stays,
resetting fields; form Cancel resets and exits to Tools list. View opens black
fullscreen player with MediaController and Back; Retry restarts the stream attempt;
Done/Back finishes to camera controls. Playback stops on Activity stop/disposal.

**Proposal/feasibility:** list/selected-camera context stays in workspace; Add and
Remove use its sheet/pane; only explicit View enters fullscreen. Setup-sheet Cancel
returning to camera workspace is a proposed navigation change. Reuse existing
native player boundary. Browser preview must be a sample: normal browser video
does not directly replace RTSP playback.

Sources: `CameraViewerActivity.kt::CameraControlsContent/CameraLiveStream`,
`CameraConfig.kt`, `ApprovedEditorsUi.kt`.

### 3. Countdowns (`countdowns` → `ToolPage.COUNTDOWNS`)

**Existing:** persistent events (`id`, label, emoji, month, day, year); list sorted
by days until occurrence, showing date, yearly/one-off and relative phrase.
Add: required Name, Icon (12 supplied emoji), Month 1–12, Day 1–31, Repeats every
year default on; Year 2000–2100 appears when off. Stored year 0 means yearly.
Date defaults to today; icon defaults to birthday. Palette: 📅, 🎂, 🎉, ✈️, ❤️,
🎄, 🏖️, 🎓, 💍, 🍼, ⭐, 🏠.
Today/tomorrow/future and past one-off phrases exist. Add clears name but retains
other choices; no edit/reorder/notifications.

**States/prerequisites:** local calendar; generic empty collection. Blank name
errors inline; numeric picker rejects invalid/range input. Day is not constrained
by month/year, so impossible dates can be stored and normalized by lenient
`Calendar`; do not portray strict calendar validation as existing.

**Nested/return:** each numeric field opens draft value + stepper + Save/Cancel
dialog (month/day stepper wraps; year clamps). Icon opens palette with selection
and Cancel. Remove has confirmation. These return to draft form; main Cancel
resets draft and uses host back (Tools list or Settings caller).

**Proposal/feasibility:** event list and relative dates in workspace; Add in setup
pane, icon/number selectors nested within it, confirmations return to list.
Calendar date picker/strict date validation would be new UI/behavior; keep those
explicitly proposed if mocked. Existing local storage and calculations are reusable.

Sources: `CountdownSettingsActivity.kt::CountdownSettingsContent`,
`CountdownConfig.kt`, `ApprovedEditorsUi.kt::ApprovedEditorsNumber`.

### 4. Lamp (`lamp` → `ToolPage.LAMP`)

**Existing:** ephemeral Brightness 5–100% (default 90%), Warmth 0–100% (default 60%),
Red night light toggle (default off; hides Warmth), and Preview. Sliders update
state live; clicking/focusing the value row opens numeric editor. Preview displays
solid warm-white/red panel, overrides window brightness and keeps screen awake.
It provides the same controls, Hide/Show controls, Sleep, and always-visible Back.

**States/prerequisites:** local panel; no network, empty list or saved preset.
Numeric editor validates range. Sleep calls existing `ScreenControl.sleep`;
device-admin activation is required, otherwise Sleep is a logged no-op. Preview
uses window brightness rather than a new global brightness permission flow.

**Nested/return:** brightness/warmth dialogs have numeric draft, ±1%, error,
Save/Cancel/Close; save applies, cancel discards. Explicit Preview launches Activity;
Back returns edited values to controls. Stop/destroy restores previous window
brightness/keep-awake flags. No persistent lamp scheduling or smart bulb control.

**Proposal/feasibility:** controls and setup/value pane in workspace; explicit
fullscreen preview only. Preserve red/warmth gating and result return. Native
panel output is already feasible; browser preview can only depict its color and
brightness, not set real Portal brightness or sleep the device.

Sources: `LampActivity.kt::LampControlsContent/LampSettings/LampPreview/LampControlDialog`.

### 5. Bedtime stories (`stories` → `ToolPage.BEDTIME`)

**Existing:** bundled five-story picker: The Tortoise and the Hare, The Lion and
the Mouse, The Little Red Hen, The North Wind and the Sun, The Ant and the
Grasshopper. Each has emoji, title and paragraphs. Selection opens scrollable
dark reader and automatically requests read-aloud. Stop / Read aloud controls TTS;
speech rate 0.85, engine language device locale. No downloads, accounts, custom
story creation, voice picker, bookmark, pause/resume or progress tracking.

**States/prerequisites:** text works offline without setup. Narration depends on
installed Android TTS engine/voice data; engine init, language/speak results and
utterance completion are not reflected in UI. “Speaking” can remain true after
completion/failure. No native story-empty/loading state for the bundled list.

**Nested/return:** picker → reader is in-body state, not a new Activity from Tools.
Android Back in reader stops speech and returns to picker; next Back exits to
Tools. Fixed Tools return exits directly. Lifecycle stop halts speech; disposal
shuts down TTS. Legacy standalone scrim behavior differs from Tools host.

**Proposal/feasibility:** immersive reader remains in the same workspace/host;
give reader an explicit return to story library and keep Tools navigation distinct.
No fullscreen exception needed. Native text/TTS reuse is feasible; reliable speech
ready/error/completion feedback would require new Android event wiring.

Sources: `ToolsActivity.kt::BedtimeOverlay`, `BedtimeStoryActivity.kt::BedtimeContent`,
`Stories.kt`.

### 6. Intercom (`intercom` → `ToolPage.INTERCOM`)

**Existing:** continuous one-way audio. This Portal's Wi-Fi address when available;
Broadcast this room / Stop broadcasting; Other Portal's address text field;
Listen / Stop listening. Modes idle/broadcasting/listening; “Starting…”,
“Connecting…”, startup/connect success or failure text. No room picker, pairing,
discovery, push-to-talk, video, recording or two-way call controls.

**States/prerequisites:** same reachable LAN, other Portal running broadcast,
TCP port 8724; internet not required. Broadcast requests microphone permission
and uses ordinary `MIC`, not privileged far-field capture. Permission denied has
message; bind failure reports port in use; connect failure names target. Blank
listen address does nothing; missing local IP hides address card. Startup success
means socket bound/connected, not verified audio delivery; later failures only log.

**Nested/return:** Android microphone permission prompt is the only external setup
UI. Host address is inline. Switching mode replaces the underlying session;
explicit Stop resets idle/status. Tools/Settings exit, lifecycle stop or disposal
stops sockets/audio; stale callbacks are guarded.

**Proposal/feasibility:** mode/status and address setup remain in workspace/pane;
permission outcome returns to requesting mode. Reuse native LAN engine; browser
mock simulates it. Do not imply browser support for raw TCP, a room directory,
background baby monitoring after leaving, or far-field capture.

Sources: `IntercomActivity.kt::IntercomContent`, `LanAudio.kt`, `LanAudioSessions.kt`.

### 7. Timers (`timers` → `ToolPage.TIMERS`)

**Existing Tools:** several persistent timers (`id`, generated label, duration,
end time); presets 1/3/5/10/15/30 minutes, Custom timer, live remaining-time rows
sorted by end time, and Cancel per timer. Custom fields Hours 0–23, Minutes 0–59,
Seconds 0–59, default 0:05:00; digit-only input and positive total required.
Preset labels are “N min”; custom label “Timer.” No rename/pause/resume/reset here.

**States/prerequisites:** local; “No timers running” empty state. Custom Start is
disabled outside validity (blank/unparseable fields are treated as zero). Schedule
uses exact alarm with idle fallback and no visible failure. Expiry plays chimes,
announces label and forgets timer; no fullscreen timer ring in this Tools path.
Running timers persist and are rearmed on boot/app start.

**Nested/return:** Custom dialog → Cancel or Start → timer list; row Cancel →
Keep timer / Cancel timer confirmation → list. Tools return closes workspace;
scheduled timers continue. Home's timer-page opening has separate return semantics
in the map above.

**Existing Home difference:** `TimerStore` supports one timer, pause/resume/reset
and stop ringing; custom minutes 0–180, seconds in five-second wrap steps. Its
custom picker and data must not be silently substituted for Tools' multi-timers.

**Proposal/feasibility:** workspace active list/presets, custom-duration pane and
cancel confirmation. Retain Tools behavior. Unifying Home timers or adding
pause/rename requires explicit native work, beyond a host redesign.

Sources: `HomeToolOverlays.kt::TimersOverlay`, `TimerConfig.kt`,
`TimerScheduler.kt`, `TimerReceiver.kt`, `HomeActivity.kt::ImmortalTimersWidget/CustomTimerDialog`,
`ApprovedHomeNavigation.kt::ApprovedHomeTimersPage`, `TimerStore.kt`.

### 8. Leave a note (`notes` → `ToolPage.NOTES`)

**Existing:** one local text note and one local voice memo. Text field; explicit
Save note → Saved checkmark; editing clears saved indicator; Clear note.
Record / Re-record → Stop recording; Play memo / Stop playback; Delete memo.
Saved text is independent of audio; audio stored as one AAC/M4A file. No multiple
notes, sharing, transcription, rich text or note titles.

**States/prerequisites:** blank note/no memo valid empty state; text works offline.
Record needs microphone permission and ordinary close-range microphone.
Permission/start error messages exist; too-short/failed recording is discarded
(under 500 ms guard) with retry message. Playback failure ends playback without
separate error UI. Re-record overwrites old file immediately, with no confirmation.
The engine exposes a peak-level method, but this Tools UI has no meter/duration.

**Nested/return:** permission prompt → recording or message. Clear text and Delete
memo have independent Cancel/confirm dialogs. Save stays in tool; exit loses
unsaved text draft without a discard prompt. Lifecycle stop ends/finalizes recording
and stops playback; disposal releases audio. No ongoing recording after exit.

**Proposal/feasibility:** text editor, saved status and voice actions in workspace;
destructive confirmations remain in-context. A discard/re-record consent flow is
proposed, not existing. Native storage/record/play reuse is feasible; browser memo
states should be fixtures, not evidence of Portal microphone access.

Sources: `HomeToolOverlays.kt::NotesOverlay`, `NotesConfig.kt`, `AudioNote.kt`.

### 9. Converter (`converter` → `ToolPage.CONVERTER`)

**Existing:** Category, From, To pickers; decimal Value default “1”; automatic
result rounded to four decimals. Category change resets units to first/second
while keeping input. Families/units:

| Family | Existing options |
| --- | --- |
| Length | m, km, cm, mm, mi, yd, ft, in |
| Mass | kg, g, lb, oz, st |
| Volume | L, mL, gal (US), qt (US), cup (US), fl oz (US) |
| Speed | km/h, m/s, mph, kn |
| Temperature | °C, °F, K |
| Currency | EUR, USD, GBP, RON, PLN, CHF, SEK, NOK, DKK, CAD, AUD, JPY, CZK, HUF |

**States/prerequisites:** measurement conversions entirely offline. Invalid number
→ “Enter a valid number”; result pending → “Calculating…”; currency without rate
→ “no rate (offline?)”. Currency uses keyless Frankfurter/ECB, six-hour persistent
cache per base, with stale fallback; same currency needs no fetch. No cache date,
stale-rate badge, history, manual Refresh or swap action is exposed.

**Nested/return:** Category/From/To each opens selectable list with checkmark;
selection dismisses to converter; Back dismisses active picker first, then tool.

**Proposal/feasibility:** conversion workspace with same-host selector sheets and
visible result. Existing computation/cache reuse is feasible. Date/staleness
metadata or a swap button would be extra UI/API behavior, not current parity.

Sources: `HomeToolOverlays.kt::ConverterOverlay`, `Converter.kt`.

### 10. ISS passes (`iss` → `ToolPage.ISS`)

**Existing:** automatically predicts on opening; Refresh passes; next-pass heading
and up to five upcoming rows. UI shows local start time, rise direction, peak
elevation/direction, set direction and visible badge. Model also has peak/end
timestamps, not individually displayed. Local SGP4 search covers 36 hours,
filters peaks below 10°, marks visibility from sunlit ISS/dark observer sky.
No map, live tracking, reminder, pass-selection detail or horizon editor.

**States/prerequisites:** “Finding passes…”; empty results share one message about
online/location. Missing coordinates, failed orbit load/scan and genuine no-pass
result collapse to empty list. Coordinates use weather IP cache (seven-day refresh,
stale fallback); orbit fetch Celestrak then wheretheiss.at, persistent TLE cache
refreshed after twelve hours with stale fallback. Cached inputs permit offline
prediction; no data-age badge or separate offline status.

**Nested/return:** no tool setup/pickers. Refresh reloads/recomputes with existing
caches, not guaranteed fresh download. Tools Back → library.

**Proposal/feasibility:** pass list, directions and guidance in workspace, refresh
in place. Native prediction reusable; distinct location/no-pass/feed-error states,
manual location setup or cache-age indicators require new reporting/control APIs.
Do not imply GPS, a compass sensor or camera-based sky identification.

Sources: `HomeToolOverlays.kt::IssOverlay`, `IssPasses.kt::predict/loadSat`,
`Weather.kt::coordinates`.

### 11. Aurora outlook (`aurora` → `ToolPage.AURORA`)

**Existing:** fetch on opening, Refresh outlook; classified headline/detail;
Kp now, next-24-hour peak and N/S viewing direction; NOAA source and after-dark,
clear-horizon guidance. Four chances: none/slim/possible/likely based on modeled
geomagnetic latitude and Kp. No map, cloud measurement, alerts or location editor.

**States/prerequisites:** “Checking the K-index…”; unavailable status shares
online/location guidance. Uses same cached IP coordinates. NOAA current/forecast
feeds; Kp pair cached fifteen minutes in memory, stale fallback if current fetch
fails. Forecast failure yields zero then floors to current Kp; “24 h peak” can
therefore be current-only without a warning. Refresh respects cache.

**Nested/return:** no setup or pickers. Refresh updates in place; Tools Back →
library. Low/no chance is a valid result, not a fetch error.

**Proposal/feasibility:** workspace outlook and viewing guidance, in-place refresh.
Native classification reusable. Separate location/feed/forecast/stale states are
proposed API reporting; no certainty of visible aurora or sensor-based sky reading.

Sources: `HomeToolOverlays.kt::AuroraOverlay`, `Aurora.kt::status/kpPair/classify`,
`Weather.kt`.

### 12. Speed test (`speed` → `ToolPage.SPEEDTEST`, Advanced)

**Existing:** Start test, then Run again. Connection server label (Cloudflare plus
region when trace works), progress text and indeterminate bar; ping, jitter,
download and upload metrics. Phases ping → download → upload → done. Download
and upload each run about ten seconds; zero/unavailable metrics display dash.
Fresh result object per run; no automatic run on tool opening.

**States/prerequisites:** internet/reachable Cloudflare required. Ready/testing/
complete/incomplete, all-zero reachability error, stopped message and exception
message. Start disabled while running. Phase failures are swallowed in runner,
so nonzero partial result can appear complete; no result history, server picker,
packet-loss metric, network diagnostics or explicit Cancel button.

**Nested/return:** no input form/picker. Tools Back exits; lifecycle stop cancels
sockets/job; re-entry does not resume transfer, requires Run again. Advanced
consent is a Settings-level prerequisite, not a speed-body popup.

**Proposal/feasibility:** Advanced library entry and same-host test workspace;
start/retry/progress/error fixtures. Preserve actual upload capability despite
tile subtitle mentioning only download/latency. Reuse cancellable runner in native;
mock transfers must stay simulated. Per-phase failure/cancel controls are proposals.

Sources: `ApprovedToolsSpeed.kt::ApprovedToolsSpeedScreen`,
`ApprovedToolsSpeedRunner.kt`, `HomeToolOverlays.kt::SpeedTestOverlay`.

## Proposed same-host flow contract (browser only)

The mock controller should preserve `origin`, selected tool, library
category/scroll and workspace state separately from the active setup/picker.
This is a proposed navigation contract, not an existing Android API.

| Flow | Proposed return |
| --- | --- |
| Library tile → tool workspace | Keep categorized library available; selected tool highlighted. Re-selecting the active bottom Tools tab clears tool selection and restores library filters and position. The left rail has no duplicate return button. |
| Workspace → setup sheet/pane → nested picker | Back/Cancel closes the topmost picker, then setup; returns to that workspace with draft policy made explicit. Main setup Cancel staying in workspace differs from native camera/countdown Cancel. |
| Add/Save/Start | Apply only the existing tool's supported operation; dismiss setup and show resulting workspace state. Preserve native immediate-save alarm/slider semantics unless explicitly marking a draft proposal. |
| Remove/clear/cancel confirmation | Confirm applies action; Cancel/Close returns to the same collection/draft. Never treat a confirmation close as a tool exit. |
| Camera View / Lamp Preview | Explicit fullscreen layer remembers originating workspace; Back restores it. Lamp returns preview values. Camera error Retry stays fullscreen; Done returns to controls. |
| Story picker → immersive reader | Remains inside host; reader Back → story picker; active bottom Tools tab → library. Stop narration on exit/hide. |
| Alarm ring | Only other fullscreen exception; show existing Dismiss / fixed nine-minute Snooze. Restore prior host if there is one; no invented mandatory Tools origin. Note one-shot limitation. |
| Home/Settings opening | Fixture controller may select the matching workspace and retain caller origin, but this is proposed routing. Native Home timer dialog and Settings Speed row do not deep-link directly into a tool workspace. |
| Dock/tab change or host hide | Dismiss active child UI and stop tool audio/transfers as applicable; do not claim persistent background Intercom, recording or TTS. Scheduled alarms/timers are distinct from foreground media sessions. |

Cover each tool's entry, supported nested actions, validation/empty state,
network/permission prerequisite where applicable, Back/Cancel and revisit.
Use synthetic sample data for saved items and results. Capability additions must
be visibly identified as proposals; a browser state does not verify native
hardware, network, scheduling or persistence behavior.

## Audit basis and checks

Primary files were read directly: `ToolsActivity`, `HomeToolOverlays`,
`AlarmSettingsActivity`, `CameraViewerActivity/CameraConfig`, `LampActivity`,
`BedtimeStoryActivity/Stories`, `IntercomActivity`,
`CountdownSettingsActivity/CountdownConfig`, `Converter`, `IssPasses`, `Aurora`,
`ApprovedToolsSpeed/ApprovedToolsSpeedRunner`. Supporting source inspected:
`ApprovedToolWorkspace`, `ApprovedEditorsUi`, `ApprovedSettingsRows`,
`ApprovedSettingsNavigation`, `ApprovedHomeNavigation`, `ApprovedLauncherTabs`,
`HomeActivity`, `ImmortalSettingsActivity`, `AppSwitcherActivity`, alarm/timer
config/schedulers/receivers, `NotesConfig/AudioNote`, `LanAudio/LanAudioSessions`,
`ScreenControl`, and `Weather`.
All Kotlin filenames refer to `app/src/main/java/com/immortal/launcher/`.

Verification is source-to-document coverage and whitespace checking only.
Android build/unit/device validation is outside this documentation-only audit.
The docs CI uses `mkdocs build --strict`; MkDocs is not installed in the current
Python environment, so that build is unverified. No dependency installation,
source changes, mock changes or device mutations were performed.
