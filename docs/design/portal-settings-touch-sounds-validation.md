# Touch sounds: move system work off the input thread

The approved Touch sounds page previously read the Android system setting during composition
and again on resume. Its toggle synchronously checked write permission, wrote the setting,
loaded or unloaded Android's sound-effect cache, and read the flag twice. The audio service can
wait while loading effects, so a lightweight settings page could still block the input thread.

The page now loads permission and sound state on Dispatchers.IO while the pane is active and
RESUMED. Leaving the pane cancels publication of an unfinished resume query. It shows loading
rather than an invented initial switch value. A process-owned IO queue applies the final write
using only the application context; navigation does not discard an accepted edit. The pending
queue retains the latest value per control and has no idle polling.

The ordered writer was extracted from the tested Display queue. Display retains its own queue;
Touch sounds has a separate queue, so slow audio loading cannot hold up brightness or volume.
The switch updates immediately and temporarily disables duplicate input while applying. Completion
reads the actual system state in the background. A failed read-back does not leave the optimistic
value displayed as real state. Permission requests still open the existing Android grant screen.
No preference field, registry schema, audio backend mapping or new library was introduced.

## Local verification

The off-input-thread regression failed before implementation. Two diagnostics regressions also
failed before the public switch observation was implemented. Final debug and non-debuggable,
optimized benchmark builds passed. The full JVM gate passed 1,775 tests in 202 suites, with zero
failures, errors or skips. Six sound-loader/writer tests cover thread placement, read failure,
cancellation, no publication after departure, latest-value delivery during a blocked write and
continuation after a failed write. The existing seven Display writer tests still pass after the
shared queue extraction.

The native driver passed 19 offline tests, including its original nine safety guards. It uses
observed bounds and two stable observations, dispatches at most one change tap and one restoring
tap, and requires exact restoration before accepting the candidate. It does not retry an uncertain
tap. Only the authenticated public system Touch sounds switch exports checked state for the
restoration check; other preferences, editable/password fields, arbitrary text and values do not.
The original system flag stays in runner memory and is not written to the report.

## Performance evidence and limits

Source inspection confirms five synchronous backend operations have left the input callback;
the callback now performs zero system-setting/audio-service operations. Initial/resume service
queries also run on IO. These are code-level counts, not sampled device Binder transactions or a
frame-time improvement percentage. The blocked-provider tests prove the input thread is not the
writer and that the final value is retained.

The exact APK profile maps 8,633 methods and 2,565 classes, including 282 app methods and 75 app
classes. The local mapper's historical required-symbol gate is not used as admission: R8 renames
those symbols. Decoder and DEX mapping passed; the device check compares complete bundled and
reference profile sets and validates the installed artifact. Individual method compilation and
universal 60 FPS are not proven by this functional check.

Android references:

- https://developer.android.com/kotlin/coroutines/coroutines-best-practices#main-safe
- https://developer.android.com/kotlin/coroutines/coroutines-best-practices#create-coroutines-data-layer
- https://android.googlesource.com/platform/frameworks/base/+/4565a47/services/core/java/com/android/server/audio/AudioService.java

The AOSP revision demonstrates synchronous waiting in audio loading; it is not a measured stall
duration on this Portal. Family Area is the only deployment target for this bounded issue.

## Family Area result

Seven native checks passed in 72.67 seconds, including installation and exact
compilation/profile verification. The native system switch changed once, remained changed after
header Back/Forward and pane activation, and was restored to its original value with one restoring
tap. Navigation stayed in the same Settings window. The candidate remains installed; Home was
restored, the collector is inactive and configuration/photo-source snapshots are unchanged.
Living Area and the phone received zero operations. Exact bundled/reference profile sets passed;
individual method compilation is not claimed.

Evidence resides in the local diagnostics directory:
`~/portal-prime/build/fleet/diagnostics/settings-touch-sounds-2026-10-11`.
The postflight SHA-256 is `6ef9a69d49fbfd1a114bd64261e6807b213fb67a8aea55bc2310aa007efb16c7`.
The installed candidate SHA-256 is `b4ee4f3e719f02f9bc75d9a95b07cbde4ff43ab55e55f2dbc5b1080f8ca09d00`.
This issue was completed inside its 30-minute window; the overall performance goal remains open.
