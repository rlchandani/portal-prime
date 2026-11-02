# World Clock fit — local device delivery

Issue slot: **2026-11-02 06:27:26–06:57:26 UTC**, including restoration.
Branch: `codex/portal-hi-widget-fixes-20261102`, base `13760826`.
This is a bounded correctness/UX delivery within the whole-product goal.
The broader widget-gallery mock remains unapproved.

## Before and after

Family Area's existing 488 × 380 px card showed New York and London's time,
but London's city/day label was below its initial viewport. Time text used
48sp with a 100sp line height.

The existing card now fits complete rows, with compact line spacing and a count
of remaining saved cities. It retains ordered zones, timezone/date formatting,
12/24-hour selection and the active-page minute producer. Only World Clock uses
the shell's bounded body; other widgets retain scrolling. No new timer,
preference, API or background observer was added.

The first candidate fixed normal-size clipping but clipped its footer at 150%
text size, including after a clean launch. It was **superseded**, not promoted
to Living Area. The final budget reserves each line's larger SP-to-DP or explicit
configured-font-scale height and uses that budget for footer admission too.

## Verification and limits

| Gate / case | Observed result |
| --- | --- |
| JVM gate | **3,788 tests / 397 suites**, zero failures/errors; nine new capacity tests. Final clock-suite timestamp 06:48:04 UTC. |
| Builds | Debug and optimized performance APKs passed; R8 and performance vital lint completed. Final combined build: **3m 33s**. Version-sync check passed. |
| Source review | Independent reviewer found no concrete regressions in the final source. This is static review, separate from hardware acceptance. |
| Two cities, normal size | Two complete time/city/day rows, no unnecessary overflow caption. |
| Three cities, normal size | Two complete rows plus “1 more city.” |
| Four cities, normal size | Two complete rows plus “2 more cities.” |
| Three cities, 150% text, live change | One complete row plus an unclipped “2 more cities”; caption bounds **[1643,513]–[1807,549]**. |
| Three cities, 150% text, cold launch | Same complete row/caption result; cropped native screenshot inspected. |
| Restoration | Original New York/London/Tokyo order, Paris unselected, font scale 1.0, Home page 1. All **36** original screensaver fields matched after restoring idle enablement. Grid spans/order untouched. |
| Family artifact/service | Final installed APK hash matched after UI tests; Fleet reported Family Area / 1.66 / code 60. Wi-Fi ADB reconnected. |
| Living delivery | One Fleet install invocation; restart made ACK uncertain. Existing `InstallObserver` reached **ready after six readbacks**, then a separate exact APK/readiness readback matched again. Room identity and Dev Mode preserved. |

The initial combined compilation ran out of compiler memory. Sequential workers
and an in-process compiler with a 4 GiB Gradle heap completed both subsequent
builds; no project-wide memory setting was changed.

Private captures/baselines are in `/private/tmp/portal-world-clock-20261102`;
personal full-screen photos and credentials are not committed.
The inspected crash buffer retained three older package process-crash records,
latest **11-01 10:28:18 device time**; no package process-crash record appeared
after the candidate installations through **11-01 22:54:11**. This limited buffer
check is not universal crash coverage.

**Unverified on hardware:** taller/resized cards, empty or very tiny cards,
24-hour display, other locales/font scales, exact capacity-boundary rounding,
and Living's physical interaction. Larger/empty/invalid capacities are covered
by the pure policy suite. No frame-rate, latency or zero-lag claim is made.
Existing exhausted performance/night/handoff issues remain parked.

## Accepted artifact

- Local iteration **1.66 / versionCode 60**, `com.immortal.launcher.debug`.
- Optimized, nondebuggable APK: **25,860,666 bytes**.
- SHA-256: `c38b048ae8fe3ef1338bc1ac266997276e5fe484444e470a9f6bdf6762c9af9a`.
- Signer SHA-256: `4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6`,
  matching both preserved installed 1.65 baselines.
- Both rooms have this exact artifact. No GitHub authentication, push, tag,
  public release or HA service deployment occurred.
