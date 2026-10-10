# Family Area Apps preparation candidate — 10 October 2026

The current scope is the known Filmstrip and Home/Apps performance outliers, with Family Area as the only measurement device. The Filmstrip visibility experiment was withdrawn after its device comparison failed to establish meaningful tail improvement. This independent Apps candidate retains all baseline screensaver assets.

## Change and boundaries

`ApprovedAppsContent` remembers its deterministic ordering, installed/built-in IDs, favorite keys, folder names, filtered apps and assembled tile list. Activation without a data change reuses preparation. Cache keys include the actual inventory/order/assignment/search/Recents/folder inputs and shortcut icons as applicable. These source collections are replaced on publication or edits; no unsafe in-place mutation path was identified in the current producers. Existing tile keys, ordering/filter expressions and atomic inventory publication are preserved. Badges, callbacks, refresh/lifecycle work and time-relative Recents labels stay live.

This is a bounded data-reuse candidate, not a transition rewrite. **The focused Family comparison supports retaining this change; complete transition performance and physical tab-content readiness remain unaccepted.** The retained change reduces median Window p95 to 44.5 ms; the remaining stalls still need attribution. Family's earlier badge-comparison extreme frames more often contained substantial Main animation/draw work than RenderThread commands. Those selected maxima do not attribute the complete p95 distribution. Components overlap and must not be summed.

## Validation and focused comparison

All 1,468 native unit tests freshly executed and passed after the Kotlin change, with zero failures/errors/skips. Debug and nondebuggable benchmark APKs built. After restoring the original Filmstrip asset, 93 relevant slideshow regressions passed; the subsequent required native unit task was UP-TO-DATE and reused that fresh native gate. ZIP comparison against the restored installed baseline finds only `classes3.dex` and the corresponding `assets/dexopt/baseline.prof` changed. All screensaver assets, the manifest and other APK resources match.

| Artifact | SHA-256 |
| --- | --- |
| Restored Family baseline | `0bd5abb3cbe67dca685adf68360c4a1a3f3b311cc28869acd3a9de24b3bb9d97` |
| Apps-only benchmark | `cff6ce8b8f9adff6d8e8c7d82eb2dcd86fcfc0f0fd4b9433f6c82e8ce90e887d` |

The existing Home/Apps protocol is adapted to reject other room names and limit mutations to Home/Apps navigation plus bounded unsampled captures. Twenty-two virtual ownership/response-loss/restoration cases, four warmup cases and eight Family-only/action-boundary rejections pass without device requests. Each physical block uses the same three-action warmup and thirty four-second Home–Apps–Home cycles. Light capture-ownership reads precede actions; full reports and Window checks occur every five cycles and at closure. Configuration/schema and dormant source credentials remain in memory and must match afterward. Command receipt and the retained Window identity do not prove actual tab content readiness, physical touch latency or delivered FPS. Cold/JIT/thermal/background state is uncontrolled; app PSS omits a separate Chromium renderer.

## Family Area result and remaining work

The A1 → B1 → A2 → B2 sequence completed all four thirty-cycle captures: **120 cycles**, four collector closures and exact original configuration/schema/dormant source-credential preservation. Every final known Home Window check passed; all first-draw, callback-drop, invalid and overflow counts were zero. Each installation verified its preserved APK SHA-256. Family retains the Apps-only candidate; Living Area remains unchanged.

| Block | Frames | Mean ms | Window p95 ms | Window p99 ms | Largest ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| Baseline one | 780 | 16.93 | 60.75 | 83.75 | 112.58 |
| Candidate one | 782 | 14.60 | 46.25 | 70.00 | 105.13 |
| Baseline two | 781 | 16.09 | 60.75 | 75.75 | 91.78 |
| Candidate two | 782 | 13.95 | 42.75 | 62.75 | 98.20 |

Across the two rounds, median Window p95 decreased **26.75%** (60.75 → 44.50 ms), median p99 **16.77%** (79.75 → 66.375 ms), and median mean **13.51%** (16.506 → 14.276 ms). P95/p99 and mean improved in both comparisons and met the predeclared advisory thresholds. The fraction of over-budget frames did not improve (median 16.40% → 16.69%); tails still exceed the approximately 16.7 ms display budget. This is a focused observed improvement, not complete performance acceptance or a causal/statistical estimate.

The candidate's worst frames still include 73–80 ms animation-callback duration and about 16–19 ms Main draw time. The back/installer accessibility services do not directly receive Home/Apps content-change events; no evidence supports a per-frame service event storm. A separate short Main attribution capture is the next navigation step, rather than changing bar/pin/recovery behavior on a guess. Filmstrip's rejected visibility experiment and unresolved stalls remain documented separately. No repeated healthy-feature or Living Area benchmarks are scheduled.

Private immutable numeric evidence and preserved builds live under ignored `build/fleet/diagnostics/apps-family-preparation-2026-10-10/`. No Living Area benchmark, deployment, GitHub connection, push or release is performed by this experiment.
