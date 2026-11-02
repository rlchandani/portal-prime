# Weather cached clock-format correction — 2026-11-02

Source commit `86e83486`, branch `codex/weather-clock-format-20261102`.
Final optimized1.74/code68 is installed in both rooms; this is local device
iteration, not publication or Weather layout approval.

## Correction

The retained forecast held labels formatted at fetch time. Home's producer retains
its forecast and30-minute deadline, so a clock preference change could retain old
labels. Each parsed hour now also keeps its instant and location UTC offset. Home
re-reads the existing clock preference on resume and memoizes derived labels from
that retained data. `Now`, legacy timestamp-free entries and all weather values are
preserved. No producer keys, URLs, refresh deadline code, preferences or registry
fields changed. There is no network request solely to reformat the cached labels.

## Verified scope

- Two assertion failures exposed the stored-label behavior through a neutral new
  API scaffold; this was not an original-APK runtime reproduction.
- Final Weather20/20; full3845 JVM tests/403 suites, zero failures/errors/skips.
- Debug, test and optimized APK assembly, R8, vital lint, version sync and signing
  checks passed. Final combined gate took156s with serial workers and a command-only
 4GB heap. Earlier compilation and2GB compiler-memory failures are preserved in
 private receipts, not promoted as successful gates.
- Independent source review found no concrete blocking defect. It did not run
 device/UI tests or measure requests/deadlines.
- Family API29 ran274/274 public native formatter fixtures, including108 cached
 label12→24→12 checks across three offsets. These ran on a temporary exact debug
 build, not the optimized renderer. The original test APK was restored exactly.
- Both rooms' final installed optimized APK matches25860686bytes and SHA256
 `2443d2fd3f3634f73117de02cbc75c6463735413adef6886f98ceb5664dd8189`.
 Complete authenticated settings profiles and screensaver configuration match each
 room's original baseline.

Family's Fleet automatic confirmation failed terminally (`ok:false`); one observed
Install tap did not complete within75s. One subsequent shell install succeeded,
with full installed APK readback. Living's single Fleet deployment returned success
with full installed APK hash proof. This is not a claim that Family's automatic
installer worked.

## Remaining acceptance

Actual Home→Settings→Home retained-forecast behavior, physical layout, request
counts, deadline timing and performance remain unverified. Native formatter tests
cannot establish those. Existing normal Google sign-in remains user-owned; no
account/cookie automation, HA music setup, GitHub authentication or publication.
Weather-only and gallery design approval remain separate.

## Recovery limit

Family resumed its original Chrome task27174 after the optimized restoration.
One sealed app-owned installer session1294906606 remains from the failed automatic
confirmation; its dialog is no longer foreground. Shell abandonment was rejected
because the app owns it. Existing browser task was restored, not account automation.
Final installer-session cleanup is unverified and remains a separate bounded issue.
