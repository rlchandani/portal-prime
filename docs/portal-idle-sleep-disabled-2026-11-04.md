# Respect idle screen-off Off at alarm delivery

Exact optimized **1.152/code146** is installed and fully hashed in both rooms,
one install each. Living's acknowledgment was uncertain; six read-only
observations confirmed the exact APK without another install attempt.
All43/30 preference files and four complete configuration maps match.
Family font, accessibility, original Chromium activity, resumed frame and
awake state remain. Public1.83/code77 is unchanged.

An idle alarm armed before a setting change previously suppressed Dream and
requested sleep even after idle screen-off or the screensaver was disabled.
The callback now loads one current read-only screensaver snapshot and returns
before either effect when enabled or idleSleepOn is false. Enabled delivery
retains suppression then sleep; scheduling, cancellation, duration, overnight
policy, sleep-timer behavior, audio and app-close behavior remain unchanged.

All **4,476 JVM tests /467 suites**, debug and optimized assembly, R8 and vital
lint pass. Twelve frozen receiver regressions produce six expected
AssertionErrors and six passing controls before, then twelve passes. These
tests capture the real scheduler's arm and invoke the receiver with that action,
using generated preferences and mocked Android boundaries.
Independent source and native/artifact reviews pass.

On Family/API29, four frozen actual-debug receiver cases improve three policy
failures and one control to four passes, using identical Java, DEX and gates.
Generated current preferences remain exact with zero edits/puts/applies/commits.
Null-base contexts expose no real resolver, admin service or alarm manager;
enabled delivery retains attempted suppression and the existing missing-admin
no-op. Both fixture packages, processes and external directories retire.
The native case names containing AfterInertArm do not prove native arming,
earlier mutation or real AlarmManager delivery.

Real alarm delivery, secure-provider writes, physical sleep, mounted UI,
optimized/API28 runtime and Living functional acceptance remain separate.
Immediate cancellation/rearming, duration changes, stale generations and
atomic concurrent disable are outside this two-line callback guard.
Off-to-On before an old deadline keeps the existing current-policy behavior.
No household media, provider, phone or account was used.

The first candidate fixture repack could not replace an owned read-only
generated source.apk. Only that generated input was replaced before native
execution; frozen code and gates stayed exact. The failed setup log is retained
and excluded. Four Java8 compilation warnings are retained.
Original11:24:52–11:54:52UTC window and11:39:52 target remain.

Evidence: `mocks/portal-idle-sleep-disabled-20261104/README.md` in the repository.
