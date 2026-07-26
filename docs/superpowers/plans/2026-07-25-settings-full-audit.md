# Immortal Settings — Full Audit (2026-07-25)

Read-only inventory of every Immortal-owned setting, mapped against what the current
mac/iPad-style `ImmortalSettingsActivity` two-panel UI actually surfaces. Goal: surface
ALL Immortal-owned settings in the new sidebar UI.

## Sources examined

- Registry: `app/src/main/java/com/immortal/launcher/settings/SettingsDomains.kt` (10 domains)
- Renderer: `app/src/main/java/com/immortal/launcher/SettingsRenderer.kt` (`SettingsList(domain, snapshot)`)
- Current UI: `app/src/main/java/com/immortal/launcher/ImmortalSettingsActivity.kt`
- Bespoke sub-screen Activities: `ChimeSettingsActivity.kt`, `SunriseSettingsActivity.kt`,
  `SleepSettingsActivity.kt`, `CountdownSettingsActivity.kt`, `WelcomeSettingsActivity.kt`,
  `ClockSettingsActivity.kt`, `ScreensaverSettingsActivity.kt`
- Sub-screen Composables + thin Activity hosts: `ImmortalSubScreens.kt` (hosts
  `MqttScreen`, `MultiRoomScreen`, `WorldClockScreen`, `BootAppsScreen`, `DeviceHealthScreen`
  — all defined inside `ImmortalSettingsActivity.kt`)
- Non-registry configs: `Wallpaper.kt` (`WallpaperConfig`), `CalendarPacks.kt`,
  `SystemSounds.kt`, `BackHelper.kt`, `CountdownConfig.kt`, `BootLaunch`

---

## KEY FINDING — the current 6-section UI is a near-total regression

The two-panel `ImmortalSettingsScreen` (`ImmortalSettingsActivity.kt:107`) hand-writes rows
for a SMALL curated subset and reaches only **5 sub-screens by Intent**:
`FacePickerActivity`, `ScreensaverSourcesActivity`, `ChimeSettingsActivity`,
`SunriseSettingsActivity`, `DeviceHealthActivity`
(refs at `ImmortalSettingsActivity.kt:295, 381, 508, 516, 543, 551`).

A large set of NavRow/section composables from the previous design still EXIST in the same
file but are **never invoked** by the 6-section screen — dead entry points:
`MqttNavRow` (:1102), `MultiRoomNavRow` (:697), `RemoteNavRow` (:1127),
`BootAppsNavRow` (:1778), `DeviceHealthNavRow` (:1571), `FeatureSettingsNavRow` (:1070),
`MoreFeaturesSection` (:1032), `WallpaperSection` (:1916), `QuickButtonsSection` (:1003).
Their target screens (`MqttScreen`, `MultiRoomScreen`, `WorldClockScreen`, `BootAppsScreen`,
`ClockSettingsActivity`, `ScreensaverSettingsActivity`, `SleepSettingsActivity`,
`WelcomeSettingsActivity`) are fully built and manifest-registered but **unreachable from
the new Settings UI**. `CountdownSettingsActivity` is reachable only from `ToolsActivity`
(`ToolsActivity.kt:125`), not from Settings.

The good news: `SettingsRenderer.SettingsList(domain, snapshot, onApply)` already renders any
registry domain generically (bool→toggle, enum→segmented, int→stepper, NavSpec/StringSpec.Nav→
nav row, Derived→skipped, grouped by `domain.sections`). Most "MISSING" rows are a one-line
`SettingsList(SettingsDomains.<x>, snapshot) { … }` call — no new UI needed.

---

## Table A — Immortal-owned settings inventory

STATUS legend: **ported** = a row shown in one of the current 6 sidebar sections;
**reachable** = only via a sub-screen Activity that the 6-section UI launches by Intent
(Chime / Sunrise / DeviceHealth / FacePicker / ScreensaverSources);
**MISSING** = not reachable from the current Settings UI at all.

### Domain: `screensaver` — `ScreensaverConfig` (SettingsDomains.kt:176)

