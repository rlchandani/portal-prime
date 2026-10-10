# Quiescent release performance validation

## Change and invariant

The baseline is local commit `f25431d`. A normal browser/Home route can dismiss at navigation admission and again when Home consumes its approved-tab Intent. Both admissions remain necessary: an owned Dream or preview can appear between acceptance and delivery.

`PinnedAppFrameHost.release` now skips only its trailing accessibility observation if, before clearing state, there was no pin, overlay/native cover, pending reveal, pending retirement, owned Immortal Dream/preview, or raw system-bar visibility. Lost and expired pins still count as pins; exhausted retirement remains pending. Every release, cancellation and captured-surface cleanup statement still runs. The hidden skipped path publishes unavailable/unselected/uncovered pin-control state.

Normal accessibility events and explicit status requests still read fresh windows. A visible system bar retains observation, including failed launches. `QuickBar.isBarVisible` reads raw volatile visibility independently of settings and current eligibility.

Home continues to clear the pin. A pinned overlay dismissal continues to reveal the existing app window; this change adds no persistent preferred-return app.

## Tests and build

Twenty real-host/routing tests use mocked Android boundaries and cover repeated empty release, actual duplicate Home fallback/Intent handling, retained Home selection, fresh visible-bar eligibility, failed Home launch, hidden eligibility invalidation followed by reveal, live/lost/expired pins, native/overlay covers without a remaining pin, reveal cancellation, pending/exhausted retirement ownership and budgets, closed-host cleanup, owned Dream/preview, intentional-exit ordering and ownership appearing between admissions.

The first targeted command did not run tests: the signing guard's case-insensitive `Release` task-name match also matched the literal test-filter class name `PinnedQuiescentReleaseTest`. The equivalent `PinnedQuiescent*` filter avoided that configuration false match. Against original production, the actual RED run completed 20 tests with six failures, zero errors/skips. The unchanged full suite after the production patch passed 1,214 tests, zero failures/errors/skips; debug and benchmark APK builds passed.

Independent source review found no verified candidate-specific regression. Its limits include mocked Android boundaries, covers without a real photo controller and manually triggered bar observation. It did not independently run Gradle or hardware.

The installed benchmark was verified by SHA-256 on both rooms:
`6e31a62e8f2e7fa1bcb7b20e8168624381aa5f5f4fa912e14e04aa6f5706aec0`.

## Interrupted baseline and workload correction

The initial 16-cycle remote-only baseline was interrupted by a native Immortal `PhotoDreamService`. Family recorded native Dream frames during its no-query phase; Living's query foreground assertion failed after a 2,741 ms action. Those records and the stopped partial capture were retained. Both rooms returned Home and all owned collectors stopped.

App idle sleep was zero, presence was always-on and overnight sleep was disabled. Remote app/Home navigation does not itself inject touch input or reset Android's native idle timer. This remains separate unresolved behavior; this optimization does not alter it.

For a bounded matched comparison, each phase uses three warm-up browser/Home cycles, a real neutral Home-header tap outside capture, return Home, and a 4.2-second settling delay. The delay exceeds the remote pointer's 4-second hide timeout. The earlier touch cohort with only 2.2 seconds was preserved but is not used below. No sleep/screensaver preferences were changed.

Each settled phase then runs 12 browser/Home cycles, 24 accepted actions, with the same 800/600 ms waits. Family runs query first and Living no-query first. Query phases verify package/ownership after every action; no-query phases verify only final Home. Every measured settled phase rejected native Dream frame contamination, completed successfully and stopped its collector with zero listeners.

## Matched results

| Room | Queries | Build | Frames | First / steady | Mean ms | p95 ms | Maximum ms |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |
| Family | Final only | Baseline | 35 | 24 / 11 | 80.10 | 154.00 | 176.85 |
| Family | Final only | Candidate | 24 | 24 / 0 | 85.42 | 153.75 | 155.18 |
| Family | Every action | Baseline | 36 | 24 / 12 | 85.88 | 147.00 | 176.58 |
| Family | Every action | Candidate | 24 | 24 / 0 | 90.43 | 181.75 | 183.74 |
| Living | Final only | Baseline | 35 | 24 / 11 | 57.63 | 129.50 | 135.76 |
| Living | Final only | Candidate | 24 | 24 / 0 | 64.74 | 95.75 | 196.16 |
| Living | Every action | Baseline | 34 | 24 / 10 | 53.89 | 119.25 | 126.40 |
| Living | Every action | Candidate | 25 | 24 / 1 | 49.92 | 100.00 | 104.14 |

Frame mixes differ. The candidate's Family query p95 worsened and Living no-query maximum worsened. This evidence does not establish a consistent visual speedup or delivered FPS.

| Room | Queries | Browser command median ms, baseline → candidate | Home command median ms, baseline → candidate |
| --- | --- | ---: | ---: |
| Family | Final only | 45.45 → 32.55 | 58.62 → 30.66 |
| Family | Every action | 35.73 → 28.27 | 49.29 → 31.69 |
| Living | Final only | 62.58 → 42.91 | 47.81 → 42.47 |
| Living | Every action | 51.47 → 36.80 | 44.88 → 41.67 |

