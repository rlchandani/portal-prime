# Rebrand inventory: Immortal → Calder

Scope: `app/src/main` (Kotlin, `res/`, `assets/`, `AndroidManifest.xml`), plus the root `catalog.json`
that the bundled `assets/catalog.json` mirrors. Searched for `Immortal|immortal|IMMORTAL|Portal Toolkit`
(and bare `Toolkit` for the companion app). About 1,800 raw hits; most are the `com.immortal.launcher`
package and class names.

Rule: change only what a person sees or hears. Anything persisted, wire-level or internal keeps the
legacy name. "Portal" meaning the Meta hardware stays as it is.

## Changed (visible)

| Area | Where | Change |
|---|---|---|
| App label (launcher, recents, settings) | `res/values/strings.xml` `app_name` | Immortal → Calder |
| Accessibility service label/descriptions | `strings.xml` `back_gesture_service_*`, `install_confirm_desc`, `bar_watch_desc` | Immortal → Calder |
| Activity labels | Manifest: settings activity, "Install with …" APK handler | Immortal → Calder |
| Home wordmark (top-left) | `HomeActivity`, `ApprovedHomeWidgetLibrary`, `ApprovedAppsChrome` | `"immortal"` → `Brand.WORDMARK` (`"calder"`, lowercase kept) |
| Widget picker package label | `HomeActivity` built-in widgets | → `Brand.NAME` |
| Welcome / help copy | `HelpActivity`, `ApprovedSettingsNavigation` | "Welcome to Calder", body copy |
| Settings copy | `ImmortalSettingsActivity`, `SettingsDomains` (domain title), `SettingsPresentation`, `ApprovedSettingsShell` (version line), `SleepSettingsActivity`, `ScreensaverDismissAppActivity`, `ScreensaverSourcesActivity`, `FolderPickerActivity`, `DavConnectActivity`, `SmbConnectActivity` | Immortal → Calder |
| Diagnostics / permission fixes | `DevicePermissions` (`fix`, `enables`, `degraded` text) | "Run Calder setup …" |
| Store / installer | `StoreActivity`, `ApkBrowserActivity`, `ApkInstallActivity` ("via Calder") | Immortal → Calder |
| Notifications | `MqttService`, `FleetAgentService`, `SystemBackGestureService` titles | Immortal → Calder (channel names had no brand) |
| Toasts | `FleetRoutes`, `MqttPublisher` | Immortal → Calder |
| TTS spoken string | `ChimePlayer`, `SoundPreviewPlayback` (EN + RO) | "…selected voice for Calder." |
| Screensaver face name | `ScreensaverFace`, `FaceCatalog` (`immortal-classic`) | name → `Brand.NAME`; id unchanged |
| Built-in photo source label | `ScreensaverSourcesActivity`, `SettingsDomains` | "Calder photos" |
| Phone remote web UI | `RemoteHtml` (title, header brand, noscript), `RemoteRoutes` PWA manifest name/short_name/description, `assets/remote/control-center.js` (status, help, errors, headings) | Immortal → Calder |
| Screensaver page title | `assets/screensaver/index.html` | Calder Screensaver |
| Catalog copy | `catalog.json` + `assets/catalog.json`: self entry `name`/`description`, mentions in other apps' descriptions | Immortal → Calder |
| Companion app name | `ShareHomeNotes`, `DinnerCallSheet`, `DinnerCallModel`, `HouseholdList{Migration,Widget,Columns}`, `RoutineSchedule`, `RoutinesHomeWidget`, `control-center.js` | Portal Toolkit / Toolkit → Calder Pocket |

## Kept (internal, persisted or wire-level)

| What | Examples | Why |
|---|---|---|
| applicationId, package, class/function names | `com.immortal.launcher[.debug]`, `ImmortalApp`, `ImmortalSettingsActivity`, `ImmortalBackGestureService` | Updates and component names |
| Resource and style names | `@drawable/immortal_banner`, `Animation.Immortal.Navigation` | Internal identifiers |
| SharedPreferences names and keys | `immortal_settings`, `immortal.maApiToken`, `immortal.hideStatusBar` | Household data |
| Settings registry / domain ids | `"immortal"` domain, `SettingsRegistry.apply(context, "immortal", …)` | Persisted, remote API |
| Watch face ids | `immortal-classic` | Persisted |
| Log tags and log messages | `ImmortalYtEngine`, `BackHelper` log lines, `DreamPolicy` log | Not shown to people |
| Protocol constants | `IMMORTAL_HH/1`, `_immortal-remote._tcp.`, `x-immortal-household`, `immortal:wake` wake-lock tags | Wire-level |
| HTTP User-Agent | `Immortal/1.0`, `PhotoFrameController.USER_AGENT` | Wire-level |
| URLs | `github.com/starbrightlab/immortal/...`, update and catalog URLs, `immortal.apk` | Hosting, not copy |
| On-disk paths and file names | `/sdcard/Pictures/Immortal`, `immortal-update.apk`, `immortal-snapshot.json` download name | File names on disk |
| Web storage keys | `assets/remote/core.mjs` `immortal_remote_*` localStorage keys, `window.immortalSlideshow` | Persisted in the phone browser |
| Fleet API response values | `FramePerformanceCapture` `scope` strings | Fleet API field values |
| QA fixture notification | `AppBadgeProbe` "Immortal verification" | Fleet-driven test fixture, not normal use |
| ADB command text | `DevicePermissions.technicalFix` | Contains the component name, which must stay literal |
| Code comments | KDoc and manifest comments | Not shown to people |

## Left for a human decision

| What | Where | Note |
|---|---|---|
| Music Assistant `device_name` | `MaMusicClient`, `MaLegacyAuth` (`"Immortal"`) | People see it in Music Assistant's token/device list, but it's sent to an external server. Kept for now. |
| Live catalog | root `catalog.json` | The devices fetch this from GitHub `main`, so the rename shows up for every user once it merges. That includes older builds that still say Immortal everywhere else. |
| Debug-only mock | `app/src/debug/.../IosSettingsMockActivity` ("Immortal Account") | Outside `app/src/main`; dev mock only. |
| Repo/URL naming | `starbrightlab/immortal` | Renaming the GitHub repo would change the update and catalog URLs. Out of scope. |
