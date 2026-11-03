# Portal Toolkit monitor cancellation and CI checks

Locally verified in the separate Portal Toolkit app. A stopped or replaced monitor
can no longer turn the covered late ping/reconnect completions into new connection
events or signals. The monitor rethrows cancellation and checks its owning coroutine
after each probe; ordinary failure thresholds, recovery and backoff remain.

The original discovery window is **17:40:01–18:10:01 UTC on 2026-11-03**, including
source audit, fixtures, both test runs, review, documentation and local commits.
The preceding path guard retains its separate **17:15:43–17:45:43 UTC** clock.

The frozen real-monitor fixture has 17 methods: five original methods and twelve
additions. Unchanged production produced **nine AssertionErrors and eight passing
controls**, with no errors or skips. The same fixture passes after correction.
The complete local command passes **195 JVM tests across 34 suites**, with zero
failures, errors or skips, and builds the debug APK. Every result tree was parsed
and its timestamp checked; independent review confirms all 195 rows. The original
five methods and helper remain byte-exact.

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon --offline --rerun-tasks
```

The gate ran from **17:49:44.374088 to 17:50:04.411687 UTC**, exit 0. The resulting
APK is 13,901,710 bytes, SHA-256
`cd61d2919bd9f955661bf8871dd779e1eb2fbd53e97086f46896371db16cb4bd`.
Two existing native libraries could not be stripped and were packaged unchanged.
This establishes assembly and artifact bytes, not signing or device acceptance.

A new local GitHub Actions workflow configures push, pull-request and manual checks.
It uses JDK 21, SDK 35, the same two Gradle tasks, read-only repository permission,
a 15-minute timeout and cancellation of superseded runs. SDK/test failures fail
the job; available reports upload even after failure, while the debug APK requires
successful checks. CI omits local `--offline` for fresh dependencies.
`actionlint` and both shell syntax checks pass. Independent source review passes
16 monitor checks and 10 CI checks. **Hosted execution remains unobserved**.

Local Toolkit commits are `0c9a37ca62353dfbce97a4efc21c92b00d24263a` (monitor/tests)
and `44d75050b85c2c14e72bc30b4e4fb1b554e1c68c` (workflow/README), following
`5bee76745775af97454e46768fa6abdbb541c9b6` (path guard). The original checkout stays
clean at `4e1dadb8ec2ec018b6fdc250486e0c476467a7c4`; all 152 selected original bindings
match, and 146 untouched candidate bindings match. No merge, push, install or
publication occurred. Toolkit's declared 1.4.0/code5 remains; README's historical
release version is retained.

The saved unified queue gains one locally verified task: **108 tasks, 69 delivered,
29 verified, 3 approval, 6 parked and 1 queued**. All 107 prior objects and their raw
suffix remain; Weather remains the default. Immortal app/source versions and
current room delivery are unchanged and were not rechecked. Phone, external
provider, HA and publication restrictions remain.

The fixture runs actual monitor methods with virtual callbacks. It does not run
ConnectionViewModel, Android UI, Dadb or a real transport. The check cannot undo a
reconnect callback's earlier holder/socket effects, and it is not an atomic
publication guarantee against arbitrary concurrent stop. Manual Disconnect is
reachable while Connected with a pending ping; Wi-Fi Reconnecting has no such
button. Physical timing, connection ownership and device workflows remain open.

Separate source triage found a possible custom-URI APK copy-failure boundary in
Toolkit's `installApkFromUri`: partial read failure can bypass explicit Failed
state, input close and temporary-file cleanup. Cancel or a new selection can reset
Idle. **No fixture was frozen/admitted, no baseline executed and no fix accepted**.
Any reproduction must exercise the real AppsViewModel route with inert dependencies
and generated host files. This candidate retains the same original discovery
clock; no copy of a decision helper, permanent-lockout claim or fresh clock is
substituted for evidence.

Structured local evidence: `mocks/portal-widget-gallery-20261102/evidence/toolkit-monitor-ci-verification.json` (repository file, outside the MkDocs docs tree).
