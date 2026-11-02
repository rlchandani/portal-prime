# Family automatic install confirmation — 2026-11-02

The bounded unit started at **14:45 UTC**, with a **15:15 UTC** deadline.
Family's current optimized **1.77/code71** now automatically confirms a real,
valid Fleet installation. This unit adds workstation QA tooling; it does not
create a new Portal release or change installer product code.

## Cause and recovery

Family's secure accessibility list included both Immortal services, with the
global flag enabled. Android nevertheless listed them under **Binding services**;
the exact `InstallConfirmService` ActivityManager service record was absent.
The agent's `autoConfirm: true` metadata reflects enabled configuration and
does not establish that the service is connected.

Device logs record the service being stopped during earlier instrumentation
teardown. Android 10 starts and finishes instrumentation by internally stopping
its target package. Avoiding UiAutomation alone therefore does not preserve the
target's accessibility-service connections. This is the supported diagnosis for
the observed Family failure, not a claim about every Portal firmware.

One controlled reconnect temporarily removed only Immortal's existing entries
from the secure enabled list. The stale binding did not clear. The original
list was restored exactly in `finally`; the global flag stayed unchanged.
Repeating that operation was not used as a fix.

Reinstalling the **exact same optimized 1.77 APK** over wireless ADB cleared the
stale state. Both Immortal services then appeared bound, with no owned component
left in Binding services. Android's package-replacement handler clears matching
binding entries and reconciles accessibility services. The APK bytes, enabled
list, global flag and complete saved settings remained unchanged. Package
installation timestamps/source metadata are outside that preservation claim.

## Actual acceptance

After recovery, the existing original `.debug.test` APK was reinstalled through
the Fleet API. The request returned a valid installed result, and a fresh
`ImmortalFleet` log explicitly recorded **auto-confirmed install via "Install"**.
No manual installer tap was used.

The new smoke tool passed on the same real Family path, then passed again after
independent review corrections. Its final run took **14,012.595 ms**, including
transfer, installation and complete before/after APK readbacks. This is test
wall time, not a UI or installer latency benchmark.

Final checks established:

- ADB hardware identity matched the selected Fleet registry room.
- The exact installer service had a published binder, `received=true`,
  `hasBound=true`, and a live connection; owned Binding services were empty.
- Preflight, Fleet deployment and postflight passed, with one fresh automatic
  Install log event during the final run.
- Full app APK SHA-256 stayed
  `5df871735ada5aae37375a6cd6129aa9fcb13329c982441a0cc90db2fd4f383e`.
- Full original test APK SHA-256 stayed
  `b318d39ff76ce0ee59fce2bff4164745da6da28e48d4ed5ffcfa6dcc2635c136`.
- Complete registry profile and screensaver configuration equalled the preceding
  unit's original baseline. The secure list and global flag also matched.
- Original Chrome task 27174 remained listed. Foreground, process retention,
  account sign-in and music continuity are not inferred from that observation.

## Regression protection

`scripts/portal_installer_smoke.py` permits only a byte-identical reinstall of an
already-installed `com.immortal.launcher.debug.test` fixture. It rejects mismatched
local/installed hashes, missing/split packages, mismatched ADB/Fleet identities,
stale bindings and postflight drift. ADB operations are read-only; installation
uses the existing room helper. It does not instrument, force-stop, write secure
settings, launch UI, or replace the production app.

The tool captures child output privately and prints selected JSON without
credentials or device dumps. A timeout kills the local helper's process group.
An already-submitted Android install may outlive that local group, so timeout
always fails with remote completion **unknown** and an **observed-only**
postflight; it never claims settled success.

Nineteen focused and all **180 Portal Python regression tests** passed, including
a real local descendant timeout test. An initial run caught a malformed null-binder
fixture, which was corrected. An unrelated existing timeout fixture reported a
transient `Operation not permitted`; its targeted and full reruns passed. Independent review identified identity, timeout and binding
false-positive gaps; all three were corrected and the follow-up review found
no material residual issue in this scope. The normal CI Python discovery
pattern includes the new tests without a workflow change.

Example, with private paths and hashes supplied locally:

```sh
python3 scripts/portal_installer_smoke.py \
  --adb-serial '<current-adb-transport>' --device '<exact-registered-room>' \
  --cli '<fleetctl>' --registry '<private-registry>' \
  --fixture-apk '<original-installed-test.apk>' \
  --expected-fixture-sha256 '<original-test-sha256>' \
  --expected-app-sha256 '<current-app-sha256>'
```

## Limits and sources

This proves the normal valid fixture path on Family's API29 firmware after
recovery. It does not prove valid self-update, two concurrent valid installations,
real remote timeout recovery, reboot persistence, API28, Living native behavior,
physical phone or general performance. Both rooms retain 1.77; Living received
no operation in this unit. No Meta preferred-home, firmware, root or permission
configuration was changed.

Android 10 source explains
[accessibility binding state](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r1/services/accessibility/java/com/android/server/accessibility/AccessibilityServiceConnection.java#89),
[binding reconciliation](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r1/services/accessibility/java/com/android/server/accessibility/AccessibilityManagerService.java#1629),
[package replacement](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r1/services/accessibility/java/com/android/server/accessibility/AccessibilityManagerService.java#399),
and
[instrumentation start/finish](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r1/services/core/java/com/android/server/am/ActivityManagerService.java#15777).
The documented
[UiAutomation flag](https://developer.android.com/reference/android/app/UiAutomation#FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
addresses accessibility suppression, not target-package lifecycle.

Selected public evidence:
`mocks/portal-widget-gallery-20261102/evidence/installer-confirm-readiness-verification.json`.
Private payloads, APK backups, settings values and logs remain outside the repo.
