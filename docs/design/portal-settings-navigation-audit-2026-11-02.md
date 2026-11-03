# Settings navigation and clarity audit — 2026-11-02

Status: source-verified structure and proposed presentation corrections. Not a device walkthrough or approval of new native UI.
Baseline: `8075ef38` on `codex/widget-gallery-20261102`.

## Coverage and findings

- **8 categories, 54 unique registered pages, 44 overview rows.** Every overview destination exists in the page table; each registered page has either a local body or an explicit external Activity.
- **39 local bodies, 15 external-Activity destinations.** The shared detail pane already exists. Standalone wrapper metadata does not mean every tap starts that wrapper: `activity` controls routing, while `standaloneActivity` maps registry navigation and direct launches.
- **16 Advanced pages.** Fifteen are listed in Advanced; the sixteenth, `ha-entities`, is a second-stage editor. Ordinary categories and widget actions retain the global gate.
- **4 pages have intentional cross-category entry points:** Touch sounds, Quick buttons, Alarm, Android & Portal settings. All refer to the same page/editor; do not clone their values or forms. History should return to the entry context.
- **14 pages are secondary or search destinations.** Absence from a category overview is not proof of an unreachable page; source selection, widget setup and atomic forms provide additional entry points. Runtime reachability still needs testing.
- The earlier duplicate category/page wording is already partly resolved: Photos & screensaver contains **Slideshow**, and Home & widgets contains **Home dashboard**. The latter is still ambiguous with the configured web dashboard.

## Minimal proposed organization

Keep the approved one-left/one-right settings shell and Back/Forward history. Preserve every existing capability and its conditional gate. Changes below are proposals pending design approval, not deployed behavior.

| Category | Clarity/action correction | Preserve |
| --- | --- | --- |
| Appearance | Keep Display, App appearance and Wallpaper distinct. Shared time format remains discoverable from time-based widget setup. | Existing brightness permission and auto-brightness capability handling. |
| Photos & screensaver | Label the playback page **Slideshow when idle**; media-selection page **Photo source**; explain that Photo Memory uses the same source. | Style, clock-face, dismissal and atomic source editors; current advanced-source status when Advanced is off. |
| Home & widgets | Name the placement entry **Customize Home**. Provide a complete **Widget setup** index with true data owners; avoid calling this the web dashboard. | Saved grid/size/order and Android provider configuration. |
| Sound & wake | Keep alarm discoverable here and from Widget setup as a cross-link. Explain chime, greeting and wake-light separately. | Quiet-hours and sound-specific children. |
| Connections | Distinguish **Home dashboard connection** (web URL), **Home Assistant controls** (API/entities), Phone remote and Intercom. | No new Home Assistant service/server deployment. |
| Input & accessibility | Cross-links for Touch sounds and Quick buttons are acceptable; label Android/Portal handoffs clearly. | Existing accessibility and platform settings boundaries. |
| Device | Separate Device name, Sleep timer, About & device health, Help and platform settings. | Physical capability checks and provisioning guidance. |
| Advanced | Keep technical connections and diagnostic controls here; ordinary mode retains saved selections rather than clearing them. | The shared global Advanced flag, consent, registry validation and protected credentials. |

## Widget-specific architecture corrections

Refer to [the widget ownership map](portal-widget-settings-map-2026-11-02.md) for source symbols and complete data-owner details.

1. **Quick controls:** heading routes to Display and edit routes to Quick buttons, neither an editor for its discovered app/dashboard shortcuts. Both need the same truthful setup destination; do not claim app-launch shortcuts are direct HA entity controls.
2. **Photo Memory:** enter Photo source directly from edit; playback-enable visibility must not block editing the source used by an active Home tile.
3. **Today:** its composite Calendar/Weather/Alarm/To-do/Countdown data needs links to existing owners. Calendar-only setup is incomplete.
4. **Timers:** `TimerStore` and `TimerConfig` are separate owners. Resolve which model the widget/editor must expose before storage changes. A label change does not fix ownership.
5. **Widget setup index:** currently omits Quick controls, Today and Timers. Include every custom kind and preserve provider-owned configuration.

