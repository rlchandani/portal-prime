# Snapcast metadata connection ownership — 2026-11-03

A completed metadata exchange left its connected socket open. A reconnect could
replace the stored socket reference, leaving the earlier connection beyond
`stop()`'s reach. Each `connectAndRead` exchange now owns its socket through
Kotlin `use`, closing it after EOF or setup/request/read/callback failure.
The initial JSON-RPC request, metadata parsing, reconnect policy and public stop
behavior remain.

Eight unchanged actual-helper regression methods reproduce **five assertion
failures, zero errors/skips**, then pass. Six use real generated loopback
connections, covering EOF, callback failure, successive exchanges, malformed/
blank messages followed by valid metadata, foreign-group metadata and stop.
Two use strict inert sockets for output/input acquisition, write, flush and
read failures. The full gate passes **4,205 JVM tests / 437 suites**, zero
failures/errors/skips, plus debug/optimized APKs, R8 and vital lint in **2m**.

Actual debug APK private-helper execution on Family Android10 improves
**5/8 → 8/8**: clean EOF, metadata EOF and connected-callback failure now close
the socket and produce peer EOF before fixture cleanup. Five checks retain
the literal request, metadata and terminal behavior. Public stop is exercised
while an actual `BufferedReader.readLine` frame is observed; the existing
SocketException outcome remains. Baseline and candidate each perform four
generated exchanges. Both optimized APKs report **UNAVAILABLE with zero helper
invocations**: R8 removes the port parameter and specializes callback classes.
Generated interface callbacks cannot construct that client without service-owned
callbacks. Optimized installation is verified separately.

Source/test/APK/mapping/Java/compiler/JAR/runner bindings were independently
checked. Four parent processes, eight generated debug exchanges and device
scratch are retired. One ambiguous Java import failed compilation and two
fixture guard reviews were corrected before any device execution; those attempts
remain recorded separately. Acquisition/write/flush/read faults are JVM-only.
Native callback failure is exercised. External runner timeout/failure cleanup,
actual reconnect thread, real Snapcast provider, audio, HA, API28 and native
feature UI remain unverified.

Exact signed optimized **1.112/code106** is installed and fully hashed in
Family Area and Living Area. SHA-256:
`fcdcffe7132878b731eea4139a79ba2ef5c4f578c0f0db2c204c96773db53f4a`
(25,862,702 bytes). Complete settings/profile, screensaver, layout and source
maps match preflight. Living's eight captured preference files are byte-identical;
Family's seven other files are byte-identical. The initial strict postflight
**failed with exit1** because Family's Weather XML changed only `resolved_at`.
Its location values are unchanged. The timestamp advanced605171779ms, exceeding
the unchanged source's seven-day cache TTL604800000ms; this is consistent with
the existing successful cache-refresh path. Provider/write-origin instrumentation
was not performed. Both original snapshots and the failed comparison remain;
the qualified check passes with this explicit runtime-cache exception. No owner
preferences were edited or restored.

Family's current black night clock and date were visually inspected; frame host,
font, services, original Chrome task and awake display remain. Original registry
hashes match and copied credentials are removed. Installed normal authenticated
diagnostics return in both rooms. Recent1500-line samples contain no fatal/ANR
markers; this is a limited observation, not complete runtime-health acceptance.
Living visual and real Snapcast feature acceptance remain open.

Public manifest stays **1.83/code77**; the version-sync guard rejects local1.112.
No phone/HA/GitHub authentication, push or publication. Original independent audit
clock **07:55:54–08:25:54 UTC** includes discovery through commit and cleanup.
The broader goal stays active: **86 tasks, 53 delivered, 23 locally verified,
3 approval, 6 parked, 1 queued**. All85 earlier records, default Weather selection
and other boundaries remain.

Strict documentation and roadmap checks at390×844 and1600×1000 are recorded in
the structured proof, including current and previous delivery selection,
overflow/errors/external requests, served-proof byte equality and owned browser
cleanup. The existing deliverable preview server remains.

Structured proof:
`mocks/portal-widget-gallery-20261102/evidence/snapcast-connection-verification.json`.
