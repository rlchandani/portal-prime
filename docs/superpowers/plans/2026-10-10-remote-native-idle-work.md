# Remote navigation and native idle activity

## Evidence and scope

The installed baseline is 824cebe. Earlier remote-only navigation recorded native PhotoDreamService frames despite app idleSleepMin=0, always-on presence and overnight disabled. Real gestures avoid the interruption. Current source admits remote launch/Home/Apps without native activity; SleepScheduler only controls app alarms. Repeat a five-minute remote-only baseline on both rooms before deployment.

## Design

Use an optional successful-navigation acknowledgement inside the existing admitted main-thread action. Keep cleanup and intentional-exit ordering; no callback in HTTP response handling, Home lifecycle or Intent delivery. Default callbacks are no-ops, so background/MQTT/scene callers preserve power policy. Direct authenticated RemoteRoutes Home/Apps/app/dashboard and direct companion operations explicitly opt in. App lookup rejection, failed launch and cancelled queued commands produce zero acknowledgements; an already-running action may acknowledge once after successful completion even if its worker reports completion uncertainty. Callback failure cannot undo a successful launch.

ScreenControl.noteRemoteNavigation obtains the PowerManager. While interactive, use SCREEN_DIM_WAKE_LOCK with ON_AFTER_RELEASE, acquire with a 1000ms backstop and release immediately in finally. If noninteractive, reuse the existing bounded wake path. No permanent flag, recurring timer, persistence or new setting. The Android API documents ON_AFTER_RELEASE as renewing user activity on release; the device experiment must establish Portal effectiveness.

## Verification

1. Add real entry-point regression tests that compile on original production and fail because no native activity is reported. Cover retained/cold Home, Apps, launch/dashboard success and failure, polling/background no-op, cancelled and started admissions, and cleanup ordering. Main owns all Gradle/device work; delegated writes are limited to one test file.
2. Run RED, implement, then full unit gate plus debug/benchmark builds. Review the actual diff independently.
3. Install the same tested benchmark on both rooms and verify installed hashes. Repeat the identical five-minute sequence with no gestures during capture. Require no native Dream frames during navigation and no destination misses. Then stop commands, continue read-only polling and require natural native Dream entry within a bounded interval derived from the baseline. Preserve failed attempts and always stop collectors/return Home.
4. Run physical pin/cover/tap/Home regression, preserve preferences, record timing tails and limits, commit locally. Keep the complete performance/UX goal active.

## References

[PowerManager ON_AFTER_RELEASE](https://developer.android.com/reference/android/os/PowerManager#ON_AFTER_RELEASE), [WakeLock timeout/release](https://developer.android.com/reference/android/os/PowerManager.WakeLock).

## Result

Implemented and verified through the full 1,240-test gate, both APK builds, installed SHA verification, matched two-room navigation/passive-polling experiments, pin regressions and a same-build reinstall probe. See [the validation report](../../design/portal-remote-native-idle-validation.md) for the failed setup attempt, mixed timing results, hardware scope and remaining work. The full performance/UX goal remains active.
