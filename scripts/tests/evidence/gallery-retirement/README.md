# Gallery Wall invisible-image retirement evidence

Base: `227d2ffe0fb8baefebbf1670686731827927966c`.
Branch: `codex/portal-gallery-retirement`.
Checkout: `~/.codex/worktrees/portal-gallery-retirement/portal-prime`.

Only Gallery Wall's render/swap replacement lifecycle in
`app/src/main/assets/screensaver/mode-impls.js` changes product behavior.
CSS, scheduler, resource resolution, Filmstrip, native code and build files are unchanged.

Before, replacement images were removed by a pausable 1200ms timer. Promotion could
pause that timer while an obsolete image completed its CSS opacity transition.
After, the exact obsolete image's own `opacity` transition completion can retire it,
even when the timer is paused. Retirement requires its owned map entry, a different
current image, the original parent, and computed opacity exactly zero. The existing
1200ms fallback uses the same visibility guard. Cleanup clears owned handlers,
map entries, timers, frames and pending decode work through the existing timer owner.
Initial composition no longer schedules empty old-image callbacks.

## Reproduce

Run from the checkout above. No Gradle, hardware, network API or real photos are used.
The browser requires existing `playwright` and `sharp` packages and Chrome; it does not
install dependencies. On this host:

```sh
export NODE_PATH=~/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules

GALLERY_MODE_REF=227d2ffe0fb8baefebbf1670686731827927966c \
  node --test scripts/tests/slideshow_gallery_retirement.test.mjs
# Expected RED: exit 1, 5 passed / 4 failed.

node --test scripts/tests/slideshow_gallery_retirement.test.mjs
# GREEN: exit 0, 9 passed / 0 failed.

node scripts/gallery_retirement_browser.mjs --red --out=/private/tmp/gallery-browser-red.json
# Expected RED: exit 1. All 36 baseline-repeat pixel samples match, but
# all 3 format cases retain the obsolete image after a native completion event.

node scripts/gallery_retirement_browser.mjs --out=/private/tmp/gallery-browser-green.json
# GREEN: exit 0. All 3 format cases retire the zero-opacity obsolete image,
# retain the current image and release the handler; all 36 pixel samples match.

node --test scripts/tests/slideshow_framed_lifecycle.test.mjs \
  scripts/tests/slideshow_resolved_resources.test.mjs \
  scripts/tests/slideshow_style_reporting.test.mjs \
  scripts/tests/slideshow_gallery_retirement.test.mjs
# 65 passed / 0 failed.

node scripts/verify_slideshow_engine.cjs
# 14/14 source fixtures.
node scripts/test_slideshow_sources.mjs
# 5/5.
node scripts/test_slideshow_labels.mjs
# 2/2.
```

## Fidelity gate

Chrome `155.0.8059.40`, fresh headless profiles, offline contexts, 800x600 viewport,
device scale 1. All image bytes are generated in memory. JPEG is opaque;
PNG and lossless WebP contain fully transparent, 128/255-alpha and opaque regions.
The fixture uses a colored `#204060` backdrop, actual Gallery Wall layout and CSS,
actual image decode, and the actual scheduler's Gallery Wall-to-Cinema promotion.
It exercises literal face focus `70% 25%`, rounded clips and existing shadows.

Twelve full-viewport samples per format cover initial rest, fade start, 350ms,
550ms, 700ms, promotion start, incoming dissolve at 200ms, just before old opacity
reaches zero, native zero-opacity completion, dissolve midpoint/completion and
outgoing disposal. The old fade is 1100ms; the incoming beat dissolve is 1600ms.
Inner Cinema transitions are sampled independently with their original timing.
Global scheduler pause/resume is also checked against a real CSS transition.
A capture listener records computed opacity exactly zero while the old image is
still connected, before the production completion handler can remove it.

Both thresholds were zero from the first probe: **zero changed pixels and zero
maximum RGBA channel difference**. No threshold was relaxed. JSON stores raw-pixel
SHA-256 values for both renders and hashes of every generated encoded fixture.
It also records exact product/CSS source hashes and lifecycle/geometry results.

Playwright's JS clock does not control CSS animation's rendering clock. Early
calibration exposed an uncontrolled inner Cinema transition and occasional
one-channel baseline-repeat differences at layer admission. These runs were not
accepted as fidelity evidence. The final configuration uses `--disable-gpu` and
`--disable-threaded-animation`, explicitly pins animation/transition phases and
allows sampled state to reach the renderer before recording pixels. A complete,
exact baseline-repeat RED control precedes the accepted GREEN comparison.

## Artifacts and limits

- `node-red.log`, `node-green.log`: focused lifecycle RED/GREEN.
- `browser-red.json`, `browser-green.json`: native-event RED/GREEN and exact pixel comparisons.
- `regressions.log`: all 65 focused and existing slideshow Node tests.
- `source-checks.log`: source isolation, hashes, syntax and the 14+5+2 existing source checks.
- `manifest.json`: SHA-256 of the changed source, tests and evidence.

Node fixtures execute actual mode code with controlled DOM/timer boundaries.
Their computed-opacity input is explicit; they do not parse CSS or rasterize.
Browser evidence uses software rendering with sampled animation phases. It does
not certify Portal WebView raster behavior, GPU/layer costs, arbitrary image
dimensions or every style pairing. DOM retirement counts establish lifecycle only.

No performance improvement or cause of the soak tails is claimed. Main owns
integration, build/deployment and any later matched hardware A/B. This branch is
frozen after the local commit; no remote Git, hooks, devices or Gradle were used.

## Corrected main integration

Main integrated the worker candidate, then independent review reproduced malformed-opacity
coercion and a late zero-opacity fade missing the original one-shot fallback. Three additional
actual-mode tests first failed against that candidate (12 tests: nine passed, three failed).
The corrected implementation requires a valid numeric CSS-opacity string representing zero.
An unresolved image has one owned fallback check every 1200ms; retirement cancels its task,
clears its own handler and releases the entry. Scene disposal cancels all work, and a moved
node releases bookkeeping without removing it from its new parent. The shared timer owner
adds `cancel(task)` for Gallery's use; other modes' scheduling and CSS remain unchanged.

The corrected combined lifecycle/resource/style suites passed all 68 tests. Fresh isolated
Chrome evidence for the corrected product source again passed 36 exact, zero-tolerance pixel
comparisons across the three generated formats. The main source SHA-256 and raw comparison
results are preserved under `build/fleet/diagnostics/slideshow-gallery-integration-2026-10-10/`
in `final-browser-green.json`, alongside immutable review RED/GREEN logs. The manifest below
now describes the corrected source/test files; the worker RED/GREEN logs remain historical
controls for the original worker implementation, not a claim that they execute the final code.

DOM retirement and owned callback cancellation do not establish immediate renderer/GPU-memory
release or improved Portal frame timings. Native integration passed 1448 JVM tests and built
both APK variants. Two-room deployment and the new tier-aware 900-second run provide the next
hardware validation; overall performance acceptance remains open.
