# Settings: approved mock vs build (2026-11-17)

The owner noticed that the built split-view Settings does not match the approved mock
(`mocks/settings-redesign-20261109/split-view/index.html`). For example, Display & Brightness ›
Appearance is two large Light/Dark previews in the mock, but in the build it is a row that opens a
list.

This file compares every page of the mock with `main` as of 1ecce828 (Phase 1 and Phase 2 merged).
Each difference gets one of three verdicts:

- **MOCK**: the mock is better, and branch `settings-fidelity` now builds it.
- **BUILD**: the build is better or equivalent, so it stays as it is.
- **NEW**: neither version; an improvement on both, built in `settings-fidelity`.

Each verdict was judged against five criteria:

1. Touch from 2–3 m on a 10-inch screen.
2. Number of taps.
3. Clarity.
4. Consistency across pages.
5. Performance, including the deferred composition from Phase 2.

Side-by-side pictures are in `mocks/settings-fidelity-20261117/index.html`.

## One rule for choices (applies on every page)

The mock uses big segmented controls for small, fixed sets, but it is not consistent about it: it
uses a list for Weather widget (3 options) and segments for Fit (2 options). The build always opens
a list dialog, which takes 2 taps plus a dialog.

**NEW: one rule everywhere.** Any registry choice with 2–5 options whose labels are each ≤ 12
characters is drawn as `SettingsChoiceTilesRow`:

- Tiles are 76 dp tall, in a filled track, and the selected tile is lifted.
- Changing a value takes 1 tap.
- Short sets sit beside the title. Longer sets get the full width below the title (the mock's
  own layout rule).
- Longer lists keep the value row and its list.

Number rows the mock shows as presets get preset tiles plus a **Custom** tile. Custom opens the
existing number editor and shows any stored value that is not a preset, so no value becomes
unreachable. Levels (volumes, warmth, night-clock brightness, opacity) are sliders that save once,
when the finger lifts.

The policy lives in `settings/SettingsChoicePolicy.kt`. It is pure code, covered by JVM tests,
and changes only presentation: storage, the phone schema and the registry titles are unchanged.

## Sidebar

| Element | Mock | Built (before) | Verdict | Reason |
|---|---|---|---|---|
| Home button, "Settings" title, search field | Pill "‹ Home", 52 sp title, 76 dp search | Same | BUILD | Identical |
| Width | 600 dp at 1920 | 31 % of width (595 dp) | BUILD | 5 dp difference; the fraction scales to 1280 dp |
| Groups and rows | 4 groups, 18 rows, 80 dp, coloured tiles | Same | BUILD | Identical |
| Status hints beside rows | Photo Frame "On", Alarms "07:30"/"Off", Wallpaper "Dark", Advanced "On" | Advanced "On/Off" only | MOCK | Status readable at a glance from across the room, no tap needed. Facts are read after the first frame and follow preference changes live |
| Selected row | Solid blue, white text | Focus-tint fill, semibold | BUILD | Same meaning, and matches Home's tokens |

## Profile card

| Element | Mock | Built | Verdict | Reason |
|---|---|---|---|---|
| Name | "Family Area" | FleetConfig name | BUILD | Same |
| Second line | "Living room · Portal+" | Model only | BUILD | There is no Room field. The plan chose not to add one, and a new setting is out of scope |
| Version and status line | Dot + "Hearthpane 1.239 · Up to date" | Same, live from OtaUpdater (also Checking/Update available) | BUILD | Same, but live |

## Search

| Element | Mock | Built | Verdict | Reason |
|---|---|---|---|---|
| Results replace the groups | "N results", 96 dp rows, small tile, crumb, ADV tag | Same | BUILD | Identical |
| Matched text | Highlighted with a soft accent mark | Plain | MOCK | The eye finds the match faster at a distance |
| Shortened titles (e.g. "Time format") | Shown | n/a | NEW | Search shows the same title as the row, and the old registry title still matches as a keyword |
| ADV result while Advanced is off | Turns Advanced on, with a toast | Asks first in a consent dialog | BUILD | Explicit and reversible |

## 1. Display & Brightness