| Setting (key) | Title | Logic lives | Status |
|---|---|---|---|
| enabled | Show the photo-frame screensaver | ScreensaverConfig.setEnabled | MISSING (shown only inside `ScreensaverSettingsActivity`, unreachable) |
| clockFace (NavSpec) | Clock face → FacePickerActivity | FaceCatalog / FacePickerActivity | **ported** (Clock Face section) |
| photoSource (NavSpec) | Photo source → ScreensaverSourcesActivity | ScreensaverSourcesActivity | **ported** (Screensaver section) |
| dismissTarget (NavSpec) | Open when you tap to exit | ScreensaverDismissAppActivity | MISSING |
| albumRefreshMin | Album refresh | ScreensaverConfig.setAlbumRefreshMin | MISSING |
| fit | Fit | ScreensaverConfig.setFit | **ported** (Screensaver) |
| intervalSec | Photo interval | ScreensaverConfig.setInterval | **ported** (Screensaver) |
| shuffle | Shuffle | ScreensaverConfig.setShuffle | **ported** (Screensaver) |
| includeVideo | Play videos | ScreensaverConfig.setIncludeVideo | **ported** (Screensaver) |
| cacheEnabled | Store media on this device | ScreensaverConfig.setCacheEnabled | MISSING |
| cacheBudgetGb | Storage limit | ScreensaverConfig.setCacheBudgetGb | MISSING |
| batterySaver | Sleep on battery when nobody's around | ScreensaverConfig.setBatterySaver | MISSING |
| showNowPlaying | Show now playing | ScreensaverConfig.setShowNowPlaying | **ported** (Clock Face) |
| antiBurnIn | Reduce screen burn-in | ScreensaverConfig.setAntiBurnIn | **ported** (Screensaver) |
| showGradient | Legibility gradient | ScreensaverConfig.setShowGradient | **ported** (Screensaver) |
| presenceMode | Power (Always on / Follow presence) | ScreensaverConfig.setPresenceMode | **ported** (Screensaver) |
| idleSleepMin | Idle screen-off | ScreensaverConfig.setIdleSleepMin | MISSING |
| overnightEnabled | Overnight screen-off | ScreensaverConfig.setOvernightEnabled | MISSING |
| overnightStartMin | Off from | ScreensaverConfig.setOvernightStartMin | MISSING |
| overnightEndMin | Off until | ScreensaverConfig.setOvernightEndMin | MISSING |
| overnightNightClock | Show a dim night clock | ScreensaverConfig.setOvernightNightClock | MISSING |
| feed | Photo feed (built-in source) | ScreensaverConfig.setFeed | MISSING |
| sleepTimerEnabled | Sleep timer | ScreensaverConfig.setSleepTimerEnabled | MISSING (in `SleepSettingsActivity`, unreachable) |
| sleepTimerMin | Sleep after | ScreensaverConfig.setSleepTimerMin | MISSING (SleepSettingsActivity) |
| pauseAudioOnSleep | Pause audio on sleep | ScreensaverConfig.setPauseAudioOnSleep | MISSING (SleepSettingsActivity) |
| closeAppOnSleep | Close app on sleep | ScreensaverConfig.setCloseAppOnSleep | MISSING (SleepSettingsActivity) |
| soundscape | Ambient sound | ScreensaverConfig.setSoundscape | **ported** (Audio section) |
| soundscapeVolume | Soundscape volume | ScreensaverConfig.setSoundscapeVolume | MISSING |
| ambientDashboard | Ambient dashboard | ScreensaverConfig.setAmbientDashboard | MISSING |
| gestureWave | Gesture wave to wake | ScreensaverConfig.setGestureWave | MISSING |
| welcomeEnabled | Welcome screen | ScreensaverConfig.setWelcomeEnabled | MISSING |
| source / folderPath / albumUrl | (DerivedSpec, read-only) | — | n/a (not rendered) |

31 writable specs; 3 derived (read-only). Ported: 10. Reachable: 0. Missing: 21.

### Domain: `calendar` — `ScreensaverConfig` calendar slice (SettingsDomains.kt:66)

| Setting (key) | Title | Logic lives | Status |
|---|---|---|---|
| widgetOn | Show calendar | ScreensaverConfig.setCalendarEnabled | MISSING |
| url (StringSpec.Nav) | Calendar feed → CalendarUrlEntryActivity | ScreensaverConfig.setCalendarUrl | MISSING |
| range | Show (Day/3-day/Week/Agenda) | ScreensaverConfig.setCalendarRange | MISSING |
| size | Size | ScreensaverConfig.setCalendarSize | MISSING |
| side | Side (Left/Right) | ScreensaverConfig.setCalendarSide | MISSING |
| enabled/hasLink/provider/supported/ranges | (DerivedSpec) | — | n/a |

