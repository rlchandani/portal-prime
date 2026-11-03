# Shared HTTP rejection diagnostic prefix — 2026-11-03

The shared HTTP helper read an entire rejected response before applying its
300-character log limit. A large or non-EOF body therefore consumed unused tails;
a late read fault discarded an available diagnostic prefix. `Http.logFailure`
now decodes into a fixed 300-character buffer and closes its UTF-8 reader after
the displayed prefix. The limit still counts UTF-16 units, including its existing
split-surrogate behavior. Request methods, successful bodies, connection ownership,
Tools cancellation checkpoints and status/error wording remain.

Ten unchanged actual private-helper regression methods reproduce **five assertion
failures, zero errors/skips**, then pass. Generated inert streams cover large,
fragmented multibyte and non-EOF input, unused-tail faults, UTF-16 boundaries,
malformed UTF-8, short/empty/null bodies and immediate read/close failures. Static
Log capture verifies exact diagnostic text. The full gate passes **4,215 JVM tests
/ 438 suites**, zero failures/errors/skips, plus debug and optimized APKs, R8 and
vital lint in **2m 2s**.

Protected actual APK execution on Family Android 10 improves **8/10→10/10 in both
debug and optimized variants**. Two checks correct whole-tail consumption and
reaching a generated tail fault; eight controls remain. The old helper consumes
524,288 bytes and EOF for the generated large case, or 16,384 bytes before a tail
fault. Each candidate consumes 8,192 bytes, returns without EOF/tail fault and closes
the stream once. That observation reflects standard decoder read-ahead; it is
not a universal HTTP raw-byte budget or connection deadline. Native execution
observes stream behavior only; Log text is checked on JVM.

Four final protected parent processes perform 32 helper invocations / 28 non-null
streams and retire normally; scratch is removed. An initial two-parent baseline
with broader Java path guards remains recorded separately and is excluded from
final acceptance. Independent review required exact canonical root, marker line,
APK parent and four allowed filenames before recompilation and the final full
differential. Original clock retained. External runner timeout/failure cleanup,
public HTTP GET/POST, live providers, Tools cancellation, API28 and native rejection
UI remain unverified. Source/test/APK/mapping/Java/compiler/JAR/runner/raw-result
bindings were independently checked with zero blockers.

Exact signed optimized **1.113/code107** is installed and fully hashed in Family
Area and Living Area. SHA-256:
`34706507bc80bf69b88511444df6d02d575975728e5cba63906e8da2c7cda5ca`
(25,862,702 bytes); signer matches the retained 1.112 rollback. Complete captured
settings/profile, screensaver, layout and source maps match preflight. **Eight
named preference states per room match: Family has eight existing byte-identical
files; Living has five byte-identical files and three unchanged absences**.
Strict postflight passes with no exception and no preference edit/restore. This is a scoped current
comparison; the previous Snapcast receipt retains its Weather cache exception.

Family's black night clock/date appearance was visually inspected; frame host,
font, services, original Chrome task and awake display remain. Living visual
acceptance is open. Original registry hashes match and only copied credentials
are removed. Normal installed diagnostics return in both rooms. Logcat requests
with `lines=1500` returned 1,644 output lines in Family and 1,707 in Living, with
no fatal/ANR markers. These are limited observations, not complete
runtime health or live shared HTTP provider acceptance.

Public manifest stays **1.83/code77**; the version-sync guard rejects local 1.113.
No Google phone operation, HA change, GitHub authentication, push or publication.
Original independent audit clock **08:32:00–09:02:00 UTC** includes discovery,
implementation, gates, delivery, documentation, cleanup and commit. Broader goal
stays active: **88 tasks, 54 delivered, 24 locally verified, 3 approval, 6 parked,
1 queued**. All 87 prior task records and raw bytes outside the explicit timestamp,
current version boundary and new record remain; default Weather selection remains.

Strict documentation and two-viewport roadmap acceptance, immutable file review
and final served-proof comparison are recorded in the structured proof. Owned
browser contexts close and the existing deliverable preview server remains.

Structured proof:
`mocks/portal-widget-gallery-20261102/evidence/http-error-body-verification.json`.
