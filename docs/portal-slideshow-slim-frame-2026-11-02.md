# Slim slideshow frame

The user's latest slideshow complaint was recovered from the interrupted chat
and completed on **2 November 2026, Pacific time**. Exact optimized
**Portal 1.97/code91** is installed in Family Area and Living Area.

Framed Print added a 46-pixel cream mat and a 10-pixel outer edge to every side
of the measured photo. The same decoration consumed more of a narrow portrait
than a landscape. The mat is now **4 CSS pixels**, with a **2-pixel edge**.
The photograph's existing measured dimensions, orientation, placement, selection
and motion preference remain in use.

A rendered motion check exposed an additional consequence of the thinner mat:
the existing six-percent zoom could paint over it. The bevel now clips that
animation to the photo opening. This retains the existing motion inside the
photograph and keeps the mat visible; it does not disable motion or add another
decoded image.

## Verification

- The unchanged baseline failed the narrow-frame condition for all **8** cases;
  each already retained its aspect ratio and fit inside the viewport.
- With the thin mat, **8 of 16** still/animated cases initially retained the
  visible mat. After clipping the zoom, **16/16** pass thin decoration,
  aspect ratio, viewport containment and a screenshot pixel check of the mat.
  Cases cover portrait, landscape, square and panoramic images at
  2160×1440 and 1024×600, with motion enabled and disabled.
- All **354 slideshow tests** and **4,066 JVM tests in 423 suites** pass.
  Debug and optimized `performance` assembly, R8 and vital lint pass.
  The combined Gradle run completed in **109 seconds**.
- Three old assertions failed because they pinned the former padding or its
  complete CSS snapshot. Those existing expectations now record the requested
  slim frame; the six random selections, geometry and lifecycle checks remain.
  An initial command used the nonexistent `assembleOptimized` task; its failure
  is retained privately, and the corrected `assemblePerformance` gate passed.
- The final APK contains the exact verified stylesheet and uses the same
  signing certificate as the installed preview. Both deployment helpers read
  and hash the complete installed APK. Postflight confirms 1.97/code91,
  unchanged device names/dev mode and identical complete settings, source,
  screensaver and layout responses.
- Family's real screenshots show the slim border on three portrait photos,
  including a before/after capture of the same photo. The coordinator observed
  four subsequent snapshots over 64 seconds. Original shuffle mode was restored;
  font, accessibility services, original Chrome task and the normal manual frame
  remain. Living received identity/artifact/configuration verification.

Native landscape, square and panorama screenshots are not claimed: their
geometry and animated-border acceptance comes from the generated desktop
rendering of the same shipped engine. No general FPS, natural system Dream
recovery, other framed styles or whole-product acceptance is implied.

## Evidence and boundaries

The [sanitized verification receipt](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-widget-gallery-20261102/evidence/slideshow-slim-frame-verification.json)
contains the artifact hashes, generated geometry/pixel checks and preservation
results. Generated portrait and landscape screenshots sit beside it. Owner
photos, credentials and the actual native comparison remain private.

APK SHA-256:
`92e8a3b0d394f1cbd6ad47b4566e1f969031ff967c6f27e5ddf54b5f48202278`.

The original request clock is **03:17:14.224–03:47:14.224 UTC on 3 November**;
takeover did not restart it. A bounded five-minute Family screenshot monitor
uses this same deadline and must pause after the observation ends. No Google
phone, Home Assistant, GitHub publication or previously parked investigation
was accessed. The public update manifest remains unchanged.
