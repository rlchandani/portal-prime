# Portal+ native UX validation

Branch: `codex/portal-plus-approved-ux`. Baseline:
`caf8f350cabd00ab1b4aa2951e512bf6e351a702`.

The native version of the revision 04 design is installed for device testing.
UX/performance acceptance remains open. The connected
Portal+ runs Android 10/API 29 at 2160 × 1440 pixels and density 180, equivalent to
1920 × 1280 dp. Its existing debug package is upgraded in place.

## Preservation and implementation

- All 67 recorded mock-file hashes remain unchanged. The 795 frozen reference
  images were not regenerated. Existing mock folders and the pre-upgrade APK/app
  data also have private local rollback backups.
- Existing widget identities, favorite order, photo sources, credential groups,
  validators and scheduling hooks remain in their original subsystems.
- Advanced settings and settings appearance are registry-bound preferences.
  Advanced defaults off; hiding technical controls does not disable configured
  features. Search retains parent/hardware prerequisites and explicit consent.
- Ordinary settings and 24 additional existing editor/control bodies use local
  routes in one Settings Activity. Screensaver, its source/style/face selection,
  clock, sounds, wake/sleep, widget collections and connection controls share the
  fixed sidebar/header and 160 ms detail transition. Nested Back returns to its
  parent and editor Cancel leaves the local pane. Source credential/URL/folder
  forms retain their Activity wrappers, with a 160 ms internal handoff fade and
  disabled black starting-window preview. System settings, installed applications,
  provider widgets and external dashboards remain real native handoffs.
- Home uses the bounded card layout, compact favorites and a separate dock.
  Home, Apps and Tools now share HomeActivity, the Home palette and a 160 ms
  horizontal transition. Visited tabs retain UI state; inactive tabs lose input
  and drop to CREATED lifecycle state. Photos remains a full-screen handoff.
  Home and shared dock/search entries use the same embedded Apps screen;
  standalone Recents remains available from outside the launcher.
- Store/APK dock and search actions also return to the retained Home tabs.
  Repeated incoming search requests leave search open. Timer-dialog Back dismisses
  to Home; its explicit Tools action selects the retained Tools tab.
- Apps no longer repeats an Options link beneath each installed app or a Tools
  shortcut in its grid. A long press opens the existing app-action menu, with an
  accessibility action label. The Tools dock entry and saved Home favorites remain.
- Home and Customize use one paged, four-column rectangle layout, so changing
  editing mode does not change card order, geometry, selected page or viewport.
  Packing fills holes beneath short cards independently of tall neighbors,
  retaining the normal 24 dp gutter. The first photo remains the shared hero.
  Per-widget explicit size choices no longer switch the sizing of other cards
  or pages. Legacy dimensions remain presentation hints; merely viewing or
  toggling Customize does not rewrite the saved widget data.
- Dragging owns a transparent gesture layer above each widget body and below
  its editing controls. Current ordered identities and placements refresh the
  detector after each drop. Reorder and resize save only on release; cancellation
  discards their drafts. Hit testing and horizontal resizing account for RTL.
  Move supports numbered destinations across pages, and Settings uses the
  existing editors. Photo long-press still enters Customize.
- The settings shell, rows, dialogs, source forms, collection editors, Store,
  Tools, Help and Lamp use the approved palette and geometry. All 45 vector icons
  reproduce the 51 original SVG paths from a small immutable, versioned input.
- Real device content replaces mock fixture values. Existing explicit wallpaper
  choices and third-party/provider styling are preserved.

## Performance architecture

App inventories and icons use reusable snapshots. Hero photos decode on a
bounded worker with 1200-pixel sampling, EXIF correction and a transfer limit.
Photo retry uses deadline timers while Home is resumed, retains the last image
on failure, and stops when Home leaves. HTTP connections and SMB sockets belong
to their request; cancellation closes transport off the UI thread. Home SMB
connect/read/protocol steps have eight-second bounds.

