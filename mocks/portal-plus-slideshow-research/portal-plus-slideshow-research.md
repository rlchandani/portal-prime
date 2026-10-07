# Portal+ slideshow overhaul research

Research completed October 7, 2026. This combines the current working tree,
observation of the installed Portal+ build, deterministic source probes and
official product/engineering documentation. It proposes an overhaul; it does not
implement it. Application code and slideshow settings were not changed.

## Recommendation

Build a bounded native photo stage for the default slideshow, retaining the
existing source adapters, clock/calendar/media overlays, presence and overnight
behavior. Start with three calm compositions: a single photograph, a pair of
portraits, and a whole-photo framed view. Share one upright-image, crop and motion
contract across all compositions.

The valuable improvements are reliable image readiness, correct framing and
predictable resource use. More transition styles alone would leave the underlying
problems intact. Keep existing named styles available through a compatibility
path until their replacements have been reviewed and tested; do not silently
remove selections or reinterpret saved preferences.

Face-aware framing is achievable without photo uploads or Google Play Services.
The existing bundled detector is the first candidate to validate. It needs better
integration, not an assumed SDK upgrade. Where detection is missing, uncertain,
stale or geometrically incompatible with the screen, show the whole photograph.

Supporting audits:

- [Face detection, geometry, offline alternatives and 17 official references](portal-plus-slideshow-face-research.md)
- [Photo sources, decoding, persistence, cancellation and memory](portal-plus-slideshow-pipeline-research.md)

## What is already there

`PhotoDreamService` and `PhotoFramePreviewActivity` share the same
`PhotoFrameController`. The Photos dock deliberately opens the preview Activity;
the idle screensaver uses the DreamService. The controller also owns native
overlays, welcome behavior, calendar, captions, soundscape and video playback.

There are two renderers:

| Renderer | Current behavior |
|---|---|
| Native Classic | Two ImageViews, a 1.5-second crossfade and centered 1.12× zoom/vertical pan in fill mode. Native video playback; SMB always uses this path. |
| Bundled WebView engine | Twelve styles plus Shuffle. Orientation-dependent selection, several collage layouts, motion, blur backgrounds, internal photo fades and separate whole-scene fades. Shuffle is the configured default. |

Sources include local folders, shared albums, Immich, WebDAV, SMB, a default
online feed and an arbitrary web-page mode. Capabilities differ: WebView image
styles exclude videos, while the native renderer can play eligible local,
Immich and WebDAV videos. The arbitrary web page supplies its own content.

Existing useful work should be retained: EXIF support for local images,
duplicate avoidance within compositions, bounded local engine manifests,
scaled local copies, optional native persistent media caching, album refresh,
provider backoff, aspect-preserving video derivatives and lifecycle handling.
The audits document important gaps in each feature's coverage.

## Verified problems and source risks

**Confirmed by source and deterministic probes:**

1. **Motion duration and photo duration disagree.** The WebView single-photo
   rule uses a default 13-second forward-only animation; its photo timer uses
   the configured interval. At 30 seconds, motion can stop for the remaining
   17 seconds. Memories singles hardcode 12 seconds. This is inconsistent with
   the source comments promising movement through the fade.
   Evidence: `assets/screensaver/styles.css:20,80`;
   `mode-impls.js:20-27,147-148`; `scheduler.js:24-30`.
2. **Scene switching does not wait for ready images.** `scheduler.js` starts a
   mode synchronously, begins its 1.6-second scene fade and removes the old scene
   after 1.7 seconds. Several modes start an asynchronous preload internally.
   A delayed-image fixture using the actual scheduler reproduced removal of
   the outgoing scene while the incoming image was still unready.
   Evidence: `scheduler.js:103-116`; `mode-impls.js:40-47`.
3. **Face-first is not face-safe.** The actual mosaic helpers place a face at the
   beginning of a full-image pan, but do not constrain its end. A probe with a
   portrait, 3:2 viewport and face at normalized y=0.1 showed the final visible
   interval at y=0.556..1, excluding the focus. This is a geometry demonstration,
   not a detector accuracy test.
   Evidence: `modes.js:133-155`; `mode-impls.js:233-245`.
4. **The native decode bound is not a strict output bound.** A 4,000×3,000
   original selects `inSampleSize=1` despite `MAX_EDGE=2560`; no final native
   resize enforces that edge. Its pixels cost approximately 45.78 MiB before
   rotation, GPU upload or decoder scratch. Local EXIF transforms can briefly
   retain another same-size bitmap. Evidence: `PhotoFrameController.kt:1369-1435`.
