# Framed Print animation lifetime — Portal validation

## Contract

Both Portal+ devices use Shuffle. Framed Print is one of the retained styles. Keep its
2.4-second crossfade and 22-second alternating breathe effect, but hold the invisible slot's
animation timeline and release its explicit transform-promotion hint. Resume that same timeline
when the slot is reused. Retirement must not stop a slot that has become visible again.

The browser scheduler can pause/play animations through Web Animations APIs. Declaration-only
checks are insufficient: API-controlled animations can keep running while computed CSS says
`animation-play-state: paused`. The actual animation object/state/time must be checked too. The final scheduler leaves CSS
animation ownership with CSS classes while retaining API suspension for transitions and
programmatic animations. This also preserves ordinary outgoing-scene freeze.

## Initial hardware observations

Family Area and Living Area are Portal+, Android 10, 2160 × 1440. Both use the legacy WebView
Shuffle renderer. Thirty-second idle Home phases produced zero or one observed Window frame;
that is insufficient to characterize Home's frame-time distribution. Slideshow samples varied
substantially between cohorts as their styles rotated. UI-thread samples were predominantly
native polling, with rendering command issuance contributing more mean frame time than layout
or draw. This does not isolate a GPU bottleneck or a particular style as the cause.

A separate bounded baseline forced Framed Print with a five-second interval for 90 seconds per
room. It used each device's existing library and exported no photo bytes or visible content.
The complete original screensaver response matched after restoration, and capture cleanup
left no active collector or listeners. This is a repeat-transition stress condition; photo
choices, thermal state and background work are uncontrolled. Window FrameMetrics measures
host frame duration, not delivered compositor FPS or time until a browser document is usable.

## Reproducible source checks

```sh
node --test scripts/tests/slideshow_framed_lifecycle.test.mjs
node scripts/verify_slideshow_engine.cjs
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleBenchmark --no-daemon
```

The engine fixture stubs the bundled readiness logger's `console.info` method. Its earlier
missing-method failure was a fixture error, not an application exception.

## Generated-media browser checks

The private local Chromium fixtures load the actual bundled CSS and JavaScript through a
route whitelist. Their media consists only of generated SVG shapes. The checks cover:

- Repeated internal crossfades: visible motion is running; fully hidden motion is paused and
  `will-change` is `auto`; slot dimensions and effect duration remain intact.
- Timeline identity: seed a zoom-out iteration, retire the slot, verify its actual animation
  time stays fixed while hidden, then reuse the same object and verify zoom-out continues.
- Actual bundled scheduler pause/resume and outgoing-scene freeze: verify actual motion stops
  for user pause, hidden retirement and outgoing promotion, retains identity through reuse,
  and leaves no delayed DOM mutations on stop. CSS motion stays under class-based ownership;
  the source fixture separately verifies API suspension of transitions/programmatic motion.
- All twelve styles: readiness, bridge controls, bounded composition and configured dwell.

These are desktop Chromium rendering/lifecycle checks, not Portal FPS measurements. They
complement the deterministic source fixtures and the on-hardware navigation/resource checks.

## Final build, hardware observations and outcome

The debug/benchmark builds and Android unit gate passed. The JVM inputs were unchanged, so
Gradle reused the successful 1,126-test report (zero failures/errors/skips). Fresh source checks
passed: six owned lifecycle regressions and fourteen engine/ownership fixtures. All twelve
styles passed the generated browser smoke. Actual Chromium checks verified timeline identity,
zoom-out reuse, hidden time suspension, user pause and outgoing-scene freeze after reuse.
Independent final static review found no remaining actionable defects in this patch.

Both rooms received the same signed benchmark APK, with installed APK SHA-256 verification:
`8d156f0f792994235f907a2938a51a1e757dd70849fdb188e509bceef7a91584`.

| Room | Cohort | Frames | Mean ms | p95 ms | Maximum ms |
| --- | --- | ---: | ---: | ---: | ---: |
| Family Area | Initial baseline | 5,373 | 24.30 | 32.75 | 98.07 |
| Family Area | Initial candidate | 5,384 | 26.01 | 32.50 | 86.75 |
| Living Area | Initial baseline | 5,390 | 25.66 | 32.50 | 67.96 |
| Living Area | Initial candidate | 5,385 | 26.76 | 32.25 | 81.68 |

The one-bin p95 changes are not a meaningful performance improvement. Mean duration was
higher in this initial candidate cohort. Both captures had zero callback drops. To check the
warm-baseline/fresh-install imbalance, run another baseline/candidate pair with matched
process restarts. Photo choice and thermal state remain uncontrolled; do not infer causality
or delivered FPS from these aggregate Window timings.

The deployed build completed a five-minute, 30-cycle navigation run per room: 270 accepted
actions (240 navigation commands plus 30 empty-text checks), 180 verified foreground-window
checks, zero failures and zero callback drops. All original screensaver settings matched;
collectors stopped with zero listeners. The sampled mixed-navigation p95 values were 73.00 ms
(Home) / 75.75 ms (preview) on Family Area and 64.00 / 71.25 ms on Living Area. These tails
remain above the quality target; this pass proves regression checks, not completion of the
broader performance goal. Browser document readiness was not measured by this run.

The matched-process-restart repeat completed on both rooms with settings restored and
collectors inactive. Each cohort ran the same 90-second, five-second-interval workload:

| Room | Cohort | Frames | Mean ms | p95 ms | Maximum ms |
| --- | --- | ---: | ---: | ---: | ---: |
| Family Area | Restarted baseline | 5,379 | 24.81 | 32.25 | 103.07 |
| Family Area | Restarted candidate | 5,382 | 24.85 | 32.25 | 92.18 |
| Living Area | Restarted baseline | 5,383 | 25.84 | 32.00 | 86.23 |
| Living Area | Restarted candidate | 5,371 | 26.63 | 32.25 | 91.78 |

The mean changed by +0.04 ms on Family and +0.80 ms on Living; p95 was unchanged or one
histogram bin higher. The initial and repeated results do not establish a frame-time speedup,
or isolate the cause of the higher means. Keep this change for the verified motion-lifetime
correctness; broader rendering and transition costs remain unresolved. Neither aggregate
host timing nor fewer running animations proves delivered FPS or reduced GPU memory.

The original signed control build was used temporarily for that repeat. Both rooms were
then restored to the final candidate with installed APK SHA verification. The final check
completed another five navigation cycles and thirty foreground checks per room. Pin →
screensaver cover → center tap preserved the exact target browser Window ID on both rooms;
this round checks window identity, not independent browser-document retention. Both rooms
finished Home, unpinned, with no owned cover, no pending/failed cleanup and no active collector
or listeners. Original screensaver settings matched after every hardware cohort and smoke.

## Next measured scope

A separate read-only callgraph audit found a retained-preview path through QuickBar →
AppSwitcher → external app. It bypasses the navigation retirement used by Home/remote routes.
The preview has no reversible host suspension; its controller stops on destruction. This
establishes scheduled-work exposure while retained, not continued GPU drawing. Treat that
lifecycle integration as separate work, preserving document/photo identity and user-pause
state and distinguishing owned Immortal Dream covers from Meta navigation.