Catalog, update discovery and installation use separate workers. Update checks
coalesce only identical catalog inputs. Terminal delivery is atomic with
subscription, and an offline refresh retains the latest cached hosted catalog.
Installed versions are read once on IO per catalog/resume/install epoch.

Store icons have three workers, a bounded queue, a size limit, sampled decoding,
memory/disk caching and atomic file publication. The last disposed card cancels
both queued work and active transfer. Obsolete work cannot overwrite a newer
request's cache entry. There is no idle marquee or per-frame weather/Sky loop.

Touch Home retains at most three adjacent widget pages, with cached data and
refresh deadlines owned by the Home tab. Paging changes visible layers and input
eligibility; it does not restart each widget's network producer. Re-entry waits
for any cancelled blocking producer to release its mutex before issuing another
request. Multi-feed/entity/option-page loaders check cancellation between reads.
The shared widget layout remembers packing by spans. During a move, only the
dragged visual layer and target highlight change; during resize, a local outline
previews the requested dimensions. Neither gesture writes preferences or repacks
the grid on each pointer event. This is an implementation property, not a
measurement of final-device frame timing.

Settings compose individual visible specs as lazy items with stable identities.
The main host retains at most four visited scalar panes. Each pane keeps its own
route and scroll state; inactive panes lose semantics/input and their lifecycle
drops to CREATED. Formerly standalone rich bodies dispose immediately when
navigation leaves them, releasing request, polling, TTS and audio ownership.
Only route metadata is retained in a bounded 32-entry history; Activity recreation
restores that history without storing credential drafts. Screensaver reuses its
single lazy list, and its initial focus request runs after the row mounts.
Display reads permission, brightness, volume and Night Shift
as one snapshot on IO, with stale-read protection around newer user edits.
The Apps tab refreshes inventory on host resume/package changes rather than every
tab activation, while favorite assignments and badges refresh on activation.
Cached perimeter paths preserve continuous grouped cards. Search indices account
for section headers and hidden rows. The shared focus modifier skips identity
scale layers and transparent borders on unfocused touch controls; TV focus rings
and animated scaling remain available.

## Verification

The complete debug build and test APK build passed. All **505 JVM tests** passed,
with zero failures or errors. The frame probe's 14 offline checks and the icon
generator's 12 tests passed. Icon generation also matched all six frozen input
hashes. Independent review drove corrections to catalog delivery, transport
cancellation, hidden-page input, and producer restart behavior. Its Intercom
and timer-dialog findings were corrected. Audio starts own separate sessions;
pending sockets, listeners and audio handles close on cancellation, including
an unbound server owned before bind. Nine JVM tests cover these ownership races.

Native device checks covered:

- Home card/favorites/dock separation, widget paging, library navigation,
  saved favorites, both Apps entry points, and favorites cancellation.
- Home → Apps → Tools → Home kept the same Activity and native window. Embedded
  Converter and Back, Apps search and Back, and full-screen Photos return passed.
- Appearance → Display → Back, Appearance → Wallpaper and sidebar category
  changes kept the same Settings Activity and native window. These checks verify
  continuity; they do not establish frame-time acceptance.
- Opening Settings from Apps and selecting its Home button returned to the
  actual Home tab. The final installed build displayed native Display controls,
  kept Advanced off, and preserved the original nine widget placements.
- The expanded continuity check passed for Sounds, Welcome, Sunrise, Alarm,
  Sleep, Screensaver, Digital Clock, and nested Style/Clock face/Photo source
  navigation with parent Back. Collection-editor and HA connection-form Cancel
  stayed in the main Settings Activity. World clock, health, HA controls and
  Intercom panes also rendered there. No microphone session or HA discovery was
  started. Together with Store/APK/tab checks, 48 recorded functional assertions
  passed on the expanded-routing artifact (`df4c6c28…`).
- Store → Tools, Store → Apps, APK header search → Apps and repeated search requests
  returned to the original Home native window. Bedtime/Intercom tool navigation
  and Back also kept that window; Apps → Settings → Home selected actual Home.
