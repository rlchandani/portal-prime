# Fleet diagnostics: capped output ownership — 2026-11-03

Diagnostic output could reach its byte cap while the child still had more to
write. The helper then waited for child exit with the pipe open, allowing the
writer and Fleet worker to wait for each other. It now closes the selected output
stream before waiting and retires the created process in finally, including
stream acquisition/read/close/wait failures. Fixed commands, returned prefix
caps, UTF-8 decoding and the existing error string surface remain.

Eleven unchanged actual-helper/public-logcat regression methods reproduce
**seven assertion failures, zero errors/skips**, then pass, with four retained
controls. Inert process/stream doubles model the capped-pipe wait, plus resource
retirement and failure paths. Controls retain empty output, UTF-8 byte-boundary
decoding, logcat command/line clamps and start failures. The full gate passed
**4,197 JVM tests / 436 suites**, zero failures/errors/skips,
plus debug/optimized APKs, R8 and vital lint in **2m 16s**.

Actual debug and optimized APK private diagnostics exec on Family Android10
both improve **9/11 → 11/11**, exactly two corrections and nine retained checks.
Generated read-only cat output is6MiB; both the real4MiB cap and1024-byte cap
preserve the exact expected prefix. Each older-build capped call failed to finish
within a protected1.5-second wait, with actual `java.lang.UNIXProcess.waitFor`
stacks and exact owned cat/payload identities. The fixture terminated those
identified children, letting the real helper return its retained prefix. Candidate
calls complete without intervention. Small UTF-8, empty and missing-command
controls pass. Prefix SHA values were independently recomputed. Separately, authenticated
installed `/diag` and five-line `/logcat` requests return expected normal fields
and text in both rooms; rejection and oversized owner-log cases remain open.

R8 maps the helper to FleetFs.u; actual method/source/fixture/compiler/runner/APK
bindings match. Four parents and sixteen generated children are retired and
scratch removed. Java guard and runner retirement improvements were made before
any native execution. Expected javac source/target8 warnings remain; native
compilation succeeded. Native exception injection and external runner failure/
timeout cleanup were not exercised. This is an output-pipe ownership correction,
not a universal command deadline or arbitrary process-tree guarantee.

Exact signed optimized **1.111/code105** is installed and fully hashed in Family
Area and Living Area. SHA-256: `98d593dc92a3d9f57bc003546ae56b4d061deb878e76bcc8a6440f1135de9d37`
(25,862,702 bytes). Complete maps and captured Home/timer/Sunrise/Chime/Weather/
packs/camera preference bytes match originals. Family's current night clock with date
was visually inspected; frame host, font, services, original Chrome task and awake
display remain. Original registry hashes match and copied credentials are removed.
Debug/rollback hashes, source bindings and terminal outcomes are in the proof.

Native testing calls the private helper with generated commands in a separate
shell process. Authentication rejection, real oversized owner logs, API28, native UI
and Living generated-child/visual acceptance remain open. Recent1500-line samples from
each room had no fatal/ANR markers; this does not prove complete runtime health.
The independent keyless Tools scan supplied no stronger selected defect.
Public manifest stays **1.83/code77**; local version-sync mismatch is rejected.
No phone/HA/GitHub auth/push/publication. Original independent audit clock
**07:36:53–08:06:53 UTC** includes all work. The broader goal stays active:
**85 tasks, 52 delivered, 23 locally verified, 3 approval, 6 parked, 1 queued**.
All84 earlier records, default Weather selection and other boundaries remain.

Strict documentation builds without warnings. Roadmap checks at390×844 and
1600×1000 retain all85 records and the default Weather selection, select this
delivery and the previous camera delivery, and report no page errors, external
requests or page overflow. Current fact cells fit; both current screenshots were
visually inspected. The served proof matches the file bytes. Owned browser
contexts closed and the existing deliverable preview server remains.

Structured proof:
`mocks/portal-widget-gallery-20261102/evidence/fleet-diag-process-verification.json`.
