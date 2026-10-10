# Fleet polling performance validation

Validation date: 2026-10-09. Branch: `codex/portal-fleet-poll-performance`.
The broader rendering, browser-readiness, settings, direct-input and mobile/fleet
performance objective remains active. This change concerns metadata reads only.

## Final design

Fleet `/info`, `/apps` and paired `/remote/catalog` share copied package/version
primitives and explicit query coverage. Each request captures one catalog list,
then uses that same list for its query scope and response rows. The package union
includes every catalog name requested in this process, including absent packages.
Cold reads call `getPackageInfo(0)` for that union; they never scan every installed
package. Warm reads reuse the snapshot. Coverage distinguishes checked absence
from names not yet queried. New names invalidate before loading the expanded union.

An application-context receiver registers before the first read and invalidates
on protected added/removed/changed/replaced events, including replacement removal.
It performs no scan. HTTP/IO callers share a single cold flight through
`HomePresentationInventoryCache`; generation changes reject invalidated in-flight
results. The actual returned list must cover the caller's names and be younger
than five seconds on the monotonic clock. Conditional expiry cannot discard a
newer list. Snapshots retain no PackageInfo, drawable or Activity objects.

`NameNotFoundException` represents covered absence. Unexpected scan/registration
failures return unavailable for the existing uncached fallback; they cannot publish
an empty inventory. API 28+ retains long version codes; API 24–27 uses the legacy
integer. Presence, permissions, device identity and connection/install mode remain
live. Installer decisions and readback remain uncached. Owned completion and
reconciliation invalidate before publication, including terminal callbacks that
arrive after an HTTP waiter times out.

## Validation ledger

| Check | Result |
| --- | --- |
| Full JVM gate | 1,126 tests; zero failures, errors or skipped tests |
| Scoped cache regression gate | 26 cases; RED checkpoint had five expected failures, final gate passed |
| Build variants | Debug, benchmark and Android-test APKs passed offline builds |
| Independent review | Late terminal callback, returned-list expiry and coverage/consumer paths reviewed; no remaining findings |
| Native package events | Generated install, update and uninstall confirmed through all three APIs |
| Native cleanup | Fixture/session absent, compatibility overrides reset, zero owned forwards; emulator exited successfully |
| Device deployment | Final benchmark installed in both rooms, installed APK SHA-256 verified |
| Matched latency/payload comparison | Two scoped cohorts complete; fields and app arrays match all checked baseline responses |
| Final navigation/pinned-window smoke | Five cycles and thirty foreground checks per room; cover tap retained the same browser window |
| Broader rendering/readiness | Open; this metadata change proves no compositor/browser speedup |

Final artifact hashes, recorded in `fleet-poll-scoped-final-build.json`:

| Artifact | SHA-256 |
| --- | --- |
| debug | `98ffce77cc7da9bf07cf51e69fca52ee47088386b187dd1a5b5018e64586eee0` |
| benchmark | `21a03ec5d27d74fff7ad0e9a2ff37b02cb758ac502ada3a27318e374af12b450` |
| android-test | `1295ebdb9cd89a2587c01d137c92c682143bdc36a1493db854a15b831f70c469` |

The late-terminal regression models a cache rebuilt while an installer continues
after its HTTP deadline. The expiry regression first failed with expected version
3 versus actual version 2; it now checks the returned replacement's age. Scoped
cases cover union expansion, immutable negative coverage, disjoint concurrent
requests, query failure/retry and targeted PM reads. The PM tests reject a full
`getInstalledPackages()` scan. Both catalog consumers capture their list once,
preventing a refresh between coverage selection and response construction.

## Rejected iteration

The first implementation scanned every installed package. Device measurement
rejected it: first reads after more than five seconds cost substantially more than
the restarted baseline. Both Portals were restored to verified pinned-session
build `c00377b` before the scoped implementation was tested and installed.

| First read after idle | Family baseline | Family full-scan | Living baseline | Living full-scan |
| --- | --- | --- | --- | --- |
| `/info` median | 37.11 ms | 57.67 ms | 55.89 ms | 76.24 ms |
| `/apps` median | 28.77 ms | 56.74 ms | 49.71 ms | 68.14 ms |

There were six first-after-idle samples per endpoint in each cohort. These are LAN
response times, with network/device noise; the extra full-package work was also
removed structurally. The intermediate full-scan APKs and their native checks are
superseded evidence, not the shipped implementation. Response top-level fields
and app arrays matched the baseline on both rooms in that comparison.

## Native freshness