- Cached scalar-pane revisits after more than four routes, Display's Night Shift
  parent gate and its dependent rows, retained Notes navigation, and the enlarged
  resize handle passed. Original Night Shift, Advanced and widget values were
  restored after the checks.
- Settings categories, light/dark appearance, Advanced consent/off/on behavior,
  settings search destinations, and conditional screensaver/clock/sleep/display
  controls with their prior values restored.
- Source and URL form cancellation/validation, clock/style pickers, collection
  editors, camera RTSP validation and remove-confirm/cancel flows.
- Adding temporary camera and to-do entries, cancelling removal, then removing
  those exact temporary entries. Existing entries were not modified.
- All eight Help topics, navigation/exit, Lamp preview/show/hide/return,
  Sunrise rows, tool navigation, Store layout, Updates dialog, and cancelling
  an install confirmation before downloading or installing an app.
- Phone-remote JavaScript syntax and offline fixtures for hidden-domain
  rendering, hidden-value reset preservation and configured-source gating.

An early 47-destination smoke run kept every destination in the launcher.
Touch/album first-draw and prerequisite cases required targeted checks rather
than interpreting an absent fixture title as a product failure.

The test-only observer includes the app's separate Compose popup windows.
It is stopped for all performance measurements. Raw UI, images and app backups
remain private local artifacts and are excluded from Git.

## Additive browser navigation preview

The local preview at
<http://127.0.0.1:8765/portal-plus-navigation-live/> demonstrates retained
launcher tabs, the Settings shell, nested Back/Cancel, separate internal handoffs
and theme independence. Home/Apps/Tools stay dark; Settings appearance applies
to its own panes and internal editors. The HA entity chooser is explicitly
shown as a separate Activity simulation.

All 93 isolated browser assertions passed with zero browser errors, including
26 Apps gesture/grid regressions and 28 widget geometry/action checks added
after the original 39 checks. The final
Home layout was also visually inspected. This preview uses the approved
renderer and sample fixtures read-only; the 67 frozen mock hashes remain
unchanged. It is a browser demonstration, not an Android emulator, live Portal
connection, or proof of native frame timing and pixel parity.
Home and Customize use the same packing and page-local size resolution.
The browser demo's Size/Move controls use the frozen renderer's menus; native
freehand drag/resize gestures are verified on the Portal separately.

## Apps long-press refinement

The debug build and test APK build passed after the Apps change; all 485 JVM
tests passed with zero failures or errors. On the Portal+, 18 checks verified
Options absence, one Tools entry in the dock across grid scrolling, retained
Home window identity, long press opening the existing actions without launching
VLC, dialog dismissal, movement without opening the menu, and dock navigation.
Two additional checks verified a normal tap opening the existing Material Files
app and Back returning to Apps. No favorite/folder edits, uninstall, or file
operations were performed.

The initial long-press test selected the search field because it shared the VLC
label with the tile. Targeting the app tile and hiding the keyboard corrected
the test; no product change was needed. The test-only observer was stopped after
verification.

## Widget layout and editing refinement

The final widget debug build and test APK build passed, with all 505 JVM tests
passing and zero failures or errors. Twenty added JVM cases cover deterministic
packing, first-fit/non-overlap properties, legacy dimensions, explicit size
roundtrips, page-local sizing and photo-removal stability. Independent review
found and drove fixes for consecutive equal-size drag identity, pointer coroutine
cancellation, RTL hit testing and resizing, and unintended neighbor promotion.

On the final installed artifact, 36 recorded device assertions passed. Both
pages retained identical card bounds through three Customize/Done cycles each,
without rewriting preferences. Resizing Today committed its exact dimensions
only on release, preserved every neighbor and placed Calendar directly below it
with a 27 physical-pixel gutter (24 dp at density 180). Preview and editing
bounds matched after resizing and after two successive equal-size card moves.
Neither resizing nor dragging changed saved data while the finger remained down.

