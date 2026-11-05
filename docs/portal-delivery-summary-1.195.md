# Recorded delivery 1.195 / code 189

Offline summary of supplied evidence; this is not a fresh device check.

Reject malformed timed calendar starts, ends and exclusions before existing timezone conversion.

Recorded UTC: 2026-11-05T03:29:56.910658\+00:00.

Build exit 0 in 100.16 seconds. JVM: 4688 tests across 488 suites; zero failures, errors or skips. Independent review recorded as accepted.

Candidate: 25903487 bytes, SHA-256 `7b6cedffef60368869fe8810e50c3f628b5805c9a66542bdf0fe0e8fd20c970a`.

| Room | Recorded version | Candidate bytes/hash | Service ready |
| --- | --- | --- | --- |
| Family Room | 1.195 / 189 | Match | Yes |
| Living Area | 1.195 / 189 | Match | Yes |

Original issue clock (recorded time is not a completion timestamp):

- Start: 2026-11-05T03:17:44Z
- Discovery cap: 2026-11-05T03:22:44Z
- Target: 2026-11-05T03:32:44Z
- Hard limit: 2026-11-05T03:47:44Z

Recorded limitations:

- Generated timed DTSTART/DTEND/EXDATE parser acceptance only; no household calendar/provider/network, live event ingestion, UI/pixel or full recurrence acceptance.
- The native eight-case set is a subset of eleven host cases; host adds minute60, second61 and Asia/Kolkata controls.
- Existing all-day, UNTIL, unknown-zone, recurrence, cache, local DST-gap and leap-second conversion behavior is retained; this does not validate every ICS feature.
- Actual debug APK parser executed on Family API29; no optimized parser execution, API28, Living native, phone, account or public publication claim.
- Discovery overran its five-minute cap; original03:17:44 start/03:32:44 target/03:47:44 hard limit retained. A wrong baseline filename stopped native preparation before probe execution; scratch removed, repaired fixture refrozen before actual RED/GREEN. Earlier held clocks and whole active goal remain.
