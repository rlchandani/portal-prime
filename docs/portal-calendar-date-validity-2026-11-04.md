# Calendar feeds: reject impossible all-day dates

Exact optimized **1.165/code159** is installed in both rooms, one install each,
with full APK readback SHA256
`f9cfee038106e6e487b99f8904b54fe2235efd895d236fd575686746b53b5717`
(25,886,927 bytes). Living's uncertain acknowledgment was resolved by seven
read-only observations. All 43 Family and 30 Living preference files and four
complete maps remain exact. Family font/accessibility, original Chromium activity,
resumed frame and awake state are retained. Saved Shuffle/fit remains; captured
fatal/ANR markers are zero.

An all-day `DTSTART;VALUE=DATE:20230230` previously became March 2.
The same invalid value in `EXDATE` could hide a real March 2 appointment.
The shared date-only parser now requires eight ASCII digits and validates the
date without leniency in UTC before retaining its existing local conversion.
Invalid starts are omitted; invalid exclusions are ignored; invalid ends use
the existing one-day fallback.

Only six production parser lines change. Timed values, source timezones,
local-midnight normalization, recurrence, caching, settings and callers remain.
This deliberately preserves the existing behavior of valid dates with a
midnight daylight-saving gap. It does not change timed or `UNTIL` validation.

The final frozen 14-case fixture improves **ten actual parser assertion failures
and four passing controls to fourteen passes**. It covers invalid February/day/
month values, suffixes, neighboring valid events, exclusions, end fallback,
valid leap and bare dates, midnight gaps and timed daylight-saving gaps.
An initial midnight-gap end expectation incorrectly assumed next-day midnight;
the corrected control retains the existing normalized next-day 01:00.
That initial run is excluded from the paired result.

All **4,555 JVM tests / 474 suites**, debug and optimized assembly, R8 and vital
lint pass. Both signed 1.165/code159 artifacts have the expected debug package
and signer; all 23 source assets remain exact against source and 1.164.
Only compiler-generated optimized baseline.prof differs; baseline.profm and
all other asset payloads remain exact. One combined independent review has no
blockers; root fully read it and rehashed all 26 bindings before promotion.

Generated feed text only: no live calendar/provider/source, mounted calendar UI,
native parser, optimized runtime or API28 acceptance is claimed.
Public1.83/code77, phone restrictions and existing parked issue clocks remain.

Original discovery **14:45:30 UTC**, selection **14:50:39**, target **15:00:30**,
hard deadline **15:15:30**, without reset. Evidence is retained in
`mocks/portal-calendar-date-validity-20261104/evidence/`.