The owned API 34 read-only emulator used documented compatibility changes
`135549675` (package visibility) and `255038118` (foreground-service type policy)
to exercise Android 10-style behavior. Both were reset afterward. This does not
establish support under default API 34 policies or Portal broadcast timing.
Official references: [Android 11 compatibility changes](https://developer.android.com/about/versions/11/reference/compat-framework-changes#filter_application_query)
and [Android 14 compatibility changes](https://developer.android.com/about/versions/14/reference/compat-framework-changes#fgs_type_none_disabled_change_id).

Generated no-code `org.videolan.vlc` versions 1 and 2 were installed only on the
owned emulator, with a preexisting-package refusal. No household apps were changed.
Setup compared requested package metadata with direct Android reads, verified warm
reuse and unknown absence, and created a test session through the normal PIN
exchange. Reads exercised `/info`, `/apps` and paired `/remote/catalog`.

| Native transition | Expected version | First poll | Confirmed from command start |
| --- | --- | --- | --- |
| Install | 1 | Previous state | 621.27 ms |
| Update | 2 | Previous state | 618.94 ms |
| Uninstall | Absent | Matched | 110.98 ms |

All transitions converged before five-second expiry. Some immediate polls were
stale while Android broadcast delivery was pending; no every-first-poll guarantee
is claimed. Owned installer callbacks additionally invalidate on terminal outcomes.
Cleanup verified the generated package and private session absent, pairing/Fleet
disabled and flushed, compatibility overrides reset and forwards removed. Ephemeral
fixture signing secrets were removed. Both owned emulator sessions exited with code
zero after shutdown.

Earlier fixture failures remain recorded privately: instrumentation force-stop
preceded queued service startup/asynchronous preference writes, repaired by empty
synchronous commits; default API 34 service-type policy required the documented
profile; the original F-Droid fixture identity was outside the catalog and was
replaced by the generated catalog identity above.

## Ten-minute navigation baseline

Before metadata changes, `c00377b` ran 60 cycles per room: 540 accepted actions,
480 navigation commands, 60 empty-text checks and 360 foreground-window checks.
Both returned Home with unchanged screensaver configuration and no collector or
listener left running. Both rooms had zero failures, callback drops and attach
failures. No household photo bytes or screenshots were exported.

| Room | Duration | Home host p95 | Preview host p95 |
| --- | --- | --- | --- |
| Family Area | 600.44 s | 64.50 ms | 71.00 ms |
| Living Area | 600.45 s | 61.25 ms | 75.50 ms |

These are Immortal Window FrameMetrics, not delivered compositor FPS. Dream had
one sample per room, insufficient for a distribution. These rendering tails remain
high and require separate work. Foreground-package checks do not prove browser
document readiness or visual transition completion.

## Final controlled comparison

The restarted baseline and two scoped cohorts use Home with no active frame
collector. Each cohort alternates `/info` and `/apps` as the first request after
5.25 seconds, then calls the other endpoint and immediately repeats the first.
`/remote/pin` is a lightweight control. Other clients could warm a shared cache,
so first-after-idle describes host cadence rather than guaranteeing a cache miss.
Each baseline endpoint/kind has six samples; the two scoped cohorts combined have
twelve. Control has twelve baseline and twenty-four scoped samples. These are LAN
response times; no statistical population or visual-readiness claim is made.

| Room | Endpoint | Request kind | Baseline / scoped samples | Baseline / scoped median | Scoped max |
| --- | --- | --- | --- | --- | --- |
| Family Area | `/info` | expired-first | 6 / 12 | 37.11 / 44.60 ms | 66.52 ms |
| Family Area | `/info` | shared-warm | 6 / 12 | 33.82 / 22.87 ms | 42.99 ms |
| Family Area | `/info` | repeat-warm | 6 / 12 | 25.81 / 20.55 ms | 28.53 ms |
| Family Area | `/apps` | expired-first | 6 / 12 | 28.77 / 29.12 ms | 37.40 ms |
| Family Area | `/apps` | shared-warm | 6 / 12 | 27.51 / 11.83 ms | 13.79 ms |
| Family Area | `/apps` | repeat-warm | 6 / 12 | 22.48 / 10.86 ms | 13.41 ms |
| Family Area | `/remote/pin` | control | 12 / 24 | 15.92 / 22.55 ms | 48.96 ms |
| Living Area | `/info` | expired-first | 6 / 12 | 55.89 / 50.50 ms | 66.26 ms |
| Living Area | `/info` | shared-warm | 6 / 12 | 43.27 / 30.88 ms | 50.44 ms |
| Living Area | `/info` | repeat-warm | 6 / 12 | 39.68 / 32.04 ms | 37.96 ms |
| Living Area | `/apps` | expired-first | 6 / 12 | 49.71 / 45.32 ms | 61.96 ms |
| Living Area | `/apps` | shared-warm | 6 / 12 | 36.82 / 20.90 ms | 33.18 ms |
| Living Area | `/apps` | repeat-warm | 6 / 12 | 31.70 / 19.28 ms | 26.66 ms |
| Living Area | `/remote/pin` | control | 12 / 24 | 28.09 / 29.27 ms | 61.40 ms |

Repeated `/apps` replies improved in both scoped cohorts. Cold results were mixed:
Family `/info` stayed approximately seven milliseconds slower than its baseline;
its control response was also slower. That correlation does not prove the cause.
There is no across-the-board cold-read improvement claim. The full-inventory scan
regression was removed; the final code structurally queries only requested catalog
identities and avoids repeated provider queries on a fresh covered snapshot.

All 36 scoped responses per endpoint/room preserved the baseline top-level keys
and exact app arrays, including row order. Presence and other dynamic values were
not frozen for comparison. Final device smoke passed: five navigation cycles and thirty foreground checks
per room, plus a pinned browser cover/tap cycle preserving its window identity.
This smoke does not independently establish browser document retention or visible
paint timing; the prior pinned-session fixture ledger covers document/form/scroll
retention on `c00377b`. Both final room checks preserved full screensaver settings
and ended on Home without a pin, cover, pending/failed cleanup or active collector.
Local commit is the final repository action after this validation gate.
Private evidence includes `scoped-final-controlled-summary.json` and the room
`inventory-controlled-baseline-restarted`, `scoped-final` and `scoped-confirmation`
JSONs. No credentials, private paths or household media are committed.