| Element | Mock | Built (before) | Verdict | Reason |
|---|---|---|---|---|
| Appearance | Two 220×146 previews, Light and Dark, with radio rings | "Appearance" value row → dialog list | MOCK | 1 tap instead of 3; the previews read from 3 m |
| Appearance footer | "Same switch as the old header button…" | none | BUILD | The mock's footer is a migration note, not product copy |
| Brightness | Title and value above a full-width slider | Label (240 dp) + slider + value on one row | BUILD | Same control and same reach; works today |
| Auto-brightness subtitle | "Follows the room using the light sensor." | none (the footer explains it) | BUILD | The footer already says it |
| Night Shift From / Until | Value rows "21:00" › time editor | Inline ± steppers (30 min steps), "9:00 PM" | MOCK | Consistent with every other time row (quiet hours, overnight, alarm), 24-hour labels, any minute reachable |
| Warmth | Slider | ± stepper (10 % steps) | MOCK | A level is a slider everywhere else (brightness, volume); 1 drag instead of up to 10 taps |
| Clock (time format) | "Time format", segmented Auto/12-hour/24-hour | "Clock" → list | MOCK | 1 tap; the clearer name says what it changes |
| Hide status bar (ADV) | Switch | Same | BUILD | Identical |

## 2. Sounds

| Element | Mock | Built (before) | Verdict | Reason |
|---|---|---|---|---|
| Media volume | Slider | Slider + footer | BUILD | Same |
| Chime volume | "Chime & voice volume" slider in the Volume section | "Volume" → stepper dialog (10 % steps) in Chimes | MOCK | A level should be a slider, and the name no longer clashes with Media volume. It stays inside the chime editor, so its saving and status logic is unchanged |
| Touch section header | "Touch" | "Options" | MOCK | Says what the section is |
| Test touch sound | Row + "Tap to test" | Same (plus state-aware help) | BUILD | Built is richer |
| Hourly / Spoken / Golden-hour subtitles | Spoken: "Says the time after the chime.", Golden: "A gentle tone before sunset." | none (Advanced off) | MOCK | Clarity. The copy is accurate to the build: golden hour sounds at sunrise **and** sunset |
| Sunrise sound | Value row (Birdsong, Bells…) | 0–1 stepper dialog "Morning/Rooster" | NEW | Two built options → two one-tap tiles. The mock's option names don't exist in the build |
| Preview sounds | 2 buttons | Up to 5 buttons + voice picker + status | BUILD | More useful |
| Quiet hours | Own section with switch and two times | Same rows inside the chime editor | BUILD | Equivalent, and keeps the editor's write queue |
| Ambient sound | Value row → list | Same | BUILD | 7 options; a list is right |
| Soundscape volume | Slider | Stepper dialog (5 % steps) | MOCK | A level is a slider |

## 3. Photo Frame

| Element | Mock | Built (before) | Verdict | Reason |
|---|---|---|---|---|
| Live preview | 16:9 stage | Same | BUILD | Same |
| Master switch | "Photo frame screensaver" | "Show the photo-frame screensaver" | MOCK | Shorter; reads as one line from afar |
| Style | "Style" → list with one-line descriptions | "Screensaver style" → list | MOCK (title) / BUILD (descriptions) | "Style" under the Style header is enough. The descriptions are left for a later pass, because the build's style list (native + engine styles) differs from the mock's |
| Gentle motion / Keep people in frame | Switches | Same | BUILD | Same |
| Fit | Segmented Fill/Fit, "Classic style only." | Fill/Fit list, shown only for Classic | MOCK (control) / BUILD (visibility) | 1 tap. Hiding it outside Classic is better than an explanation |
| Photo source | Value row › Photo Sources | Same | BUILD | Same |
| Photo interval | Segmented 10 s / 30 s / 1 min / 5 min | Number field 5–600 s in a dialog | MOCK + NEW | Presets are 1 tap. The extra Custom tile keeps every 5–600 value |
| Shuffle / Play videos | Switches | Same | BUILD | Same |
| Clock face / Show now playing / Welcome screen | Rows and switches | Same | BUILD | Same |
| Calendar on the photo frame | Link with value "Off" | Link, no value | MOCK | State visible without opening it ("On", "Off", "Not set") |
| Open when you tap to exit | Value row | Same (live value) | BUILD | Same |
| Wave to wake (ADV) | "Wave to wake" | "Gesture wave to wake" | MOCK | Shorter |
| Ambient dashboard (ADV section) | Switch | Same | BUILD | Same |
| Preview full screen | Text button row | Primary pill | BUILD | Bigger target |