5. **Swipe navigation does not route to the WebView engine.** The controller's
   `next()`/`prev()` dispatch only to native local/remote/default-feed methods.
   Engine startup returns before setting native local/remote playback state;
   it does not retain a JS engine handle or provide a navigation bridge.
   Thus the gesture path cannot explicitly advance the visible engine.
   This is a source-confirmed integration gap, not a new device gesture test.
   Evidence: `PhotoFrameController.kt:365-379,398-400,1301-1315`;
   `assets/screensaver/index.html:88-93`.

**Confirmed integration gaps, with runtime impact still to measure:**

- Face results affect only parts of the WebView styles and people ordering.
  Native Classic receives no face data. The cache stores a point and weight,
  discards face boxes and has no source/model revision. Newly detected results
  become available on a later engine start, not the current deck. Detection does
  not normalize EXIF orientation.
- The engine does not receive the fit/fill selection. It also does not use the
  native Immich/WebDAV `MediaCache`; browser caching and local scaled copies
  are different mechanisms. Default WebView feed URLs lack the native bundled
  fallback chain. Offline readiness is therefore source/renderer dependent.
- The active mode implementation uses its own `Image.onload` helper; the shared
  `preloadDecoded()` helper is unused. Several collage replacements skip even
  that preparation. Timer cleanup does not cancel pending image promises.
- Image reads, decoding and background tasks need bounded admission and session
  cancellation. Rapid navigation can queue work whose results are rejected only
  after expensive processing. Some cache housekeeping occurs on the UI thread.

**Performance hypotheses, not established root causes of every stall:**

Full-screen blur duplicates, multi-tile texture residency, nested scene/photo
alpha transitions, animated layout properties and concurrent face/cache warming
can compete for CPU, memory bandwidth and GPU time. Static filters can sometimes
be cached by Chromium; their presence alone does not prove continuous repaint.
The next implementation should profile these individually instead of attributing
all slowdown to WebView or to the GPU.

## What the connected Portal showed

The existing benchmark APK was inspected without installing a replacement:
`com.immortal.launcher.debug`, SHA256
`b8806f7952981c03118e1a8a2cc4400ea3c62ed0565a503aac784758c341d35f`.

| Actual device property | Observed value |
|---|---|
| Android / ABI | Android 10, API 29, arm64-v8a |
| Panel | 2160 × 1440, 60 Hz |
| Graphics | Qualcomm Adreno 615, OpenGL ES 3.2 |
| OS-reported RAM | 2,848,792 KiB, approximately 2.72 GiB |
| WebView package | `com.facebook.portal.webview`, `14.0.4.0.0.767920214` |
| Engine viewport | 1920 × 1280 CSS pixels, device pixel ratio 1.125 |

The WebView package version is not a verified Chromium version. Do not use a
desktop Chrome result or another Portal model as this hardware's benchmark.

An observation used six 15-second waits, plus command overhead, with the saved
library and overlays. Runtime logs showed Ken Burns, Diptych, Hero + Sidebar and
Filmstrip scenes. No cache was purged, so this is not a controlled cold-cache test.

| Android host statistic | Observed |
|---|---:|
| Frames counted after reset | 1,152 |
| Median / p95 / p99, reported histogram | 19 / 24 / 121 ms |
| Frame-deadline-missed counter | 21 |
| Main app PSS, six samples | 266.2–410.8 MiB |

These measurements establish uneven host timing and fluctuating process memory.
PSS includes retained Home, services and overlays; it is not solely slideshow
bitmap memory. WebView uses a separate rendering process, so these counters
are not a complete delivered-FPS measurement. The host also labeled 938 frames
janky and 1,043 high-input-latency events; those classifications must not be
presented as “81% of the slideshow frames dropped.”

A separate 127-row compositor presentation buffer contained 16.67 ms animation
intervals and approximately one-second updates during a still hold. The latter
are not evidence of dropped animation frames. Separate moving, transitioning
and intentionally static phases in future benchmarks.

Raw logs, native screenshots, hierarchy and household content remain in private
test cache. Only sanitized summaries and source-probe evidence accompany the
browser research brief. The Portal was returned to Home after observation.

## What current products and engineering guidance contribute

| Reference | Applicable improvement |
|---|---|
| [Aura positioning][aura-position] | Prioritize faces, but give the user a saved per-photo positioning override and whole-photo option. |
| [Aura Photo Match][aura-pairs] | Pair portrait photographs on a landscape display; retain a single-portrait fallback. |
| [Google Nest Photo Frame][nest] | Related portrait pairs, optional photo information and curation controls. Use available date/album information first; identity grouping is a separate feature. |
| [Frameo adjustments][frameo-crop] and [quick menu][frameo-menu] | Make fit/fill and photo adjustments accessible at the photo, rather than sending users through global settings. |
| [Apple iMovie Ken Burns][apple-motion] | Treat motion as a deliberate start/end composition and allow it to be disabled. |
| [Android rendering][android-render] and [hardware acceleration][android-gpu] | Prepare images before display, avoid oversized bitmaps and control overdraw/layer memory. |
| [Web animation guidance][web-animation] | Prefer transform/opacity; animated geometry can trigger layout/paint. Layer promotion still consumes memory. |