5 writable; 5 derived. Ported: 0. Missing: 5. (Rendered only inside the unreachable
`ScreensaverSettingsActivity`, ScreensaverSettingsActivity.kt:112.)

### Domain: `immortal` — `ImmortalSettings` (SettingsDomains.kt:484)

| Setting (key) | Title | Logic lives | Status |
|---|---|---|---|
| weatherUnit | Temperature unit | ImmortalSettings.setWeatherUnit | **ported** (Weather) |
| tileSize | App tile size | ImmortalSettings.setTileSize | **ported** (Appearance) |
| weatherWidget | Weather widget | ImmortalSettings.setWeatherWidget | **ported** (Appearance) |
| clockFormat | Clock (Auto/12/24) | ImmortalSettings.setClockFormat | **ported** (Clock Face) |
| showMiniPlayer | Now-playing mini-player | ImmortalSettings.setShowMiniPlayer | **ported** (Appearance) |
| hideStatusBar | Hide status bar | ImmortalSettings.setHideStatusBar | **ported** (Appearance) |
| constrainPageWidth | Constrain page width | ImmortalSettings.setConstrainPageWidth | MISSING |
| multiRoomEnabled | Multi-room audio | ImmortalSettings.setMultiRoomEnabled | MISSING (`MultiRoomScreen`, unreachable) |
| snapcastHost | Snapcast host | ImmortalSettings.setSnapcastHost | MISSING (MultiRoomScreen) |
| maPort | Music Assistant port | ImmortalSettings.setMaPort | MISSING (MultiRoomScreen) |
| maUsername | Music Assistant user | ImmortalSettings.setMaUsername | MISSING (MultiRoomScreen) |
| maPassword | Music Assistant password | ImmortalSettings.setMaPassword | MISSING (MultiRoomScreen) |

12 specs. Ported: 6. Missing: 6.
Additionally `ImmortalSettings.worldClockZones` (Set) is NOT in the registry — managed by the
bespoke `WorldClockScreen` (ImmortalSettingsActivity.kt:1455). MISSING.

### Domain: `mqtt` — `MqttConfig` (SettingsDomains.kt:631)

Context-as-snapshot; `SettingsList` will NOT recompose (see the WARNING in SettingsDomains.kt:618).
Served on-device by bespoke `MqttScreen` (ImmortalSettingsActivity.kt:1163).

| Setting (key) | Title | Status |
|---|---|---|
| enabled | Publish to MQTT | MISSING |
| host | Broker host | MISSING |
| port | Port | MISSING |
| username | Username | MISSING |
| password | Password | MISSING |
| useTls | Use TLS | MISSING |
| validateCert | Validate certificate | MISSING |

7 specs. Ported: 0. Missing: 7 (all in unreachable `MqttScreen`/`MqttActivity`).

### Domain: `quickbar` — `QuickBarConfig` (SettingsDomains.kt:708)

| Setting (key) | Title | Status |
|---|---|---|
| enabled | App-switcher button | MISSING |
| alwaysShow | Always show | MISSING |

2 specs, both MISSING. (`QuickButtonsSection`, ImmortalSettingsActivity.kt:1003 — orphaned.)

### Domain: `fleet` — `FleetConfig` (SettingsDomains.kt:742)

| Setting (key) | Title | Status |
|---|---|---|
| name | Device name | MISSING |

1 spec, MISSING.

### Domain: `chime` — `ChimeConfig` (SettingsDomains.kt:774)

Rendered in full by `ChimeSettingsActivity` (via `SettingsList(SettingsDomains.chime)`),
which the Audio section launches by Intent (ImmortalSettingsActivity.kt:508).

