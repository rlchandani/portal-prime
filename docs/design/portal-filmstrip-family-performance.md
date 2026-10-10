# Family Area Filmstrip performance experiment — 10 October 2026

The current user scope is **known performance outliers, with Family Area as the only monitoring and benchmark device**. Healthy feature performance sweeps and duplicate Living Area benchmarks are deferred. Filmstrip is first; measured Home/Apps transition delays remain the next known outlier. A validated improvement will later be deployed to Living Area.

The candidate passed its software rendering and functional gates, but its uniform-geometry Family Area comparison did not establish a meaningful device improvement. **The visibility candidate is withdrawn and the existing Filmstrip asset restored.** The experiment remains preserved in commit `837bdf9` and private APKs. This report does not claim delivered FPS, first-photo presentation, or improved device smoothness.

## Rendering change

The withdrawn candidate on `codex/portal-filmstrip-family-performance` retained the existing twelve card slots, card decoration/shadow/filter layers, decoded image resources, geometry, CSS and animation timing. Images beyond a conservative full-display exposure envelope are hidden. Before navigation, the complete unresolved motion union is revealed after decoded preparation succeeds. A narrower resting mask requires two owned native-frame observations of the current computed track endpoint. Pause, supersession, resize and disposal revoke old settlement work; unknown geometry keeps complete image admission.

Whole-card suppression was rejected: independent baseline controls were stable, but forward settled phases produced repeatable software raster differences, including at Portal resolution. Hiding image content while retaining the decorated card paint layers removes those sampled differences. This correction preserves the original paint topology rather than increasing pixel tolerance.

## Verification

The paint-layer regression runs RED against the whole-card implementation: 43 focused tests, 38 passes and five intended failures, without cancellation or skip. The corrected implementation passes all 111 slideshow/resource/lifecycle regressions.

The generated offline browser gate completes three independent baseline renders before two candidate renders in each matrix. JPEG, transparent PNG and transparent WebP portrait/landscape cases include both full wraps, pause with frozen/continuing CSS, rapid reversal, delayed decode and outgoing dissolution. Additional matrices cover both Portal orientations at 2160 x 1440 / 1440 x 2160 and 320 x 200 to 1920 x 1080 expansion during decode and after readiness, including exposure before scheduler recovery. All **844 baseline controls and 844 candidate comparisons** have zero pixel/channel differences and exact geometry. Initial resolution/decode demands are preserved; hidden images are exercised again after settled wraps and interruption. Software Chrome raster evidence is not installed WebView or GPU-performance evidence.

Debug and nondebuggable benchmark APKs build successfully. The native unit task is UP-TO-DATE and reuses the previously verified 1,468-result native gate; those tests were not freshly rerun for this asset-only change. ZIP comparison verifies that the only substantive archive change against the installed baseline is `assets/screensaver/mode-impls.js`; native instrumentation, manifest, profile and other rendering assets match.

| Artifact | SHA-256 |
| --- | --- |
| Installed Family Area baseline | `0bd5abb3cbe67dca685adf68360c4a1a3f3b311cc28869acd3a9de24b3bb9d97` |
| Corrected candidate benchmark | `568a36440ccdefc0b05cd698b603e3ebee015b90f46c746f2071f9ce3841ed0c` |
| Corrected Filmstrip asset | `7f04fa6227902091081c9a0a4dafacdd6e5e5e7644e16c3d4cb12140bceb779d` |

## Focused physical baseline

The Family-only protocol passes 90 synthetic capture, current-value schema, ownership, restoration and privacy scenarios before hardware use. Twelve previously generated JPEGs are reverified by size and hash on Family Area. The protocol temporarily selects that owned folder with Filmstrip, the source shuffle setting disabled and a five-second interval. Inspection later established that the legacy JavaScript engine still unconditionally reshuffles its deck. Actual photo order was not controlled, and the mixed aspect ratios vary card geometry. This block is descriptive evidence rather than a controlled input for candidate acceptance. Each block uses one warmup plus three timed 120-second playbacks, approximately two nominal twelve-card wraps per entry. This is warmed generated-content stress; actual advancement and cold/household performance are not established by nominal cadence.

The baseline block completes all four entries and collector stops, with known Home recovery and exact original configuration/settings-schema/dormant source-credential restoration in memory. Immediate lightweight startup reads overlap native promotion in all three timed entries. Promotion remains an admission marker.

Timed pre-exit Window metrics have median p95 **51.75 ms**, median p99 **113.25 ms** and maximum **152.04 ms**, without histogram overflow, invalid samples or callback drops. These are Window durations, not delivered FPS. The candidate must be compared using the same images, cadence, observer and instrumentation. A new hash-pinned cohort contains twelve distinct generated JPEGs with uniform 2560 x 1706 geometry and near-identical texture/compressed sizes; this controls the geometry workload despite the unchanged engine shuffle. Its Family-only baseline and candidate comparison completed; the result is below. Existing mixed-shape evidence remains immutable. Queued/command components overlap and are not additive CPU or GPU totals. Cache/JIT/background state remains uncontrolled.

## Uniform Family Area comparison and decision

Both blocks completed one warmup and three timed 120-second playbacks, with immediate startup observation, all eight collector stops and exact configuration/schema/dormant source-credential restoration. The generated folder was deselected and Home recovered. Installed APK hashes matched the preserved baseline and candidate. The native culling eligibility and image visibility were not observed, so these results do not establish that the installed WebView admitted the mask.

| Timed aggregate | Baseline | Candidate |
| --- | ---: | ---: |
| Median Window p95 | 53.25 ms | 53.00 ms |
| Median Window p99 | 126.25 ms | 123.75 ms |
| Median mean duration | 29.46 ms | 30.57 ms |
| Largest recorded frame | 194.71 ms | 164.09 ms |
| Median app PSS | 179,118 KiB | 179,424 KiB |

P95 decreased 0.47% and p99 1.98%, while the median mean increased 3.77%. The lower single maximum does not establish smoother playback; neither tail reduction meets the predeclared 10% advisory thresholds. These three-entry sequential cohorts do not control thermal/cache/JIT state or establish statistical confidence. App PSS excludes a separate Chromium renderer. The visibility code and its extra regressions are restored to parent `18f5d973`; the experimental commit, raster evidence and installed-comparison data remain preserved. No Living Area rollout is accepted. Filmstrip stalls remain unresolved, and the next focused measurement is the already prepared Home/Apps data-reuse candidate.

Immutable private numeric evidence and preserved APKs are under ignored `build/fleet/diagnostics/filmstrip-family-integration-2026-10-10/`, `filmstrip-family-comparison-2026-10-10/`, `filmstrip-family-uniform-2026-10-10/` and `filmstrip-culling-browser-2026-10-10/`. Household photos, UI text, DOM, credentials and raw configurations are not exported. The generated input folder remains in app cache and is deselected after each block. No GitHub connection, push or release is used.
