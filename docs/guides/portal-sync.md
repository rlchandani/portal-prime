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
| Meta debloat (`DEBLOAT_PACKAGES`) | **Opt-in** (`--debloat`) | Disables five unused Meta background apps and frees about 120 MB of RAM. Per device: `--status` shows whether it is applied. See [Debloating Meta services](portal-debloat.md). |

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
| Jarvis: `PROJECT_MEDIA` app-op (**new**, `COMPANION_PROJECTION_PKGS`) | "What's on my screen?" over another app: Jarvis takes one screenshot on an explicit ask without Meta's capture dialog (its own "looking" chip and thumbnail show instead). Never on Home, the photo frame, calls or while the camera is in use. By hand: `adb shell appops set com.portal.assistant PROJECT_MEDIA allow`. |

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

## Household settings sync

Portals that share the household key keep their household settings the same automatically.
A change on one Portal reaches the others within a few seconds, and a Portal that was off or
offline catches up when it comes back. Settings that belong to a room never leave it.

### What syncs

Every setting in Hearthpane's registry declares a `scope` in its definition
(`app/src/main/java/com/immortal/launcher/settings/`). `SettingScopeTest` fails the build if a
setting has no scope, if a credential is not `DEVICE_SECRET`, or if a protected key is marked
household.

| Scope | Settings |
| --- | --- |
| `HOUSEHOLD` (synced) | Screensaver mode, style, fit, interval, shuffle, videos, now playing, presence and idle sleep, overnight, night clock (tone, brightness, dark-room clock), sleep timer, soundscape, ambient dashboard, welcome; calendar widget look (on, range, size, side); the photo-frame clock face, its size and on/off; the photo source when it is the built-in feed or a shared album link; digital clock face and style; chime, spoken time, golden hour, chime volume, quiet hours; music quiet cap and follow-me; Control Center policies (kids restrictions, confirmations, banners, warnings, look, PIN length, exit delay); Calls policies (who can call, ringtone, ring volume, quiet mode, quality, groups, messages, phones); theme, advanced settings, weather unit, tile size, clock format, mini player, status bar; quick buttons; home dashboard, almanac packs, dinner, sharing, household list, briefing, doorbell, multi-room on/off and its Snapcast/Music Assistant host and port. |
| `DEVICE` (this Portal only) | Room name; drop-ins on this Portal and the per-room drop-in grid; Control Center "This Portal shows" (Kids mode) and Shared room; the guest card; the sunrise alarm (it wakes one room); MQTT connection; the Music Assistant user and player id; the calendar feed link (it can carry a private token); this Portal's photo folder and Immich/NAS/WebDAV logins; the screensaver cache, battery saver, dark-room light threshold and dismiss app; the startup app. |
| `DEVICE_SECRET` (never synced, never sent) | Music Assistant password and API token, MQTT password. |

Rotation, brightness, volume levels, Grandma mode and its auto-answer list, Who's there
recognition, dev and debug flags and the household key live outside the registry, so sync can't
reach them. Hearthpane-internal levels (chime, ring and soundscape volume) are household
policies; Android's own volume and brightness stay per device.

### How it works

- **Changes.** A change made on this Portal, on the phone remote or by fleet is noticed after the
  registry write and sent at once as a signed bus message
  `setting_changed {key, value, version, deviceId, ts}`. Each key has a Lamport version
  (counter, then device id). The highest version wins; wall clocks never decide.
- **Catch-up.** Every Portal gossips `settings_rev {digest, address, port, joining}` every
  60 seconds, when its network comes back and after any change. A Portal whose digest differs
  POSTs its versions to the sender's `/remote/settingspeer`; both sides merge, so one exchange
  brings both up to date. The request carries a fresh household-signed header that pins the body
  and is replay-guarded. The reply is signed against that request's nonce.
- **Validation.** A peer value is applied only if its key is a household setting and the value
  matches that setting's schema: the right type, the integer range, one of the enum's options
  exactly, and strings at most 2,048 characters with no control characters. Unknown keys are
  refused. A bus change must come from the Portal that wrote it. Batches hold at most
  512 records and bodies at most 256 KB. A drop-in, Kids-mode, Shared-room, room-name, MQTT or
  credential key can't be household: the catalog refuses to build.
- **Live apply.** Values are written through the same registry apply as the on-device page, so
  each domain's side effects run as usual: screensaver reaffirm and night-clock refresh, chime
  rescheduling, Control Center reload, call announce, and the theme's live listener.
- **Joining.** Untouched defaults are never sent. When a Portal first runs this version it joins
  the household:
  - If the household already has settings, Settings › About & Health › Sync offers a one-time
    **Adopt settings from …**. Adopting takes the household's values. Values this Portal changed
    that the household never set are shared.
  - If this Portal changed something the household also set, **Keep this Portal's settings** is
    offered too. Either way, a fresh Portal with defaults never overwrites the household.
  - If no Portal has settings yet, the first one (the lower device id when two meet) founds them
    from its own changes.

Settings pages that hold household rows say **Synced across your Portals**. Rows that stay
on the Portal say **This Portal only**. **About & Health › Sync** shows the status, the last sync
time and each Portal last seen.

For testing, the fleet action `{"action":"settings_sync","op":"status|pause|resume|adopt|keep|rejoin"}`
reports the digest and peers. It can also pause sync on one Portal (it neither sends nor accepts),
or resolve the join choice.

### Limits

- Catch-up uses the peer HTTP route, which runs only while the Fleet agent or phone pairing is
  on (the household list and chores use the same route). Live `setting_changed` messages work
  without it.
- The clock-face picker reports its changes at once. The photo-source screens write outside the
  registry, so their changes are noticed by the 60-second check.
- Settings with no registry spec don't sync. That includes the welcome overlay's colours and
  greetings.
- Jarvis has no settings in Hearthpane's registry yet, so there is nothing of Jarvis to sync.
