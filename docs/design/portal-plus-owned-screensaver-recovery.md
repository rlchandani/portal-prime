# Recover only Immortal's screensaver

## Observed failure

Leaving the Portal browser with system Back brought Meta Home forward. Its cold
startup briefly ran `HomeDreamService`, then redirected to the default Immortal
launcher. When the stock dream stopped, Immortal received the global
`ACTION_DREAMING_STOPPED` broadcast and classified the awake screen as `REDREAM`.
It launched `PhotoFramePreviewActivity` over Home even though its own photo dream
had not been interrupted.

This was observed in the connected Portal+'s navigation logs. The unwanted preview
also reproduced in a JVM regression test against the previous implementation.

## Ownership boundary

The global broadcast has no dream-component identity. `DreamPolicy.onDreamingStopped`
therefore observes presence only. It cannot launch a frame or enter the overnight
recovery path.

`PhotoDreamService.onDreamingStopped` calls the separate
`DreamPolicy.onImmortalDreamingStopped` entry point. This lifecycle callback supplies
the ownership evidence for recovery. Both paths reuse the existing dream-stop
classification; the owned path preserves user-tap, screen-sleep, Calls-handoff and
overnight handling.

The change adds no timers, polling, persistent settings or per-frame work. It does
not change the external browser's own Back behavior or Meta Home's bootstrap.

## Verification

- The regression first failed because a generic system-stop handler launched photos.
- Debug and non-debuggable benchmark builds succeeded.
- All 724 JVM tests passed, including six recovery cases: foreign stop, owned wake
  with surrounding global broadcasts, tap exit, Calls handoff, real sleep and disabled
  screensaver.
- The dedicated `dreamRecovery` instrumentation run passed all 60 assertions on the
  connected Android 10 Portal+ using the non-debuggable benchmark APK. It finished
  in 27.76 seconds.
- Generic stop-handler checks with both zero and expired exit/handoff markers
  produced no preview launch during their complete three-second observation windows.
- The configured dashboard opened in the browser. One system Back event returned
  to Immortal Home; no preview launch or transient preview lifecycle event occurred
  during the following twelve seconds.
- A normal OS dream was started through SystemUI's `Somnambulator`. The runner
  verified Immortal's bound service, start broadcast and `Dreaming` power state,
  cleared exit/handoff protection, and injected an accepted OS wake key. The real
  stop callback restored an attached, resumed `PhotoFramePreviewActivity`.
- After finishing that recovered frame, Home remained stable without a second
  preview launch. Cleanup restored the original volatile markers, persisted bridge
  keys and secure screensaver settings, and removed both observers and the monitor.

The runner first settles any pre-existing Immortal dream before recording negative
test baselines. Its temporary exit protection is removed before navigation checks.
It uses public Activity lifecycle callbacks and OS diagnostics instead of private
Android service-field reflection, which is unavailable on this benchmark target.

Device coverage verifies ownership and recovery wiring; real sleep, Calls handoff
and disabled-screensaver suppression are covered by the JVM regression cases.
Overnight-window timing and photo-renderer performance are outside this narrow fix.

Deployed benchmark APK SHA-256:
`978c78a2420ffd1bb4a816b8adbfe75d2771c4b8431f9b9f82f9a7a1397e9af5`.
