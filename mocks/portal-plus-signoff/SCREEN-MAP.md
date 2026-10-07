# Screen and settings map

The mock includes 22 screen functions and eight settings categories. These are design surfaces; the named Android classes are existing integration points, not files already changed.

## Primary screen coverage

| Browser screen | Design coverage | Existing Android integration / work required |
| --- | --- | --- |
| Home | Greeting, time, photo hero, four cards, six favorites, dock | `HomeActivity`, `HomeWidgetStore`, `UserLayout`; new composition, favorites and migration |
| App library | Installed-app grid and launching | `HomeActivity`, app discovery, `UserLayout`; actual installed icons |
| App folder | Named collection and contained apps | Existing folder model and launcher behavior |
| App Store | Feature area, products, Get / Adding / Open | `StoreActivity`, `StoreCatalog`, installer pipeline |
| App details | App identity, description, installation entry | Existing catalog detail; real download, progress and errors |
| Tools | Six common tool destinations | `ToolsActivity`, `HomeToolOverlays`; keep additional current tools reachable |
| Timer | Duration, running, paused, Reset | Existing timers/countdown tools; real completion behavior |
| Customize home | Visible Done, Move, Remove, density | `HomeActivity`, `HomeWidgetStore`, layout persistence; preserve extras |
| Widget library | 14 representative content choices | Existing built-ins and Android widget hosting; no provider removal |
| Settings | Eight categories and exact-row search | `ImmortalSettingsActivity`, `SettingsRenderer`, `SettingsComponents` |
| Photo sources | Local, Immich, SMB, WebDAV, shared album | `ScreensaverSettingsActivity`, `LocalMedia`, source connect Activities |
| Connect source | Validation, credential visibility, pending and Cancel | `ImmichSource`, `SmbSource`, `DavSource`, `RemoteAlbum`; atomic credentials |
| Choose albums | Multi-select and confirmation | Existing source album selection / screensaver persistence |
| Clock picker | Digital, Minimal, Photo only previews | `ClockSettingsActivity`, `ClockFaces`, `DigitalClock*`; retain unillustrated faces |
| Photo display | Full image, clock/date, Home | `PhotoDreamService`, `PhotoFrameController`; preserve dream lifecycle |
| Wallpaper picker | Scenic / ambient / solid previews | `WallpaperConfig`, `HomeBackground`, `AmbientBackground` |
| Calendar | Feeds, display and almanac | `CalendarFeed`, calendar registry, `CalendarPacks`; complete existing editor |
| World clock | City cards, Add / Remove, time zones | Existing world-clock editor and widget |
| Sunrise & wake | Enable, schedule and intensity rows | `SunriseSettingsActivity`, sunrise registry |
| Home Assistant | Server/token, test and choose controls | `HaSettingsActivity`, `HaClient`; actual REST behavior |
| Phone remote | Local address, network help, access | `Remote*`; address fixture must become actual advertised address |
| Device health | Identity, status, diagnostics | Existing Device Health and `FleetDiag`; real data and permissions |

## All 22 current settings destinations

| Proposed category | Current sidebar destinations retained |
| --- | --- |
| Appearance | Display; Wallpaper; Appearance |
| Photos & screensaver | Screensaver; Clock Face |
| Home & widgets | Weather; Calendar; World Clock; Countdown; Quick Buttons |
| Sound & wake | Chime; Sunrise & Wake; Multi-room Audio; Welcome Greeting; Touch Sounds |
| Connections | Home Assistant; Phone Remote |
| Input & accessibility | New grouping for Back/input; existing Android accessibility remains reachable |
| Device | Sleep Timer; Start on Boot; Device Name; About & Device Health |
| Advanced | All Android Settings |

Settings domains stay `screensaver`, `calendar`, `immortal`, `mqtt`, `quickbar`, `fleet`, `chime`, `digitalclock`, `welcome`, `sunrise`. Cross-links such as Photo services and multi-room audio lead to the same editor and state. Existing system display permissions, night-shift, media volume, start-on-boot app selection, and all other persisted fields remain available even where this mock shows a shorter summary.

## What the browser actually exercises

| Interaction | Prototype status |
| --- | --- |
| Navigation, Back, settings categories and search | Functional local navigation, category selection, exact row highlight |
| Light/dark, density, wallpaper, clock/date visibility | Functional visual changes within the preview |
| Widget move/remove/add/replace and favorite selection | Functional in-memory composition, bounded to five widgets and six favorites |
| Photo-source form | Local validation and simulated 500ms response; no real credentials or server requests |
| Album selection and confirmation | Functional in-memory selection and source summary |
| Store Get / Adding / Open | Simulated download/install, about 700ms; no APK activity |
| Timer | Real local countdown, Pause and Reset; no native alarm integration |
| Playback and household controls | Sample state changes, offline failure/recovery; no real playback or entity command |
| Calendar and world-clock edits | Local form/selection examples; no feed fetch |
| Tool and advanced settings dialogs | Representative form/choice patterns; some Save actions only demonstrate acknowledgement |
| External app and Android settings handoff | Explicit handoff dialog; external screen itself not reproduced |
| Service status, storage and remote address | Fixtures, not fresh device telemetry |

Reloading clears changes. A control updating the sample state does not establish native integration. Rich editors must be completed against the existing code after approval; this prototype freezes their shared visual pattern and the specific pictured states.

## Review sequence

Start with Home → Customize → Add widget → Done; Apps → Utilities folder; App Store → app details; Tools → Timer. Then inspect every settings category. Follow Photos & screensaver → Photo sources → Immich → Test with dummy values → Choose albums → Confirm → Clock picker → Preview. Repeat representative settings in dark mode; compare Home offline and first use.

Screens taller than the detail pane or widget library deliberately scroll within that pane. Reference images show the initial viewport; use the interactive preview to inspect the remaining rows.
