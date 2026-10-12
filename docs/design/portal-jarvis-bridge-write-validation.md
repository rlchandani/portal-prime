# Portal Jarvis bridge-write worker validation

Jarvis taps previously synchronously committed the screensaver recovery bridge
to SharedPreferences on the UI thread before launching the assistant.
The commit is intentionally durable: an Immortal process restart must preserve
handoff suppression rather than recovering its screensaver over Jarvis.

The write now runs on a single FIFO worker. Main receives completion only after
commit succeeds. Marks and clears share that queue, preventing a delayed mark
from overwriting a newer clear. Existing Meta Calls keeps its synchronous
durability contract. Home resume clears the visible bridge immediately and
queues its durable clear without UI-thread disk work.

Jarvis launches after successful persistence only while its request remains
current and the host remains resumed. Host pause or tab navigation cancels a
pending request; superseded completion retires its pending host without clearing
a newer bridge. A standalone Apps caller finishes only after actual dispatch.
The existing foreground wake gate still requires successful launch and a stopped
covering host before sending Jarvis's wake broadcast.

## Verification

- Full gate: 1856 tests / 216 suites,
  zero failures, errors or skipped tests. Debug and optimized builds passed.
- The initial extracted synchronous queue failed four of twelve focused checks.
  A separate regression verifies superseded completion cleanup.
- Three JVM fixture references were updated for the renamed private tab-state field;
  their existing navigation, pin and remote behavioral assertions were retained.
- 109 offline native-runner checks passed.
- Two native Family checks paused the actual production bridge-write queue,
  using fixed generated diagnostic preferences rather than real recovery state.
  Main had to process a heartbeat while that worker remained paused. Each check
  then verified one successful commit, one callback on Main, persisted readback,
  and deletion of the generated preferences fixture.

| Native check | Main heartbeat while worker paused | Writes | Callbacks | Readback | Fixture deleted |
|---|---:|---:|---:|---|---|
| 1 | 0 ms | 1 | 1 | True | True |
| 2 | 0 ms | 1 | 1 | True | True |

The recorded heartbeat uses truncated integer milliseconds; 0 ms means under
1 ms in these generated checks, rather than zero physical latency.

Candidate retained, Home restored, collector inactive, configuration/source
unchanged; zero setting edits and zero Living Area or phone operations.
Exact APK, SDK and bundled profile mapping verified.

## Limits

This proves worker ownership, Main responsiveness during paused write work and
durable completion ordering on Family Area. It does not measure total Jarvis
launch latency, sustained frame rate or codec performance. The native fixture
does not launch Jarvis or Meta, activate the microphone, send a wake broadcast,
or make any assistant API request. Cancellation and failure behavior is covered
by JVM regressions and integration review; actual voice-session functionality
has not been retested in this pass.

Evidence: `~/portal-prime/build/fleet/diagnostics/bridge-write-worker-2026-10-11`.
Candidate APK SHA-256: `c41b5b5719bfb4afe7a368c47e8daf642af11802d943ab9d32a940590e379640`.
Source inventory SHA-256: `13a9445fdb89764a8cc7f2c2bde233b2c323d42ed1ff3f765810c8a051cba172`.
Postflight SHA-256: `5e0f4ef0a7afb5fee3788ab074fd7fc515a1d9d3e7e3b4521d66a4d47846d1af`.
Recorded at 2026-10-12T01:22:01.161478+00:00.