## 4. Alarms & Wake

| Element | Mock | Built (before) | Verdict | Reason |
|---|---|---|---|---|
| Alarm layout | Header "Alarm", one card: switch "Alarm", time, Repeat chips | No header; three cards; switch "Enabled" (Material switch); "Repeat on" label outside | MOCK | "Enabled" is ambiguous. One card groups what belongs together, and the switch matches every other switch |
| Repeat chips | 76 dp circles M–S | 68 dp circles | MOCK | Bigger targets. The two copies (Alarm and Sunrise) are now one shared `SettingsDayChipsRow` |
| Repeat summary + "Next:" | Footer | Same | BUILD | Built is more informative |
| Sunrise layout | Header "Sunrise wake", one card: switch with "Slowly brightens the screen to wake you gently.", Wake time, Wake on, Ramp, Chime | Separate cards, "Enabled", "Ramp minutes" | MOCK | Same reasons as the alarm |
| Ramp | Segmented 10/20/30 min | 1–60 stepper dialog (5 min steps) | MOCK + NEW | 1 tap; Custom keeps other lengths |
| Preview a 1-minute sunrise | Button | Same | BUILD | Same |

## 5. Night & Sleep

| Element | Mock | Built (before) | Verdict | Reason |
|---|---|---|---|---|
| Overnight (switch, Off from/until, dim clock) | Same | Same | BUILD | Same |
| Clock when the room is dark | Switch | Same | BUILD | Same |
| Night clock brightness | Slider | 1–15 % stepper dialog | MOCK | A level is a slider |
| Night clock colour | Segmented Warm/Red/White | Warm/Red list | MOCK (control) / BUILD (options) | 1 tap. "White" doesn't exist in the renderer, and adding it would change behaviour |
| Darkness threshold (ADV) | Value row | Same | BUILD | Same |
| Sleep timer rows | Inside a card: countdown, "Sleep after" list, switches | Countdown card, then **loose rows** outside any card (Material switches, ◀ "30m" ▶ stepper) | MOCK | Consistent card and switches. The ◀▶ stepper needed 6 taps to go from 30 to 60 minutes |
| Sleep after | Value row → 15/30/45 min/1/2 hours | ◀▶ ±5 min | NEW | Tiles 15 min / 30 min / 1 hour / 2 hours + Custom: 1 tap, any 1–240 still reachable. A running timer still restarts at the new length |
| Power (ADV) | Power list, Idle screen-off, Sleep on battery | Same | BUILD | "Follow presence" is too long for a tile; the list stays |
| Lamp | Open Lamp ↗ | Same | BUILD | Same |

## 6. Wallpaper

| Element | Mock | Built | Verdict | Reason |
|---|---|---|---|---|
| Preview on mini Home | Yes | Yes | BUILD | Same |
| Swatches | Grid with names, LIVE badges | Same grid (14 options, photo presets) + selected label | BUILD | Equivalent |
| "Background" / "Effects" headers | Two sections | One card with Film grain under a divider | BUILD | Same content; one fewer section to scroll |

## 7. Home Screen

| Element | Mock | Built (before) | Verdict | Reason |
|---|---|---|---|---|
| Customize Home… | Button + "Opens Home in edit mode…" | Row + "Touch and hold a widget on Home…" | BUILD | Known deviation: the build's copy is accurate (there is no separate edit mode) |
| App tile size | Segmented Compact/Standard/Large | Standard/Large/XL list | MOCK (control) / BUILD (options) | 1 tap; the build's real sizes are kept |
| Now-playing mini-player | Switch | Same | BUILD | Same |
| Quick buttons | Header, switch with "A floating button to jump between apps.", Always show, footer | Header, switches, setup note | BUILD | The built note explains the accessibility requirement, which matters more |

## 8. Widgets & Calendar (with Weather and Calendar sub-pages)