These measure HTTP command acceptance, including network and main-thread admission, rather than touch-to-visible or browser document readiness. All four median pairs improved in this one sequential baseline/candidate run. Thermal/background/browser state was uncontrolled; this is an observed result, not a guarantee.

Inclusive `AccessibilityWindowInfo.getRoot` wall-time observations were 263/1,712 → 168/1,618 in Family no-query, 245/1,713 → 205/1,623 in Family query, and 177/1,852 → 102/1,812 in Living query. Living no-query candidate omitted that symbol from its bounded histogram; this does not mean zero root calls. Every capture reached its symbol limit. These are incomplete sampled stack observations, not CPU time or call counts. Deterministic tests provide the direct proof that the guarded release path makes no accessibility acquisitions.

## Physical regression

Both rooms completed a five-minute soak: 30 cycles, 216 accepted navigation commands, 170 foreground checks and six actual Settings-header entries each. The source-known Settings gear target was `(2059, 61)` at 2160×1440 / density 1.125. Changed window identity and `ImmortalSettingsActivity` frame records confirmed entry; individual detail pages were not inspected.

Full remote-settings schema and screensaver configuration stayed unchanged. Each collector stopped with zero listeners, and each room returned Home with no pin/cover or pending/failed cleanup.

| Room | Host | Frames | Mean ms | p95 ms | Maximum ms |
| --- | --- | ---: | ---: | ---: | ---: |
| Family | Home | 1,681 | 22.91 | 82.75 | 379.96 |
| Living | Home | 1,696 | 21.33 | 68.75 | 351.53 |
| Family | Slideshow preview | 1,372 | 37.73 | 112.50 | 1,566.82 |
| Living | Slideshow preview | 890 | 44.27 | 111.75 | 1,112.00 |
| Family | Settings | 24 | 176.12 | ≥500 | 631.07 |
| Living | Settings | 24 | 147.27 | ≥500 | 541.54 |

Settings p95 overflowed the collector's 500 ms histogram range in both rooms, so only a lower bound is reported. No metric callbacks were dropped; that establishes collector completeness rather than smoothness. This mixed workload includes first draws, uncontrolled slideshow selection and configured animation. The long Settings tails and slideshow outliers remain unresolved. Ending PSS was 126,775 KiB in Family and 156,032 KiB in Living; ending snapshots alone do not establish bounded memory or absence of leaks.

A separate bounded regression began with a neutral Home touch, then completed five Home/Apps/browser/Back/screensaver cycles and 30 destination ownership checks per room. Pin → overlay → actual center tap retained the same Chromium window ID; Home cleared the pin. Hide dispatch was 1,265 µs in Family and 2,380 µs in Living, which does not measure touch-to-visible time.

The physical system-bar check then swiped from the top edge, observed fresh eligible/visible pin controls on Chromium and tapped the actual control at its reported geometry. The pin selected the original browser window on both rooms. An unavailable synthetic package returned HTTP 404 before navigation admission and left the existing pin/cover intact. A center tap revealed the same window. Home cleared the pin; revealing Home's system bar showed fresh unavailable/unselected/hidden pin controls. Hide dispatch in this second test was 2,879 µs in Family and 1,772 µs in Living. Full settings and screensaver configuration stayed unchanged; collectors were inactive.

This physically verifies the system-bar eligibility event path and retained overlay window ownership. Launch failure after admission remains covered by the deterministic real-routing tests, rather than the device unavailable-package check.

## Next measured-work hypothesis

A read-only source trace found that remote app/Home/Apps commands reach `PinnedAppScreensaver.navigation`, retire owned surfaces and launch/select retained state without reporting native user activity. `SleepScheduler` controls Immortal's own alarms, not Android's native Dream timer. Fixing the unused `RemoteApps.launch` helper alone would miss the current routes.

A next candidate could carry an explicit user-interaction acknowledgement into the admitted main-thread action and invoke it once after successful navigation. The existing three-second `ScreenControl.wake` pulse includes `ON_AFTER_RELEASE`; its effectiveness at renewing Portal's native idle timer requires hardware verification. Do not acknowledge unavailable packages, failed launches, cancelled queued commands, read-only polling, MQTT or scheduled scene execution. Home Intent delivery/lifecycle must not acknowledge the same command again.

The meaningful follow-up is a remote-only navigation run beyond several native idle periods, followed by passive polling only. Navigation should renew idle; polling and background automation should permit normal Dream entry. No such candidate is implemented or verified by this report.

## Limits and remaining work

This change preserves both navigation admissions and pin/cover ownership. It does not establish browser document identity/readiness, external Chromium rendering speed, every native Dream transition, all settings detail-page UX, delivered compositor FPS or bounded memory over long uptime.

Native idle during remote-only navigation and long Settings/slideshow frame tails remain unresolved. Evidence stays private and ignored; no screenshots, household photo bytes, UI text or credentials were exported. The broader performance and UX goal remains active.