Numbered Move destinations worked across pages, with the moved card reachable
on its destination page. Move and Remove cancellation preserved the layout.
Widget Settings opened the existing shell; Back followed its page-to-category
path and then returned to the original Home window. Settings and widget-library
navigation preserved the edited layout. The observer was stopped, and all nine
original widget identities, order, dimensions and metadata were restored
byte-for-byte after verification.

Device gesture tests cover the connected device's left-to-right layout.
RTL behavior and coroutine cancellation have source-review evidence; this run
does not claim physical RTL or interrupted-gesture device coverage, arbitrary
provider-widget certification, or final frame-time acceptance.

## Device performance evidence

Current installed debug artifact:
`409bd1f3ae61766e3afedbba466ee759a7fb40fb57ac3ed0214fa6d2531f5509`.
Its APK hash was independently read from the device and matched the tested
build. This artifact adds the shared Home/Customize packing and gesture fixes,
the Apps long-press refinement, expanded local Settings
routing, nested Back/Cancel, shared launcher return/search routes and internal window presentation after the
measured Settings checkpoint. The table below records earlier artifacts, not a
performance measurement of this final widget build.

Warmed navigation and scrolling measurements are recorded separately from
functional tests. Zero-frame/static-page samples do not establish smoothness.
Frame elapsed time is a rendering proxy, not input-to-photon latency. Android
window creation and first-install/JIT effects are distinguished from settled
scrolling; debug results do not constitute release performance certification.
Performance acceptance is still open. The following debug measurements used
65 warmed navigation roundtrips, stopped the screenshot observer, and checked
the initial native window identity before every tap. Each result below has
120 retained frame samples. Instrumentation still adds an accessibility client.

| Candidate / navigation | p50 ms | p95 ms | Largest ms |
|---|---:|---:|---:|
| `21a12d44…` Settings, rebuilding details | 9.63 | 76.77 | 128.41 |
| `ec3d8af8…` Settings, retained panes + Display IO | 10.44 | 40.93 | 52.17 |
| `93a6c45d…` Measured checkpoint, Settings | 10.65 | 34.14 | 46.12 |
| `7f9a9065…` Home widget paging | 44.76 | 58.75 | 66.43 |
| `7f9a9065…` Home/Apps/Tools tabs | 14.90 | 62.14 | 77.39 |

The earlier tab candidate measured p95 45.46 ms, so removing inventory reloads
has not established a frame-time improvement; the later run's tails were worse.
These are individual trials, not a pooled percentile or a matched old/new
launcher benchmark. All remain above the 16.67 ms screening budget at an assumed
60 Hz. The measured `93a6c45d…` checkpoint passed 65 same-window Settings roundtrips; its
APK hash was independently read from the device and matched the tested build.
The inspected recent crash buffer contained no launcher process crash entry.

Earlier three-trial warmed scrolling on candidate `7f418725…` yielded p95 ranges of
17.68–19.76 ms for the long screensaver settings list, 17.10–20.64 ms for Store,
14.68–17.45 ms for Apps and 12.60–14.78 ms for Tools. Those trials predate the
continuous navigation work and are not final-artifact performance certification.

## Remaining live integration limits

Phone pairing and Home Assistant entity discovery await the user's response to
the separate approval request. Automatic approval review blocked these tests
because pairing enables remote access and discovery uses saved HA credentials.
No household devices were operated. The pairing test harness restores the
previous remote opt-in when it exits.

Native microphone/playback teardown timing was not tested by capturing or
broadcasting room audio. Session ownership/cancellation is verified with JVM
tests and source review; this is a separate limit on live audio certification.

Third-party app installation, real provider credentials and every stock firmware
screen have not been exhaustively retested. Store install-confirm cancellation
and the existing installer/unit-test paths were checked. Stock Android Settings
crash entries were observed during inspection; no launcher crash was identified
in the inspected crash log. This is a native implementation test report, not a
claim of pixel-identical provider content or exhaustive external integration
certification.