| Element | Mock | Built (before) | Verdict | Reason |
|---|---|---|---|---|
| Widget link rows | Coloured tile per widget (Calendar red, Weather blue…) | Title + chevron only | MOCK | Scannable; tiles come from the page tree, so nothing new is stored |
| Weather value "Auto °" | Shown | none | BUILD | It would need another domain loaded on this page (perf); the sub-page is one tap away |
| Footer (alarm/HA widgets live elsewhere) | Same | Same | BUILD | Same |
| Options scanner (ADV) | ADV row | Same | BUILD | Same |
| Weather › Temperature unit | Segmented Auto/°C/°F | Auto/Fahrenheit/Celsius list | MOCK | 1 tap, short degree labels |
| Weather › Weather widget | Value row Off/Compact/Detailed | Off/Hourly/Daily list | NEW | The one-choice rule makes it 1 tap, consistent with Temperature unit beside it |
| Calendar › Show on | "Home" and "Photo frame" switches | "Show on screensaver" switch (Home always shows a set feed) | BUILD | The build has no "Home" switch; inventing one would change behaviour |
| Calendar › Events to show | Segmented Today/3 days/Week | Day/3 days/Week/Agenda list | MOCK | 1 tap; all 4 real options kept |
| Calendar › Widget size | Segmented Small/Medium/Large | 0–2 stepper dialog | MOCK | 1 tap |
| Calendar › Position | 4-corner list | Left/Right list | NEW | Two options become one-tap tiles |
| Almanac (ADV) | 3 switches | Intro + Photo Frame link + 3 switches | BUILD | Same switches, more context |

## 9. Clock Faces

| Element | Mock | Built (before) | Verdict | Reason |
|---|---|---|---|---|
| Live preview | Yes | Yes | BUILD | Same |
| Photo frame clock › Face | Segmented Hearthpane/Corner/Centred/Flip | "Photo clock face" → list of 5 | MOCK | 1 tap, with the live preview above as feedback; all 5 built faces kept |
| Size | Segmented Small/Medium/Large | List | MOCK | 1 tap |
| "Preview before choosing" card | Removed (live preview) | Present | BUILD | Full-screen preview is still useful on a 10-inch screen |
| Digital clock rows | Value rows | Lists; Size (4), Position (4), Background (3) | NEW | The one-choice rule makes them 1 tap; Clock face (6 options) stays a list |

## 10. Photo Sources

| Element | Mock | Built | Verdict | Reason |
|---|---|---|---|---|
| 2×2 source tiles with icons | Yes | Yes | BUILD | Same |
| Current source, Change connection | Yes | Yes | BUILD | Same |
| Refresh album | Visible in Current source | ADV only, shared albums only | BUILD | It applies to one source type and is technical |
| Self-hosted (ADV) | Immich/SMB/WebDAV/Web + Store media | Same, split between the block and the Self-hosted section | BUILD | Equivalent |

## 11. Welcome Greeting

| Element | Mock | Built (before) | Verdict | Reason |
|---|---|---|---|---|
| Preview | Yes | Yes | BUILD | Same |
| Show section | Header "Show": Greeting message, Clock, Date, Speak greeting ("Reads the greeting aloud.") | No header; "Show greeting message", "Show clock", "Show date", "Speak greeting (TTS)" | MOCK | The header removes the repeated "Show"; plain-language "Speak greeting" |
| Greeting texts | Name + 4 greetings | Name; 4 greetings ADV only | BUILD | Existing Advanced policy |
| Timing & look | One section: Display duration stepper, Text size segmented, Background opacity slider | "Timing" (ADV) stepper, "TEXT SIZE" (uppercase) list, "Background" (ADV) ◀▶ opacity | MOCK | One section with consistent header case, 1-tap text size, opacity as a slider. Duration and opacity stay ADV, as before |

## 12. Home Assistant

| Element | Mock | Built | Verdict | Reason |
|---|---|---|---|---|
| Dashboard address, Open dashboard | Yes | Yes | BUILD | Same |
| Connection (Base URL, token, Save & connect) | Visible | ADV only | BUILD | Existing Advanced policy; a token is technical |
| More (entities, dashboard view) | Yes | Yes (ADV) | BUILD | Same |
| MQTT (ADV) | Link with value "Off" | Link | BUILD | MQTT is an ADV sub-page; its state is one tap away |