| Setting (key) | Title | Status |
|---|---|---|
| hourlyChimeOn | Hourly chime | reachable (Audio → Chime) |
| chimeVolume | Volume | reachable |
| spokenTimeOn | Spoken time | reachable |
| spokenVolume | Volume | reachable |
| goldenHourOn | Golden-hour tone | reachable |
| goldenVolume | Volume | reachable |
| sunriseVariant | Sunrise sound (Morning/Rooster) | reachable |
| pingVolume | Ping volume | reachable |
| quietHoursOn | Quiet hours | reachable |
| quietStartMin | Quiet from | reachable |
| quietEndMin | Quiet until | reachable |
| spokenVoice | Voice (TTS picker) | reachable — BESPOKE (ChimeSettingsActivity.kt:74, `managedElsewhere`) |

11 registry specs + 1 bespoke. All reachable via the Chime nav row, none surfaced as a section.

### Domain: `sunrise` — `SunriseConfig` (SettingsDomains.kt:906)

Rendered by bespoke `SunriseSettingsActivity` (hand-built, NOT via SettingsList), launched
from Audio (ImmortalSettingsActivity.kt:516).

| Setting (key) | Title | Status |
|---|---|---|
| enabled | Sunrise alarm | reachable (Audio → Sunrise Wake Light) |
| hour | Hour | reachable |
| minute | Minute | reachable |
| rampMinutes | Ramp minutes | reachable |
| chime | Chime at end | reachable |
| days | Wake-on days (Set) | reachable — BESPOKE day-picker (SunriseSettingsActivity.kt:110, `managedElsewhere`) |

5 registry specs + 1 bespoke. All reachable, none surfaced as a section.

### Domain: `digitalclock` — `DigitalClockConfig` (SettingsDomains.kt:979)

Rendered in full by `ClockSettingsActivity` (via `SettingsList(SettingsDomains.digitalclock)`,
ClockSettingsActivity.kt:95) — but that Activity is NOT launched from the 6-section UI.
Only `showDate` and `showSeconds` are duplicated as hand-written rows in the Clock Face section.

| Setting (key) | Title | Status |
|---|---|---|
| enabled | Digital clock | MISSING |
| style | Clock style | MISSING |
| color | Color | MISSING |
| font | Font | MISSING |
| size | Size | MISSING |
| layout | Position | MISSING |
| background | Background | MISSING |
| glow | Glow | MISSING |
| showDate | Show date | **ported** (Clock Face) |
| showSeconds | Show seconds | **ported** (Clock Face) |

10 specs. Ported: 2. Missing: 8.

### Domain: `welcome` — `WelcomeConfig` (SettingsDomains.kt:1148)

Rendered by bespoke `WelcomeSettingsActivity` (hand-built), NOT reachable from Settings UI.

| Setting (key) | Title | Status |
|---|---|---|
| durationMs | Display duration | MISSING |
| showGreeting | Show greeting | MISSING |
| showClock | Show clock | MISSING |
| showDate | Show date | MISSING |
| enableTts | Speak greeting | MISSING |
| userName, greetingNight/Morning/Afternoon/Evening | Greeting text | MISSING — BESPOKE (WelcomeSettingsActivity.kt:167-190) |
| ttsVoice | Voice picker | MISSING — BESPOKE |
| greetingSize, clockSize, dateSize | Text sizes (Float) | MISSING — BESPOKE (no FloatSpec in registry) |
| backgroundOpacity | Background opacity (Float) | MISSING — BESPOKE |

5 registry specs + ~10 bespoke (text/color/float). All MISSING.

### Non-registry Immortal-owned settings (no domain)

| Setting | Logic lives | Status |
|---|---|---|
| Wallpaper mode + film grain | `WallpaperConfig` (Wallpaper.kt) | MISSING — bespoke `WallpaperSection` (ImmortalSettingsActivity.kt:1916, orphaned) |
| World-clock cities (Set) | `ImmortalSettings.worldClockZones` | MISSING — `WorldClockScreen` |
| Countdown events (add/remove) | `CountdownConfig` | reachable via Tools only (ToolsActivity.kt:125), NOT Settings |
| Boot apps (relaunch-on-reboot set) | `BootLaunch` | MISSING — `BootAppsScreen` |
| Almanac calendar packs (per-pack toggles) | `CalendarPacks.AVAILABLE` | MISSING — `MoreFeaturesSection` (orphaned) |
| Touch sounds | `SystemSounds` (needs WRITE_SETTINGS) | MISSING — `MoreFeaturesSection` (orphaned) |
| Back gesture (deep-links to Accessibility) | `BackHelper` + system intent | MISSING — `MoreFeaturesSection` (orphaned) |
| Remote pairing | `RemotePairing` / `RemotePairActivity` | MISSING — `RemoteNavRow` (orphaned) |
| Device health / permissions | `DevicePermissions` | **ported** (System → About → DeviceHealthActivity) |
| Debug mode | local pref `immortal_debug` | **ported** (System, ImmortalSettingsActivity.kt:652) |

