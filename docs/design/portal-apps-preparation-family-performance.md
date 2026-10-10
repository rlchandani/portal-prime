# Family Area Apps preparation candidate — 10 October 2026

The current scope is the known Filmstrip and Home/Apps performance outliers, with Family Area as the only measurement device. The Filmstrip visibility experiment was withdrawn after its device comparison failed to establish meaningful tail improvement. This independent Apps candidate retains all baseline screensaver assets.

## Change and boundaries

`ApprovedAppsContent` remembers its deterministic ordering, installed/built-in IDs, favorite keys, folder names, filtered apps and assembled tile list. Activation without a data change reuses preparation. Cache keys include the actual inventory/order/assignment/search/Recents/folder inputs and shortcut icons as applicable. These source collections are replaced on publication or edits; no unsafe in-place mutation path was identified in the current producers. Existing tile keys, ordering/filter expressions and atomic inventory publication are preserved. Badges, callbacks, refresh/lifecycle work and time-relative Recents labels stay live.

This is a bounded data-reuse candidate, not a transition rewrite. **Physical performance acceptance is pending.** List preparation has not been identified as the dominant cause of the remaining 57–60 ms Window p95. Family's earlier badge-comparison extreme frames more often contained substantial Main animation/draw work than RenderThread commands. Those selected maxima do not attribute the complete p95 distribution. Components overlap and must not be summed.

## Validation and focused comparison

All 1,468 native unit tests freshly executed and passed after the Kotlin change, with zero failures/errors/skips. Debug and nondebuggable benchmark APKs built. After restoring the original Filmstrip asset, 93 relevant slideshow regressions passed; the subsequent required native unit task was UP-TO-DATE and reused that fresh native gate. ZIP comparison against the restored installed baseline finds only `classes3.dex` and the corresponding `assets/dexopt/baseline.prof` changed. All screensaver assets, the manifest and other APK resources match.

| Artifact | SHA-256 |
| --- | --- |
| Restored Family baseline | `0bd5abb3cbe67dca685adf68360c4a1a3f3b311cc28869acd3a9de24b3bb9d97` |
| Apps-only benchmark | `cff6ce8b8f9adff6d8e8c7d82eb2dcd86fcfc0f0fd4b9433f6c82e8ce90e887d` |

The existing Home/Apps protocol is adapted to reject other room names and limit mutations to Home/Apps navigation plus bounded unsampled captures. Twenty-two virtual ownership/response-loss/restoration cases, four warmup cases and eight Family-only/action-boundary rejections pass without device requests. Each physical block uses the same three-action warmup and thirty four-second Home–Apps–Home cycles. Light capture-ownership reads precede actions; full reports and Window checks occur every five cycles and at closure. Configuration/schema and dormant source credentials remain in memory and must match afterward. Command receipt and the retained Window identity do not prove actual tab content readiness, physical touch latency or delivered FPS. Cold/JIT/thermal/background state is uncontrolled; app PSS omits a separate Chromium renderer.

Private immutable numeric evidence and preserved builds live under ignored `build/fleet/diagnostics/apps-family-preparation-2026-10-10/`. No Living Area benchmark, deployment, GitHub connection, push or release is performed by this experiment.
