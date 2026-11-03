# Omitted-weather work — Family Area

Big, Bold and Minimal photo-clock faces do not contain a weather view, but
`FaceRenderer` previously submitted weather work and scheduled its 30-minute
refresh anyway. The refresh boundary now returns when its assembled
`weatherView` is absent, before submitting IO or scheduling a callback.

Weather-enabled faces retain their existing refresh interval, single in-flight
request, shared weather cache and lifecycle token checks. The controlled native
producer is a constructor test seam; production still calls `Weather.fetch`
through a lazy default lambda. No settings, photo sources or firmware policy
are added or changed by the fix.

## Before and after

The native helper runs the actual catalog faces on Family Area's Android 10
hardware, using a generated weather response and UUID-owned preferences.
It constructs detached overlays, drains the worker queue, and checks start,
direct refresh, hide, resume and stop. It never acquires UiAutomation, opens
a Window or invokes the weather network service.

| Case | Before: start calls | After: start calls | Before: calls through resume | After: calls through resume |
| --- | ---: | ---: | ---: | ---: |
| Big | 1 | 0 | 3 | 0 |
| Bold | 1 | 0 | 3 | 0 |
| Minimal | 1 | 0 | 3 | 0 |
| Weather-enabled control | 1 | 1 | 2 | 2 |

The three-call baseline includes an explicit direct refresh between start and
hide/resume. It is a controlled lifecycle sequence, not a network-traffic
measurement. The enabled control adds weather to the actual Minimal catalog
face; it is not represented as another shipped preset.

| Gate | Result |
| --- | --- |
| Original focused JVM regression | 1 failure / 3 tests; enabled and hidden controls passed |
| Original native admission | 18 failures / 163 assertions, zero isolated exceptions |
| Original native work | 1.136 seconds; four completed cases |
| Original app/config restoration | Passed; 27.798-second workflow |
| Debug, optimized and Android test builds + full JVM suite | Passed; 3 minutes 43 seconds |
| Fresh JVM tests | 3,764 / 395 suites; zero failures, errors or skips |
| Final native admission | 163/163 assertions; all original assertion names retained |
| Final native work | 0.938 seconds; four completed cases, zero isolated exceptions |
| Final install/restoration workflow | Passed; 26.529 seconds |

The native checks also verify callback admission, no work after hide/stop,
retained overlay/clock/weather-view identity, generated completion publication,
off-main producer execution, and release of receivers, listeners, requests,
owned preferences and worker executors. JVM coverage separately checks that
an existing in-flight weather request is not duplicated.

## Installation and bounds

The original issue slot is **15:21:56–15:51:56 UTC, November 1**.
Installation and restoration were verified at **15:34:15 UTC**, approximately
**12 minutes 20 seconds** into that slot. The cap was not reset.
The device workflow used **wireless ADB** and targeted Family Area only.

The installed optimized APK SHA-256 is
**4ea7f4e83b9c333fc9554f723723dc1931088725701d032cdd773d2fa0e1e06f**.
All three APK signatures match the previously installed app. Native admission
checks ran against the debug build of the same production renderer source;
the optimized artifact was installed and verified separately.

All 97 settings, source records, face-focus cache bytes, accessibility
configuration, persistent pin, font scale and brightness policy are preserved.
The original test APK and Dream are restored, the system process is unchanged,
instrumentation and performance collector are inactive, and the helper's owned
reports and preferences are removed from the device.

Raw brightness differed in both baseline and final workflows under automatic
brightness. The policy is unchanged; the raw register was not forced to its
earlier reading. This receipt does not establish optical fade comfort.
Credentials, household configuration and rollback APKs remain outside Git.
Living Area was not targeted.

## Evidence and limits

[Native assertions](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-face-weather/checks.json),
[baseline failures](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-face-weather/baseline-checks.json),
[count comparison](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-face-weather/compare.json),
[fresh unit receipt](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-face-weather/unit-gate.json)
and [restoration receipt](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-face-weather/verification.json)
support this scoped delivery.
[The original JVM failure](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-face-weather/unit-baseline.json)
is retained, and
[six independent post-install health checks](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-face-weather/health.json)
confirm the installed artifact, optimized flags, Family identity, unchanged
system process and inactive instrumentation/collector over wireless ADB.

This removes proven unnecessary weather admission. It does not measure saved
HTTP requests, CPU time, panel FPS, whole-platform performance, every face
layout or the physical room-light transition. The full platform goal remains
incomplete, and the user is currently unavailable for the natural-light check.
