# Home timer editor delivery

**Delivered locally to Family Area and Living Area: 1.67 / code 61.**
Branch: `codex/portal-timer-editor-20261102`, from `d0709c6e`.
The issue slot was 07:23:23–07:53:23 UTC, including restoration.
No GitHub authentication, publication, new service, storage migration or uninstall.

## Reproduction and change

Family's accepted 1.66 Home editor hosted the Tools kitchen-timer overlay.
Starting **5m** left the Home widget **Ready**: shared countdown `running=false`,
kitchen collection count `1`. The native ownership assertion failed.

Home's Open and Customize → Settings now open the existing Home dialog shell
with controls for `TimerStore`, the same countdown read by the widget and
screensaver. Presets, pause/resume/reset, custom duration and Stop retain the
existing persistence and exact alarm. Tools retains its separate `TimerConfig`
collection, receivers and UI. No API/schema or companion changes were needed.

The editor refreshes through the existing store listener, removes it on disposal,
and ticks once per second while running and its page is active. This is source
evidence about scheduling; it is not an FPS or responsiveness benchmark.

## Gates and acceptance

| Gate / target | Result |
| --- | --- |
| Full JVM | **3,796 tests / 398 suites**, zero failures, errors or skips |
| Debug + optimized APKs | Terminal build passed in **8m 14s**, including R8 and performance vital lint |
| Metadata/signing | Gradle/manifest/JSON agree on 1.67/61; preserved signer; optimized APK is not debuggable |
| Independent source review | [Review](portal-home-timer-review-2026-11-02.md): no concrete production regressions identified; bounded source review |
| Family native owner | Editor 5m started shared countdown, kitchen count **0**; original failure passed |
| Family native controls | Pause → Back → widget paused → reopen → Resume → Reset passed |
| Family custom/alarm | Custom **0:10** returned to parent; exact receiver produced “Time's up”/Stop; Stop reset ringing and countdown |
| Family dismissal | Custom picker Cancel returned to Timer; Close returned to widget; native Back passed |
| Family edit shortcut | Customize → timer Settings → 5m used the shared owner; Reset/Close passed |
| Family visual | Actual dialog-only capture reviewed: header, presets, caption and custom action fit without overlap |
| Living delivery | One install dispatch; uncertain acknowledgement; observer reached ready after three observations; independent exact installed-APK readback passed |

Native QA used a temporarily added timer widget after backing up layout/settings.
The initial driver attempted swipes while Android reported **Dozing / display
OFF**; waking the screen allowed testing. Private driver corrections also handled
flat Compose stepper semantics and the existing Remove confirmation. These were
driver/precondition issues, not additional production fixes or passing attempts.

## Artifact and restoration

- Optimized APK: **25,860,666 bytes**.
- SHA-256: `80895ce251f65b9f7a9018d0be974fd1c2c222cf4160539f970c1c9a93400262`.
- Signer SHA-256: `4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6`.
- Both rooms' installed bytes/hash/version/identity matched that artifact.
- Family's original **nine widget entries**, order and spans matched the backup
  exactly after removing only the temporary timer through its confirmation.
- Home returned to **1 of 2**; all original screensaver fields matched the backup.
- Both timer systems were restored to their original logical idle/empty state.
  The originally absent single-timer preference file now contains default idle
  values; no byte-for-byte absence restoration is claimed.
- Accepted 1.66 APK and previous branches were preserved privately.
- Private build/deployment/UI/state receipts remain under
  `/private/tmp/portal-timer-editor-20261102`; tokens and full photo captures are
  not committed. The public image is cropped to the dialog only.

![Installed Home timer editor](https://raw.githubusercontent.com/starbrightlab/immortal/main/mocks/portal-widget-gallery-20261102/roadmap/images/timer-editor-native.png)

Limits: no physical audible-sound judgement, background/screen-off alarm test,
screensaver timer rendering test, enlarged-text/remote-focus matrix, optical
frame-rate measurement or repeated Living interaction run. Existing alarm and
screensaver code were unchanged. The native settings maintenance is included in
this APK, but its settings UI did not receive fresh hardware acceptance.

Next actionable unit: shared disabled-parent settings visibility policy.
Previously exhausted performance/night/handoff issues remain parked.
