# Foreground accessibility window performance validation

## Evidence and bounded change

The previous isolated profiles observed full application-root reads around browser/Home navigation. Repeated `/remote/pin` requests also invoke these reads, so the operator collected separate query and no-query cohorts before changing the reader. Each room completed 16 Chromium/Home cycles per cohort, with three warm-up cycles before each capture. Family ran query first and Living ran no-query first. The query cohort checked destination package after every accepted action; the no-query cohort checked only final Home after stopping its collector. Neither verifies Chromium document readiness or external app rendering.

Even without those diagnostic queries, full-root reads were observed in 336 of 2,170 sampled main-thread observations in Family and 147 of 2,336 in Living. The caller `PinnedAppFrameHost.windowsChanged` appeared in 337 and 147 observations respectively. These inclusive wall-time observations are not CPU time, root-call counts or delivered FPS. Dynamic symbol limits and inspected stack depth can omit frames.

`readPinAppWindow` previously built the complete application-root snapshot before selecting one focused/active app. It now inspects window metadata first, requests only roots needed for the first usable focused candidate or active fallback, and releases every acquired owner. It rereads current state on every call. No task, window or package cache was added.

`PinnedAppFrameHost` retains full reads whenever a pin (including lost/expired), overlay cover, native cover or pending reveal exists. Unguarded observation/status, initial pin selection and expiry's foreground baseline use foreground-only selection. Eligibility still follows selection, so the reader cannot pin an app underneath Home. Release, cancellation, retirement retries and failed-cleanup barriers are unchanged; reveal still checks the full live identities.

