# Pinned app screensaver implementation

Spec: [preserved-session design](../specs/2026-10-09-pinned-app-screensaver.md).

- [x] RED/GREEN: live window metadata ownership and session identity transitions.
- [x] Add in-memory host, single nonfocusable frame overlay and safe lost-session UI.
- [x] Add pin/unpin control to revealed top bar; keep Home navigation an explicit exit.
- [x] Connect owned Dream dismissal/recovery and remote screensaver/navigation.
- [x] Relevant and full JVM tests, debug/benchmark build, runtime regression checks.
- [x] Independent code review and address concrete findings.
- [x] Deploy signed candidate and test both rooms with generated fixtures, including
      actual Dream/continuation behavior and repeated wake timing.
- [x] Stop fixtures/resources, preserve evidence and commit finalized changes locally.

No GitHub authentication or push. No persisted source settings/layout changes.
No household photo capture or browser content export.

Validation: [final candidate and scope](../../design/portal-plus-pinned-session-validation.md).
