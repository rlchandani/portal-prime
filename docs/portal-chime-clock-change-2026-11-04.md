# Ambient chimes follow clock changes

The existing protected clock-event receiver rearmed regular alarms and the sunrise
wake light but omitted ambient chimes. In a running app, a wall-clock correction or
change to a quarter-hour timezone could leave the pending hourly cue on the wrong
local minute until another scheduling event.

The receiver now calls the existing ChimeScheduler through the same dispatcher.
Each scheduler runs independently; regular alarm rearming still retains snoozes.
Settings, cue timing policy, audio, quiet hours, provider ownership and the
screensaver renderer are unchanged.

Six frozen JVM cases invoke the actual receiver/config/scheduler through doubled
Android storage and alarm-delivery boundaries. Five expected assertion failures
and one control on unchanged code become six passing cases. Full4607 tests across
480 suites, debug/optimized builds, R8 and vital lint pass.

A generated Android10 probe invokes the actual debug APK dispatcher with generated
callbacks. Its six frozen cases change from five missing-chime failures and one
control to six passes, including scheduler failures and a later event after failure.
Both owned probe directories were removed. This does not establish real protected
broadcast reception, native AlarmManager delivery, audible cues, golden-hour
provider behavior, installed optimized helper execution or API28 acceptance.

Independent review accepted41 bindings and23 unchanged source assets, rehashed by
root before delivery. Exact optimized1.180/code174 is fully read back in both rooms,
with one install each. Living's uncertain acknowledgment resolved through six
read-only observations without replay. All43 Family and30 Living preference files
and four complete maps per room remain exact. Family font/accessibility/original
Chrome/resumed frame/awake state remain; captured fatal/ANR markers are zero.
Copied credentials were retired and owner registries/public release manifest remain.

Original22:11:32UTC start, missed22:26:32 target and22:41:32 hard stop are retained.
Native black-handoff and all held scopes remain separate; the whole-product goal
is active. Evidence: `mocks/portal-chime-time-change-20261104/evidence/verification.json`.
