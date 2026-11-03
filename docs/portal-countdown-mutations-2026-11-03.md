# Countdown list mutation integrity — 2026-11-03

Overlapping Add and Remove calls could lose a new countdown or restore a removed
one. Approved Tools performs explicit collection writes on an IO worker and
finishes them after navigation. The standalone Settings editor calls the same
config directly on the UI thread, outside the private Tools storage mutex.
Both public mutations now hold the existing `CountdownConfig` object monitor
through their complete load, modify and save operation.

The change is two synchronized annotations and one boundary comment. Read-only
load, existing identity normalization, saved label/emoji/date fields, array order,
coercion, date validation, preference format and asynchronous `apply()` remain.
This is in-process serialization; it adds no cross-process transaction or new UI.

Seven frozen actual public-config regressions reproduce **four assertion failures,
three passing controls, zero errors/skips**, then pass unchanged. The ordered
interleavings cover Add/Add, Add/Remove, Remove/Add and Remove/Remove. Generated
preference editors have independent staged values. The first apply is paused;
the competing operation completes or blocks at the actual config mutation
boundary. Both workers join and terminate before product assertions. Controls
retain ordinary Add/Remove payloads, saved IDs/order and exact read-only bytes.
Full gates pass **4,238 JVM tests / 440 suites**, zero failures/errors/skips,
plus debug and optimized APKs, R8 and vital lint in **2m 7s**.

Actual optimized APK public CRUD execution on Family Android 10 improves
**4/8 to 8/8**: all four mutation races correct and four controls remain. Each
variant performs **19 CRUD invocations and 11 generated preference applies**;
all four owned executors terminate. Baseline contenders finish before the delayed
first write and expose lost additions or resurrected deletions. Candidate
contenders block at their exact mapped Add/Remove stack frame. Strict generated
in-memory preferences reject unapproved stores or keys. Native sequential CRUD,
JSON coercion and stable read-only legacy identity controls remain. The fixture
writes no household preferences and starts no provider, service or network call.

The final Java fixture tightened monitor admission before its first compilation
or execution; its earlier source is retained separately with zero executions.
Exact scratch ownership, marker bytes, APK paths and SHA-256 are guarded. Both
final parent processes retire and owned scratch is removed. Normal cleanup only;
external runner failure/timeout cleanup and storage fault injection remain open.
Physical UI interleaving, native debug/API28, cross-process or arbitrary raw
preference writers, and asynchronous disk durability are unverified.

Exact signed optimized **1.115/code109** is installed and fully hashed in Family
Area and Living Area. SHA-256:
`60ac84d47756fa97cf9fab0dbcaa9a2eb4beba7931111782c3c8b62fe5066f6a`
(25,862,702 bytes), package `com.immortal.launcher.debug`; signer matches the
retained 1.114 rollback. Both deployment helpers exit zero and request the
screensaver. Complete captured screensaver, settings/profile, layout and source
maps match preflight. **Ten named preference states per room match: Family has
ten existing byte-identical files; Living has seven byte-identical files and
three unchanged absences.** This includes `immortal_countdown`. Strict postflight
passes without exception or household preference edit/restore; room names and
Dev Mode remain.

Family's current display was visually inspected: a large tan digital night clock
and Tuesday November 3 date on black. No current photo or Weather visibility is
claimed. Font, accessibility, original Chrome task records, resumed PhotoFrame
and awake display remain. Living visual acceptance remains open. Normal diagnostics
and recent logcat requests return HTTP200 in both rooms; actual outputs contain
1,607 Family and 1,504 Living lines with no fatal/ANR markers. These are limited
observations, not full runtime health or physical UI race acceptance. Original
registry hashes match; only the owned copied registry was removed.

Public manifest remains **1.83/code77**; its guard rejects local 1.115/code109.
No Google phone operation, HA change, GitHub authentication, push or publication.
Original independent audit clock **09:46:04–10:16:04 UTC** includes discovery,
implementation, gates, delivery, documentation, cleanup and commit. The broader
goal remains active: **91 tasks, 56 delivered, 25 locally verified, 3 approval,
6 parked, 1 queued**. All 90 prior task objects and raw bytes remain; only the
snapshot timestamp and current room-version boundary change. Default Weather
and existing design approvals and publication restrictions remain.

Malformed ISS replacement and invalid Aurora-feed hypotheses were already
recorded under **07:36:53–08:06:53 UTC** and were not reopened. The newly observed
Aurora forecast wording hypothesis shares this audit's original
**09:46:04–10:16:04 UTC** clock; it was not implemented or given another slot.
The earlier TimerAlarm AudioTrack cleanup hypothesis retains its exhausted
**08:51:50–09:21:50 UTC** clock.

Strict documentation and two-viewport roadmap acceptance, immutable file review
and served-proof comparison are recorded in the structured proof.

Structured proof:
`mocks/portal-widget-gallery-20261102/evidence/countdown-mutations-verification.json`.
