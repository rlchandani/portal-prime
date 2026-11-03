# All-day calendar ends across DST — 2026-11-03

Date-only events now end at the exclusive local date boundary, including 23-hour
and 25-hour days. Missing `DTEND` means one calendar day. Explicit date-only spans
are retained as calendar days and applied to each recurrence. Prepared definitions
retain their private timezone/locale; timed events continue using elapsed durations.
No `DURATION`, recurrence start-zone or broader RFC redesign is included.

Twelve unchanged actual-parser/window regression methods reproduce **nine assertion
failures, zero errors/skips**, then pass. Controls retain explicit nonrecurring
47/49-hour spans, ordinary/UTC dates and timed 23/25-hour durations. The full gate
passed **4,162 JVM tests / 433 suites**, zero failures/errors/skips,
plus debug/optimized APKs, R8 and vital lint in **1m 53s**.

The actual optimized parser on Family Android 10 improves **7/15→15/15**, exactly
eight corrected checks and seven retained controls. Eleven generated endpoint
observation groups and elapsed differences were independently recomputed. Fixture
APK/source/JAR bindings and process exits1→0 were verified. R8 inlines the window
function: native acceptance covers the parser; display-window edges are JVM-only.
Generated New York/UTC dates and process-local timezone changes do not write app
preferences, contact a feed or establish calendar UI/async refresh acceptance.

Independent review strengthened the private harness owner/path guards and process
records. The final guarded rerun matches the first comparison exactly; the first
run remains private. Both runs retired owned processes and removed scratch. Normal
cleanup passed; timeout/failure cleanup was not fault-injected.

Exact signed optimized **1.108/code102** is installed and fully hashed in Family
Area and Living Area. SHA-256: `efb0aa0e5ff47002ae24fd1a5a58999d641bbe2fc9c9a4bf86347c8909411615`
(25,862,702 bytes). Complete maps and Home/timer/Sunrise/Chime preference bytes
match originals. Family current night clock was visually inspected; frame host,
font, services, original Chrome task and awake display remain. Original registry
hashes match and copied credentials are removed. Debug/rollback hashes/signers,
source bindings and cleanup are retained in the structured proof.

Public manifest remains **1.83/code77**; local version-sync mismatch is rejected.
No phone/HA/GitHub auth/push/publication. API28, other timezone/calendar transitions
and Living native/visual acceptance remain open. Original independent audit clock
**06:48:28–07:18:28 UTC** includes all work. The broader goal stays active: **82 tasks,
49 delivered, 23 locally verified, 3 approval, 6 parked, 1 queued**. All 81 earlier
records, default Weather selection and other boundaries remain.

Structured proof:
`mocks/portal-widget-gallery-20261102/evidence/calendar-all-day-dst-verification.json`.
