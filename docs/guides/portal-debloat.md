# Debloating Meta's services (opt-in)

A provisioned Portal still runs Meta's whole "aloha" service stack: about 33 processes and
roughly 1 GB of memory (PSS) on a Portal+. Hearthpane replaces Meta's home and screensaver,
so some of those apps no longer do anything useful. `provision.sh --debloat` turns a
device-tested list of them off. It is **opt-in** and fully reversible.

The owner's rule (2026-11-22): of Meta's own software, the Portals only need **WhatsApp and
Messenger calling** (the original calling feature, reached from Calls → "Messenger &
WhatsApp"). Anything of Meta's that calling, Hearthpane or Android itself doesn't need may
be turned off.

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

Measured PSS for the first five is from a stock Portal+ on Hearthpane 1.279, 15 minutes
after boot. The second round (the last four rows) was added on 2026-11-22; its PSS comes
from Family on 1.327 after two days of uptime.

| Package | PSS | What it is | Why it's safe to disable |
| --- | --- | --- | --- |
| `com.facebook.alohaapps.superframe` | 45 MB | Meta's "Superframe" ambient photo frame (a dream service) | Hearthpane's photo frame replaces it. Nothing binds to it, and Meta lists it only as the handler for shared-album notifications. The stock dream that `--restore` puts back is `HomeDreamService` in the stock launcher, not this one. |
| `com.facebook.aloha.app.portalfeed` | 41 MB | Meta's home feed and notification cards | It only feeds Meta's home screen. Nothing on the device binds to it. It was also the most active of the five (about 3% CPU at idle). |
| `com.facebook.alohaapps.bugreporter` | 38 MB | Meta's internal bug reporter | Its floating pill is already hidden by provisioning. Nothing binds to it. |
| `com.facebook.alohaapps.personaluser` | 29 MB | Personal Facebook-account features | Nothing on the device binds to it, and only one other process ever mentions it in the logs. |
| `com.facebook.aloha.analytics` | 14 MB | Meta audio and usage analytics (system UID) | Telemetry only. Nothing binds to it. |
| `com.facebook.alohaservices.player2` | 29 MB | Meta's media player: a foreground `PlayerService`, a MediaBrowser for Bluetooth AVRCP, `OPEN_VIDEO`, and 12 scheduled jobs | Calling doesn't use it: nothing binds to it, and its media session is inactive. Hearthpane's Music has its own player. The cost: the Portal may stop acting as a Bluetooth speaker with Meta's now-playing controls. |
| `com.facebook.aloha.app.cameraeditor` | 0 (idle) | Meta's avatar and camera-effects editor | Hearthpane already hides it as dead. It has a `BOOT_COMPLETED` receiver, so turning it off stops it waking at boot. Calls use aiservice's camera, not this app. |
| `com.facebook.aloha.websafety` | 0 (idle) | Meta's web-safety filter for its own browser | Nothing binds to it and Hearthpane doesn't use Meta's browser. |
| `com.facebook.alohaservices.abilities.pages` | 0 (idle) | The Facebook Pages "ability" (a tile in Meta's home) | Not part of calling. Meta's home only logs "Package name is not found" for its missing tile. |

Together the first five are about 167 MB of PSS and 5 processes, plus portalfeed's background
CPU. Round 2 adds player2's process (about 29 MB) and its jobs and alarms. The three idle
apps free no memory at rest; they are off so nothing can start them.

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
| `com.facebook.alohaapps.controlcenter` | Required | Meta's system Control Center (volume, brightness and privacy UI). Persistent. |
| `com.facebook.alohaapps.settings` | Required | Android settings pages, including the OS dream settings. |
| `com.facebook.portal.webview` | Required | The system WebView provider. |
| `com.facebook.alohasdk.bluetooth`, `com.facebook.alohasdk.location` | Required | Bluetooth, and the network location that time and time zone depend on. |
| `com.facebook.aloha.state`, `platformmobileconfig`, `deviceidentity`, `alohasdk.platformsecurestate` | Required | Platform plumbing that the other services bind to. Each is about 5 MB. |
| `com.facebook.aloha.wifidiagnostic`, `com.facebook.aloha.cnshealthmonitor` | Kept (Wi-Fi), protected | Persistent system-UID services. wifidiagnostic handles captive-portal and data-stall logic, and a failure there might only show up days later. Each is about 5 MB, so there is little to gain. |
| `com.facebook.aloha.system.nativelibs`, `com.facebook.portal.sdk`, `com.facebook.alohasdk.settings` | Kept (platform), protected | Shared native libraries, the Portal SDK and the platform settings service that other Meta apps load or bind. They don't run on their own. |
| `com.facebook.aloha.dpc`, `com.facebook.alohaservices.deviceadmin` | Kept (device policy), protected | Device-owner and device-admin plumbing. Turning them off risks the device policy state, for no memory gain. |
| `com.facebook.aloha.fbttsservice`, `com.facebook.aloha.disv2`, `com.facebook.portal.dummybp`, `com.facebook.portal.dummymk` | Kept, idle | They don't run. fbttsservice is a second TTS engine; leaving it on keeps the TTS fallback unchanged. |

The `rro` overlays, `appverifier`, `devicesetup` and the OTA apps are handled by other
provisioning steps and are not on the debloat list. `--undebloat` re-enables the whole
list, so it must never include a package that another step turns off on purpose.
`provisioning/test_debloat_config.py` checks that, keeps the two lists disjoint, and checks
that every calling-critical package is in `DEBLOAT_PROTECTED`.

No Amazon or Alexa package is installed on the stock image (the Alexa client only comes
back through `--alexa`), and no Meta voice-interaction or assistant service is set
(`voice_interaction_service` and `assistant` are empty). The only live microphone client
at idle is Hearthpane's own `VOICE_RECOGNITION` capture.

The biggest remaining Meta processes are the calling stack itself: aiservice (about
170–185 MB), messenger (85–145 MB), whatsapp (50–115 MB), system.services, alohausers,
abilitymanager plus appmanager (about 105 MB together) and alohaapps.settings (up to
100 MB after it has been opened). abilitymanager and appmanager are the next candidates,
but Meta's home builds the Calls handoff screen from them, and it can't be proven safe
without a real call.

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

## Round 2 (2026-11-22, Family, Hearthpane 1.327)

The four new packages were turned off with `provision.sh --debloat`, then the Portal was
rebooted once.

| Metric | Before (5 off, 2 days up) | After (9 off, 15 min after boot) |
| --- | --- | --- |
| Meta processes | 27 | 27 (player2 gone; contacts, started by the handoff test, now resident) |
| player2 | 29 MB PSS, foreground service, 12 jobs, 2 alarms | not running |
| Meta total PSS | 934 MB | 1045 MB |
| MemAvailable | 905 MB | 848 MB |

The whole-device numbers are **not a clean A/B**. The "before" figure comes from two days
of uptime, with most of Meta's memory swapped out. The "after" figure was taken right after
the Calls handoff test opened Meta's home and Contacts, and those two alone held about
170 MB. The real saving is player2's process (about 29 MB), its jobs and alarms, and one
less boot receiver (cameraeditor). Most of Meta's remaining memory is the calling stack.

Checked after the reboot, with no calls placed:

- Hearthpane Home resumes, stays the default home, and its photo dream starts when idle.
- Calls → "Messenger & WhatsApp" opens Meta's home with its people row. Contacts →
  "See all" opens `AlohaContactsMainActivity`, and both the Messenger and the WhatsApp tabs
  load. No crashes or ANRs. Meta's home logs "Package name is not found" for the removed
  tiles. Contacts logs `WA_CONTACT_RULE_BROKEN` and still loads the WhatsApp repo; there is
  no earlier log to compare that line with.
- Messenger and WhatsApp have no launcher activity of their own (`monkey` finds nothing).
  Their processes keep running in the background for incoming calls.
- Camera: aiservice holds the physical camera and runs `CameraService`, and the virtual
  camera service runs. Camera 0 wasn't opened in this round.
- Presence: `aloha.UserPresenceManager` keeps logging availability.
- Microphone: Hearthpane's `VOICE_RECOGNITION` capture is live and not silenced. Jarvis's
  `AssistantService` runs in the foreground.
- The key-event, touch, `BarWatchService` and `InstallConfirmService` accessibility
  services stay bound.

Still to check by hand: a real incoming and outgoing call on both WhatsApp and Messenger
(ringing, camera and audio), and the Portal as a Bluetooth speaker. If calling breaks,
run `--undebloat` and move the suspect package into `DEBLOAT_PROTECTED`.

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
