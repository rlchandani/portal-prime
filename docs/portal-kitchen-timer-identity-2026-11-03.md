# Kitchen timer alarm identity and restoration

Exact optimized **Portal 1.99/code93** is installed and fully hashed in Family
Area and Living Area. Timers now have independent Android PendingIntent
identities, and saved timers are rearmed during process start and boot.

Two realistic generated timer IDs created 23 seconds apart shared the same
folded request code. Android ignores Intent extras when matching PendingIntents,
so the second timer replaced the first timer's deadline and payload; cancelling
one could cancel its neighbor. The full ID now appears in an encoded Intent data
URI. IDs and labels remain payload extras; deadlines remain AlarmManager timing metadata. Existing no-data legacy handles
are retired during scheduling and cancellation.

Process startup rearms every retained timer, including due timers, without
rewriting their saved list. The receiver's existing synchronized claim removes a
due timer before ringing, preventing duplicate legacy/new deliveries. Boot keeps
the established policy of dropping expired timers and rearming future timers.
No new preference, setting, route or interface was added.

## Verification and deployment

- Seven initial identity regressions reproduced five failures. Two cold-restore
  cases separately failed against the old behavior with the Boolean API-only
  signature. Final **29 timer tests** pass, including existing mutation/delivery
  coverage. A final test-formatting recompilation also passes.
- **4,075 JVM tests in 424 suites**, debug assembly, optimized performance assembly,
  R8 and vital lint pass. The combined Android gate took 118 seconds. Independent
  review matches all four changed production-file hashes and the final fixture.
- A separate Family shell process loaded the actual old/new optimized APKs.
  Real Android 10 PendingIntent identity improved **0/6→6/6 shared checks**;
  three additional candidate cold-restore checks pass, for **9/9**. These cover
  collisions, independent deadlines/rescheduling/cancellation, legacy retirement,
  encoded IDs and due/future restoration without saved-list mutation.
- The native fixture intercepted AlarmManager calls and used generated in-memory
  preferences. It scheduled no real alarms, wrote no app preferences and rang no
  timer. Old cancellation used the framework directly; candidate cancellation
  used production expiry cleanup. This is not UI Cancel or actual alarm-timing
  acceptance. Every owned framework handle was removed in both runs.
- Both existing deployment helpers verified complete installed APK hashes.
  Screensaver, settings profile, layout, sources, device name/dev mode and timer
  preference-file bytes match both preflight baselines. Family font and the
  original accessibility list match. Living's native appearance was not inspected.

## Family restart and recovery

The first Family postflight passed, including its original Chrome task and visible
photo frame. Later, the device reported three minutes of uptime and boot reason
`poweron_dcplug`. This report does not establish an app or human cause. Its saved
kitchen-timer list was empty; this is not running-timer reboot acceptance.

The restart ended the original Chrome task. Chrome's original component was
reopened without writing tab URLs or account data; its prior tabs are unverified.
The original accessibility-service list and WiFi ADB port were restored. The
app's own authenticated screensaver action restored the frame. Final Android
state reports the frame as resumed while Dreaming, with the display on; its
private screenshot visibly shows the normal photo layout. All saved maps, timer
files, font/services and the complete installed APK hash still match. Initial
passing checks survive in the structured tool transcript; later recovery checks
overwrote the initial raw check file.

Owned native scratch and the copied registry are removed; the primary registry
is byte-identical. Owner photos, credentials and raw diagnostics remain private.
No phone control, Home Assistant change, GitHub publication or public self-update
manifest change occurred. Android9/API28, audible delivery, real AlarmManager
timing, UI cancellation and running-timer reboot ordering remain unverified.

APK SHA-256:
`27967ddb71cc8c91a486d912bb7b4952692f61cc0b4e6bcd754fd41d965a0ab9`.

The sanitized receipt is
`mocks/portal-widget-gallery-20261102/evidence/kitchen-timer-identity-verification.json`.
The original **04:12:30–04:42:30 UTC on 3 November** clock includes diagnosis,
implementation, review, verification, rollout, recovery and closure.
