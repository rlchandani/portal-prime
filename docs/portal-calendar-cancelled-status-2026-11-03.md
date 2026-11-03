# Calendar cancelled event status — 3 November 2026

Exact optimized **1.102/code96** is installed and fully hashed in Family Area and
Living Area. Calendar feeds now omit event blocks explicitly marked
`STATUS:CANCELLED`. Previously those blocks appeared as active appointments,
including all-day events and every expanded occurrence of a cancelled series.

The existing event preparation boundary rejects that status before caching a
prepared definition or expanding its recurrence. Confirmed, tentative, absent and
unknown statuses retain their existing behavior. The raw feed cache and recovery
policy remain: a successful cancelled-only refresh replaces prior active data,
and a later transport failure retains the successfully refreshed empty result.

This is separate from the earlier comma-separated `EXDATE` fix, which removes
specified occurrences from an otherwise active series. The status check filters
an entire event block; it adds no UID or detached-instance reconciliation.
The status meaning follows
[RFC 5545 section 3.8.1.11](https://www.rfc-editor.org/rfc/rfc5545.html#section-3.8.1.11).

## Verification

| Check | Result |
|---|---|
| New production-parser/reader regression methods | Eight methods; seven failed against the original implementation, then all eight passed |
| Regression behavior | Timed/all-day/recurring cancellation, case/parameters/folding/property position, active neighbors, active status controls, text and METHOD controls, result cap, warm prepared reads and successful cancellation followed by failed-refresh recovery |
| Full JVM gate | 4,101 tests / 427 suites; zero failures, errors or skips |
| Debug and optimized builds | Passed; R8 and vital lint passed; combined terminal exit 0 in 119 seconds |
| Actual optimized APK on Family Android 10/API 29 | Baseline 4/10 → candidate 10/10, with six original cancellation failures |
| Native controls | Actual APK class loader, retained active statuses/text, EXDATE count/title, and preserved timed-event start/end/title/location/all-day fields |
| Independent review | No blocking finding; source/test/version/fixture hashes matched |
| Room delivery | Both helpers reached terminal exit 0 and verified the complete installed APK SHA-256; both served 1.102/code96 |
| Preservation | Both complete settings profiles, layouts, photo sources, screensaver maps and Home/timer preference bytes match their baselines; names and dev mode unchanged |
| Family runtime | Original font/accessibility settings and Chrome ActivityRecord retained; photo frame resumed, display on, normal frame visually observed |
| Cleanup | Both owned shell processes and device scratch removed; primary token registry unchanged and temporary registry copy removed |

The native fixture uses a separate shell process and loads each exact optimized
APK with `DexClassLoader`. It invokes the mapped production parser on generated
feeds and checks the returned rows. It does not obtain a `Context`, launch the
application, write preferences, contact a calendar service or invoke an installer.
Those fixture limits are separate from the subsequent OTA deployment.

A transient disk-capacity error occurred during the reviewer's read. The build
finished successfully. One completed owned mapping snapshot was compressed from
83,152,174 to 5,983,687 bytes, with byte-for-byte decompression and SHA-256
verification before removing its uncompressed copy. Current and rollback APKs
were retained.

## Artifact and limits

Package: `com.immortal.launcher.debug`. Optimized APK: **25,862,702 bytes**.
SHA-256: `429017496a4e5908d0ad53cf1080694e7ddc3aeeb0d5f959b9f3a364b609bfcf`.
Its verified signer matches the retained 1.101 rollback artifact.

Native acceptance covers the production parser in the optimized APK on Family,
not Home/screensaver calendar UI, owner-hosted feeds, asynchronous refresh or
reader recovery on Android. Reader recovery has JVM coverage. The native EXDATE
control checks count/title; exact excluded dates have existing JVM coverage.
Living's calendar UI and API 28 remain unverified. There is no general performance
or whole-product acceptance claim.

Cancelled detached `RECURRENCE-ID` blocks are not reconciled with an active
same-UID master, so its expansion can still contain that occurrence. `METHOD:CANCEL`
scheduling, unknown status interpretation, broader RFC support and cancellation
that has never been successfully refreshed remain outside this change.

No phone operation, Home Assistant change, GitHub authentication or publication
was performed. Public `version.json` remains 1.83/code77; the existing version
guard correctly rejects the local 1.102/code96 mismatch.

The original **05:29:15–05:59:15 UTC** issue window includes discovery, review,
checks, deployment, cleanup and commit. Prior design decisions, sign-in dependency
and exhausted issue clocks remain unchanged. The continuous product goal stays
active.

Structured receipt:
`mocks/portal-widget-gallery-20261102/evidence/calendar-cancelled-status-verification.json`.
