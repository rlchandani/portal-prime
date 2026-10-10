# Fleet polling performance

Goal: reduce repeated PackageManager work in fleet/mobile metadata reads while
preserving dynamic status and authoritative installer verification. The broader
rendering, settings, browser readiness, direct-input and fleet objective stays active.

Baseline is pinned-session commit `c00377b`. Measurement rejected the initial
full-installed-package scan because its cold reads were slower. Both rooms were
restored to the verified baseline while the replacement was built.

Final design: query only the union of catalog names requested in this process.
Capture a catalog list once per response; record immutable coverage for known
absence. Register an application-context package receiver before reads, invalidate
without scanning, share cold flights and reject invalidated generations. Verify
actual returned coverage and age under five seconds; conditional expiry cannot
discard a newer list. Unexpected query/registration failure uses uncached fallback.
Keep dynamic fields and installer decisions/readback live. Invalidate before owned
completion/reconciliation and late terminal callbacks.

- [x] Reproduce expiry and scoped coverage/query regressions before implementation.
- [x] Integrate only metadata consumers and owned-operation invalidation.
- [x] Full JVM gate: 1,126 tests, zero failures/errors/skips; all APK variants built.
- [x] Independent code review and consistent catalog-list capture.
- [x] Ten-minute pre-change navigation baseline: 60 cycles per room, zero failures.
- [x] Detect and reject full-scan cold-read regression; restore baseline devices.
- [x] Native scoped install/update/uninstall with generated no-code fixture;
      requested metadata, normal test pairing and cleanup verified.
- [x] Install scoped benchmark in both rooms with installed SHA-256 verified.
- [x] Compare same-cadence first-after-idle/shared/repeated reads and exact payloads.
      Two scoped cohorts: repeated app-list replies improved; cold results mixed,
      with Family cold `/info` about seven milliseconds above its baseline.
- [x] Final five-cycle navigation and pinned-window cover/tap smoke in each room.
- [x] Confirm final cleanup and preserve evidence.

Validation gate passed. Create the local commit as the final repository action.

Owned emulator API 34 compatibility profile uses documented changes 135549675 and
255038118, reset after testing; it does not establish default API 34 support.
Generated package fixtures stayed on the owned emulator; no household media was
exported. No GitHub authentication or push.

[Validation ledger](../../design/portal-fleet-poll-performance-validation.md) records
rejected iteration, final artifact hashes, native timing and remaining scope. HTTP
latency, Window FrameMetrics, browser readiness and delivered FPS are distinct.
