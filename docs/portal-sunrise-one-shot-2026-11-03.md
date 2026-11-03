# Sunrise one-shot consumption — 3 November 2026

An enabled sunrise alarm with no selected weekdays is documented as one-shot.
Previously its receiver showed the wake light and left the alarm enabled, so the
scheduler armed it again for tomorrow. The receiver now disables that consumed
one-shot before rescheduling. The existing scheduler cancels its old handle and
leaves it unarmed. Selected weekdays still schedule the next matching occurrence.

Only `enabled` changes. The time, weekday set, ramp, chime and unrelated saved
fields remain available for the next manual enable. The presentation uses the
original loaded configuration before consumption. Sequential duplicate delivery
and later explicit rescheduling remain silent. This follows the existing regular
alarm receiver's one-shot policy and adds no setting, route or screen.

## Verification

| Check | Result |
|---|---|
| Actual receiver JVM regressions | Seven methods: four fail on the original receiver; all seven pass after correction |
| Receiver controls | Repeating weekdays, disabled alarms and missing/unrelated actions retain their behavior |
| Presentation errors | Existing contained notification/direct-launch failures still consume the one-shot |
| Full JVM gate | 4,108 tests / 428 suites; zero failures, errors or skips |
| Build gate | Debug and optimized performance APK, R8 and vital lint pass; combined gate 2m10s |
| Actual optimized APK on Family Android 10/API29 | Original 7/11 → corrected 11/11; exactly four one-shot assertions differ |
| Native fixture controls | Notification capture, retained ramp/chime, matching repeat day/time, disabled/unrelated actions; no unsupported service calls |
| Native cleanup | Process-local services restored, synthetic handles retired, owned processes and device scratch removed |
| Room delivery | Exact signed optimized 1.103/code97 installed and full APK hash verified in Family Area and Living Area |
| Preservation | Complete settings/screensaver/layout/source maps and Home/timer/Sunrise preference bytes unchanged in both rooms |
| Family state | Normal frame visually inspected; font, accessibility services and original Chrome task retained |
| Independent review | No blocker; production, test, fixture and APK hashes match |

The final native fixture loads the actual optimized production receiver, config
and scheduler in a separate shell process. Android framework wrappers run against
process-local activity, notification and alarm service doubles. All app-pref
writes, real alarm/notification/PendingIntent registrations, activity launches,
fixture network requests and fixture installer invocations are zero.

Two earlier fixture attempts lacked the activity service needed by notification
preparation. Their 5/11 results are retained privately as fixture gaps. Adding the
missing service double, without changing production or the eleven assertions,
produced the final 7/11 baseline and 11/11 candidate comparison.

The deployed optimized APK SHA-256 is
`e12a1370c901635084d1798dd7f759036e5116ecfc2b0489e254ae6b726eee2f`
(25,862,702 bytes). The exact signed optimized 1.102 rollback APK and both mappings
remain retained privately. Copied Fleet registry records were removed; original
registry file hashes are unchanged. Credentials and raw owner snapshots are not
published.

## Acceptance limits

Native acceptance is direct receiver and mapped scheduler invocation. It does not
prove real AlarmManager delivery, PendingIntent identity/registration, full-screen
notification promotion, WakeLight UI, brightness, audio, app-start/boot lifecycle
or reboot behavior. API28, Living visual acceptance, concurrent setting changes
and fault-injected native timeout cleanup remain unverified. Notification/channel
construction failures outside the existing contained presentation calls retain
their earlier behavior.

The public manifest remains **1.83/code77**. Its version-sync guard deliberately
rejects the local **1.103/code97** mismatch; this is local delivery, not public
release readiness. No Google phone operation, Home Assistant change, GitHub
authentication, push or publication occurred.

The original **05:53:46–06:23:46 UTC** issue window includes diagnosis, tests,
builds, native verification, delivery, cleanup, documentation and commit.
The broader product goal remains active, with pending design decisions, human
sign-in dependency and previously parked clocks retained.

Structured receipt:
`mocks/portal-widget-gallery-20261102/evidence/sunrise-one-shot-verification.json`.