The Android reference describes windows as accessibility snapshots, distinguishes input focus from active/touched windows, and permits introspection below accessibility overlays. This matches the separate foreground and pin-validation requirements. The implementation uses API-21-compatible methods; the API-33 root-prefetch overload is unavailable on the target Portals. Reference: [AccessibilityWindowInfo](https://developer.android.com/reference/android/view/accessibility/AccessibilityWindowInfo).

## Tests and compiled cleanup

Twenty new deterministic tests and revised single-selection assertions cover minimal root acquisition, focused precedence over earlier active windows, failed focused/active fallback, no duplicate root retry, skipped/background/non-app roots, stale metadata/package/root failures, copied package ownership, exact node/window release despite exceptions, and fresh rereads. Existing full-reader tests remain intact.

Running the original implementation against the targeted suites produced 39 tests and 24 failures (5 existing revised expectations and 19 new cases), with no errors/skips or compile failure.

The first candidate's full run produced 1,194 tests with three failures. A throwing window recycle escaped through the early-return path. Debug bytecode inspection showed that duplicated finally code for the inlined nonlocal returns lacked the intended local catch entries, although the normal finally path had them. This uninstalled candidate was repaired by moving exception-isolated window recycling into a shared non-inline routine. The unchanged full suite then passed: 1,194 tests, zero failures/errors/skips. Debug and benchmark builds passed. Final debug bytecode confirms the helper's RuntimeException handler; independent source review reported no verified regression.

## Device validation

Both rooms received the same signed benchmark candidate with installed SHA-256 verification: `078f8bee70b29e4bb17559a7910c0f26dcad7c7725b2007dc49924ae2bc16cc3`.

The first candidate profile attempt failed during setup before any collector session began; the initial script asserted Home after a fixed 600 ms delay. Both attempts stopped cleanly and returned Home. The fixture was changed to bounded Home readiness outside the measured workload; failed records were retained under their original names. The warmed workload and per-action timing/check behavior remained unchanged. The successful retry used three warm-up cycles per phase just like the baseline. Fresh-install/startup readiness remains separate unresolved work.

| Room | Cohort | Status queries | Frames | First / steady | Mean ms | p95 ms | Maximum ms |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |
| Family | Baseline | Every action | 47 | 32 / 15 | 87.26 | 144.25 | 154.36 |
| Family | Candidate | Every action | 32 | 32 / 0 | 95.85 | 155.25 | 173.48 |
| Family | Baseline | Final only | 48 | 32 / 16 | 87.36 | 148.75 | 168.97 |
| Family | Candidate | Final only | 32 | 32 / 0 | 85.36 | 146.75 | 161.93 |
| Living | Baseline | Every action | 48 | 32 / 16 | 54.45 | 91.25 | 93.10 |
| Living | Candidate | Every action | 32 | 32 / 0 | 61.72 | 92.00 | 98.83 |
| Living | Baseline | Final only | 45 | 32 / 13 | 58.40 | 90.75 | 106.88 |
| Living | Candidate | Final only | 32 | 32 / 0 | 69.27 | 130.00 | 146.15 |

All four successful candidate phases completed 16 cycles / 32 accepted actions each, with unchanged screensaver configuration, final Home ownership and inactive collectors. There were 32 foreground checks during each query capture; no-query captures verified only the final destination. The identical workload had a different frame mix, and thermal/background/browser state was not controlled. The timing distributions do not show a consistent Home-return speedup. Removing unnecessary root requests is proven by deterministic tests, not by these frame-duration distributions.

In no-query captures, `AccessibilityWindowInfo.getRoot` was observed in 334/2,170 baseline versus 297/2,229 candidate observations in Family, and 142/2,336 versus 193/2,358 in Living. These inclusive wall-time observations are mixed too. Remaining foreground-root requests can still wait on Android; no consistent wall-time reduction, device launch-latency gain or delivered-FPS gain is claimed.

Both rooms then completed a five-minute soak: 30 cycles, 216 accepted navigation commands, 170 foreground checks (including initial/final ownership and Settings checks), and six actual Settings-header entries each. The gear target was the source-known `(2059, 61)` at 2160×1440 / density 1.125. Window identity changes and `ImmortalSettingsActivity` frame records confirmed entry; the test did not inspect each detail page or screenshot. Full remote settings schema and screensaver response remained unchanged. Both collectors stopped with zero listeners and both rooms returned Home without pin/cover or pending/failed cleanup.

| Room | Host | Frames | Mean ms | p95 ms | Maximum ms |
| --- | --- | ---: | ---: | ---: | ---: |
| Family | Home | 1,677 | 22.69 | 78.50 | 459.46 |
| Living | Home | 1,682 | 20.49 | 71.50 | 480.44 |
| Family | Slideshow preview | 1,410 | 34.71 | 99.00 | 412.79 |
| Living | Slideshow preview | 859 | 42.58 | 111.25 | 1,146.00 |
| Family | Settings | 24 | 145.15 | 449.00 | 462.22 |
| Living | Settings | 24 | 148.03 | 434.75 | 473.15 |

No dropped frame-metric callbacks were recorded; that measures collector completeness, not visual smoothness. This mixed cohort includes first draws, uncontrolled slideshow selection and configuration-dependent animation. The long Settings tails and Living preview outlier remain unresolved. Ending app PSS was 109,319 KiB in Family and 145,780 KiB in Living; isolated ending memory snapshots do not establish a leak-free or bounded-memory result.

The final physical regression completed five further Home/Apps/Chromium/Back/screensaver cycles and 30 foreground checks per room. Pin → overlay screensaver → actual center tap preserved the Chromium window ID; going Home cleared the pin. Hide dispatch measured 3,158 microseconds in Family and 2,757 in Living; these are dispatch timings, not touch-to-visible latency. Both rooms finished Home with no pin/cover, no pending/failed cleanup and inactive collectors. Screensaver configuration remained unchanged. This proves retained window ownership for the tested overlay path, not browser document identity/readiness or every native Dream transition.

## Next measured-work hypothesis

Home navigation admits dismissal in `PinnedAppScreensaver.navigation`, then a paused/cold Home fallback can receive an approved-tab Intent whose `consumeLauncherTabIntent` dismisses again. Each currently calls host release and a trailing foreground-root read even without pin or owned surface. The second admission must remain because ownership can change between acceptance and Intent delivery.

A separately reviewed bounded candidate is a host-local skip of only the trailing release observation when all of these were false/empty before clearing state: pin (including lost/expired), overlay/native cover, pending reveal, pending/failed retirement, owned Immortal Dream/preview, and the raw system-bar visibility. Keep every release/cleanup statement; publish unselected/uncovered/unavailable pin-control state on the skipped hidden-bar path, and retain fresh reads for a visible bar and its failed-launch path. Normal accessibility-event observation and explicit status queries stay unchanged. This is a hypothesis, not implemented or verified here.

Meaningful next tests should exercise the actual duplicate fallback/Intent path, ownership appearing between admissions, all lifetime/retirement guards, visible-bar launch denial, hidden-bar failure followed by reveal, and intentional-exit ordering. First prove fewer dismissal-triggered window/root acquisitions, then compare matched transition cohorts. Do not add an already-dismissed Intent flag or cache stale eligibility/identity to bypass fresh state.

All diagnostic artifacts remain private and ignored. No screenshots, household media, UI text or credential values are exported. The broad performance goal remains active.
