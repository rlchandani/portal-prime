# Current occurrences from old calendar series — 2026-11-03

Old recurring feeds could lose every current event: expansion spent its 366-period
limit from DTSTART, then discarded those historical events outside the display
window. Daily, weekly, weekly BYDAY, monthly and yearly series now seek near the
window's overlap boundary before bounded expansion. Historical generated COUNT
ordinals still precede EXDATE; UNTIL and horizon boundaries remain inclusive.
Multi-day events can retain starts before now while their end still overlaps it.

The original 366 prospective-period limit remains, with at most four additional
attempts for conservative pre-window positioning. Seeking replays the first 366
legacy calendar steps before jumping farther. Monthly/yearly date clamping is
preserved with a bounded Gregorian-phase prefix. This corrects elapsed source
age; it adds no DTSTART-zone redesign, RFC invalid-date policy, DURATION support
or broader provider behavior. Ancient Gregorian-reform dates and arbitrary
historical timezone transitions beyond the replay prefix remain unverified.

Sixteen unchanged actual `CalendarFeed.parse` methods reproduce **12 assertion
failures, zero errors/skips**, then pass. Generated UTC inputs cover decades-old
daily/weekly series, monthly dates from 1990, yearly dates from 1600, COUNT before
EXDATE, sorted BYDAY, expired counts, UNTIL, a 73-hour overlap, exact horizon,
intervals, non-recurring controls and legacy Jan31/Feb29 clamping. Full gates pass
**4,231 JVM tests / 439 suites**, zero failures/errors/skips, plus debug and
optimized APKs, R8 and vital lint in **2m 8s**.

Actual optimized APK parser execution on Family Android 10 improves
**5/15→15/15**: ten age-related checks correct, five controls remain. Each variant
performs 13 parser invocations and checks exact start/end arrays. Twelve generated
UTC feed cases cover the age corrections, finite counts, UNTIL and clamping; one
recent New York device-zone case retains the old Android gap normalization at
07:30–08:30 UTC on 1 November 2025. It verifies baseline compatibility, not a new
timezone policy. The separate host-JDK calendar probe normalizes that gap to a
different hour and is a source control only.

An unused initial fixture compilation is retained with zero executions. One
executed baseline with a host-JDK-specific gap oracle is archived and excluded
from final acceptance; its normal cleanup passed. Only that compatibility oracle
was corrected to the observed old Android result before fresh compilation and
both final runs. Exact canonical OWNER/APK paths, marker bytes and artifact hashes
are guarded. The two final parent processes retire and scratch is removed. Normal
cleanup only; external runner timeout/failure cleanup was not fault-injected.
Native debug-parser, API28, live feed/provider, network and calendar UI acceptance
remain open. Source/test/APK/mapping/Java/compiler/JAR/runner/result bindings and
all 13 final observation groups were independently checked with zero blockers.

Exact signed optimized **1.114/code108** is installed and fully hashed in Family
Area and Living Area. SHA-256:
`28509be865cc279d5d002a3021034ffacb6fcffa9d89970c65820cacb0849df9`
(25,862,702 bytes); signer matches the retained 1.113 rollback. Complete captured
screensaver, settings/profile, layout and source maps match preflight. **Nine named
preference states per room match: Family has nine existing byte-identical files;
Living has six byte-identical files and three unchanged absences.** Strict
postflight passes without exception or owner preference edit/restore. The older
Snapcast receipt retains its separately scoped Weather-cache exception.

Family's current two-portrait frame, clock and Weather appearance was visually
inspected. Font, accessibility, original Chrome task, resumed frame and awake
display remain. Living visual acceptance remains open. Normal diagnostics return
HTTP200 in both rooms; recent samples have 1,644 Family and 2,021 Living output
lines with no fatal/ANR markers. These are limited observations, not full runtime
health or live recurring-feed acceptance. Original registry hashes match; only
copied credentials are removed.

Public manifest remains **1.83/code77** and its guard rejects local 1.114/code108.
No Google phone operation, HA change, GitHub authentication, push or publication.
Original independent audit clock **09:19:42–09:49:42 UTC** includes discovery,
implementation, gates, delivery, documentation, cleanup and commit. Broader goal
stays active: **90 tasks, 55 delivered, 25 locally verified, 3 approval, 6 parked,
1 queued**. All 89 prior task objects and raw bytes remain, except the explicit
snapshot timestamp and current room-version boundary. Default Weather remains.

Strict documentation and two-viewport roadmap acceptance, immutable file review
and served-proof comparison are recorded in the structured proof. Existing design
approvals, publication restrictions and parked issue clocks remain.

Structured proof:
`mocks/portal-widget-gallery-20261102/evidence/calendar-recurring-window-verification.json`.