### Count summary (Immortal-owned)

- Registry writable specs: **89** (screensaver 31 + calendar 5 + immortal 12 + mqtt 7 +
  quickbar 2 + fleet 1 + chime 11 + sunrise 5 + digitalclock 10 + welcome 5).
- Bespoke registry-adjacent settings (`managedElsewhere` + welcome text/float/color):
  chime.spokenVoice, sunrise.days, welcome ~10 = **~12**.
- Non-registry Immortal-owned settings/features: wallpaper (2), worldClock (1), countdown,
  boot apps, almanac packs, touch sounds, back gesture, remote pairing = **~8 feature areas**.
- **Total Immortal-owned settings ≈ 109** (89 registry + ~12 bespoke + ~8 non-registry areas).

Ported (shown in a current sidebar section): **18 registry specs** + Debug + DeviceHealth nav
(≈ 20 surfaced).
Reachable-but-not-a-section (Chime, Sunrise sub-screens): **~18 specs**.
MISSING (unreachable from Settings UI): **~71 settings** across screensaver rest, calendar,
mqtt, quickbar, fleet, digitalclock rest, welcome, multiroom, worldclock, wallpaper, boot
apps, almanac, sleep timer, countdown.

---

## Table B — Proposed NEW sidebar sections

Render method: **SettingsList** = one-line generic render of a registry domain (easy);
**Activity** = a bespoke sub-screen that already exists and just needs a sidebar entry / nav row.

| Proposed section | Renders | Method | Notes |
|---|---|---|---|
| Screensaver (expand existing) | `SettingsDomains.screensaver` full domain (+ `calendar` domain) | **SettingsList** ×2 | Replace the 8 hand-written rows with `SettingsList(screensaver)`; adds cache, overnight, feed, idle, dismiss-target, ambient, gesture, welcome toggle. Calendar renders as its own card group. Mirror `ScreensaverSettingsActivity.kt:108-114`. |
| Sleep timer | screensaver keys sleepTimer*, pauseAudioOnSleep, closeAppOnSleep | **Activity** | `SleepSettingsActivity` exists (live countdown UI). Add nav row; keys already in `screensaver` domain if a plain render is preferred. |
| Clock (Digital) | `SettingsDomains.digitalclock` full domain | **SettingsList** or **Activity** | `ClockSettingsActivity` already does `SettingsList(digitalclock)` + preview. Either link it or inline the domain. Surfaces style/color/font/size/layout/background/glow. |
| Appearance / Wallpaper | `WallpaperConfig` (mode, grain) | **Activity/bespoke** | Reuse `WallpaperSection` composable (swatch grid — not a registry domain). |
| Weather | `immortal` weather* keys (already ported) + keep | SettingsList subset | Minor. |
| Calendar | `SettingsDomains.calendar` | **SettingsList** | Feed URL row uses `Entry.Nav(CalendarUrlEntryActivity)`. |
| Almanac | `CalendarPacks.AVAILABLE` toggles | **bespoke** | Reuse `MoreFeaturesSection` packs loop (not a registry domain). |
| Home Assistant (MQTT) | `SettingsDomains.mqtt` | **Activity** | MUST use bespoke `MqttScreen`/`MqttActivity` — Context-as-snapshot won't recompose under SettingsList (SettingsDomains.kt:618 WARNING). Reuse `MqttNavRow`. |
| Multi-room audio | `immortal` multiRoom*/snapcast*/ma* keys | **Activity** | Bespoke `MultiRoomScreen`/`MultiRoomActivity` (setup guide + MA sign-in test). Reuse `MultiRoomNavRow` (only show if Snapcast installed). |
| Chime / Sounds | `SettingsDomains.chime` (+ bespoke voice picker/test) | **Activity** | Already reachable; promote to sidebar. `ChimeSettingsActivity` = SettingsList + voice picker + test buttons. |
| Sunrise & Wake | `SettingsDomains.sunrise` (+ bespoke day-picker) | **Activity** | `SunriseSettingsActivity` hand-built (day-picker, ramp slider, preview). Already reachable; promote to sidebar. |
| Welcome | `SettingsDomains.welcome` (+ bespoke greetings/voice/sizes) | **Activity** | `WelcomeSettingsActivity` (text editors, TTS voice, float steppers, opacity). |
| Countdown | `CountdownConfig` events | **Activity** | `CountdownSettingsActivity` exists; currently only in Tools. Add Settings nav row. |
| Quick buttons | `SettingsDomains.quickbar` | **SettingsList** | Trivial 2-toggle render. Reuse `QuickButtonsSection`. |
| Device (name) | `SettingsDomains.fleet` | **SettingsList** | Single "Device name" string field (validated 1..48). |
| World clock | `ImmortalSettings.worldClockZones` | **Activity** | Bespoke `WorldClockScreen` (city multi-select). Not a registry domain. |
| Start on boot | `BootLaunch` package set | **Activity** | Bespoke `BootAppsScreen`/`BootAppsActivity`. |
| Remote | `RemotePairing` | **Activity** | `RemotePairActivity`. Reuse `RemoteNavRow`. |
| Device Health / About | `DevicePermissions` | **Activity** | Already ported (System → About). Keep. |
| Sound & input | Touch sounds (`SystemSounds`) + Back gesture (`BackHelper`) | **bespoke + OS link** | Reuse `MoreFeaturesSection`. Back gesture is an OS deep-link (see Table C). |

