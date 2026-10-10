# Gallery retirement and native image-tier validation — 10 October 2026

The latest two-room run completed correctly but still contains long frame stalls. It does not establish a performance improvement from Gallery retirement. Every recorded local image bridge request returned the requested ready derivative tier, so oversized-tier/original fallback did not reproduce in this run.

## Installed candidate and correctness checks

Both rooms ran the same preserved benchmark APK: SHA-256 `762c9645676a8f8abd0c733374c795d5bac0b66b5174c3a146f146f29b62468d`, 37,064,080 bytes. The background WebView preparation experiment remained disabled. The main source commits are `721b7de` and the documentation-only whitespace correction `c53ff61`.

Gallery now retires obsolete images only when their computed opacity is valid numeric zero. Exact-target/property checks, scene ownership, an owned retry after delayed fades, and cancellation of successful retirement work cover event loss, pause/resume and disposal. Generated Node checks passed 68 tests. A fresh isolated software Chrome probe passed 36 baseline/candidate pixel comparisons across generated JPEG, RGBA PNG and WebP fixtures with zero changed pixels and zero channel delta at the sampled phases.

The native probe observes the actual local resolver outcome once per request. It retains four fixed result counters, five requested/delivered cache-tier counters and their fixed 5 × 5 pairing table. It stores no paths, URLs, photo identity, settings values or image bytes. An inactive capture schedules no new Main work. Test-first checks reproduced 15 failures in 17 tests for the initial no-op seam, then two failures in eight checks for missing paired-tier accounting. Integration passed 1,448 native tests with no failures, errors or skips and built debug and benchmark variants. Packaged asset bytes and the new DEX keys were verified before deployment.

These checks establish behavior, resource ownership and sampled generated-image fidelity. Hardware timing remains a separate acceptance boundary.

## Completed two-room 900-second observation

Both rooms reached the exact 900,000 ms capture deadline with Main stack sampling disabled. Each produced nine checkpoints. The collector stopped, the known preview window exited, and the original Home window returned. Screensaver configuration and the settings schema matched their pre-run snapshots, which were held only in memory. Frame callbacks had no recorded drops or invalid samples, and recorded metadata preparation remained off Main.

| Room | Preview frame samples | Steady samples above 16.67 ms | Histogram p95 / p99 | Largest frame | Promotions / distinct styles | Local resolver requests |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Family Area | 24,535 | 14,705 / 24,533 | 32.75 / 50.25 ms | 696.93 ms | 30 / 10 | 177 |
| Living Area | 26,851 | 11,401 / 26,849 | 31.75 / 44.75 ms | 508.43 ms | 30 / 11 | 145 |

All 177 Family requests and 145 Living requests returned the requested ready tier. Family recorded 92 requests at 1200, 70 at 1800 and 15 at 2400; Living recorded 63, 60 and 22 respectively. Each nonzero requested/delivered pair was diagonal. Missing-original, larger-tier-ready and original-fallback counters were zero on both rooms. These configured cache tiers do not measure actual decoded bitmap dimensions, presentation or per-style attribution.

Family's retained 696.93 ms steady frame included 671.25 ms queue delay. A nearby retained 372.12 ms frame included 341.55 ms command submission. Their nearest earlier accepted style report was Ken Burns, after Hero Sidebar, outside the 1.7-second promotion-proximity heuristic. Living's retained 508.43 ms frame included 324.67 ms queue delay and 160.98 ms command submission. Its retained 356.42 ms frame included 349.82 ms command submission. Their nearest earlier accepted report was Memories, after Ken Burns, within that heuristic. These reports locate nearby accepted promotions; they do not identify the originating graphics cause, exact phase or independent stall events.

Bridge-to-Main admission delay reached 118 ms on Family and 75 ms on Living. Family retained 32 of 79 eligible slow samples, including two first draws; Living retained 32 of 106. The retained set is bounded and sorted by duration rather than a complete chronological journal. First draws are separated from steady-frame investigation.

The previous shuffled run used different randomized compositions and content. Its lower or higher timings cannot establish matched-content regression or improvement. Each current device reported fewer than all twelve styles. Window timing components are not summed; command submission is not GPU execution. App PSS excludes the separate Chromium renderer and cannot establish leak-free behavior or immediate GPU-memory release.

## Current next work

Two isolated candidates are being evaluated: relocating Filmstrip brightness to its image child while preserving exact generated pixels, and releasing Ken Burns hidden-slot backgrounds/animation promotion only after the outgoing slot is fully transparent. Neither candidate is installed or performance-accepted at this boundary. They need independent review, integration checks and comparable physical-device validation.

Browser document readiness, complete settings/swipe flows, physical phone acceptance and longer stability remain open. The companion generated-component rerun is pending Pixel unlock; the earlier header failures were diagnosed as mismatched installed test-APK bytecode, and installing matching bytecode alone is not a passing UI result.

For deeper embedded-renderer investigation, Android's [TracingController](https://developer.android.com/reference/android/webkit/TracingController) is available from API 28. [TracingConfig](https://developer.android.com/reference/android/webkit/TracingConfig) provides rendering categories and a continuous ring-buffer mode. Its process-wide WebView scope, asynchronous output and measurement overhead need explicit ownership and bounded handling before use. It does not measure the external Portal browser that owns the dashboard session. No tracing feature was enabled by this run.

Private immutable numeric evidence is under the primary checkout's ignored `build/fleet/diagnostics/slideshow-gallery-image-tiers-2026-10-10/`: deployment verification, nine checkpoints per room, both summaries, build verification and `gallery-tier-soak-analysis.json`. Configuration, credentials and household image data were kept out of exports. The local analysis adapter passed eight aggregate-consistency and scope-rejection checks before analyzing the completed run.
