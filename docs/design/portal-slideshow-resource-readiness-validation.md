# Slideshow prepared-resource validation

## Problem and implementation

The legacy gallery, collage, scatter, mosaic, diptych and filmstrip paths could resolve a photo URL again after loading and decoding an earlier URL. Native derivative availability can change between those resolutions: readiness for the first resource does not establish readiness for the second.

Commit `03b769445cbde50454799da5c87842ee7cd97138` carries the resolved URL through each preparation and uses that exact resource for its background. Diptych and Filmstrip create the prepared image cells after their matching loads complete. Layouts, photo selection, faces, timing, navigation history, pause and cancellation retain their existing behavior.

This fixes a resource-readiness mismatch. It does not establish first-photo visibility, post-decode cache-eviction safety, or overall slideshow smoothness.

## Automated validation

- The same 33 new regression entries against the original renderer produced 14 failures; 19 already passed.
- The integrated synthetic engine/source/lifecycle suite passed 47 entries with no failures or skips.
- All 14 engine source fixtures passed. These are source fixtures, not device benchmarks.
- The combined Android gate built debug and benchmark APKs successfully and reported 1,362 JVM tests with no failures, errors or skips. Unchanged native tasks can be up-to-date.
- An independent two-file review checked exact resource/photo pairing and the existing lifecycle safeguards.

The benchmark APK SHA-256 was `7e00ae760fb593a64ee409ce6eb3fba3bd8e20a8a6f1427574abcb20aa058a40`. Both rooms' installed APK hashes were verified. The release-faithful benchmark uses the debug package and existing device configuration; this was not a release publication.

## Device entry and recovery

Each room completed 10 warmup and 10 timed fresh-preview entries. All 40 owned collectors stopped; both devices returned to Home with no pending cleanup. The full screensaver configuration and remote settings schema matched their original snapshots in memory.

| Room | First post-install observed WebView construction, ms | Warmed median, ms | Largest timed preview frame, ms |
| --- | ---: | ---: | ---: |
| Family Area | 1191.00 | 14.07 | 404.41 |
| Living Area | 780.17 | 13.68 | 211.89 |

These are overlapping elapsed startup spans, not CPU time or input latency. The first post-install measurement is a single observation per room, not a repeated controlled cold-start study. `enginePageFinished` records HTML completion, not the first visible photo. Fresh preview Activities reuse an existing process for warmed measurements.

## Fifteen-minute playback run

Both 900-second captures completed all nine checkpoints. Capture expiry stopped its collector and listeners; explicit final cleanup and return to Home also succeeded. Original idle/presence settings were retained, and configuration/schema equality was verified afterward. No callback drops or invalid frame/detail samples were observed.

| Room | Steady Window frames | Above 16.67 ms reference | Largest frame, ms | UI-thread waiting in that same frame, ms | App PSS, KiB, first → last |
| --- | ---: | ---: | ---: | ---: | ---: |
| Family Area | 20958 | 72.57% | 408.82 | 357.25 | 143072 → 133604 |
| Living Area | 21257 | 59.42% | 329.26 | 288.28 | 158204 → 146851 |

The worst Family frame arrived around 791 seconds and the worst Living frame around 275 seconds; both were steady frames rather than first draws. Continuing stalls therefore remain after initial startup. The current run does not meet the intended smoothness objective.

The Window measurements do not measure delivered slideshow FPS, physical touch latency or photo advancement. The 16.67 ms reference is not proof of the device's actual presentation rate. App PSS excludes some Chromium renderer memory; a lower final PSS does not prove absence of leaks. Same-frame components can overlap and must not be summed to explain total duration. Receipt elapsed times are not vsync/presentation timestamps.

Shuffle's chosen styles and randomized photo deck were not recorded. Prior runs are not matched causal baselines, so differences cannot be attributed to this patch. The fixed-Filmstrip follow-up controls style but still has a randomized deck.

## Follow-up and acceptance

The exact-image preparation fix is deployed and its entry/recovery checks are verified. Overall performance acceptance remains open. Follow-up includes first-use WebView preparation, profiling continuing UI-thread waiting, renderer-specific comparisons, longer endurance, browser/navigation and phone/web performance.

A separate undeployed Filmstrip opacity prototype passed opaque generated-image browser checks, with documented raster-edge differences. Transparent-image fidelity is still under investigation; that prototype is not included in the deployed build described here.

Numeric evidence is retained privately under `build/fleet/diagnostics/slideshow-resolved-resources-2026-10-10/`, with startup-entry records under `startup-frame-attribution-2026-10-10/`. Source, APK and harness hashes accompany the evidence. No household photo bytes, screenshots, visible UI text, credentials or source-field text were saved in these reports.
