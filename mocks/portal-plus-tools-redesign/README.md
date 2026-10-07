# Portal+ Tools redesign review

Open <http://127.0.0.1:8765/portal-plus-tools-redesign/?screen=tools>.

This is an interactive design reference with synthetic sample data for the native
Tools redesign implemented on `codex/portal-plus-approved-ux`. The browser does
not connect to the Portal, schedule alarms, stream cameras, record audio, change
brightness, or run a bandwidth test. Native device evidence is tracked in
[portal-plus-tools-native-validation.md](../../docs/design/portal-plus-tools-native-validation.md);
the implemented build has passed the representative native checks described there.

## Design

The shared header has phone remote, sleep, day/night, search and Settings.
Phone remote uses a smartphone; actual Calls keeps the handset. The decorative
Portal outline before “immortal” is removed. Day/night updates Home, Apps, Tools
and Settings together while retaining pages and drafts. Camera, lamp and alarm
fullscreen displays keep their native dark controls.

The library groups all eleven ordinary tools into Everyday, Home & downtime,
and Discover. Speed test appears only when Advanced tools is enabled in the
review toolbar. Search and category filters keep the library easy to scan.

Selecting a tool opens a workspace alongside a compact tool rail. The existing
launcher header, wallpaper, and bottom dock stay mounted. The Tools page and
left rail are transparent over the shared launcher background; cards, selected
rows, and setup sheets retain surfaces for readable controls. There is one title,
the selected tool remains highlighted, and there is no top **All tools** button.
Tapping the active bottom Tools tab returns to the library with its filters and
scroll preserved. Opening a tool
does not send the user into Settings or a separate simulated app. Switching to
another launcher tab and back retains the selected tool.

Setup uses consistent in-context sheets. Picker Back returns to the draft;
setup Cancel returns to the tool; tool Back returns to the library and restores
its scroll. Draft sheets commit only on Save/Add/Start. Alarm day changes and
lamp sliders apply immediately within the preview. Note drafts survive tool
switching, and Save explicitly updates the saved note.

Only explicit Camera View, Lamp display, and Alarm ring preview cover the
launcher. Their Back controls return to the originating tool. Stories retain
the tool rail and open a spacious reader in the same workspace.

## Every tool and opening flow

| Tool | Workspace | Child flows |
|---|---|---|
| Alarm | Single alarm, enabled state, time, repeat days | Time sheet → minute picker → Save/Cancel; ring preview → Dismiss or nine-minute Snooze |
| Timers | Six duration presets and active multi-timer list | Custom hours/minutes/seconds → validation → Start; Cancel timer confirmation |
| Countdowns | Saved dates and relative day counts | Add sheet, month/day/icon pickers, conditional year for one-off dates, Remove confirmation |
| Converter | Length, mass, volume, speed, temperature, currency | Category and unit pickers, live value/result, Swap units |
| Lamp | Brightness, warmth, red light | Numeric value sheets; fullscreen display with sliders, red mode, Hide/Show controls, Sleep preview |
| Leave a note | One text note and one voice memo | Save/Clear text, Record/Stop, Play/Stop, Delete memo, microphone prerequisite |
| Cameras | Saved external RTSP cameras | Add sheet and address validation, Remove confirmation, fullscreen View, unavailable/retry return |
| Intercom | Continuous one-way broadcast or listening | Address entry, Start/Stop, microphone prerequisite, connection failure |
| Bedtime story | Five bundled public-domain stories | Reader, Read aloud/Stop simulation, reader Back → picker → Tools |
| ISS passes | Next visible pass, upcoming directions/elevations | Refresh in place; unavailable and no-result previews |
| Aurora outlook | Kp now, next-24-hour peak, viewing guidance | Refresh in place; unavailable and no-result previews |
| Speed test | Explicit Start, latency/download/upload phases and results | Cancel/Run again; offline state; hidden outside Advanced |

Use **Preview state** in the browser toolbar to review Normal, Empty, Offline,
or Permission needed. These controls belong to the review environment.
Local-only tools continue to work in Offline; permission prerequisites apply
to microphone features. The illustrated camera, feed readings and exchange rates
are explicitly synthetic. The browser does not request microphone access.

## Native implementation and validation

