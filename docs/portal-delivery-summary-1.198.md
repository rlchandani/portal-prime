# Recorded delivery 1.198 / code 192

Offline summary of supplied evidence; this is not a fresh device check.

Use the existing single-occurrence fallback when a present recurrence UNTIL is malformed, instead of ignoring or normalizing that end limit.

Recorded UTC: 2026-11-05T04:10:30.694558\+00:00.

Build exit 0 in 108.87 seconds. JVM: 4719 tests across 491 suites; zero failures, errors or skips. Independent review recorded as accepted.

Candidate: 25903483 bytes, SHA-256 `1418818aaabe410cc1e513f3a82c3d6a8cddc0d06121b2af8d1d0e48a47e4136`.

| Room | Recorded version | Candidate bytes/hash | Service ready |
| --- | --- | --- | --- |
| Family Room | 1.198 / 192 | Match | Yes |
| Living Area | 1.198 / 192 | Match | Yes |

Original issue clock (recorded time is not a completion timestamp):

- Start: 2026-11-05T03:57:55Z
- Discovery cap: 2026-11-05T04:02:55Z
- Target: 2026-11-05T04:12:55Z
- Hard limit: 2026-11-05T04:27:55Z

Recorded limitations:

- Acceptance executes actual recurrence expansion with generated rules; one host test also exercises actual full parse. Native helper does not execute full parse, HTTP, provider refresh, cache or rendered calendar.
- Host eleven methods and native eight vectors differ. Host additionally covers minute60/second61, full generated ICS parse, existing DST-gap behavior and leap-second conversion.
- Initial host11/9 result is excluded: its extra failure was an incorrect03:30 spring-gap oracle. The sole control was corrected to existing01:30 before final baseline and production edits; final11/8 failures/3controls are frozen.
- Native uses actual debug APK on Family API29. Optimized helper execution, API28 and Living native are separate; optimized packaging/signing and exact delivery are verified. Explicit comma rejection is source-reviewed without a dedicated frozen vector.
- Only malformed present UNTIL admission/fallback changes. Absent and valid limits retain original end-of-day/UTC/local conversion; existing malformed-rule fallback intentionally remains single original start. No COUNT/EXDATE/window/cache/provider redesign.
- Final corrected baseline overran the five-minute discovery cap; initial reproduction preceded it. Original03:57:55 start/04:12:55 target/04:27:55 hard limit and earlier held scopes remain, without reset.
- No phone, household provider/account/media operation, GitHub authentication/push or public publication. Public manifest1.83 and whole product goal remain.
