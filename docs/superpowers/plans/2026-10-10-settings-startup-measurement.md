# Next measured slice: Settings startup

## Evidence and hypothesis

Both rooms recorded Settings-frame p95 at least 500 ms in the mixed 824cebe soak. This is an aggregate of first and subsequent draws, not touch-to-visible latency. Source trace identifies `ApprovedSettingsShell` synchronously constructing `approvedSettingsSearchIndex` before the Appearance overview's first draw even with an empty query. The builder walks static page/spec metadata and native field names; it does not read setting values or execute visibility predicates. Its cost on Portal hardware is unmeasured.

## Measure before choosing a change

Keep the native-idle build as the functional baseline after its matched device experiment and pin regression complete. Add a fixed-name, bounded diagnostic observation around index construction only while an explicit performance capture is active. Record durations, invocation counts, entry counts and thread identity; never query text, result titles, credentials or screen content. Keep the inactive path free of measurement work. Unit coverage must test active/inactive and bounded storage semantics.

Use repeated fresh Settings Activity entries through the actual source-known Home gear, with neutral input and no screensaver contamination. Record each capture separately rather than merging startup and later frames into one p95. Separate first Settings entry in the existing process from later fresh-Activity entries. Do not label either as cold process unless a real process restart is measured. Registry reads used for setup can warm static initialization; report that observer effect. Keep timing beyond the 500 ms histogram overflow available through raw maxima or fixed bounded observations.

If index construction occupies only a small part of the slow entry, reject it as the explanation for the large tails and trace layout/draw/compilation work instead. Deferring work is acceptable only if the startup improvement and first-search responsiveness both withstand matched comparison.

## Potential implementation if measurement supports it

Prepare the existing immutable search metadata on first use, retain it for the shell lifetime, preserve result IDs/order and route/prerequisite behavior, and avoid repeatedly building it on each keystroke. Moving preparation to a background dispatcher must not introduce stale results, unbounded jobs, bogus no-results states, registry writes or hidden panes doing background work. First-search latency is a required outcome, not an excluded case.

Use independent write scopes for a focused worker if helpful, while the main agent owns Gradle, both devices and final integration. Verify the actual shared Settings search entry point, not just a duplicate helper.

## Separate compilation hypothesis

Official Android guidance supports bundled Baseline Profiles and sideloaded profile installation on API 28/29 without requiring Play delivery. The current benchmark APK contains `assets/dexopt/baseline.prof` (11,328 bytes) and `baseline.profm` (581 bytes), and its merged manifest includes ProfileInstaller. No app-authored `baseline-prof.txt` was found. Presence in an APK does not establish installation or ART compilation state on either Portal. Debug APK has no corresponding profile assets. These are observed packaging facts, not evidence of a speedup or proof that compilation is responsible for the Settings tails.

Do not reset compilation, force-stop the running fleet agent, add arbitrary shell execution or change provisioning during another device experiment. Any compilation experiment needs a separately verified delivery path and matched measurements of the installed package.

References:
- https://developer.android.com/topic/performance/baselineprofiles/manually-create-measure
- https://developer.android.com/develop/ui/compose/performance/baseline-profiles

## Scope remains open

Settings startup/search, all detail-page transitions, slideshow tail latency, app/browser website readiness, long-uptime memory, sliding gestures and phone/web responsiveness remain within the full performance/UX objective. This slice does not redefine completion.

## Completed initial pilot

The installed native-idle baseline is local commit `0bd99f0`, benchmark SHA `30c255c087acbd820239004ecc5ee9b1cd20600b1123b6b2ef5932a2a5eeee42`. Both rooms completed ten verified Settings entries through the actual source-known Home gear, with changed window identity and ImmortalSettingsActivity frames. Full settings schema and screensaver configuration stayed unchanged. No native Dream host contaminated capture; collectors stopped with zero listeners and rooms returned Home.

| Room | Settings frames | Mean ms | p95 ms | Maximum ms | Queue mean / maximum ms | Layout mean / maximum ms | Observed entry median / maximum ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Family | 39 | 153.77 | ≥500 | 561.63 | 76.64 / 505.45 | 52.68 / 370.49 | 590.45 / 1,074.24 |
| Living | 39 | 140.04 | 468.75 | 572.04 | 70.59 / 453.00 | 47.09 / 370.79 | 479.84 / 1,060.12 |

Observed entry durations begin before the tap request and end after both window and frame observation. They include request/network, metadata query and polling delay; they are not precise physical touch-to-visible values. Each Settings histogram has 28 FIRST_DRAW_FRAME flags and 11 subsequent frames. The flag is not proof of a cold process. Component maxima can belong to different frames and must not be summed into a single alleged critical path.

Main-stack sampling adds overhead. Family recorded 903 samples, 672 native-poll observations and 1,665 skipped cadence slots; Living recorded 965, 769 and 1,646 respectively. Both inclusive maps reached their 256-symbol limit. Settings search-field and icon code appeared in selected application observations; index preparation had no authoritative duration measurement. Missing builder identities in bounded/top-only histograms do not prove zero work. Foreground-reader observations also reflect the test's metadata polling. These pilot values are diagnostic evidence, not an optimization comparison.

Next: exact bounded index-construction timing during explicit performance captures, with main-stack sampling disabled. Measure before selecting deferred search or a different startup change. The overall performance and UX objective remains active.

## Completed direct index measurement

The explicit-capture probe and bounded accumulator passed 30 new tests and the full 1,270-test gate; both APK variants built, and source review found no verified issue. The benchmark hash `524a7c1c0c8dfb1b5df91b8d2613f0bf6fcd0d47b5d5261924d87035126e07df` was verified installed on both rooms. Ten individual Settings entries per room, with stack sampling disabled, recorded one index build of 218 entries per capture. First observed index wall time was 7.895 ms Family / 2.507 ms Living; later medians were 0.452 / 0.383 ms. First maximum Settings frames were 486.835 / 498.416 ms and layout maxima 341.423 / 299.740 ms. Reject the index as the explanation for these tails under this protocol; keep its behavior and investigate initial layout/composition next. See `docs/design/portal-settings-startup-measurement-validation.md` for scope, observer effects and evidence. No configuration changed, no native Dream host entered capture, collectors stopped and both rooms returned to clean Home. The larger goal remains active.