## Native verification matrix (pending)

| Scenario | Required behavior | Evidence needed |
| --- | --- | --- |
| 8 category overviews, Advanced off/on | Correct selected sidebar, truthful labels, no extra columns; only permitted entries shown. | Family Area screenshots and route capture for each overview. |
| All 54 registered page IDs | Local page renders in shared pane or documented external editor opens; Back reaches the prior context. | Device route walk; each destination recorded. |
| Back/Forward after sidebar, deep link, search and external form | History follows visits; repeated current-route taps add no duplicate step; disabled Advanced routes are skipped. | Existing focused unit tests plus device walkthrough. |
| Parent-dependent setting search | Search gives a useful prerequisite; parent activation reveals the expected child without losing value. | Registry gate tests and native search checks. |
| Global Advanced switched off with advanced values saved | Values remain saved; ordinary view explains the configured source/connection where relevant. | Before/after settings snapshot and native status screenshot; secrets redacted. |
| Photo source with idle slideshow disabled | Home Photo Memory still has a usable source-setup route. | Native widget edit plus source selection, slideshow remaining disabled. |
| Duplicate widgets and cross-category links | Shared owners remain shared; editing one route updates other consumers without duplicated preferences. | Two instances and both entry points exercised. |
| Atomic connection editor cancel/failure | Cancel retains prior connection; failed/cancelled requests cannot publish stale values or expose credentials. | Existing request-gate tests plus mock-server/device check. |
| Text scale 100/125/150%, dark/light | Rows, selectors, dialogs and headings stay readable with no overlap or clipping. | Device screenshots/layout bounds; browser checks alone insufficient. |
| Warm category/page navigation | Baseline and final frame deadlines/latency/memory measured on identical Family Area scenarios. | Native capture before/after; no claim based on Mac browser timing. |

## Complete registered page ledger

“Owner” is metadata for breadcrumb/grouping; a cross-link can intentionally enter from another category. “Pane” records the routing table, not a measured animation or runtime result. “Secondary” means not directly listed in the eight category overviews.

