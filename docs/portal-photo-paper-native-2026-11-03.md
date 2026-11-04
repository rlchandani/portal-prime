# Paper photos: installed Android geometry

Optimized Immortal **1.129/code123** is installed in Family Area and Living
Area. Complete installed APK readbacks match the final build:
**25,884,988 bytes**, SHA-256
`65c3b65d5f2b6404f888278a0eee02f5f137524e987612b2e787ede98d597ddc`.
The package remains `com.immortal.launcher.debug`, signed by the existing
iteration certificate. Public release metadata and publication are unchanged.

This delivers the [Paper implementation](portal-photo-layout-paper-2026-11-03.md):
six proportional whole Mosaic photos, photo-sized slim frames, a larger
Framed print and quiet original SVG doodles. The installed build also includes
the preceding committed local changes; their individual acceptance limits
remain in their existing receipts.

## Actual Android checks

A separate, temporary framework-only Activity loaded the installed package's
assets through its package context. It generated six mixed-aspect PNGs and
ran the actual mode renderers and shared geometry inside Family's Android
10/API29 WebView. It never loaded household photos or changed settings.

All **43 checks pass**:

- Framed and Mosaic settle at native 2160×1440, narrow to 892×1440 and return
  to their original geometry in the same WebView.
- CSS dimensions match native dimensions at device pixel ratio 1.125. Images
  preserve their aspect ratios, use `contain`, fit inside the root and remain
  separated. Mosaic has six transparent, borderless cell areas.
- Native PixelCopy captures show the doodles and thin frames. Independent
  PNG decoding finds all **28 colored corner markers** across the one Framed
  photo and six Mosaic photos.

Independent review inspected both generated native captures and confirmed
the scoped result. Corner pixels cover the landscape captures; narrow and
restored cases check DOM geometry. Framed uses one 4:3 photo, and motion is off.
The fixture calls `SS.MODES.run` directly with the standalone clock-strip
fallback. It does not execute the production entry point, scheduler, native
clock bridge or FaceRenderer.

The exact optimized DEX retains both `getPhotoLayoutBounds` and
`paperSurfaceChanged`, including each `JavascriptInterface` annotation.
All eight bundled renderer assets match the source in both final APK variants.

## Build and installation

All **4,359 JVM tests / 453 suites** pass with zero failures, errors or skips.
Debug assembly, optimized assembly, R8, performance vital lint and the
AndroidTest APK assembly pass.

The first Kotlin instrumentation attempt stopped before rendering because
the optimized package had renamed `kotlin.jvm.internal.Intrinsics`. It is
an excluded harness failure, not renderer evidence. The original test APK
and crash-guard file were restored, followed by a launcher process restart.
The successful fixture uses only Android framework classes and its own package;
that package was removed after collection.

Family's install returned Success. Living's single install invocation
disconnected with incomplete HTTP headers. A CLI-exit-code adapter initially
misclassified this as refusal; explicit transport inspection and strictly
read-only full APK observation reconciled installation. Its acknowledgment
remains **uncertain**; installation was not retried.

## State preservation

All four complete maps—screensaver, settings profile, sources and layout—
match in both rooms. All **43 Family and 30 Living** captured preference
files remain byte-identical, with no added files. Room name and Dev Mode match.
Family's font, accessibility services, original Chromium Activity/task,
resumed frame and awake display remain. Its current frame was viewed privately.
Both rooms retain the classic white clock.

Limited final HTTP200 diagnostics contain 1,656 Family and 1,616 Living
logcat lines without fatal/ANR markers. The earlier known harness crash is
retained separately. These bounded observations do not assert crash-free operation.

The exact pre-promotion Family 1.128 APK and private configuration backup
are retained. Ordinary Android downgrade support is not asserted.
Copied registry credentials are removed after verification; the original
registry remains unchanged.

## Remaining acceptance

Actual native measured reservations, visible-child backing, no-empty-chip
behavior and Shuffle retirement remain source/unit checked. The standalone
native fixture does not establish their device coupling. Custom dark or
low-opacity clocks have a known contrast limitation on the fixed dark backing;
gradient compatibility is pre-existing and unproven. Android 9/API28,
Living's visual appearance, motion, burn-in and hardware performance remain open.

No live photo transfer, phone operation, provider/account action, push or
publication is performed.

Aggregate evidence is in
`mocks/portal-photo-layout-20261103/evidence/native-delivery-verification.json`,
with generated captures, raw geometry, validation and exact DEX annotations
beside it. Household captures and credential-bearing state stay private.

The separate native-delivery clock is **04:45:43–05:15:43 UTC November 4**,
including discovery, fixes, build, device work, review, cleanup and local commit.
The original review, Paper implementation and all parked clocks remain.
Queue **119: 72 delivered, 37 verified, 2 approval, 7 parked, 1 queued**.
Only the selected photo task changes; all other 118 objects, their raw suffix
and the Weather default remain.
