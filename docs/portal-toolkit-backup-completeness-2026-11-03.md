# Portal Toolkit declared APK restore completeness

Toolkit now rejects an app restore when any APK named in that app's manifest is
missing or empty. Previously it silently omitted that file and could install the
surviving subset, then count the incomplete app as restored. The complete ordered
list is now checked before package metadata or installation. The failure stays
inside the existing per-app result boundary, allowing a following complete app
to restore normally.

Local Toolkit commit: `a74f5663d4ee38367e7a1769f55f4075a4befc92`, parent `23073a5538191cde30b64058f558859d602f7c5b`.
Only `PortalBackupManager.kt` and the new `BackupDeclaredFilesTest.kt` change.
All remaining manager bytes, existing helper tests, callers, build configuration,
dependencies, package, version and workflow are unchanged. The original Toolkit
checkout remains clean at `4e1dadb8ec2ec018b6fdc250486e0c476467a7c4`.

The real public `restoreBackup` fixture runs JSON/ZIP extraction and generated
host files, with Android input, package metadata and Dadb mocked. The accepted
baseline has **five AssertionErrors and four passing controls**. Four missing or
empty base/split variants install a surviving subset; the two-app case incorrectly
counts both apps. The same frozen nine cases all pass after the correction.
Complete single/split installs, file order/bytes, metadata mismatch, all-absent
failure, exact progress, input close, owned temporary-directory cleanup and an
unrelated sentinel are checked.

The full command `./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon
--offline --rerun-tasks` passes **212 unique tests across 36 fresh suites**, with
zero failures, errors or skips. The debug APK is 13,901,710 bytes,
SHA-256 `190407c6dc0bdfba81ae56e6ef19436c94dfd631a4e2fccbe77847c548461b32`. Independent source/result review passes
**13/13** with no blockers. Existing unstripped-library and JVM bootstrap-sharing
warnings remain in the retained build log.

An initial fixture run has nine setup failures from Kotlin/Mockito matcher
interception and contributes zero product failures. Those logs, rows and fixture
bytes are retained. Explicit four-argument push matching and inert non-null
matcher fallback values correct the helper; all nine literal case bodies remain
byte-identical. Accepted baseline and passing run use identical repaired bytes.

Acceptance is limited to generated host archives with mocked metadata and
transport. No real APK parser, provider, remote install, phone/native UI, SDK33
metadata branch, actual settings write or newly executed three-failure stop is
accepted. Moving list construction also makes existing declared filename/path
errors per-app failures; malformed declarations are not separately tested.
Cancellation, concurrent operations, universal setup-fault cleanup and durability
remain open. No device, deployment, authentication, push or publication occurred.
The earlier APK read candidate remains parked and was not promoted.

Source checkpoint **18:46:51 UTC** completed before the **19:05:45 UTC**
deadline. Initial records were written **19:17:27 UTC**, after cutoff. Subsequent
record edits and docs/browser checks are maintenance only: no new implementation,
fixture, app test/build, device acceptance or clock reset. Whole-unit closure
within the original deadline is not claimed.

The original **18:35:45–19:05:45 UTC** window includes backup, store and scanner
discovery; it is not reset. Stores yielded no distinct supported defect. Scanner
progress inconsistency remains an unselected source prediction, with no fixture
or fix. All earlier issue clocks, approvals and holds remain. Queue 111:
69 delivered, 31 verified, 3 approval, 7 parked and 1 queued; all 110 prior task
objects and the Weather default are preserved. The broader product goal is active.

Structured evidence: `mocks/portal-widget-gallery-20261102/evidence/toolkit-backup-completeness-verification.json` (repository file outside the MkDocs docs tree).
