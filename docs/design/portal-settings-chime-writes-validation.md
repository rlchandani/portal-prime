# Sounds worker saves: source-tested, device verification incomplete

ChimeSettingsContent now submits scalar registry edits to a process-owned worker. The Chime
registry continues to own validation, persistence and alarm scheduling. Accepted writes survive
pane departure; readback awaits the writer and reloads the stored snapshot. Cancelled reads do
not mark the pane ready. A resume retains the edit's failure baseline, and obsolete completions
cannot clear a newer save. Numeric rows and their Save actions honor the busy gate. Save feedback
is above the independently scrolling list.

## Evidence

- Debug and optimized builds passed. Full gate: 1,867 tests across 218 suites, with zero failures,
  errors or skips. Six worker tests and five save-state tests cover the new behavior.
- 69 offline native-runner guard checks passed. Exact frozen APK/profile mapping was validated.
- The Family Area trial stopped at the foreign-surface guard during the install/Home-return
  sequence: zero completed installation sequences, zero Sounds checks and zero setting edits.
  Device behavior and any speedup remain **unverified**. This change is not counted as delivered.
- A subsequent read-only check proved that the prior accepted APK remains installed, the browser
  is foreground, no pin/cover cleanup is pending and the collector is inactive.
- The tested candidate is preserved on `codex/portal-chime-writes-pending`; deployment recovery
  must be corrected and native validation completed before it is promoted.
- No Living Area or phone operation was performed; voice selection/playback were not changed.

Frozen source SHA-256: `dc26f3ce1f926998b899e3d7a1112a3986fbfcc39dd957505a68820a19dc5770`.
Candidate APK SHA-256: `8bea72ec84d7974a810045401a5648e2ca00afa0dee000d08df6d59db7938a27`.
Proof root: `~/portal-prime/build/fleet/diagnostics/settings-chime-writes-2026-10-11`.
