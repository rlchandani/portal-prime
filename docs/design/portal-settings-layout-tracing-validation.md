# Settings traversal: actual-region measurements

## Outcome

Ten fresh Settings entries per room measured the actual shell's executed composition and child measurement. The sidebar is the largest measured child layout region; first-use search composition is also substantial. Each entry recorded one shell composition and one shell measure, so this cohort does not support repeated full-shell traversal caused by version publication or the initial pane effect. This does not prove the footer has zero work or identify every cost outside the measured boundaries.

| Region | Family first ms | Living first ms | Family later per-entry maximum median ms | Living later per-entry maximum median ms |
| --- | ---: | ---: | ---: | ---: |
| Shell composition | 103.973 | 119.310 | 21.037 | 20.151 |
| Search composition | 75.566 | 81.558 | 9.000 | 7.822 |
| Sidebar item composition, largest individual item | 5.694 | 3.492 | 4.168 | 2.559 |
| Detail composition | 7.900 | 10.802 | 4.268 | 4.652 |
| Shell measure | 131.107 | 114.452 | 86.195 | 69.080 |
| Sidebar measure | 94.471 | 82.075 | 58.912 | 51.284 |
| Search measure | 9.029 | 6.160 | 3.481 | 2.987 |
| Detail measure | 10.914 | 12.062 | 7.961 | 7.806 |

Later sidebar maximum ranges were 43.923–89.890 ms Family and 43.431–60.043 ms Living. Each entry composed eight sidebar items. Search composition is nested within shell composition; sidebar item composition can run within lazy-list measurement. These elapsed wall spans overlap and must not be added. They are not CPU, placement, drawing or physical input latency measurements.

First maximum Settings frames remained 503.818 ms Family / 482.230 ms Living. Later per-entry frame maximum medians were 188.994 / 176.648 ms, with ranges 167.950–242.849 / 166.050–229.123 ms. Each room recorded 30 frames and 20 FIRST_DRAW_FRAME flags, zero invalid frame samples and zero callback drops. This is a diagnostic cohort, not an optimization comparison or a cold-process measurement.

## Observer integrity and boundaries

All 360 UI work observations were accepted, with none invalid or discarded; each room retained 180 across ten individual captures. Every entry produced one version publication, pane-effect start and pane-effect end marker. Those markers are assignment/effect boundaries, not visible completion. Canceled effects can lack an end marker.

The fixed-name accumulator retains at most 128 primitive observations per capture and aggregates all valid records. Event aggregates expose null duration values. No query, setting value, content label, credential, screenshot, photo byte or DOM content was exported. Setup schema/config reads can warm domain initialization. Main-stack sampling was disabled, and metadata polling began one second after the tap request completed.

Modifiers are attached only when composition occurs during an explicit active capture. Outside capture, or for event stages, factories return the identical original modifier. The stable observer elements pass constraints, child size and relative placement through unchanged. Nodes can remain until recomposition after capture stop; newly started callbacks then read no clocks and record nothing. A span already in progress may read its finish clock, but its session guard discards observations after stop, replacement or expiry. Starting capture on an existing composed screen can miss observer attachment until recomposition, so this protocol captures before each fresh entry.

Independent review found that the first factory recreated its layout lambda, potentially forcing extra measure invalidation. A focused regression reproduced modifier inequality: one test, one assertion failure, zero errors/skips. The corrected factory caches immutable fixed-stage elements/lambdas retaining no UI, context or capture. Independent review found no remaining verified issue in the correction. The previous built APKs were preserved and never deployed.

## Verification

The original deliberate RED ran 29 tests with 21 assertion failures, zero errors and zero skipped tests. The observer-stability RED is recorded separately. The final full unit gate passed **1,300 tests**, zero failures/errors/skips, and debug plus benchmark builds succeeded. Assertions execute outside optional diagnostic callbacks. Review was source-only; main executed the gates and hardware protocol.

The room fleet helper verified the installed benchmark SHA on both devices:

- Benchmark: `dab673789a6a944ff2066d28c3947341041ac2760533ee1075f1fb1e75edc6b7`
- Debug: `989ec0da5e8f9beed0f6dd5d2e1c123e36caff3e3656c40d3bff4f97eb6c6b29`

Both ten-entry cohorts completed with screensaver configuration and full settings schema unchanged, no native Dream host in capture, inactive collectors with zero listeners, and clean Home ownership afterward. Raw timing evidence, RED/GREEN XMLs, review finding/correction and preserved APKs remain under ignored private `build/fleet/diagnostics/settings-layout-2026-10-09/`.

## Next measured candidate

Replace only the bounded eight/nine-category lazy sidebar with a keyed scrolling Column, preserving category order, advanced gating, dimensions, focus/click actions and accessibility. This is supported by the measured sidebar cost and Android guidance to avoid lazy virtualization for collections of roughly one to ten items. It remains a hypothesis until a matched comparison confirms improvement; moving item composition out of measure is not itself proof of less total work.

Reference: https://developer.android.com/agents/skills/jetpack-compose/jetpack-compose-performance/references/lazy-layouts

First-use search initialization, work outside shell boundaries, slideshow tail latency, browser document readiness, long-uptime memory, gestures and phone/web responsiveness remain open. No delivered-FPS, leak-freedom or whole-objective completion claim follows from this slice.

## Post-cohort navigation regression

Both rooms passed five navigation cycles and 30 foreground checks each, including Apps from slideshow. A pin/cover/tap sequence returned to the identical existing Chromium window in both rooms. Returning Home cleared pin/cover and cleanup ownership settled; configuration stayed unchanged and collectors were inactive. Hide dispatch was8,560 microseconds Family /2,428 microseconds Living, not a visible latency measurement. No browser or photo content was exported.
