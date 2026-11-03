# Prayer header: tomorrow's Fajr — 2026-11-03

After the last prayer, the header now computes Fajr for the next local calendar
date. Previously it displayed today's time with a tomorrow label. A cloned
`Calendar` advances one day; the existing calculator supplies that date's times
and UTC offset. Current-day and exact-minute selection, settings and astronomy
remain unchanged. The caller's calendar is preserved.

Twelve unchanged actual prayer/header regression methods reproduce **seven
assertion failures, zero errors/skips**, then pass. Generated New York examples:
spring **04:50 → 05:48**, fall **05:52 → 04:53**. Seasonal, month/year rollover,
pack integration, input preservation, exact-minute and disabled-pack controls are
covered. The full gate passed **4,174 JVM tests / 434 suites**,
zero failures/errors/skips, plus debug/optimized APKs, R8 and vital lint in
**1m 53s**.

Actual debug APK `CalendarPacks.headerLines` on Family Android 10 improves
**16/22 → 22/22**: six corrected next-date headers and sixteen retained checks.
Generated fresh cached coordinates use strict preference reads; owner app data
is not used. Current/next date Fajr references come from the same APK's actual
header at 03:00, after the New York DST transition and before Fajr. This tests
next-date consistency, not independent astronomical accuracy. Expected values
and offset changes were independently checked against frozen JVM evidence.

R8 removes the optimized header's injectable date argument. Actual baseline and
candidate optimized reflection probes record that limitation and invoke zero
headers; no date-controlled optimized native acceptance is claimed. Two initial
unsupported JVM transport-instrumentation attempts are retained and excluded.
Fresh cached-location read triples establish the existing early-return path;
there is no independent transport counter. Normal owned-process/scratch cleanup
passed; failure/timeout cleanup was not fault-injected.

Exact signed optimized **1.109/code103** is installed and fully hashed in Family
Area and Living Area. SHA-256: `4e1811cb3f4b2b25446b2ee91035d09d01e03b4ba0d5f681dc6d04ddb810126c`
(25,862,702 bytes). Complete maps and Home/timer/Sunrise/Chime/Weather/packs
preference bytes match originals. Family's current photo frame with its clock was visually
inspected; frame host, font, services, original Chrome task and awake display
remain. Original registry hashes match and copied credentials are removed.
Debug/rollback hashes, source bindings and process outcomes are in the proof.

The requested-instant offset policy, high-latitude behavior and astronomy remain
outside this fix. Prayer UI/live location, API28, other timezone transitions and
Living native/visual acceptance remain open. Public manifest stays **1.83/code77**;
the local version-sync mismatch is rejected. No phone/HA/GitHub auth/push/publication.
Original independent audit clock **07:01:27–07:31:27 UTC** includes all work.
The broader goal stays active: **83 tasks, 50 delivered, 23 locally verified,
3 approval, 6 parked, 1 queued**. All 82 earlier records, default Weather selection
and other boundaries remain.

Strict documentation build passed. Roadmap checks at 390px and 1600px preserve
the default selection and earlier records, with no page errors or external
requests. The initial unavailable local preview was restored. Mobile status copy
was corrected after visual inspection exposed clipped text inside a fact cell;
final page and fact-cell overflow checks pass. Browser contexts are closed.

Structured proof:
`mocks/portal-widget-gallery-20261102/evidence/prayer-tomorrow-verification.json`.
