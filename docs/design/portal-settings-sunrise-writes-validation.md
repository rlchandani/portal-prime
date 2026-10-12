# Sunrise settings: background saves and endpoint-inclusive ramp picker

Sunrise edits previously rewrote selected days and all five scalar fields in the input callback,
then synchronously ran the registry's SunriseScheduler.reschedule hook, including Android alarm
operations. Each scalar edit now submits only its own field to one process-owned IO writer.
The settings registry still owns validation, persistence and the scheduling hook. A scalar edit
uses one preference setter instead of the previous six; this counts setter calls, not physical
storage transactions or a measured latency percentage.

The bespoke days set is copied before queuing. Its storage and the existing enabled-value
reaffirmation are synchronized on the registry domain, so the same hook runs once after days
are stored. The enabled value is retained. Accepted writes survive pane departure; Activity
contexts are not retained. Pane readbacks await completion and load the stored snapshot on IO.
Busy controls are disabled, stale completions cannot clear newer saves, and the process-owned
failure flag survives pane recreation and clears after a successful save.

The ramp now uses the existing registry numeric picker instead of a custom 1–45 slider. The
spec already permits 1–60 minutes. Shared numeric steps clamp their final non-wrapping step to
the endpoint, so five-minute increments reach both 1 and 60. Existing wrapping behavior is
preserved and arithmetic uses Long to avoid overflow. Sounds keeps its existing save behavior
through an alias to the shared SettingsSaveState machine.

## Verification

- Debug, full unit, optimized native-R8 and exact-candidate bootstrap builds passed.
- Full unit gate: 1885 tests / 221 suites, zero failures, errors or skipped tests.
- Nine Sunrise writer tests cover actual registry validation/partial persistence, worker/hook
  ordering, days-before-hook ordering, invalid days, immutable day snapshots, accepted saves
  surviving pane-read cancellation, pending-event ordering/coalescing, recreated-pane failure
  feedback and non-replayed failures. Scheduling thread tests use a controlled registry hook;
  they do not measure Android AlarmManager latency.
- Three numeric-step tests cover uneven endpoints, wrapping/normal steps and integer overflow.
  A diagnostic boundary test checks finite public choices without admitting private text or input.
- Thirty-three offline native-runner tests passed and were independently rerun.
- Seven native checks passed on Family Area: Settings entry, Sound & wake overview, original
  Sunrise readback, changed ramp save/readback, Back, Forward with retained value, and original
  ramp restored. Every check returned to the same Settings window. No standalone Settings
  Activity was launched by the embedded navigation sequence.
- Exactly two ramp saves. No alarm enable/time/day/chime/preview/audio action. No Living Area
  or phone operations. Home restored; collectors inactive; source unchanged; candidate retained.
- Exact installed APK, SDK/reference and bundled profile matching verified. Own OAT metadata
  reports speed-profile / install-dm. Signature matches the preceding accepted build. No
  individual-method compilation or frame-rate gain is inferred.

The issue began at 03:05:49 UTC on 2026-10-12. Successful device proof was confirmed at
03:24:03 UTC: 18 minutes 14 seconds, including investigation, implementation, review fixes,
both full builds, bootstrap preparation and native verification. This is one cumulative issue
budget, not a reset follow-up window. The earlier pre-review APK remains locally preserved and
was never installed. All app source was frozen for each build and through its native trial.

## Limits

The Sunrise schema hides its ramp field when the alarm is disabled. The trial did not enable
an alarm just to expose it. Native save-ready feedback publishes only after writer completion
and a real Config.load on IO; Back/Forward repeats that readback. The original ramp was restored
through this native path. Independent remote-schema ramp verification was unavailable and the
postflight explicitly records that limitation. Complete returned schemas and source snapshots
were compared in RAM; hidden fields are not independently audited through that endpoint.

The legacy six-symbol profile-coverage checklist still fails for the optimized layout, as it
already did for the accepted base. Profile decoding and DEX mapping validated; the exact full
installed profiles passed their native comparison. This is not a claim that the obsolete
checklist passed or that every application method is compiled.

These checks establish save correctness and remove synchronous alarm work from input callbacks.
They do not establish tap-to-visible latency, an FPS percentage, universal 60 FPS, website-load
readiness or long-duration stability. The full performance goal remains active.

Candidate APK SHA-256: `511c1285d31d0dfd1b8253a752ae84d8dcece045420a589effc53101a4ef410b`.
Source SHA-256: `e2fbe16fcafb814de1aa310808e87cbf027a95c9c1cd8952decd8b48f93ce0dd`.
Postflight SHA-256: `2366e4307f4e487a18e4903729b31d3fd7008cae0a970881e342255b3bea8d99`.
Evidence: `~/portal-prime/build/fleet/diagnostics/settings-sunrise-writes-2026-10-11`.
