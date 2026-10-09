# Rendering investigation — 9 October 2026

The verified photo-label fix is committed and installed on both rooms; see
[truthful labels](portal-plus-truthful-photo-labels.md). Broader performance work
remains active. The following experiments are not in the deployed application.

## Filmstrip compositor prototype

Filmstrip animates brightness filters alongside opacity and transform. A throwaway
real Chrome prototype replaced brightness with an animated black overlay only for
owned opaque JPEG fixtures at 2160 × 1440. Selected/unselected screenshots differed
by less than 0.001 mean RGB levels per channel, with edge differences up to 10 levels.
This does not justify applying the overlay to transparent or unknown-format images;
prepared native JPEG derivatives provide a possible safe opaque-source boundary.

Three short desktop transitions produced 56 raster tasks in each variant, totaling
about 0.860 ms versus 0.588 ms. Paint cost was 0.272 ms versus 0.295 ms. These are tiny,
noisy desktop proxy measurements, not evidence of faster Portal rendering. No
production filter/layout change was promoted. A Portal comparison and broader image
coverage are required before choosing a rendering change.

## Source-opacity proof

The subsequent source audit found that Filmstrip does **not** always receive a
prepared JPEG. Native `scaledUrlForSize` returns only ready local derivatives;
`photos.js` falls back to the original URL when preparation is absent, pending,
skipped or unsuccessful. Remote sources bypass this local derivative path. The
photo deck carries dimensions and optional face coordinates, but no alpha or
resolution-provenance flag.

Successful app-owned `EngineScaledCache` output is explicitly JPEG encoded and
published by rename. Original local PNG/WebP/GIF files and remote server responses
have no enforced opacity guarantee. An inferred MIME type is not evidence of alpha
absence. Even the slide and its backdrop may resolve differently because they
request different size tiers.

An overlay experiment therefore needs provenance for the exact URL assigned to
each slide, with unknown/original fallbacks retaining the existing filter. It must
resolve that URL once for assignment and preload. The audit also confirmed that
Filmstrip uses centered CSS cover; its slide does not invoke face-aware background
positioning. Neither the overlay nor a framing change is implemented or deployed
from this evidence. Physical comparison and image-coverage verification remain
required.

## Settings diagnostic draft

A parallel worker implemented a test-APK-only probe for Appearance, Photos &
screensaver, Sound & wake, Home & widgets, Display and App appearance. Integration
added bounded singleTask-aware Home startup, real timestamped Back input, authoritative
Home-role queries and cleanup of the observer/collector while preserving the default
launcher. The command budget is at most 60 seconds, including cleanup.

One default-launcher emulator run passed all 90 assertions: eight sidebar changes,
retained Activity/window/theme/focus, both embedded subpages and Back returns, closing
Settings with Back and Home, unchanged appearance and released resources. It observed
93 host frames with zero callback drops; sidebar text first appeared in 293–490 ms.
The command completed in 13,525 ms. These API 34 software-renderer observations are
not a physical Portal performance comparison or compositor FPS measurement.

Later runs reproducibly failed the first Photos sidebar transition despite reporting
a retained focused Settings host. Input cleanup and Home restoration completed.
Synchronous injected input did not resolve the mismatch. Initial failures also exposed
zero-time Back events rejected by Android as stale, an unbounded creation wait on a
reused singleTask Home and package-visible intent resolution disagreeing with actual
Home-role ownership. Those harness issues were corrected in the draft; the sidebar
failure still requires correlation of live rendering, input delivery and accessibility
bounds. No assertion was relaxed and no success is claimed for those failed runs.

The candidate Kotlin probe and runner are preserved in a private, ignored diagnostic
archive rather than added to the verified test suite. The committed runner is unchanged.
No production Settings navigation was altered. The emulator's temporary Home-role
experiment restored the exact role it held before that experiment; the phone and
household photo content were not touched. Raw logs and owned screenshots stay local
under ignored `build/fleet/diagnostics/` directories.

Next steps: resolve the diagnostic input mismatch, measure physical Settings/browser
navigation, and compare Portal compositor behavior for a validated Filmstrip prototype.
The broad performance/UX objective remains incomplete.
