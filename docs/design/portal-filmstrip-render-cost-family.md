# Family Area Filmstrip render-cost diagnostics — 10 October 2026

The scope remains the measured Home/Apps and Filmstrip outliers, using **Family Area only**. Two isolated software candidates fail their visual gates; a single-effect physical diagnostic then completes. The **original Filmstrip and retained Apps improvement remain installed**. No new rendering candidate is accepted. Living Area is untouched and the performance goal remains active.

## Isolated visual candidates

Both prototypes retain the original mode JavaScript and use generated JPEGs, phase-pinned real CSS and offline software Chrome 155 at 1200 x 600. Three independent baseline renders yield 92 exact baseline comparisons before two candidate renders. The probe exercises navigation, both wraps, pause/resume, reversal, pending decode and outgoing dissolution.

| Candidate | Exact candidate comparisons | Largest channel difference | Decision |
| --- | ---: | ---: | --- |
| Move card brightness filtering to the photo child | 2 / 92 | 66 | Reject before deployment |
| Retain backdrop opacity compositing layers | 44 / 92 | 2 | Hold; zero-difference gate fails |

Geometry, resource demand and the exercised timing/lifecycle invariants match, but visible pixels change. The first changes up to 238,699 pixels in a sampled phase, the second up to 149,266. Neither pilot is broadened or deployed and no pixel tolerance is relaxed. Baseline controls are stable. These software results do not establish installed WebView behavior or GPU savings.

## Backdrop blur-cost diagnostic

The diagnostic removes only `blur(24px)` from Filmstrip's backdrop filter. Brightness/saturation, dimensions, cards, shadows, transforms, transitions, JavaScript, native code and performance observer remain identical. It deliberately changes generated-image appearance to isolate cost; it is **not an approved UX replacement**.

The nondebuggable benchmark builds. ZIP comparison verifies that only `assets/screensaver/styles.css` differs from retained APK `cff6ce8b8f9adff6d8e8c7d82eb2dcd86fcfc0f0fd4b9433f6c82e8ce90e887d`. Diagnostic APK `3f6826c47eb6e7519e151f7f0b8eee46ffe36dda8c163dc01d906e86905d40d6` is 37,065,584 bytes. Original source assets are restored after building. Fresh slideshow/resource/lifecycle tests pass 93/93 without failure, skip or cancellation; native unit tests are not freshly rerun for this temporary asset-only diagnostic.

The adapted Family-only protocol passes 68 virtual capture/readiness/foreign-ownership cases, 17 source-restoration cases and five ambiguous-ownership/export cases. All twelve existing generated uniform-geometry JPEGs are freshly verified by device-file size/hash. Original configuration, current-value settings schema and dormant source groups remain in memory.

Three sequential blocks—baseline before, blur removed, restored baseline—each contain one explicit warmup and three timed 40-second entries at a five-second nominal interval. Main stack sampling is disabled. Forty seconds allows eight nominal advance opportunities and no complete twelve-card wrap. Actual advance/order/presentation are not measured; the legacy engine shuffle remains uncontrolled, while uniform geometry bounds card-size variation.

An initial installation stops before any diagnostic capture because foreground ownership is unconfirmed after installation. The exact retained APK is explicitly restored and verified before retry. The corrected helper acknowledges Home after installation, subject to inactive collection and clean pin/cover state. The failed attempt contributes no timing samples. No production navigation/recovery code changes.

| Timed block median | Window mean, ms | p95, ms | p99, ms | Mean commands, ms | Frames/entry | App PSS, KiB |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Baseline before | 31.02 | 54.00 | 127.25 | 14.23 | 689 | 170289 |
| Backdrop blur removed | 29.16 | 47.75 | 95.25 | 13.51 | 715 | 160042 |
| Restored baseline | 28.74 | 55.00 | 119.25 | 13.49 | 700 | 155306 |

Blur removal reduces median p95 by 11.57% against the preceding baseline and 13.18% against the restored baseline. Corresponding p99 reductions are 25.15% and 20.13%; mean command-duration reductions are 5.11% and -0.16%. Negative values indicate a slower diagnostic.

The tail direction is consistent against both surrounding baselines, while mean command duration is not. This supports a backdrop contribution to occasional stalls; it does not establish the blur as the dominant per-frame cost. Preserve the current blur and target the remaining rendering work.

These are short sequential diagnostic observations, not acceptance of blur removal or statistical confidence. Startup is included, frame populations can differ, and cache/JIT/thermal/background state is uncontrolled. Window durations are not delivered FPS, command duration is not isolated GPU time, and app PSS excludes a separate Chromium renderer. Keep both surrounding baseline comparisons when selecting the next rendering candidate.

All twelve collector stops and all three block-level source-restoration checks complete. Final installed APK is the exact retained `cff6ce8b…`, the generated folder is deselected, Home is confirmed, collector/listeners are inactive and pin/cover/cleanup state is clear. No household photos, screenshots, DOM, credentials or configuration values are exported. No Living Area operation or GitHub connection/push/release occurs.

## Evidence

Immutable private evidence lives under ignored `build/fleet/diagnostics/filmstrip-filter-surface-2026-10-10/`, `filmstrip-backdrop-layers-2026-10-10/` and `filmstrip-blur-cost-2026-10-10/`. The last contains corrected `family-preflight-verified.json`, APK difference proof, protocol verification, test proof, deployment/restoration records, separate block captures, final postflight and paired analysis. Incomplete attempts remain separate.

The [retained Home/Apps improvement](portal-apps-preparation-family-performance.md) and [previous attribution](portal-family-outlier-attribution.md) remain valid. Continue with one bounded rendering candidate informed by these observations; healthy-feature sweeps and duplicate Living Area benchmarks stay deferred.

The completed read-only native-layer audit identifies one next experiment: request `View.LAYER_TYPE_HARDWARE` only for an explicitly selected Filmstrip WebView, immediately after existing controller ownership registration. Current hosts are accelerated, but the WebView has no explicit layer request. CSS, JavaScript, dimensions, touch handling, overlay order and lifecycle would stay intact. Existing Android display-list reuse means an additional offscreen layer may provide no benefit or worsen synchronization. One 2160 x 1440 RGBA target is about 11.9 MiB; covered previews and Dream coexistence can multiply residency. Validate the attached layer, both preview/Dream lifecycles, visual/input/overlay behavior, repeated-entry memory and Family-only A/B/A before retaining anything.

Today’s sunrise/sunset formatting reuse is deferred: three attribution observations support a small allocation saving, not an explanation of the larger Home/Apps semantics stalls. No Home UI, locale/time-zone logic, power acknowledgment or accessibility behavior is changed.


The subsequent [native-layer diagnostic](portal-filmstrip-native-layer-family.md) rejects the explicit layer request after Family-only A/B/A. The accepted view flags change, but the paired tail thresholds fail; the retained Apps build is restored. The next delivery window targets direct rendering/navigation implementation rather than more layer-hint experiments.
