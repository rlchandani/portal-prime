# Debloating Meta's services (opt-in)

A provisioned Portal still runs Meta's whole "aloha" service stack: about 33 processes and
roughly 1 GB of memory (PSS) on a Portal+. Hearthpane replaces Meta's home and screensaver,
so some of those apps no longer do anything useful. `provision.sh --debloat` turns a short,
device-tested list of them off. It is **opt-in** and fully reversible.

```bash
./provision.sh --debloat      # disable the apps in DEBLOAT_PACKAGES (config.env)
./provision.sh --undebloat    # turn them back on (--restore does this too)
./provision.sh --status       # shows "Meta debloat: N of M listed apps disabled"
```

On Windows, use `provision.ps1 -Debloat` and `provision.ps1 -Undebloat`. Reboot the Portal
afterwards so it starts cleanly.

## How it works

The Portal is locked and not rooted, so system apps can't be uninstalled. Instead, the
script runs `pm disable-user --user 0 <package>` as the adb shell user. Android stops the
app at once, and it stays off across reboots until `pm enable` turns it back on. The
script never runs `pm uninstall` or `pm clear`, and it never force-stops anything.
`DEBLOAT_PROTECTED` in `config.env` is a hard guard: the script refuses to disable any
package on that list, even if someone adds it to `DEBLOAT_PACKAGES`.

## What gets disabled, and why

Measured PSS is from a stock Portal+ on Hearthpane 1.279, 15 minutes after boot.

| Package | PSS | What it is | Why it's safe to disable |
| --- | --- | --- | --- |
| `com.facebook.alohaapps.superframe` | 45 MB | Meta's "Superframe" ambient photo frame (a dream service) | Hearthpane's photo frame replaces it. Nothing binds to it, and Meta lists it only as the handler for shared-album notifications. The stock dream that `--restore` puts back is `HomeDreamService` in the stock launcher, not this one. |
| `com.facebook.aloha.app.portalfeed` | 41 MB | Meta's home feed and notification cards | It only feeds Meta's home screen. Nothing on the device binds to it. It was also the most active of the five (about 3% CPU at idle). |
| `com.facebook.alohaapps.bugreporter` | 38 MB | Meta's internal bug reporter | Its floating pill is already hidden by provisioning. Nothing binds to it. |
| `com.facebook.alohaapps.personaluser` | 29 MB | Personal Facebook-account features | Nothing on the device binds to it, and only one other process ever mentions it in the logs. |
| `com.facebook.aloha.analytics` | 14 MB | Meta audio and usage analytics (system UID) | Telemetry only. Nothing binds to it. |

Together that is about 167 MB of PSS and 5 processes, plus portalfeed's background CPU.

## What stays on, and why

Each Meta package was classified from three sources: who binds to its services or
providers (`dumpsys activity services` / `providers`), what it logs, and what Hearthpane
depends on in its own code.

| Package | Classification | Why it stays |
| --- | --- | --- |
| `com.facebook.portal.aiservice` | Required | Owns camera 0 and runs the AI framing stage (Scaas). Calling, Who's there and presence all go through it. |
| `com.facebook.alohasdk.virtualcameramanager` | Required | The virtual camera that apps open as camera 0. |
| `com.facebook.alohaservices.presence` | Required | Meta presence. It also holds the `TouchEventAccessibilityService`. |
| `com.facebook.alohaservices.alohausers` | Required | Hosts `UserPresenceService`; Hearthpane reads presence from its `aloha.UserPresenceManager` log lines. Almost every Meta app binds `AlohaUsersService` / `UserIdentityService`. |
| `com.facebook.aloha.system.device` | Required | `KeyEventAccessibilityService` (the volume keys) and Meta's push channel. |
| `com.facebook.aloha.system.services` | Required | Meta's app foundation service. Its bug-report pill is hidden separately. |
| `com.facebook.alohaapps.launcher` | Required | Meta's stock home. The Calls handoff (the "Messenger & WhatsApp" button) opens it, and `--restore` falls back to it. |
| `com.facebook.aloha.app.messenger`, `com.facebook.aloha.app.whatsapp`, `com.facebook.alohaapps.contacts` | Kept (Calls handoff) | Meta's calling apps behind the handoff. Disabling them would remove that option, so they stay. |
| `com.facebook.alohaservices.abilitymanager`, `com.facebook.alohaappmanager` | Kept (Calls handoff) | The stock home reads its app tiles from the abilitymanager provider, and abilitymanager reads from appmanager. |
| `com.facebook.alohasdk.pushnotification` | Kept (Calls handoff) | Push delivery for incoming calls. |
| `com.facebook.aloha.app.ttsservice` | Required | The only active text-to-speech engine. Hearthpane's spoken features (for example the bedtime story) use it. |
| `com.facebook.alohaservices.player2` | Unknown, kept | The media browser for Bluetooth AVRCP and A2DP sink (the Portal as a Bluetooth speaker). That path couldn't be tested here. |
| `com.facebook.alohaapps.controlcenter` | Required | Meta's system Control Center (volume, brightness and privacy UI). Persistent. |
| `com.facebook.alohaapps.settings` | Required | Android settings pages, including the OS dream settings. |
| `com.facebook.portal.webview` | Required | The system WebView provider. |
| `com.facebook.alohasdk.bluetooth`, `com.facebook.alohasdk.location` | Required | Bluetooth, and the network location that time and time zone depend on. |
| `com.facebook.aloha.state`, `platformmobileconfig`, `deviceidentity`, `alohasdk.platformsecurestate` | Required | Platform plumbing that the other services bind to. Each is about 5 MB. |
| `com.facebook.aloha.wifidiagnostic`, `com.facebook.aloha.cnshealthmonitor` | Unknown, kept | Persistent system-UID services. wifidiagnostic handles captive-portal and data-stall logic, and a failure there might only show up days later. Each is about 5 MB, so there is little to gain. |