The source audit is
[portal-plus-tools-overhaul-inventory.md](../../docs/design/portal-plus-tools-overhaul-inventory.md).
It records the pre-redesign baseline for eleven ordinary tools plus Advanced
Speed test: Kotlin bodies, persistence, services, prerequisites, nested controls,
and return boundaries.

The layout uses the same 1920 × 1280 dp canvas as the connected Portal+
(2160 × 1440 pixels at 180 dpi), bundled Roboto weights, ordinary surfaces,
vectors, rows, bounded scrolling, and 68 dp touch targets. These are the visual
primitives used by the native redesign. There is no glass blur, backdrop shader,
constant decorative animation, or dependency on a browser-only camera player.
The mock inherits the existing 160 ms launcher tab transition; tool selection
changes content inside the retained shell.

The native redesign is implemented with the retained launcher shell, categorized
library and tool rail, contextual sheets, and lifecycle-cancelled asynchronous
work. The Tools body and rail are transparent over the existing launcher
wallpaper. Its navigation follows the return behavior above: the active bottom
Tools tab restores the library's filter and scroll, while switching other tabs
and returning preserves the selected tool. Custom browser selectors remain
design references for picker presentation; they do not use the desktop browser's
native dropdown UI.

The underlying tools remain scoped to existing capabilities: one alarm, multiple
Tools timers, one text note/memo, external RTSP cameras, continuous one-way
intercom, and the five bundled stories. Camera thumbnails in this review are
illustrations, not evidence of native camera frames or compatible household RTSP
streams. Native thumbnails are not currently implemented.

The source audit found two different timer stores (Home and Tools), a one-shot
alarm snooze edge, and incomplete live TTS/intercom/feed error reporting. The
mock does not unify those stores or establish that these hardware behaviors are
fixed. The native validation record tracks actual device evidence and remaining
checks; browser results do not establish that the audit's hardware concerns are
resolved.

The demo stops simulated recording, narration, intercom and bandwidth work when
leaving their tool/tab. Its timer display updates only while Timers is visible;
elapsed time continues independently. The native redesign applies lifecycle
cancellation to foreground asynchronous work while retaining the existing
scheduled timer/alarm services. Actual device behavior remains part of native
validation.

Actual device screenshots and measured limitations are available in the
[native evidence gallery](http://127.0.0.1:8765/portal-plus-tools-redesign/native-validation.html).

## Review links

- [25-screen Portal-sized gallery](http://127.0.0.1:8765/portal-plus-tools-redesign/review.html)
- [Library](http://127.0.0.1:8765/portal-plus-tools-redesign/?screen=tools)
- [Alarm workspace](http://127.0.0.1:8765/portal-plus-tools-redesign/?screen=tools&tool=alarm)
- [Cameras](http://127.0.0.1:8765/portal-plus-tools-redesign/?screen=tools&tool=camera)
- [Stories](http://127.0.0.1:8765/portal-plus-tools-redesign/?screen=tools&tool=stories)
- [Advanced speed test](http://127.0.0.1:8765/portal-plus-tools-redesign/?screen=tools&tool=speed&advanced=1)

The old navigation, complete, and sign-off previews remain available. The shared
navigation script has an optional mock-only Tools mount/back/lifecycle hook;
this review page mounts the browser Tools design reference.

## Verification

The latest verified browser runs passed 156 navigation and 130 Tools checks
with 0 browser errors, plus 8 focused checks for the final Sleep SVG. The
review gallery contains 25 captures at the Portal+'s actual 2160 × 1440 pixel
resolution.

Run `node mocks/portal-plus-tools-redesign/verify.browser.cjs` from the repository.
It uses the existing authorized Playwright runtime and restricts requests to the
loopback server. It checks every tool entry, retained chrome, sheets/pickers,
validation, fullscreen returns, state retention, audio/transfer exit behavior,
Advanced gating, reduced motion, and browser errors. Review screenshots are saved
only in this mock's `reference/` directory.

Browser verification demonstrates the design reference's simulated behavior.
The final native functional run passed 405 assertions, including an actual
memo Record/Stop/Play/Stop/Delete cycle with original data restored. Final warm
switching p95 after the header/theme update was 37.77 ms, but the stricter 16.67 ms
target remains unmet. Browser results do not establish Android pixel parity,
scheduling, feed accuracy or compatible household RTSP streams. See the
[native validation record](../../docs/design/portal-plus-tools-native-validation.md)
for device evidence.