These are transferable behaviors, not claims about proprietary algorithms or
performance superiority on Portal hardware.

## Proposed experience

**Recommended default:** a calm 30-second interval, a short dissolve around
0.8–1.2 seconds and at most about 3% extra zoom when safe. These are design
starting points requiring a device preview, not measured optimal values.

- **Smart frame:** one photograph, whole-image framing where uncertain, a
  bounded gentle drift where composition permits it. Use the approved Meta
  charcoal matte instead of a full-resolution live blurred duplicate.
- **Portrait pair:** two upright photographs selected from the same album and
  nearby dates when metadata permits; never repeat one photo into both slots.
  When no suitable pair exists, use one framed portrait.
- **Framed photograph:** preserve the entire image with a quiet matte,
  particularly for large groups, panoramas, artwork and low-resolution scans.
  Static display with a dissolve is a complete presentation.
- **Optional gallery:** a small static composition with one prepared tile
  replacement at a time. Large animated mosaics, filmstrip and scatter effects
  stay in existing-style/Advanced choices until separately benchmarked.

Do not randomly change the presentation theme every photo. Keep a chosen style
stable; optional mixing should select from tested compositions based on aspect,
protected content and readiness.

For the deliberately opened Photos viewer, a tap can reveal a small toolbar:
pause/resume, previous/next, photo information, adjust and explicit return to
Home. A per-photo fit/crop adjustment should be non-destructive and saved.
The idle Dream retains its current tap-to-exit and chosen-dismiss-app behavior.
These two interaction policies should share rendering, without confusing their
different entry/exit purposes.

Keep basic settings short: presentation, motion, interval and “Keep people in
frame,” alongside existing source and overlay controls. Detector resolution,
cache budgets and diagnostics belong in Advanced. Every global setting still
uses the declarative settings registry and phone-remote schema.

## Face-safe preparation and motion

Use one upright derivative for display, dimensions and analysis. Normalize all
eight EXIF cases before expressing face boxes in 0..1 image coordinates. Analyze
once on a cancellable worker, reusing acquired bytes; do not detect on each
animation frame or redownload the photograph only for detection.

Retain boxes and padded group bounds, asset revision, orientation, analysis
status and model/policy version. Distinguish pending, failure and no detections.
Publish late metadata at the next slide boundary so a visible photograph does
not jump as its face is found.

For an upright image `W×H`, viewport `Vw×Vh` and protected region `Bw×Bh`:

```text
scale needed to cover = max(Vw/W, Vh/H)
largest scale preserving the region = min(Vw/Bw, Vh/Bh)
```

If cover needs a larger scale than the safe maximum, choose whole-photo framing
or a different composition. Otherwise constrain both scale and translation
against image edges, face bounds and overlay occlusion. A shared monotonic
interpolation between verified endpoints can preserve rectangular constraints;
independent easing/random translation requires additional path verification.
Disable movement when no safe path exists.

The current **bundled ML Kit 16.1.7** includes an arm64 model/runtime and remains
the documented bundled face version. Its packaging is eligible for this API-29
device; successful initialization/inference on this Portal is still unverified.
Historical diagnosis recorded a warm-up stall without proving its cause.
Investigate it first. If reproducibly unsuitable, evaluate bundled MediaPipe
CPU face detection, then a pinned OpenCV/YuNet pair on the same test set.
Do not switch to the Play Services model-download variant. Local image processing
does not imply the SDK never sends operational metrics.

## Architecture options

| Approach | Benefit | Tradeoff / recommendation |
|---|---|---|
| Repair the current WebView engine | Fastest way to preserve every style; add readiness gates, bounded images, motion-duration synchronization and navigation. | Useful immediate stabilization and compatibility path. Browser texture/cache behavior and a large style surface remain to manage. |
| Native bounded photo stage | Explicit image, memory, transition and cancellation ownership; reuse source/overlay integration. Suitable for the three default compositions. | **Recommended destination.** Rebuild the stage around prepared images and one transition state machine; do not merely reuse Classic unchanged. Port elaborate styles only after benchmarks. |
| Dedicated OpenGL renderer | Potentially precise texture/blend control. | Higher driver, lifecycle, video and maintenance complexity. Investigate only if measurements show a simpler native stage cannot meet the target. |

