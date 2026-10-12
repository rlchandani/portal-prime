# Current-weather request sharing validation

Home weather text, detailed current conditions and slideshow weather independently requested the same Open-Meteo current-condition URL. Weather.fetch and fetchCurrent now share a typed temperature/weather-code result through a worker cache. The full URL keys include coordinates and temperature unit. Up to four successful results are retained for 30 seconds using a monotonic clock; each key shares one active FutureTask. Loading occurs outside the map lock, unrelated keys proceed independently, and failed/invalid payloads are not retained. City is read fresh per detailed-current caller. Location re-detection detaches current cache entries and prevents older requests from republishing over newer entries.

## Scope and original clock

Original start 2026-10-12T10:23:31Z, deadline 10:53:31Z, no reset. Initial widget/app inspection, implementation, tests/review corrections, builds, native trial, commit and cleanup all count. Family Area only; parked candidates stay parked. Home/selectors, other app source, forecast/sun/location resolution algorithms and browser JS/core/CSS remain unchanged. No new persistent setting or UI controls. Existing callers stay on IO/executor workers.

## Work and correctness evidence

The independent-reader reference performs eight loads for eight concurrent readers and fails six of the original ten cache tests. The candidate suite now has fourteen passing tests: request sharing, brief warm reuse/expiry, completion-based expiry, independent keys, failure recovery, clear during active work, bounded least-recently-used successes, backwards-clock handling, invalid bounds, key isolation, the production Weather URL, production temperature/code parsing, invalid production payload recovery and interrupted-waiter behavior. The same eight-reader workload performs one loader invocation (-87.5%). This is a controlled loader-count result; actual Portal weather HTTP counts and device launch latency are not measured.

Independent review caught that FutureTask.get clears the interrupt flag of a waiting reader. A dedicated pre-fix test fails on that exact flag while the owner still completes. The correction restores/rethrows InterruptedException without detaching the shared owner. The full corrected suite passes, and final read-only review confirms the finding resolved.

All 118 browser tests and 77 offline helper checks pass. Frozen source before full build: 619d5a320e97580ffc7d75dd024dd2645fc2be8301a2dffd36fdc4aa0eacc9ee / 862 files.

## Full Android and Family Area evidence

Full Android gate passes: 1908 tests / 223 suites, zero failures/errors/skips. The corrected fourteen-case cache/Weather suite reports eight readers and one loader invocation. All 118 browser tests and 77 offline guards pass; final read-only review has no remaining blocker.

The single bounded Family trial passed and retained the candidate. Two signed installations, bootstrap then candidate. Installed candidate SHA-256 3494c23c16c19c2a7a5e77ac3f93e6e6937d1713905b34f1482734c52030d543; bootstrap 2f48050c9540f2ea8ee53f561c6d53210e3b82af82ea1f82bbbfb3c4f13c3bf9. Both APKs are 25447090 bytes. The optimized candidate DEX differs from the accepted version and retains the new SingleFlightValueCache class; its R8 mapping also retains Weather and the cache class.

Exact SDK reference/bundled profiles match the complete inventory: 8667 methods, 2576 classes, 285 app methods, 75 app classes. Individual app-method compilation is not proven. Exact served JS/core/CSS remain unchanged at 152124/19089/22706 bytes. Home -> Apps -> Home passes three destination checks, final Home ready, collector inactive, configuration/source snapshots unchanged, no rollback. Living Area/phone/settings/note/audio/GitHub operations zero.

The native trial verifies deployment, navigation and preservation. Actual weather-service traffic/counts, availability and Portal cold launch latency remain unmeasured; the claimed -87.5% reduction is the controlled concurrent-reader loader workload. Validation, local commit and ledger cleanup remain within the original clock.
