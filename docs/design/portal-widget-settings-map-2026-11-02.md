# Portal widget settings ownership — 2026-11-02

Status: source inspection and design proposals; native redesign requires user design approval.
Scope: fourteen built-in `HomeWidgetStore.KIND_*` kinds on `codex/widget-gallery-20261102`.
This document records current routes separately from proposed changes. No runtime, browser,
device, build or performance verification was performed.

## Current route and editor map

“Edit” means the **Settings** action in `ApprovedWidgetCell` while editing Home.
Routes resolve through `openApprovedHomeSettings`, `approvedSettingsIntent` and
`ApprovedSettingsPages`; most use the existing Settings detail pane, with standalone
Activity wrappers retained. Domain IDs below describe data coverage, not page IDs.

| Kind / rendered symbol | Heading or primary action → destination | Edit → destination | Owning data editor | Registry / spec coverage |
| --- | --- | --- | --- | --- |
| `weather` / `ApprovedHomeWeather` | `weather` | `weather` | Weather detail in `ImmortalSettingsActivity` | `immortal.weatherUnit`, `immortal.weatherWidget`; shared `immortal.clockFormat` is on App appearance. |
| `world_clock` / `ImmortalWorldClockWidget` | `world` | `world` | `WorldClockContent` / `WorldClockActivity`; `ImmortalSettings.setWorldClockZones` | Shared `immortal.clockFormat` only; selected zone collection has no spec. |
| `timers` / `ImmortalTimersWidget` | `ApprovedHomeTimersPage` dialog; overrides nominal `timers` route | Same dialog | Widget controls use `TimerStore`; dialog hosts `TimersOverlay`, which edits `TimerConfig` | No timer domain/spec. **Different data owners**, despite the matching heading/edit destination. |
| `calendar` / `ApprovedHomeCalendar` | `calendar`; empty-state Add calendar → `calendar-url` | `calendar` | Calendar detail; `CalendarUrlEntryActivity` | `calendar.url` uses `Entry.Nav`; `widgetOn`, `range`, `size`, `side` configure the screensaver overlay. Shared clock format is `immortal.clockFormat`. |
| `alarm` / `ImmortalAlarmWidget` | `alarm` | `alarm` | `AlarmContent` / `AlarmSettingsActivity`; `AlarmSettingsEditor` → `AlarmConfig` + reschedule | No alarm domain. `AlarmConfig` is an explicit bespoke scalar-editor exception in `SettingsDomainTest`; distinct from `sunrise`. |
| `crypto` / `ImmortalCryptoWidget` | `crypto` | `crypto` | `CryptoSettingsContent` / `CryptoSettingsActivity` → `CryptoConfig` | No domain/spec; tracked coin collection is an explicit exception. |
| `todo` / `ImmortalTodoWidget` | `todo` | `todo` | `TodoSettingsContent` / `TodoSettingsActivity` → `TodoStore` | No domain/spec. `TodoStore` is user content; the persisted-`*Config` tripwire does not establish its coverage. |
| `news` / `ImmortalNewsWidget` | `news` | `news` | `NewsSettingsContent` / `NewsSettingsActivity` → `NewsConfig` | No domain/spec; feed collection is an explicit exception. |
| `stocks` / `ImmortalStocksWidget` | `stocks` | `stocks` | `StocksSettingsContent` / `StocksSettingsActivity` → `StocksConfig`; account form via `alpaca` | No domain/spec; tickers and `AlpacaConfig` credentials are explicit exceptions. |
| `options` / `ImmortalOptionsWidget` | `options` | `options` | `OptionsSettingsContent` / `OptionsSettingsActivity` → `OptionsScanConfig`; shared Alpaca account | No domain/spec; collection + scanner parameters and credentials are explicit exceptions. `options` / `alpaca` are Advanced pages. |
| `ha` / `ImmortalHaWidget` | With dashboard URL: `HomeDashboardActivity.openDashboard`; otherwise `ha` | `ha` | `HaSettingsContent` / `HaSettingsActivity`: `HomeDashboardSettings`, then Advanced connection/entity editor | `home_dashboard.url` binds `HomeDashboardConfig`; `HaConfig` URL/token/entities are an explicit exception. Ordinary page returns before native API editor when Advanced is off. |
| `today` / `TodayStripWidget` | `calendar`; Add calendar → `calendar-url` | `calendar` | Composite: calendar detail, Weather, `AlarmContent`, `TodoSettingsContent`, `CountdownSettingsContent` | `calendar.url`, `immortal.weatherUnit` / `clockFormat`; alarm/content/countdown owners remain bespoke. Calendar route alone does not edit all displayed data. |
| `quick_controls` / `QuickControlsWidget` | `display` | `quickbar` | **No dedicated editor.** PackageManager app discovery + `HomeDashboardConfig`; dashboard URL edited through `ha` | `quickbar.enabled` / `alwaysShow` control the app-switcher button, not these tiles. `home_dashboard.url` covers only dashboard input; no discovery spec. |
| `photo_memory` / `ApprovedHomePhoto` | Whole card → `PhotoFramePreviewActivity`; title has no separate settings action | `screensaver` | `ScreensaverSourcesContent` / `ScreensaverSourcesActivity`; folder/album/Immich/SMB/WebDAV/web editors | `screensaver.photoSource` is a `NavSpec` to source selection, visible only when screensaver enabled. `source` / `folderPath` are derived; connections remain atomic bespoke groups. Slideshow scalar settings have specs. |