API 29 lacks Android `RenderEffect` blur (API 31) and `RuntimeShader`/AGSL
(API 33). If a blurred matte is retained, generate a small derivative off-thread
and cache it; do not make the proposal depend on those APIs.

Suggested pipeline:

```text
Existing source adapter
  -> incremental manifest / stable asset identity
  -> prioritized, cancellable acquisition
  -> upright bounded decode + optional cached analysis
  -> prepared image + validated composition/motion
  -> ready transition -> hold -> next
```

One demand image job, one next-image lookahead and one analysis worker should
share admission rules; background album warming/video export yields to visible
work. Keep the previous valid image while loading or offline. At most three
prepared single-image slots need to be retained, with separate per-composition
admission for pairs or galleries.

At panel resolution, one ARGB_8888 image is **11.87 MiB**; three cost **35.60 MiB**
before scratch and GPU copies. A **48 MiB native prepared-pixel budget** is a
starting candidate, not an established safe total process/GPU budget. Overscan,
orientation transforms, video surfaces and collage slots must be admitted too.
Use strict decode output bounds and reserved temporary storage, not merely
compressed-cache size or a nominal maximum edge.

## Delivery and acceptance

1. Snapshot the approved UX work and use a separate slideshow implementation
   branch. Preserve persisted preferences and a tested compatibility path.
2. Fix correctness first: ready-before-replace, actual decode limits, session
   cancellation/backpressure, engine navigation and fit/fill parity.
3. Validate bundled inference independently on licensed test images, then build
   upright protected-region geometry and derivative/cache identity.
4. Build and review the three native compositions on the Portal, preserving
   overlays, photo sources, videos, presence and sleep policies.
5. Benchmark each scene separately, including source and orientation combinations;
   promote a new default only after those gates pass.

Proposed gates below are targets for implementation, not current achievements:

| Area | Acceptance proposal |
|---|---|
| Readiness | No black/empty replacement or partly loaded composition; fallback is ready even with network unavailable. |
| Framing | All retained padded face regions remain visible for the complete path; impossible/uncertain crops fall back to contain. Manual adjustment wins. |
| Motion | No mid-photo reframe, duration mismatch, alternating bounce, nested scene/photo transition or stale completion. Reduced Motion stops continuous drift. |
| Timing | Measure continuous motion separately: aim for p95 within 16.7 ms and p99 within 33.3 ms at 60 Hz; no unexplained freezes. Use native frame and compositor evidence together. |
| Startup / exit | Initial targets: warm first photo within 0.5 s, local/offline cold fallback within 1.5 s, exit response within 0.15 s. Confirm on device before promising them. |
| Resources | Enforced pixel/temporary-space budgets; no monotonic memory rise during a two-hour loop; no redundant acquisition/inference on a warm replay. |
| Interruption | Rapid swipes, exit during fetch/decode/inference, dream-to-preview handoff and source refresh cannot publish stale content. |
| Coverage | EXIF 1–8, portraits/groups/edge faces, panoramas/art, huge/corrupt inputs, tiny/one-item libraries, slow/offline LAN, signed-URL refresh, videos and all enabled overlays. |

A single browser animation or today's short host sample cannot sign off these
hardware guarantees. First produce the controlled device prototype and measured
comparison; then finalize the premium slideshow mock around verified capability.

## References

Accessed October 7, 2026. Face SDK/model references are collected in the separate
face audit.

[aura-position]: https://help.auraframes.com/hc/en-us/articles/360007591474-How-can-I-reposition-the-photos-on-my-frame
[aura-pairs]: https://help.auraframes.com/hc/en-us/articles/360051589374-Showing-two-portrait-photos-next-to-each-other-Carver-Frames
[nest]: https://support.google.com/googlehome/answer/9136992?hl=en
[frameo-crop]: https://support.frameo.com/hc/en-us/articles/8714987838098--Why-Is-My-Photo-Cropped
[frameo-menu]: https://support.frameo.com/hc/en-us/articles/26517961948050--The-Quick-Menu
[apple-motion]: https://support.apple.com/guide/imovie-iphone/adjust-zoom-effects-knac3eec39c6/ios
[android-render]: https://developer.android.com/topic/performance/issues/render
[android-gpu]: https://developer.android.com/develop/ui/views/graphics/hardware-accel
[web-animation]: https://web.dev/articles/animations-guide

- [ML Kit bundled face detection](https://developers.google.com/ml-kit/vision/face-detection/android)
- [ML Kit release notes](https://developers.google.com/ml-kit/release-notes)
- [Android RenderEffect API floor](https://developer.android.com/reference/android/graphics/RenderEffect)
- [Android RuntimeShader API floor](https://developer.android.com/reference/android/graphics/RuntimeShader)
