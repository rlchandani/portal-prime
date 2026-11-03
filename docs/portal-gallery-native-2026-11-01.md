# Gallery color fix and actual Family Area verification

**Delivered:** Gallery print and Gallery spread preserve photo colors instead of
darkening their lower portions with the clock overlay's full-width gradient.
The other styles retain their existing legibility shading. Family Area has the
optimized, non-debuggable APK **e47d6e71…**, signed with the existing certificate.

Source commit **d6ffba96**, branch `codex/family-gallery-native-20261101`.
The slot started at **10:24:22 UTC** on November 1, with a hard deadline of
**10:54:22**. Final delivery, native checks and fresh health verification passed
at **10:39:51**: **15 minutes 29 seconds**. Living Area was not changed.

## Concrete defect and fix

The original Gallery geometry already contained the whole image and reserved
space for clock metadata. `FaceRenderer` subsequently drew its bottom gradient
across both the matte and the photo. Actual Family Dream screenshots exposed
this independently of the layout calculations: the generated blue lower corner
changed from RGB **64,120,232** to **25,46,89**, and the yellow corner from
**232,176,48** to **89,68,18**. The upper corners kept their original values.
The baseline therefore failed both lower-corner color checks.

`SlideshowPresentationPolicy.needsPhotoScrim` exempts the two Gallery modes.
Clock, date, weather and caption construction stay in their existing paths.
Full-bleed clocks retain their existing behavior. Other and unknown styles keep
the gradient so text over photos remains readable.

Overlay assembly reuses its existing settings snapshot for both music visibility
and shading; the fix adds no preferences read. Gallery creates one fewer shading
view and gradient drawable. Decoding, bitmap budgets, face analysis, timers and
slideshow dissolves are unchanged. Removing that drawing work is an implementation
reduction, **not a measured CPU/GPU/FPS improvement**.

![Cropped native before/after captures using a generated test image](https://raw.githubusercontent.com/starbrightlab/immortal/main/mocks/portal-premium-player-20261030/evidence/family-gallery-native/before-after.png)

## Actual Portal checks

The installed optimized APK ran on Family Area's Android 10/API 29 Portal+,
**2160 × 1440 at 180 dpi**, over wireless ADB. No emulator, debug APK,
instrumentation or performance collector supplied these results.

Four generated PNGs carried distinct solid corner markers flush to their edges.
The pixel checker independently measures corner positions and dimensions,
aspect preservation, viewport containment, pair spacing and alignment.
It rejects crop, stretch, missing corners and overlap in its separate test set.
No household photo was used or published.

| Native Dream case | Displayed photo size | Relative aspect error | Result |
| --- | ---: | ---: | --- |
| Gallery print, landscape 1200 × 800 | **1704 × 1136** | **0** | All four original corner colors present |
| Gallery print, portrait 800 × 1200 | **884 × 1326** | **0** | Whole image, bounded placement |
| Gallery spread, two 800 × 1200 portraits | **852 × 1278 each** | **0 each** | Matching dimensions/top/bottom, **28 px** gap, no overlap |

Those Dream cases pass **51 image/geometry assertions**. A separate actual
pinned accessibility overlay passes **29**, for **80/80** total.
The final native run, including restoration, took **50.451 seconds**.

The pinned Gallery spread entered the warm night clock using real **3 lux**
sensor readings and a temporary registered **20 lux** threshold. It retained
both photos while showing time, weekday and date. Registered clock disable
returned to the spread with **byte-identical pixels in both complete photo
rectangles**, unchanged geometry and the same Files activity/window/process.
One admitted tap then revealed that retained app without relaunching it.

Brightness-command progression from its first explicit down-ramp sample took
**2.974 seconds**; registered disable to the default-policy endpoint took
**3.117 seconds from the request**. These are sampled window commands, not
optical backlight curves. The long **3.641-second** sampling gap occurred before
the ramp while capturing the photo baseline; active progression gaps stayed
below **0.200 seconds**. Neither number is a frame-time measurement.

## Regression checks and restoration

- Full Gradle gate: **3,717 tests / 392 suites**, zero failures, errors or skips.
  Two new tests guard Gallery color preservation and retained shading for other
  styles.
- Debug and optimized performance APK assemblies pass.
- The independent pixel checker passes **11 tests**, including damaged/cropped
  compositions; read-only review found no concrete blocker.
- Final installation took **17.621 seconds**. Its APK hash and signing
  certificate were verified.
- All **97 setting values**, **22 source fields**, persistent pin state,
  accessibility configuration and global brightness were restored exactly.
  Android's system process stayed unchanged.
- The four temporary generated files and their owned directory were removed.
  Home was restored beneath the original Immortal Dream.
- Fresh checks confirm the installed hash, non-debuggable build, wireless
  physical-device identity, original Dream, and no pin cover, instrumentation
  or active collector/listeners.

The current household style and photo source were restored. This delivery makes
Gallery's existing selectable styles correct; it does not select them for the user.

## Acceptance limits

This accepts the tested native Gallery compositions, their original marker colors
and the pinned spread's retained-photo return. It does not accept every legacy
style, every overlay arrangement, saved manual crops, remote albums, videos,
cold startup, browser retention, complete GPU residency or delivered panel FPS.
The clock exit case uses registered disable; a natural bright-room exit and
physical brightness comfort remain pending because the user cannot test now.

The broader optimization/screensaver/music goal remains incomplete. Parked
Home/cold-start/renderer investigations were not reopened. Music provider/server
wiring remains outside scope.

[Sanitized receipt and native captures](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-gallery-native/verification.json).
Credentials, source/configuration snapshots, raw device dumps and rollback APKs
remain private outside Git.