All custom instances share these data owners. `HomeWidgetStore.HomeWidget` stores placement,
kind and size, not individual widget accounts or feeds. A new settings label must not imply
that changes apply only to the selected instance.

## Actionable findings and minimal proposals

1. **Give Quick controls a truthful setup destination.** `QuickControlsWidget` probes launchable
   `com.portal.assistant` and both HA companion packages on resume. A configured dashboard
   suppresses the four companion-app shortcuts; “Lights”, “Scenes”, “Climate” and “Home” all
   launch the same app. They are not device controls or independently editable shortcuts.
   Smallest correction: both heading and edit open existing `ha`, with explicit “Dashboard
   address” wording and a separate Apps setup action using `openApprovedHomeApps`. Preferred
   pilot proposal: one small **Quick controls setup** page showing discovered-app availability,
   linking to `ha` and Apps, and explaining dashboard precedence. Both entry points use that
   owner. Do not add persisted discovery toggles, package pickers or duplicate credentials.

2. **Separate photo selection from idle playback.** Change Photo Memory's edit destination to
   existing `sources`; retain its primary action as preview. Use **Photo source** for shared
   media selection and **Slideshow when idle** for `screensaver`. The current indirect
   `screensaver.photoSource` link disappears when `enabled` is false, although
   `ApprovedHomePhoto` still consumes the selected source. Add helper text: “Used by Photo
   Memory and the screensaver.” `sources` includes web pages as well as photos; preserve
   the explicit “Web page” label and the existing `ApprovedHomePhoto` web-page message.
   Its loader selects the first available photo; this is not date-matched “On this day”
   memory. Do not reuse the older `PhotoMemoryStrip` description.

3. **Make composite ownership reachable without duplicating editors.** Keep Today’s heading
   opening Calendar, but label that intent clearly. Make its edit/setup action a small
   linked list: Calendar feed, Weather, Alarm, To-do and Countdowns. These links reuse current
   pages. Calendar already groups `url` under “Home & screensaver” and overlay controls under
   “Screensaver widget”; retain that distinction. In particular, calendar `size` is unrelated
   to the Home tile span, and `widgetOn` does not remove the Home tile.

4. **Treat the timer discrepancy as an ownership decision before changing navigation.**
   The widget's `TimerStore` is a single pause/resume/ringing timer; both settings entry points
   open a `TimerConfig` collection editor through `ApprovedHomeTimersPage`. Source inspection
   demonstrates separate reads/writes, not a verified runtime failure. Keep this recorded
   as a pilot blocker: select the intended owner, then make editor and widget agree. A route
   rename alone cannot resolve it. Storage unification or migration needs separate approval.

5. **Distinguish dashboard, controls and placement.** `widgets` is titled “Home dashboard”,
   `home_dashboard` is a domain for a web address, and `ha` is the dashboard setup page.
   Rename the placement page **Customize Home** and use **Home dashboard connection** for `ha`.
   Preserve the configured HA widget's heading as “Open dashboard”; its edit action remains
   setup. Keep native HA entities under the existing Advanced route, clearly labelled as
   controls. Quick controls' app-launch tiles and `ImmortalHaWidget` REST entity actions must
   not share a label suggesting the same capability.

6. **Fill navigation gaps, not the entire registry.** The `widgets` detail currently lists
   Weather, HA, Calendar, Alarm, World clock, Crypto, Countdowns, To-do, News, Stocks and
   Sources, plus gated Options. It omits Quick controls, Today and Timers as owning entries;
   Countdowns is a Today dependency rather than one of the fourteen kinds. Use explicit
   widget-to-owner links instead of the current kind-string fallback, retaining provider
   configuration behavior. Preserve documented collection/credential exceptions. Any new
   persisted scalar must have a `SettingSpec` with validation/visibility and domain
   `onApplied`; do not turn this navigation pilot into a settings or persistence rewrite.

