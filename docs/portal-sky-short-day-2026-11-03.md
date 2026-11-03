# Sky palette on short days — 2026-11-03

SkyColors used fixed daytime phase distances that overlapped on short winter
days. With sunrise at 10:00 and sunset at 13:00, solar noon missed the existing
MIDDAY palette and the largest one-minute RGB channel jump was 0.49411765.
For a 10:00–11:00 day, noon also missed MIDDAY, the sunset-plus40-minute TWILIGHT
anchor was shadowed, and the largest jump was 0.7764706. Both jumps occurred
at 12:00. ApprovedHomeSky calls this pure gradient by minute for the selected
Sky home background.

The existing daytime distances now shrink proportionally only for positive
daylight shorter than eight hours. Fractional boundaries keep the short-day
anchors ordered. Existing seven named color pairs, Compose interpolation,
dawn-start 40 minutes before sunrise, dusk-end 40 minutes after sunset and
twilight-end 80 minutes after sunset remain. At eight hours or longer, the
existing distances and integer midpoint remain. No palette redesign, settings,
provider, weather cache, cloud, star-field or animation change is included.

Nine frozen actual public-function methods reproduce **five AssertionErrors
and four passing controls**, with zero errors or skips, before production
changes. Independent literal ARGB noon and twilight expectations and whole-day
adjacent-minute channel checks reproduce the defect. Existing eight-hour and
fourteen-hour phase palettes, exterior NIGHT and opaque finite sRGB checks
remain. The 0.10 adjacent-minute bound is a loose generated-case acceptance
threshold, not a formula test or a universal bound for every solar schedule.

All nine methods pass unchanged. Full gates pass **4,273 JVM tests /
443 suites**, zero failures, errors or skips, plus debug and
optimized APKs, R8 and vital lint in **2m 14s**.

Actual debug APK execution on Family Android 10 improves **7/12 to 12/12**:
five corrected checks and seven retained checks, comprising six product checks
and one classloader harness control. Each variant makes **5,788 actual
gradientFor calls, 11,576 actual Color unboxes, 5,760 whole-day samples and 28
literal anchor calls**. Generated one-hour and three-hour continuity checks
include midnight; literal eight-hour and fourteen-hour anchors remain.
The candidate's largest channel jumps are 0.082352936 and0.031372547 respectively.
The native fixture uses actual Compose channel getters and color-space/alpha
observations. No Context, household preferences, provider, service, network or
process timezone/Locale mutation occurs.

Two accepted native parents retire and owned scratch is removed, with zero
cleanup faults and zero excluded native executions. One initial compiler attempt
fails because a private JSON helper omitted its checked JSONException
declaration; the one-line fixture correction and failed log remain. The retry
compiles successfully with **three Java 8 obsolescence warnings and no deprecation
note**. Production, frozen test and APK bytes do not change for that correction.
Only normal cleanup is observed; external runner failure and timeout cleanup
remain unverified.

Native gradient acceptance is **debug only**. The optimized APK retains the
gradient as public static c(III):ec2. Its singleton INSTANCE, Pair getters and
Color unbox methods needed by this exact debug fixture are unavailable, so
the fixture admits zero optimized gradient calls; no keep rules are added.
API 28, polar or midnight-wrapping schedules,
invalid provider chronology, live solar-provider results and physical Sky UI
rendering remain unverified. Current equal or reversed minute inputs retain
their existing behavior; this correction covers ordered non-wrapping short days.

Exact signed optimized **1.118/code112** is installed and fully hashed in Family
Area and Living Area. SHA-256:
`1aa81f61457721a3efafef0a1e3848a8d117636492ebc605f1c9869718cd6236`
(25,862,702 bytes), package `com.immortal.launcher.debug`; signer matches
the retained 1.117 rollback. Both helpers exit zero and request the screensaver.

Complete captured screensaver, settings/profile, layout and source maps match
preflight. **Ten named preference states per room match: Family has ten existing
byte-identical files; Living has seven byte-identical files and three unchanged
absences.** Room names and Dev Mode remain. Strict postflight passes without an
exception, household preference edit or restore. Family font, accessibility,
original Chrome task, resumed frame, awake display and full installed SHA remain.

The coordinator inspected Family's actual current display: Large tan 3:09 digital night clock and Tuesday November 3 date on black; accessibility button visible. Resumed frame and awake display verified separately. No Sky palette, provider, weather or photos visual acceptance inferred.
This observation does not establish physical Sky background acceptance. Living
visual acceptance remains open. Normal diagnostic and logcat requests with
lines=1500 return HTTP200 in both rooms; actual outputs contain **1,642
Family and 1,715 Living lines**, with zero fatal or ANR markers.
These are limited positive observations, not complete runtime health, provider
or negative authentication acceptance. Original registry hashes match; only the
owned copied registry was removed.

Public manifest remains **1.83/code77**; its local version guard rejects
1.118/code112 with expected exit 1. Native CLI and phone, HA, authentication, push and
publication boundaries remain. Original independent audit clock
**10:55:48–11:25:48 UTC** includes discovery, implementation, gates, delivery,
documentation, cleanup and commit. The broader goal stays active. **94 tasks, 59 delivered, 25 locally verified, 3 approval, 6 parked and 1 queued**.
All 93 prior task objects and their complete raw suffix remain. Default Weather
and all boundaries remain except the current room version substitution.

Source-only Back-input, stale Sleep-window, wallpaper reload, star-field and
polar or wrapping solar hypotheses share this clock without implementation or
a new slot. Older SMB, DAV, media dedupe, Presence, MQTT, Aurora, ISS, TimerAlarm
and bedtime findings retain their original held or exhausted scopes.
Documentation and roadmap rendering acceptance are recorded separately in the
structured proof.

Structured proof:
`mocks/portal-widget-gallery-20261102/evidence/sky-short-day-verification.json`.