**Simple (SettingsList one-liner):** Screensaver+Calendar, Digital Clock, Quick buttons,
Device name, (Sleep timer if rendered from domain). These need NO new UI — the renderer
handles them.

**Needs a bespoke sub-screen (already built, just wire a nav entry):** MQTT, Multi-room,
Chime, Sunrise, Welcome, Countdown, World clock, Boot apps, Remote pairing, Wallpaper,
Almanac, Device Health.

---

## Table C — OS link-out rows (for a "System" grouping)

Android/Meta system settings we can only LINK to (never reimplement). Launch via
`context.startActivity(Intent(action))` guarded with `runCatching`. Already-used ones are
noted; the rest are proposed additions worth surfacing on the Portal.

| Label | Intent / action | In use today? |
|---|---|---|
| Wi-Fi | `Settings.ACTION_WIFI_SETTINGS` | propose |
| Bluetooth | `Settings.ACTION_BLUETOOTH_SETTINGS` | propose |
| Display & brightness | `Settings.ACTION_DISPLAY_SETTINGS` | propose |
| Sound & volume | `Settings.ACTION_SOUND_SETTINGS` | propose |
| Date & time | `Settings.ACTION_DATE_SETTINGS` | propose |
| Language & region | `Settings.ACTION_LOCALE_SETTINGS` | propose |
| About / device info | `Settings.ACTION_DEVICE_INFO_SETTINGS` | propose |
| Accessibility (back gesture) | `Settings.ACTION_ACCESSIBILITY_SETTINGS` | YES — `ImmortalSettingsActivity.kt:1058` |
| Modify system settings (touch sounds grant) | `Settings.ACTION_MANAGE_WRITE_SETTINGS` | YES — `SystemSounds.kt:68` |
| App details / permissions | `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` | YES — `AppSwitcherActivity.kt:173` |
| Stock Android Settings (full) | launch pkg `com.android.settings` | YES — folded into "System Settings" tile (`Curation.kt:53,68`) |
| Portal Settings (Meta) | launch pkg `com.facebook.alohaapps.settings` | YES — "Portal Settings" tile (`Curation.kt:54,70`) |
| Charge control (Portal) | launch pkg `com.facebook.aloha.chargecontrol` | in Settings folder (`Curation.kt:55`) |
| Picture mode (Portal TV) | launch pkg `com.android.tv.quicksettings` | in Settings folder (`Curation.kt:59`) |

Note: on the Portal, `com.android.settings` / `com.facebook.alohaapps.settings` are currently
surfaced as app tiles folded into a "Settings" folder (Curation.kt), not as link-out rows
inside Immortal Settings. Surfacing the granular `ACTION_*` intents above as rows would give a
native-feeling System section without launching the whole stock Settings app.