## Proposed native pilot hierarchy

Retain the existing two-pane shell and Back/Forward history. The following is a presentation
proposal, not a replacement domain model or approved implementation:

| Existing area | Minimal grouping / destinations |
| --- | --- |
| Home & widgets | **Customize Home** → placement/size; **Widget setup** → existing owner pages, with Quick controls and Today setup links and an explicitly resolved Timers owner. |
| Widget setup: everyday | Weather, Calendar, World clock, Today, Alarm, Timers, To-do. Shared clock format links to App appearance; expose no duplicate persisted field. |
| Widget setup: feeds | News, Crypto, Stocks; preserve the Advanced gate for Options and Alpaca account. |
| Widget setup: home / photos | Quick controls setup, Home dashboard connection, Photo source. These are aliases to their owners, not copies of the forms. |
| Photos & screensaver | Photo source; Slideshow when idle; existing photo clock faces / digital clock. Photo Memory setup enters Photo source directly. |
| Connections / Advanced | Existing dashboard connection; Advanced HA API entities and advanced photo connections. Keep existing gates and selected-source status. |

Pilot first: Quick controls destination agreement, Photo Memory direct source editing, and
dashboard/placement wording. Today links can follow; timer ownership is a separate decision.
Before native work, obtain user approval of destinations and labels. Later verification should
check heading/edit agreement, Back return, Advanced on/off, screensaver disabled, dashboard
precedence, and shared changes across duplicate widgets. These are proposed checks, not results.

## Source anchors

- [HomeActivity.kt](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt):
  `ApprovedWidgetCell`, `ImmortalWidgetContent`, `ImmortalWidgetShell`, `QuickControlsWidget`,
  `TodayStripWidget`, `ImmortalTimersWidget`, `ImmortalHaWidget` and remaining `Immortal*Widget` handlers.
- [HomeWidgetStore.kt](../../app/src/main/java/com/immortal/launcher/HomeWidgetStore.kt):
  `KIND_*`, `HomeWidget`, `serialize`; placement ownership and all fourteen kinds.
- [ApprovedSettingsNavigation.kt](../../app/src/main/java/com/immortal/launcher/ApprovedSettingsNavigation.kt):
  `ApprovedSettingsCategories`, `ApprovedSettingsPages`, `approvedSettingsIntent`.
  [ApprovedHomeNavigation.kt](../../app/src/main/java/com/immortal/launcher/ApprovedHomeNavigation.kt):
  `openApprovedHomeSettings`, `openApprovedHomeApps`, `ApprovedHomeTimersPage`.
- [ImmortalSettingsActivity.kt](../../app/src/main/java/com/immortal/launcher/ImmortalSettingsActivity.kt):
  detail `when (page)`, `DisplaySection`, `QuickButtonsSection`, `WorldClockContent`.
- [SettingsDomains.kt](../../app/src/main/java/com/immortal/launcher/settings/SettingsDomains.kt):
  `all`, `calendar`, `screensaver`, `immortal`, `quickbar`, `homeDashboard`.
  [SettingsPresentation.kt](../../app/src/main/java/com/immortal/launcher/settings/SettingsPresentation.kt):
  `advancedPages`, `advancedControls`.
  [SettingsDomainTest.kt](../../app/src/test/java/com/immortal/launcher/settings/SettingsDomainTest.kt):
  `exemptCollectionsOrContent`; the tripwire classifies persisted `*Config` files, not every store.
- [ApprovedHomePhoto.kt](../../app/src/main/java/com/immortal/launcher/ApprovedHomePhoto.kt):
  `ApprovedHomePhoto`, `loadApprovedHomePhoto`.
  [ScreensaverSourcesActivity.kt](../../app/src/main/java/com/immortal/launcher/ScreensaverSourcesActivity.kt):
  `ScreensaverSourcesContent`, `sourcePage`.
  [HaSettingsActivity.kt](../../app/src/main/java/com/immortal/launcher/HaSettingsActivity.kt):
  `ApprovedEditorsHaContent`, `HomeDashboardSettings`.
- [HomeToolOverlays.kt](../../app/src/main/java/com/immortal/launcher/HomeToolOverlays.kt):
  `TimersOverlay` → `TimerConfig`.
  [AlarmSettingsEditor.kt](../../app/src/main/java/com/immortal/launcher/AlarmSettingsEditor.kt):
  `alarmSettingsEditor`, `AlarmSettingsEditor`.
  Other editor bodies were inspected in their named `*SettingsActivity.kt` source files.
