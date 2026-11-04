# Alarm chimes: fall back after rejected PCM setup

Subsequently delivered in exact optimized **1.167/code161** to both rooms.
See the [separate delivery recovery receipt](portal-room-delivery-2026-11-04.md).
The source iteration below performed no device operations.

Signed **1.167/code161** is verified locally. All **4,565 JVM tests / 475 suites**,
debug and optimized assembly, R8 and vital lint pass. All 23 source assets match
source and the preceding local 1.166 candidate; only generated optimized
baseline.prof differs. The frozen ten-case result improves from eight assertion
failures and two controls to ten passes.
One combined independent review has no blockers; root fully read it, rehashed
all 26 bindings and replayed the complete 475-file test snapshot.

No device operation was performed in this source iteration. At its closure,
both-room deployment remained unverified after the prior connection failures.
The last complete room readbacks and preservation checks then were 1.165/code159
before the prior attempt.

TimerAlarm and AlarmSound previously ignored the accepted sample count from
their fixed-buffer PCM write and the return code from loop setup. A partial
buffer or rejected loop could therefore reach playback and report successful
chime startup.

Both startup methods now require the full sample count and a successful loop
result before playing or publishing their track. A rejected result releases
that newly rejected allocation and returns `false` to the existing ringtone
fallback. No streaming retry, synthesis, cadence, wake-lock, settings or
scheduler behavior changes.

Ten frozen JVM cases invoke the actual private production methods with generated,
inert Android constructors and audio sinks. Each player has short, zero and
negative write cases, a failed loop result, and a complete-success control.
The baseline has eight actual assertion failures and two passing controls.
The fixture checks rejection, no playback/publication, one release, and no loop
setup after an incomplete write; success checks playback, publication and no
early release. Fixture cleanup runs after these assertions.

These checks do not establish audible playback, native AudioTrack behavior,
device/API28/API29 acceptance, or actual ringtone fallback playback. The JVM
stub selects its constructor branch; configured doubles for other builders do
not establish execution of those branches.

The older TimerAlarm exception/stop cleanup hypothesis retains its exhausted
**2026-11-03 08:51:50–09:21:50 UTC** clock. Those paths are unchanged.
This slice handles returned failure statuses only.

Original observation **15:00:49 UTC**, selection **15:19:32**, target **15:15:49**,
hard deadline **15:30:49**, without reset. The target was already missed before
selection. Device connections remain a separate delivery block from the prior
Home greeting candidate. No household media, provider, account or phone operation
is needed for these generated checks.

Evidence: `mocks/portal-alarm-pcm-status-20261104/evidence/`.