| ID | Current label | Owner | Entry categories | Pane | Advanced |
| --- | --- | --- | --- | --- | --- |
| `touch` | Touch sounds | sound | sound, access | Shared detail | No |
| `display` | Display | appearance | appearance | Shared detail | No |
| `appearance` | App appearance | appearance | appearance | Shared detail | No |
| `wallpaper` | Wallpaper | appearance | appearance | Shared detail | No |
| `screensaver` | Slideshow | photos | photos | Shared detail | No |
| `sources` | Photo sources | photos | photos | Shared detail | No |
| `styles` | Screensaver style | photos | Secondary / search | Shared detail | No |
| `faces` | Photo clock faces | photos | photos | Shared detail | No |
| `clock` | Digital clock | photos | photos | Shared detail | No |
| `dismiss` | Open when you tap to exit | photos | Secondary / search | Shared detail | No |
| `folder` | Choose photo folder | photos | Secondary / search | External: `FolderPickerActivity` | No |
| `album` | Shared album link | photos | Secondary / search | External: `AlbumUrlEntryActivity` | No |
| `immich` | Connect Immich | photos | advanced | External: `ImmichConnectActivity` | Yes |
| `albums` | Choose Immich album | photos | advanced | External: `ImmichConnectActivity` | Yes |
| `smb` | Connect SMB / NAS | photos | advanced | External: `SmbConnectActivity` | Yes |
| `dav` | Connect WebDAV | photos | advanced | External: `DavConnectActivity` | Yes |
| `web` | Web page | photos | advanced | External: `WebUrlEntryActivity` | Yes |
| `widgets` | Home dashboard | home | home | Shared detail | No |
| `cameras` | Cameras | home | Secondary / search | External: `CameraViewerActivity` | No |
| `weather` | Weather | home | home | Shared detail | No |
| `calendar` | Calendar | home | home | Shared detail | No |
| `almanac` | Almanac | photos | advanced | Shared detail | Yes |
| `calendar-url` | Calendar feed | home | Secondary / search | External: `CalendarUrlEntryActivity` | No |
| `world` | World clock | home | Secondary / search | Shared detail | No |
| `countdowns` | Countdowns | home | Secondary / search | Shared detail | No |
| `quickbar` | Quick buttons | home | home, access | Shared detail | No |
| `alarm` | Alarm | sound | home, sound | Shared detail | No |
| `crypto` | Crypto | home | Secondary / search | Shared detail | No |
| `todo` | To-do | home | Secondary / search | Shared detail | No |
| `news` | News | home | Secondary / search | Shared detail | No |
| `stocks` | Stocks | home | Secondary / search | Shared detail | No |
| `alpaca` | Alpaca account | home | advanced | Shared detail | Yes |
| `options` | Options scanner | home | advanced | Shared detail | Yes |
| `chime` | Sounds | sound | sound | Shared detail | No |
| `welcome` | Welcome greeting | sound | sound | Shared detail | No |
| `sunrise` | Sunrise & wake | sound | sound | Shared detail | No |
| `lamp` | Lamp | sound | sound | External: `LampActivity` | No |
| `multiroom` | Multi-room audio | connections | advanced | Shared detail | Yes |
| `mqtt` | MQTT publishing | connections | advanced | Shared detail | Yes |
| `ha` | Home Assistant dashboard | connections | connections | Shared detail | No |
| `ha-entities` | Choose Home Assistant entities | connections | Secondary / search | External: `HaSettingsActivity` | Yes |
| `ha-dashboard` | Home Assistant | connections | Secondary / search | External: `HaDashboardActivity` | No |
| `phone` | Phone remote | connections | connections | External: `RemotePairActivity` | No |
| `intercom` | Intercom | connections | connections | Shared detail | No |
| `boot` | Start on boot | device | advanced | Shared detail | Yes |
| `device` | Device name | device | device | Shared detail | No |
| `sleep` | Sleep timer | device | device | Shared detail | No |
| `health` | About & device health | device | device | Shared detail | No |
| `apk` | Install an APK | device | advanced | External: `ApkBrowserActivity` | Yes |
| `help` | Welcome to Immortal | device | device | External: `HelpActivity` | No |
| `speed` | Speed test | connections | advanced | Shared detail | Yes |
| `system` | Android & Portal settings | device | access, device | Shared detail | No |
| `managed` | Automatically managed settings | advanced | advanced | Shared detail | Yes |
| `debug` | Diagnostics | advanced | advanced | Shared detail | Yes |

## Evidence and limits

Machine-readable structure: [`settings-navigation-audit.json`](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-widget-gallery-20261102/evidence/settings-navigation-audit.json).

Source: `ApprovedSettingsNavigation.kt` (`ApprovedSettingsCategories`, `ApprovedSettingsPages`, `approvedSettingsIntent`); `ImmortalSettingsActivity.kt` (`ApprovedSettingsRouteContent`, `ApprovedNativeSettingsPage`, `permittedHistoryRoute`); `ApprovedSettingsSearch.kt` (`approvedPageForSpec`, `approvedSettingsPrerequisite`); `SettingsPresentation.kt` (`advancedPages`, `advancedControls`); `ScreensaverSourcesActivity.kt` (`sourcePage`, ordinary/Advanced source handling); `HaSettingsActivity.kt` (entity-only direct entry).

The structural assertions prove unique page IDs, valid overview references and dispatcher coverage. They do not establish every page's controls, visibility combinations, data accuracy, visual correctness, API connectivity, Android handoff or performance. Those remain explicit native verification items. This audit does not complete the full goal.
