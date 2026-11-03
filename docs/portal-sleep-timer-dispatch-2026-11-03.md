# Sleep timer expiry dispatch — 3 November 2026

An explicitly started sleep countdown armed `SLEEP_TIMER` for `SleepReceiver`,
but the receiver handled only idle and overnight actions. Its expiry therefore
performed no sleep operation. The countdown UI updates its label; it does not
execute the expiry policy.

The receiver now dispatches that action to `SleepScheduler.onSleepTimerElapsed`.
The scheduler reads the current enable, pause-audio and close-app preferences,
ignores a disabled timer and calls the existing `sleepNow` sequence. That sequence
optionally pauses audio, optionally requests app close, suppresses the system
Dream, then requests device-admin sleep. Settings and the existing no-admin sleep
no-op remain unchanged. No new screen, setting, route or countdown clock policy
is introduced.

## Verification and delivery

| Check | Result |
|---|---|
| Actual arm/receiver JVM regressions | Nine methods: six original failures → nine passes |
| Expiry policy | All four audio/app option combinations, operation order and current-option changes |
| Controls | Disabled-after-arm delivery, same-handle Stop, no-admin behavior and missing/unrelated actions |
| Persistence | Receiver leaves every saved field unchanged |
| Full JVM gate | 4,117 tests / 429 suites; zero failures, errors or skips |
| Build gate | Debug, optimized performance APK, R8 and vital lint pass in2m36s |
| Actual optimized APK on Family Android10/API29 | Original 9/14 → corrected 14/14; five default/options dispatch assertions differ |
| Native controls | Current disabled flag, saved fields, receiver loader and inert actions; no unsupported intercepted service calls |
| Cleanup | Process-local audio service restored, captured handles retired, owned process/device scratch removed |
| Room delivery | Exact signed optimized 1.104/code98 installed and full APK hash verified in both rooms |
| Preservation | Complete screensaver/settings/layout/source maps and Home/timer/Sunrise preference bytes unchanged |
| Family state | Normal frame visually inspected; font, accessibility services and original Chrome task retained |

Native verification loads the actual optimized receiver and policy in a separate
shell process. Real framework device-policy and audio wrappers use process-local
service doubles; navigation intents are captured. An enabled delivery requests
exactly one captured lock. Default options capture two media-pause keys and one
Home close intent; the four explicit option combinations match their preferences.

The fixture deliberately refuses `getContentResolver` before any provider access.
The existing `SettingsGuard` exception boundary contains that refusal, allowing
the remaining sleep policy to execute. This proves entry into that path, not an
actual secure-setting read/write. The JVM fixture separately captures the expected
`screensaver_enabled=0` call and ordering.

Fixture real locks, media dispatches, activity launches, secure-setting access,
PendingIntent/alarm registrations, app-pref writes and network requests are all
zero. Native timer arming is not exercised; the JVM fixture captures the actual
arm action and deadline before manually delivering it to the production receiver.

The deployed optimized APK SHA-256 is
`3e8f530a500452b73037223977ad7540c9d484b632c3fc986eb508b8254ada21`
(25,862,702 bytes). Exact signed optimized1.103 rollback and both mappings remain
retained privately. Copied Fleet registry records were removed; original registry
hashes match. Raw owner snapshots and credentials are not published.

## Retained limits

Real alarm delivery, actual screen-off, audible pause, foreground app-close and
secure-setting provider acceptance remain unverified. Native checks are direct
receiver calls with service doubles, not countdown UI, reboot, API28 or Living
visual acceptance. Concurrent Stop/delivery races, clock changes, countdown
reopen/running-state persistence and fault-injected native timeout cleanup are
outside this fix.

The public manifest remains **1.83/code77**; the version guard deliberately
rejects local **1.104/code98**. No Google phone operation, Home Assistant change,
GitHub authentication, push or publication occurred.

The original **06:08:14–06:38:14 UTC** issue window includes diagnosis, tests,
builds, native verification, delivery, cleanup, documentation and commit.
The broader product goal remains active; design approvals, human sign-in
dependency and previously parked clocks are retained.

Structured receipt:
`mocks/portal-widget-gallery-20261102/evidence/sleep-timer-dispatch-verification.json`.
