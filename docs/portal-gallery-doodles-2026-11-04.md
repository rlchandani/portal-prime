# Gallery Wall gets quiet doodles in empty margins

Gallery Wall now reuses the bundled dark doodle SVG across the outer matte and
under each full contained photo. The existing opaque `#1c1b19` incoming backing
covers the outgoing photo's margins. Photo geometry, selection, fades, lifecycle
and timers stay intact. Native Gallery Print/Spread is a separate renderer.

Independent paired host traces for unset/true/false motion preserve all geometry,
opacity, resources, loads, cursor and timers with five random draws. All twelve
goldens remain unchanged after narrowly validating and normalizing the decoration.
The resource fixture checks the decoded first photo URL and permits only the fixed
bundled second SVG. The opaque-color assertion remains unchanged.

All **4,414 JVM tests / 461 suites**, **372 slideshow checks**, **743 application
JavaScript checks**, debug/optimized builds, R8 and vital lint pass.

The frozen generated Family/API29 native WebView fixture rejects the baseline for
missing decoration. The candidate passes four scenes: single landscape, single
portrait, mixed six, and ten tiles with a differently proportioned same-bucket
replacement. All **72 photo corner pixels** pass; the replacement has **69 doodle
marks in letterbox margins and zero outgoing-photo color pixels**. Unknown requests
are zero, compositor completion passes, and the WebView/package/process retire.
Independent review replays the screenshots and verifies exact fixture DEX/gates.
The initial transparent fixture proposal was corrected before candidate execution;
its earlier preparation is retained privately. No production transparency change
was made. Baseline and candidate use the same corrected frozen gates.

Both rooms contain exact optimized **1.143/code137**, 25,886,547
bytes, SHA-256 `0eeaf66d9b68682d440d4db15cc88d92ed4446cfae83b909d707a53a7aa87a11`. One install per room; Living's
uncertain acknowledgment is reconciled with 6
read-only observations. All **43 Family/30 Living preference files** and four
complete maps match. Family font, services, original Chromium activity, resumed
frame and awake state remain. Saved Shuffle/fit stays; installed support does not
establish the current style. Public release stays **1.83/code77**.

The desktop preview executes the actual renderer with bundled samples and a
simulated clock. Gallery clock overlap observed **09:21:05 UTC** is a separate
placement follow-up retaining its **09:51:05 UTC** hard limit. Native GalleryStage,
actual clock/scheduler/resize/motion/performance/API28/Living visuals remain separate.

Original **09:14:51–09:44:51 UTC November 4** clock retained; target15minutes.
Closure timing and cleanup appear in the structured receipt. Earlier parked scopes,
queue objects, raw suffix and Weather default remain. No phone, source-media
transfer, provider/account operation, push or publication. Whole-product goal active.

Portable generated fixture, images and evidence:
`mocks/portal-gallery-doodles-20261104/`.
