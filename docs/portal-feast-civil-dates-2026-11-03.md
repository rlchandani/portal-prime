# Orthodox feast civil dates — 2026-11-03

The Romanian almanac could show a movable feast one day early and omit it on
its mapped date. Bucharest Palm Sunday on March 29, 2037 crossed the spring
clock change relative to Pascha on April 5: elapsed midnight hours divided by
24 produced -6 instead of -7. March 28 incorrectly received the Sunday label.
The same historical error occurs in 2010. A supplied Calendar whose timezone
differs from the default could also lose the intended label.

`FeastDays.forToday` now subtracts Gregorian day-of-year fields within the same
year. All existing names, fixed and movable mappings, precedence, public
signatures and the documented 1900–2099 Easter algorithm remain. This preserves
the existing -48 label mapping; it does not redefine the religious observance
or extend Julian-to-Gregorian support. Settings and household preferences are
unchanged by the calculation.

Ten frozen public-function methods reproduce **seven assertion failures, three
passing controls, zero errors/skips** on the unchanged baseline. They cover
future and historical Palm Sunday, false-early labels, the existing -48 mapping,
differing input and default zones, every ordinary UTC movable label, fixed and
empty fallback, leap-year and time-of-day controls, and caller immutability.
Timezone and all process default Locale categories are restored in finally.
The receipt parser initially assumed an exact assertion class; actual string
assertions are ComparisonFailure subclasses. Only that record assumption was
corrected, with no test or source change and no excluded execution.

The ten methods pass unchanged. Full gates pass **4,248 JVM tests / 441 suites**,
zero failures/errors/skips, plus debug and optimized APKs, R8 and vital lint in
**2m 1s**.

Actual debug APK public-function execution on Family Android 10 improves
**9/16 to 16/16**: seven corrected checks and nine retained controls, with
**13 generated date calls per variant**. All observed caller Calendars remain
unchanged; process timezone and general, display and format Locale defaults
are restored. The fixture uses no Context, household preferences, provider,
service or network. Its Locale category guard was strengthened before the
first compilation or execution; the initial source is retained with zero
executions. Compilation succeeds with three Java 8 obsolescence warnings.

The optimized dated function is inlined and unavailable for direct historical
date invocation; zero optimized dated calls and no extra keep rules are
claimed. API28, live pack rendering and physical feast-date display remain
unverified. Both final debug parent processes retire and owned scratch is
removed. Normal cleanup only; external runner failure and timeout paths remain
unverified.

Exact signed optimized **1.116/code110** is installed and fully hashed in Family
Area and Living Area. SHA-256:
`644d148df8285e92fc70c00a87232a30dfffe05965f52a2e08c2e5dc1666d43b`
(25,862,702 bytes), package `com.immortal.launcher.debug`; signer matches the
retained 1.115 rollback. Both deployment helpers exit zero and request the
screensaver.

Complete captured screensaver, settings/profile, layout and source maps match
preflight. **Ten named preference states per room match: Family has ten existing
byte-identical files; Living has seven byte-identical files and three unchanged
absences.** Strict postflight passes without exception or household preference
edit/restore; room names and Dev Mode remain.

Family's current display was visually inspected: a tan digital night clock
showing 2:19 and the date. Feast pack rendering is unverified. Font, accessibility,
original Chrome task records, resumed PhotoFrame and awake display remain.
Living visual acceptance stays open. Normal diagnostics and logcat requests with
`lines=1500` return HTTP200 in both rooms; actual outputs contain **1,661 Family
and 1,743 Living lines**, with zero fatal/ANR markers. These are limited
observations, not full runtime health, live provider or negative authentication
acceptance. Original registry hashes match; only the owned copied registry was
removed.

Public manifest remains **1.83/code77**; its guard rejects local 1.116/code110
with the expected exit 1. No Google phone
operation, HA change, GitHub authentication, push or publication. Original
independent audit clock **10:07:54–10:37:54 UTC** includes discovery,
implementation, gates, delivery, documentation, cleanup and commit. The broader
goal remains active. **92 tasks, 57 delivered, 25 locally verified, 3 approval, 6 parked, 1 queued**.
All 91 prior task objects and their complete raw suffix remain; only the snapshot
timestamp and current room-version boundary change. Default Weather, design
approvals and publication restrictions remain.

Fresh Presence and MQTT source-only hypotheses share this audit clock; neither
was reproduced or assigned a new slot. Earlier ISS, Aurora, TimerAlarm and
bedtime findings retain their original exhausted or held scopes.

Strict documentation and two-viewport roadmap acceptance, immutable file review
and served-proof comparison are recorded in the structured proof.

Structured proof:
`mocks/portal-widget-gallery-20261102/evidence/feast-civil-dates-verification.json`.
