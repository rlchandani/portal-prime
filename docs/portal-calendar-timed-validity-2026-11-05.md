# Timed calendar date validation

Delivered optimized **1.195/code189** to both rooms, with complete installed APK byte/hash readback and one install attempt each.

The timed calendar parser previously rolled impossible values into real appointments: February30 became March2, hour24 became the next day, and trailing text was ignored. A malformed EXDATE could then suppress a valid recurring appointment; a malformed DTEND could invent an end time. A small shared guard checks basic syntax, civil date/hour/minute fields in strict UTC and seconds0..60 before the existing timezone conversion. Invalid starts are skipped, invalid exclusions ignored, and invalid ends use the existing zero-duration timed fallback.

Eleven frozen JVM methods improved from **seven failures/four controls to eleven passes**. Eight generated ICS cases through the **actual debug APK parser on Family API29** improved from **five mismatches/three controls to eight passes**, using the same frozen Java/JAR/driver and verified CalendarFeed/Event class origin. The valid UTC, NewYork DST-gap and leap-second controls keep their prior timestamps. Native URL opens remained zero. Host adds minute60, second61 and Asia/Kolkata cases; the sets are not identical.

All **4,688 JVM tests/488 suites**, debug/optimized packaging, R8 and vital checks passed in **100.16 seconds**. Independent source review passed; root rehashed its bindings and independently verified candidate evidence. All23 source assets and the signing certificate match the preceding delivery. Public manifest1.83 remains.

All43 Family/30 Living preference files, file sets and four complete settings/source/layout maps were preserved. Family font, accessibility, original Chromium activity, resumed frame and awake state were preserved; both normal-frame requests passed. Captured diagnostics are scoped samples, not universal crash freedom. Owned native scratch paths and copied registries were removed; owner registry hashes match.

[Generated delivery summary](portal-delivery-summary-1.195.md) and `mocks/portal-calendar-timed-validity-20261105/verification.json` record the acceptance and limitations. Household calendar/provider/network, UI, optimized parser execution, API28 and Living native acceptance remain separate. All-day/UNTIL/unknown-zone/recurrence/cache behavior was not changed.

Original start **2026-11-05 03:17:44 UTC**, discovery cap **03:22:44**, target **03:32:44**, hard limit **03:47:44**. Discovery overran its cap. A baseline filename error stopped native preparation before execution; its scratch was removed and the corrected fixture refrozen before the actual baseline. No clock was reset; previous held scopes and the whole product goal remain open.
