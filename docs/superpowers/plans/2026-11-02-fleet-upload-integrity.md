# Fleet upload integrity implementation plan

> **For agentic workers:** Parent owns the request reader, route, gates, versioning and devices. The parallel worker owns the atomic writer and its filesystem tests.

**Goal:** An interrupted upload cannot acknowledge success or damage its destination.

**Spec:** Current Family1.70 reproduction:100 declared bytes,6 received,21-byte original replaced, response `ok:true/bytes:6`.

**Architecture:** The request boundary requires every declared byte. A hidden same-directory staging file is closed before an atomic rename; the original is retained until success. Keep the existing successful JSON response and binary streaming with64KB buffering.

## Constraints and acceptance

- Unit10:37:50–11:07:50UTC, including cleanup; Family primary.
- Only public disposable files; preserve complete profile/screensaver configuration.
- No new preferences, credentials, SDK dependencies or API26-only APIs; minSdk24.
- Failure leaves an existing destination intact, creates no new destination, and removes staging files.
- Complete binary, empty, Unicode-name and concurrent uploads remain correct.
- Incomplete request/failing write produces non-success HTTP status; ordinary success remains200 with exact byte count.
- Source and full local release gates precede Family verification and exact-artifact Living delivery.
- No GitHub authentication/publication; accepted APK signing key remains identical.

## Steps

- [x] Confirm real interrupted overwrite using a named public Family scratch file.
- [x] Observe focused JVM failures on the unchanged request reader and direct-write writer baseline.
- [x] Implement strict EOF/accounting and atomic writer; wire the route and validate focused tests.
- [x] Independent review, complete local gates, optimized signed APK and version sync.
- [x] Family interrupted/complete/empty uploads and cleanup; repeat baseline configuration checks.
- [x] Final exact-artifact Living delivery and identity/config readback if gates pass.
- [x] Commit scoped code/evidence, update roadmap and close within30minutes.