Packages that don't run in the background (`cameraeditor`, `websafety`, `alohainstaller`,
`dpc`, the `rro` overlays and so on) are left alone. Disabling them frees nothing.

## Measured gain

The measurements come from one Portal+ (Android 10) running official Hearthpane 1.279,
taken 15 minutes after a reboot while idle on Home. The stock and debloated runs used the
same build.

| Metric | Stock | Debloated | Change |
| --- | --- | --- | --- |
| Meta total PSS | 1162 MB (34 processes) | 1044 MB (29 processes) | −118 MB |
| MemAvailable | 823 MB | 950 MB | +127 MB |
| Swap used | 554 MB | 375 MB | −179 MB |
| system_server PSS | 68 MB | 74 MB | noise |
| CPU idle (30 s on Home) | 64.4% | 66.5% | +2 points |
| Meta alarms (since boot) | 21 wakeups | 20 wakeups | −1 |
| Boot to Home | 24.4 s | 25.3 s | noise (runs ranged 24.2–25.9 s) |
| Home swipes, frame p50 / p90 | 10.9 / 25.1 ms | 11.8 / 26.6 ms | noise |
| Settings scroll, frame p50 / p90 | 9.4 / 15.5 ms | 11.3 / 16.1 ms | noise |
| Control Center view switch, frame p50 | 18.4 ms | 14.8 ms | noise |

A first round on 1.278 (debloat batch 1 only) showed the same memory effect: Meta PSS fell
from 1053 to 939 MB.

**The gain is memory: about 120 MB more available RAM and much less swap.** CPU barely
moves. On an idle Portal+, roughly 2 of its 8 cores go to Meta's camera presence loop
(`aiservice` about 110%, the camera HAL about 95%, `cameraserver` about 13%). Presence,
Calling and Who's there need that loop, so it stays. Frame times and boot time didn't
change measurably. Each figure is one run per state, and frame times vary from run to run
by more than any difference here.

## What was checked

Each step was followed by a reboot and the same checklist, and every check passed. No
screenshots or camera frames were captured.

- Hearthpane is the default home and resumes on Home. The photo-frame dream starts and
  exits back to Home. The OS dream settings (`screensaver_*`, screen timeout) are
  unchanged.
- Camera 0: a Who's there glance opens it (about 140–200 ms) and runs aiservice's virtual
  camera session. The privacy shutter state is still readable (`PRIVACY_OFF`, lens
  uncovered).
- Meta presence: the presence, alohausers, aiservice and virtual camera processes run,
  and `aloha.UserPresenceManager` still logs availability.
- Microphones: the built-in mic arrays are present, and opening Jarvis starts a live
  `VOICE_RECOGNITION` capture. The volume key device and `KeyEventAccessibilityService`
  are bound.
- Wi-Fi, internet access, Bluetooth, time and time zone work. Hearthpane's OTA check
  returns `up_to_date`, and the fleet agent answers.
- The Home Assistant socket is connected and the Control Center registry loads. The
  doorbell listener reports the same state as before.
- Jarvis opens. `com.portal.calendar` and PortalSnap stay enabled.
- System UI runs, the status bar stays hidden, and the accessibility services (Hearthpane's
  `BarWatchService` and `InstallConfirmService`, plus Meta's key-event and touch services)
  stay bound.
- The Calls handoff ("Messenger & WhatsApp" in Calls) opens Meta's home with no dream in
  front.
- The disabled packages stay disabled and don't run after reboots.

Not covered: the presence path with someone in the room, and the Portal acting as a
Bluetooth speaker. The tests ran at night with nobody present. `player2` (the Bluetooth
speaker path) is not disabled for this reason.

## Gotchas

- **Wait before rebooting.** Android saves package state lazily, about 10 s after the
  change. An immediate `adb reboot` after `pm disable-user` silently brought all three
  first-batch apps back. The script waits 15 s before it reports done.
- Disabling stops the app immediately, but a reboot gives Meta's other services a clean
  start without it.
- Meta's app manager re-enables individual components inside other Meta apps at runtime.
  In testing it never re-enabled a whole disabled package. `--status` shows how many
  listed apps are still disabled.
