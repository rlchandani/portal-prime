# Family Area outlier attribution — 10 October 2026

The current human scope is the known Home/Apps and Filmstrip outliers, with **Family Area as the only profiling and performance-test device**. Healthy-feature sweeps and duplicate Living Area benchmarks are deferred. Living Area receives accepted changes after Family validation. The performance goal remains active.

## Retained build and navigation capture

Family's installed Apps-only benchmark APK was freshly read and hash-verified as `cff6ce8b8f9adff6d8e8c7d82eb2dcd86fcfc0f0fd4b9433f6c82e8ce90e887d` (37,065,588 bytes). The [two-round unsampled comparison](portal-apps-preparation-family-performance.md) retains deterministic Apps preparation reuse, with median Window p95 60.75 → 44.5 ms. This remains above the approximately 16.7 ms display budget.

A separate bounded capture then performed ten Home → Apps → Home cycles, at four seconds per cycle, with Main stack sampling explicitly enabled and a sixty-second ceiling. The adapted driver passed 22 virtual capture/ownership/restoration cases and eight Family-only/action-boundary rejections before device use. It used 36 light state reads, three full reports and four foreground Window observations. All ten cycles completed, the owned collector stopped, its listeners were inactive, the known Home Window returned, and complete original screensaver configuration, value-bearing settings schema and dormant source credentials matched in memory.

| Main observation | Count |
| --- | ---: |
| Nonempty stacks | 1,644 |
| Literal native-poll leaf | 1,547 |
| Outside native poll | 97 |
| Accessibility-delegate family | 41 |
| Semantics family | 32 |
| Recomposer `performRecompose` family | 17 |
| Composer family | 16 |
| Layout-measure family | 5 |
| Node-coordinate geometry family | 3 |
| Spatial rectangle-manager family | 0 |

Counts are inclusive wall-time stack observations; operation families overlap. They are **not CPU time, invocation counts, delivered FPS or attribution of the complete p95 distribution**. The sampler skipped 2,364 scheduled slots, truncated three stacks and discarded 364 symbol admissions after its bounded symbol capacity. Zero observations do not establish absence between samples. Sampled frame timings are kept separate from the preceding unsampled acceptance comparison.

Within bounded first-app observations, `ScreenControl.noteRemoteNavigation` appeared six times and `Weather.SunTimes.formatted` three times. Source inspection confirms that admitted remote navigation invokes the power helper on Main after retained-tab selection or successful intent dispatch, and before normal completion. A queued request cancelled before admission cannot signal. Once admitted, navigation and its success callback can continue after the caller's wait times out.

A fire-and-forget idle/wake worker would change those ordering guarantees: a subsequent sleep could run before the queued navigation signal, allowing that signal to wake the panel afterward. No power-helper change is made. Compose's current accessibility delegate checks semantics whenever accessibility is enabled; restricting an individual service's event mask does not remove this internal work. Accessibility functionality is retained. The sampled semantics/composition work is a concrete investigation target, not sufficient evidence for disabling accessibility or upgrading the whole UI dependency stack.

## Filmstrip attribution and static-interval probe

The same retained APK received two separate generated-only diagnostic captures. Each uses the existing twelve hash-pinned uniform-geometry JPEGs (8,790,724 bytes total), a forty-second preview observation and a sixty-second Main-sampling ceiling. The first uses a five-second interval; the second uses the existing supported 600-second interval so no scheduled photo change occurs in the observation window. Initial entrance transitions and native overlays still run. Each adapted protocol passes 68 virtual startup/readiness/foreign-capture cases, 17 four-field source-restoration cases and five ambiguous-ownership/export-boundary cases. The sampled snapshot wrapper additionally passes five closure/identity/symbol-boundary cases before first use.

All twelve generated device files were freshly hash-verified before the first capture. The static run reuses those unchanged files and the same installed build; no deployment or generated file mutation occurs between probes. The sampler is stopped by its owned identity, and complete original configuration, current-value schema and dormant source groups are compared in memory and restored after each run. Both finish on the known Home Window, with the previous frame window out of the foreground and the collector inactive. These probes introduce no app or UI code change.

| Diagnostic observation | 5-second interval | 600-second interval |
| --- | ---: | ---: |
| Nonempty Main stacks | 1,925 | 1,615 |
| Outside native-poll leaf | 526 | 103 |
| `HardwareRenderer.nSyncAndDrawFrame` leaf observations | 430 | 65 |
| Preview Window frame samples | 699 | 135 |
| Mean render-command duration, ms | 14.13 | 13.68 |
| Mean total Window duration, ms | 30.96 | 30.78 |

Removing scheduled transitions lowers observed frame activity and native render-sync observations, but per-rendered-frame mean command and total durations remain similar in this short sequential observation. This supports investigating Filmstrip's rendering cost, rather than assuming that reducing transition frequency solves its stalls. The render-sync call can include blocking and scheduling; these samples **do not measure GPU execution time or prove which filter, shadow or backdrop is responsible**. The preceding image-only visibility candidate remains withdrawn.

Startup and exit are included. The animated run's largest preview frame was 151.13 ms at 1.825 s, including 141.45 ms command duration. The static run's largest frame was a 159.12 ms first-draw frame at 0.295 s, dominated by 136.40 ms queued duration. Frame populations differ substantially; the static p95 of 100.75 ms versus animated 59.75 ms is **not** a like-for-like regression or improvement estimate. These are diagnostic measurements, never pooled with unsampled acceptance results. Actual delivered FPS, face/card presentation, photo advance/order, separate Chromium renderer memory and thermal/JIT/cache state are not measured or controlled.

The source wrapper's inherited `warmedGeneratedContentStress` label refers to previously used generated disk content; this probe has zero explicit warmup entries and does not establish a warm WebView process. The preflight records this distinction. Native style admission was observed at 1.627 s and 1.257 s respectively, with capture overlap before the readiness marker in both runs. That marker is not first visible-photo presentation time.

The follow-up [render-cost diagnostics](portal-filmstrip-render-cost-family.md) reject two isolated software candidates and complete a generated-only Family baseline → blur-off diagnostic → restored-baseline comparison. Blur removal lowers frame tails against both surrounding baselines but does not consistently lower mean command duration. The exact retained APK and full original source/settings state are restored. The next bounded trial is a Filmstrip-only native hardware-layer request, preserving CSS/JavaScript and testing its visual, memory and lifecycle costs before acceptance. For navigation, preserve accessibility behavior while reducing unnecessary semantics/composition work. Do not move idle/wake acknowledgment to a fire-and-forget worker, disable accessibility, change household sources, or broaden into already healthy features to produce an easier success.

## Evidence and boundaries

Private immutable evidence lives under ignored `build/fleet/diagnostics/apps-family-attribution-2026-10-10/`, `filmstrip-family-attribution-2026-10-10/` and `filmstrip-family-static-attribution-2026-10-10/`. It includes adapted drivers, virtual proofs, retained-APK/generated-file verification, checkpoints, closure/restoration summaries and bounded code-symbol histograms. No household photo bytes, screenshots, UI text, DOM, credentials or configuration values are exported. No Living Area operation, GitHub connection, push or release is performed.