## 13. Phone Remote

| Element | Mock | Built | Verdict | Reason |
|---|---|---|---|---|
| Pairing: Show pairing code, footer | Yes | Yes | BUILD | Same |
| Sharing section (4 switches) | Not in mock | Present | BUILD | Known deviation: real settings that need a home |

## 14. Intercom

| Element | Mock | Built (before) | Verdict | Reason |
|---|---|---|---|---|
| Grouping | "This Portal" (Address, Broadcast) and "Listen to another room" (address, Listen) | One unlabelled column; "This Portal's address: …" as a sentence | MOCK | Headers say which side does what; the address reads as a value row |
| Calls from other rooms | Not in mock | Present | BUILD | Known deviation: real settings |

## 15. Music

| Element | Mock | Built (before) | Verdict | Reason |
|---|---|---|---|---|
| Music follows you | Segmented Off/Ask first/Always | Ask first/Automatic/Off list | MOCK | 1 tap (stacked, full width); the build's real options are kept |
| Quiet hours + Loudest music slider | Yes | Yes | BUILD | Same |
| Multi-room audio (ADV) | Switch, server, Test | Setup steps + the same | BUILD | More guidance |
| Clear music history | Danger button | Row with confirm | BUILD | Confirmation is safer |

## 16. About & Health

| Element | Mock | Built | Verdict | Reason |
|---|---|---|---|---|
| Version header card + Check for updates | Yes | OTA card (live state) | BUILD | Same, live |
| This Portal: Name, Room (NEW), Model | Name, Room, Model | Device name, Model | BUILD | No Room field (plan decision); "Device name" is clearer |
| Health banner + 6 checks | Yes | Yes (plus fixes) | BUILD | Richer |
| Tour, Remove screen-lock (ADV) | Yes | Yes | BUILD | Same |

## 17. Portal & Android

| Element | Mock | Built | Verdict | Reason |
|---|---|---|---|---|
| Network, System, All/Portal settings, footer | Same rows | Same rows | BUILD | Identical (the footer drops the migration note) |

## 18. Advanced

| Element | Mock | Built | Verdict | Reason |
|---|---|---|---|---|
| Advanced switch + footer | Yes | Yes | BUILD | Same |
| Startup, Tools, Diagnostics | Tools also lists Options scanner | Speed test only | BUILD | No duplicate (Options scanner lives in Widgets) |
| Start on boot / Managed sub-pages | Yes | Yes | BUILD | Same |

## Counts

| Verdict | Rows |
|---|---|
| MOCK | 34 (split verdicts such as "MOCK (control) / BUILD (options)" and "MOCK + NEW" count as MOCK) |
| BUILD | 70 |
| NEW | 6 |

All MOCK and NEW verdicts are implemented on branch `settings-fidelity`.

## Implementation notes

**Shared controls.** All new, all small files:

- `SettingsChoiceTiles.kt`: the big segmented control.
- `SettingsAppearanceTiles.kt`: the Light/Dark previews.
- `SettingsSliderRow.kt`: a slider that saves when the finger lifts.
- `SettingsDayChips.kt`: the weekday chips, shared by alarm and sunrise.
- `SettingsRegistryControls.kt`: chooses the control for a registry row.
- `SettingsSidebarHints.kt` and `SettingsSidebarFactsState.kt`: the sidebar status hints.

**Behaviour is unchanged.**

- Every write still goes through the same registry `apply` or editor call.
- Tapping the selected tile writes only when a displayed fallback must be persisted, as the
  list's `applyCurrentSelection` did.
- Sliders save once, on release.
- A running sleep timer still restarts at the new length.

**Performance.**

- There are no new effects per row.
- Sidebar facts load after the first frame and are reread only when one of three preference
  files changes.
- Each sidebar row recomposes only when its own hint text changes.
- Deferred composition and lazy pages are untouched.

**Diagnostics.** The new visible titles "Time format" and "Chime & voice volume" map to the same
UI-diagnostics keys as the old ones.
