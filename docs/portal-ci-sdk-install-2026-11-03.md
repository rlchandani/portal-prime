# Android CI: preserve SDK installer failures

The required compile-SDK step in `.github/workflows/tests.yml` ended with
`|| true`, which reported success when `sdkmanager` failed. The local correction
in Immortal `1ea1ed63` returns the installer result immediately. It retains
`yes` for license answers and both existing Android 36 package arguments.

`yes` can receive SIGPIPE when its consumer finishes. This one setup script
disables `pipefail`, making the pipeline return the rightmost command's status.
The job's existing Bash error handling then stops on a failed installer.
No later workflow step or Android product code changes.

## Local verification

The fixture executes the actual named workflow shell text with a generated inert
`sdkmanager`, real host Bash and `yes`. Six frozen cases cover installer exits
0, 2 and 7 under both Bash modes. The unchanged workflow has four exit-status
assertion failures and two successful-install controls; the corrected workflow
passes all six. The controls and GREEN also verify the exact two package arguments,
a consumed `y` answer, quiet stdout and retained diagnostic stderr. The failed RED
assertions occur before those later checks.

The existing Portal Python selection passes **254 unique tests**, including these
six. All workflow actionlint and the SDK script's Bash syntax check pass. The
observed host shell is macOS Bash 3.2.57; Ubuntu and real SDK installation remain
unobserved. An owned five-second fixture deadline and group cleanup are reviewed
in source, with no exercised timeout or independent descendant census claim.

Independent source and recorded-execution reviews were fully read and their
bindings checked before the local source commit. The other five workflows and
16 prior protected files remain byte-exact; `tests.yml` is the one intentionally
selected protected change. Two private protection-collector preparation errors
are retained separately from the four genuine baseline assertions. The first execution
review also falsely matched `skipped` within a passing test name; its original
report and corrected result-status selection are retained, with no test rerun.

## Delivery and boundaries

This is locally verified CI configuration. No GitHub push, hosted run, real SDK
license/download/install, Android build, APK, device, phone, provider or account
acceptance is claimed. Package versions and license policy remain unchanged.
The original **20:50:43–21:20:43 UTC** unit includes discovery and has no reset.
News overlaps and the absent Fleet watch path remain bounded negatives, with
older clocks retained. The earlier Toolkit SDK propagation belongs to its
separate checkout and original clock.

The single saved queue contains **117 tasks: 71 delivered, 35 verified,
3 approval, 7 parked and 1 queued**. All 116 earlier task objects and raw task
suffix remain exact; Weather remains the default selection. Every earlier hold
remains and the continuous whole-product goal stays active. Sanitized bindings
and actual results are retained in `mocks/portal-widget-gallery-20261102/evidence/ci-sdk-install-verification.json`.
