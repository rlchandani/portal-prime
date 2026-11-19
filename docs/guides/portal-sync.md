# Keeping Portals in sync

Every Portal in a household should share the same setup. Some of that setup comes from
provisioning, and some is set in Hearthpane's own settings. This page lists each setting,
why it is set, and which settings are meant to differ between devices.

To check a connected Portal without changing anything, run
`./provision.sh --status`. The **Household settings** block prints each value next to the
value it should have.

## Android settings applied by provisioning

| Setting | Value | Why |
| --- | --- | --- |
| Home app | Hearthpane `HomeActivity` | Replaces Meta's launcher. |
| Screensaver (`screensaver_components`, `screensaver_default_component`) | Hearthpane `PhotoDreamService` | Replaces Meta's screensaver (SuperFrame/HomeDream). This is what disables the Meta OS screensaver. |
| `screensaver_enabled`, `screensaver_activate_on_sleep`, `screensaver_activate_on_dock` | `1` | Shows the photo frame whenever the Portal is idle or docked. |
| `policy_control` | `immersive.status=*` | Hides the status bar in all apps. |
| `hidden_api_policy`, `hidden_api_policy_p_apps`, `hidden_api_policy_pre_p_apps` | `1` | Lets Hearthpane call internal Android APIs. |
| `development_settings_enabled` | `1` | Keeps ADB tooling and the debug menu available. |
| `com.facebook.appverifier` disabled, `package_verifier_enable=0` | | Allows apps to be installed on the device. |
| `com.facebook.aloha.alohaotasetup`, `com.facebook.aloha.otaui` disabled | | Blocks Meta OS updates, so an update can't undo the setup. |
| `com.facebook.aloha.system.services` `SYSTEM_ALERT_WINDOW=ignore` | | Hides Meta's floating bug-report pill. |
| Meta presence detector (`com.facebook.alohaservices.presence`) | Left **enabled** | Lets the device tell an empty room from an occupied one. Disabling it is opt-in (`DISABLE_PRESENCE`). |
| Gen-1 installer overlays | Disabled on Android 9 only | Fixes the white-on-white installer dialog. |

## Hearthpane permissions applied by provisioning

| Grant | Why |
| --- | --- |
| `WRITE_SECURE_SETTINGS`, storage, `READ_LOGS` | Lets Hearthpane restore its own screensaver settings, use the APK browser and storage, and serve fleet logs. |
| `SYSTEM_ALERT_WINDOW`, `REQUEST_INSTALL_PACKAGES`, `GET_USAGE_STATS` app ops | Covers the frame relaunch, the App Store and self-update, and the recent-apps switcher. |
| `WRITE_SETTINGS` app op (**new**) | Needed for the brightness, auto-brightness and touch-sound controls. These were granted by hand on the Portal+ units. |
| `RECORD_AUDIO`, `CAMERA` (**new**) | Needed for Calls, drop-ins, video messages and the doorbell. Granted by hand on the Portal+ units. |
| Device admin (`AdminReceiver`) | Lets Hearthpane turn the screen off. |
| Media notification listener; accessibility services (`BarWatchService`, `InstallConfirmService`) | Provide now-playing, Calls/remote access and install confirmation. |
| Jarvis (`com.portal.assistant`) and Portal Calendar: `RECORD_AUDIO`; Jarvis: `SYSTEM_ALERT_WINDOW` (**new**, `COMPANION_*` in `config.env`) | Voice input, and Jarvis's floating button. Applied only when the app is installed. |

## Household key

The household key signs Portal-to-Portal traffic: calls, drop-ins, lists, chores and music
hand-off. Every Portal in the household needs the same key, which you can confirm by
comparing fingerprints. The Toolkit phone app installs it over `/remote/household` during
a pairing done in person. Provisioning can't set it, and the key must never be committed or
printed.

## Hearthpane settings that should match

These settings live in Hearthpane's own settings registry (`/remote/settings`). Set them the
same way on every Portal. Today that means either Settings on each device, or the
Toolkit/fleet settings route.

- **Screensaver**: mode, fit, shuffle, interval, videos, now-playing card, welcome greeting,
  ambient dashboard, presence and idle sleep, overnight and night clock, sleep timer,
  soundscape.
- **Clock**: digital clock face, style, font, glow, colour, size, date and seconds.
- **Chime and quiet hours**: hourly chime, spoken time, golden hour, volume, quiet hours.
- **Calls**: who can call, ringtone, ring volume, quiet mode, quality, drop-ins, groups,
  messages, phones.
- **Control Center**: Portal mode, kid restrictions, confirmations, banners, theme, PIN.
- **Other**: Home dashboard URL, calendar packs, dinner, share, household list, music quiet
  cap, follow-me, briefing, doorbell, settings theme and advanced settings, weather unit,
  tile size, clock format.
- **Photo source**: every Portal should use the same source. Device-local folders hold
  different files on each Portal, so copy the photos or use a shared source (Immich, SMB,
  WebDAV, an album URL).

## Intentionally per-device

| Setting | Why it differs |
| --- | --- |
| Room name (`fleet.name`), Bluetooth name | Identifies the room. |
| Auto-rotate (`accelerometer_rotation`), rotation | The Mini must keep auto-rotate **on**. On the Portal+ it doesn't matter. |
| Display size, density, brightness and brightness mode, volume levels | Depend on the hardware and on the room's light and noise. |
| Camera and sensors, `com.facebook.aloha.app.storytime` (Mini only) | Hardware-specific. |
| Control Center "Shared room" | On only for rooms used by guests. |
| Grandma mode, per-room drop-in rules | Set for each room. |
| Guest card (Wi-Fi, house notes, contact) and the location grant it needs for Wi-Fi scans | Only the room that shows the guest card needs it. |
| Meta `sleep_timeout` | Each device keeps its Meta default unless the owner decides otherwise. |

## Not synced automatically (yet)

The household key is shared, but Hearthpane settings are not. Today each Portal keeps its own
copy, so a change made on one Portal doesn't reach the others. A HouseholdBus
`settings_sync` message could fix this. It would carry the household-scoped domains listed
above, signed with the household key, and would never include secrets or per-device keys.
