# Installer session cleanup — 2026-11-02

Source commit `95872586`, branch `codex/installer-session-cleanup-20261102`.
This independent issue began at **13:53 UTC**, with a 30-minute cap including
device restoration. It did not reopen a parked performance or screensaver issue.

## Correction

Closing a `PackageInstaller.Session` handle does not abandon Android's staged
installation. `HeadlessInstaller` previously left created sessions behind after
a timeout or open/write failure.

The installer now abandons only the session created by an unsuccessful attempt.
Successful attempts skip abandonment. Cleanup errors cannot prevent the
auto-confirm service from being disarmed or the receiver from being removed.

Independent review identified a related cancellation hazard: different package
installs share their result action. The receiver now accepts only callbacks with
its exact session ID. An atomic ID also provides visibility between the worker
and receiver threads. Other sessions' success, failure and missing-ID broadcasts
cannot complete this attempt or open its confirmation UI.

## Verification and artifact

Seven initial regressions produced five missing-abandonment assertion failures.
A separate asynchronous test reproduced premature completion from another
session's failure before callback correlation was added. Final coverage includes
timeout, terminal failure, creation/open/write/commit exceptions, cleanup
exceptions, success and asynchronous foreign-result rejection.

Final **3,858 JVM tests / 405 suites** passed without failures, errors or skips.
Debug, test and optimized APK assembly, R8, vital lint, version consistency and
matching signatures passed. One commit-error fixture was corrected to use an
unchecked exception matching Android's API.

Optimized **1.76/code70**, 25,860,686 bytes:
`85ae519f439ab0d6e0f66565b6bfa291ae5ab470efea9fe9bb75e0869ba96fcd`.
The signer matches the accepted baseline. This is local device delivery, not a
published release.

## Family acceptance

The previously identified sealed, committed, app-owned stale session was still
active. A test-APK-only fixture checked public PackageInstaller ownership before
mutating that exact session. It verified one fresh scratch session's removal,
removed the stale session and confirmed no owned sessions remained: **17 checks,
zero failures; original owned count 1 → 0**. The final system dump independently
confirmed the old session was absent from active sessions.

The first fixture run against the optimized app failed before reporting results:
the debug-targeted test package expected Kotlin `Intrinsics`, removed by R8.
The matching debug runtime then passed the fixture. The exact optimized APK and
original test APK were restored and fully read back. Native API cleanup is thus
verified on API29 in the matching debug runtime, not through the optimized fixture.
There were zero other original sessions, so native preservation of an unrelated
pending install was an empty case; local guards cover the ownership boundary.

On the restored **optimized 1.76**, one deliberately invalid public APK exercised
the actual Fleet dialog/headless route. It returned the expected install failure
in **191.274ms**, leaving owned sessions **0 → 0**. Its generated file was absent
before the check and removed afterward. This single failure is not a general
performance benchmark or a native 120-second timeout test.

Family's complete settings profile and screensaver configuration are unchanged.
Dev mode remains enabled and original Chrome task27174 is retained. No Home or
UiAutomation was requested; task retention does not prove foreground, browser
process retention or music continuity.

## Living delivery and limits

One Fleet deployment exited0 and verified the full installed APK SHA-256.
Authenticated identity confirms **1.76/code70**; complete settings profile and
screensaver configuration are unchanged, with Dev mode retained. Living received
the final artifact without a duplicate native interaction run.

Actual timeout, overlapping valid installs, successful/pending confirmation on
the new optimized code, API28 hardware, physical phone and whole-product
performance remain outside this acceptance. Process termination during self-update
can bypass `finally`, as before. Weather Home/settings-return acceptance and
normal YouTube sign-in/playback remain separate.

The selected public receipt is
`mocks/portal-widget-gallery-20261102/evidence/installer-session-cleanup-verification.json`.
Full payloads, device logs and APK readbacks remain private. No Home Assistant,
GitHub authentication/publication, account credentials or Meta OS configuration
was changed.
