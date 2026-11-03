# Photo screensaver clock fonts — Family Area

The digital photo-frame clock now resolves the font and weight in its existing
`ClockSpec`. Previously every selected family was drawn as `Typeface.DEFAULT_BOLD`.
The shared resolver also returned the first cached weight for later requests of
the same family.

This corrects the existing photo screensaver face path. Big and Minimal specify
the light system family at weight 200; Bold specifies the bundled Bebas Neue
at weight 400. It does not add settings, download fonts, change the separate
quiet night clock, or configure Meta's firmware.

## Implementation

`PrimeDigitalClockFaceView` uses `FaceStyle.typeface` when constructing its time
paint. Its existing measurement therefore reserves space using the resolved
font's advances and ascent/descent.

`AssetResolver` keeps one base asset per family and caches the weighted result
by `(family, normalized weight)`. It requests native weights on Android 9/10,
using `Typeface.create(base, weight, false)`, which is available from API 28.
The app's API 24–27 fallback uses normal/bold styles. Imported descriptors can
contain arbitrary weights, so the native call normalizes to its required
1–1000 range. No setting or stored face value is rewritten.

Font resolution happens at construction. The tick, minute invalidation,
locale/timezone handling, image ownership and animation cadence are unchanged.
The base cache avoids loading an asset again when requesting another weight.
These are implementation boundaries, not measured CPU or FPS savings.

## Bounded verification

The original issue slot is **15:04:49–15:34:49 UTC, November 1**.
It includes investigation, the unchanged-component baseline, implementation,
fresh builds/tests, native verification, installation and restoration.
The cap is not reset for the additional light-preset checks.
Installation and restoration were verified at **15:17:58 UTC**,
**13 minutes 10 seconds** into that slot.

Family Area is Android 10/API 29 on QCS605 / Adreno 615, with approximately
2,782 MiB kernel-visible RAM. The native helper constructs the shipped clock
and draws it into **detached Canvas bitmaps**. It starts no Activity, observer,
live clock, audio session or performance collector.

| Check | Result |
| --- | --- |
| Original component | **144/329 assertions failed**, zero isolated exceptions |
| Original requested families/weights | **One glyph mask**, weight **700** in all 18 drawings |
| Original native work | **2.646 seconds**, 18 compositions / 54 PNGs |
| Original app/config restoration | Passed, **39.669 seconds** including the baseline workflow |
| Fresh unit gate | **3,761 tests / 394 suites**, zero failures/errors/skips |
| Debug, optimized, Android test builds + fresh unit gate | Passed, **4 minutes 5 seconds** |
| Final native checks | **351/351 passed**, zero isolated exceptions, **3.093 seconds** |
| Final native compositions | **20**, with 60 generated PNGs |
| Original assertions retained | **329/329**, plus 22 light-preset assertions |
| Independent glyph comparison | **20/20 pixel-exact** to native font references |
| Fixed-size glyph masks | **One → nine** across the sampled families/weights |
| Final test-only preset rebuild | Passed, **35 seconds** |
| Final installation/restoration workflow | Passed, **41.053 seconds** |

The original 329 assertions remain. Two additional wide/narrow cases cover the
200-weight light family used by the shipped Big and Minimal presets. Those
two cases were added after the baseline; no before image is invented for them.

The helper checks:

- Independent native Typeface references for light sans, monospace fallback,
  serif fallback and bundled Bebas Neue at weights 400/700.
- Both cache request orders, repeat-pair identity, missing/default family
  fallback, default weight 200 and malformed imported weights.
- Literal generated `08:34` glyphs, responsive dimensions, two-pixel ink
  margins and separation from the seconds indicator.
- Exact glyph images against independently created native Typeface references.
- Fixed-size masks that distinguish family and supported weight changes without
  confusing different responsive text sizes with different fonts.

Canvas captures verify the font engine and the shipped drawing component.
They do not prove GPU/Window presentation, delivered panel FPS, optical comfort,
every face preset layout or settings-picker interaction. The 200-weight cases
use the shipped family/weight, with a stress size and generated fixed time.
API 24–27 fallback behavior is present in source, not accepted by this API 29 run.

## Evidence and installation

[Native assertions](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-clock-fonts/checks.json),
[retained baseline failures](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-clock-fonts/baseline-checks.json),
[independent PNG pixel checks](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-clock-fonts/pixels.json)
and [restoration receipt](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-clock-fonts/verification.json)
support this scoped acceptance. The native process is terminal.

The [native comparison gallery](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/family-clock-fonts-review.html)
shows the generated drawings; it does not recreate the clock using browser fonts.
It retains 18 exact before/after pairs and explicitly labels the two added
light-preset cases as having no exact baseline capture.

The installed optimized APK SHA-256 is
**e59bb10c47524fc190faa52fe8d87f0b8a32c1332bf9b7ffe2c0d1eee0f722d7**.
The tested debug APK is
**72b8adec25bffee3eb2ce888562e639137417d67535aba06623658e70c3cf454**.
Its signing certificate matches the previously installed build.
The optimized player/clock was not itself instrumented; the detached native
checks used the debug variant of the same production drawing source.

All 97 settings, source records, byte-identical face-focus cache, accessibility
configuration, persistent pin, font scale and brightness policy are preserved.
Raw brightness also matches in this run. The test APK and original Dream are
restored, the system process is unchanged, instrumentation and performance
collector are inactive, and owned reports/PNGs are removed from the device.
Credentials, household configuration, rollback APKs and raw command responses
stay outside Git. Living Area is outside this issue's device scope.

## Implementation reference

[Android Typeface API](https://developer.android.com/reference/android/graphics/Typeface)
documents the API 28 weighted constructor, the 1–1000 input range,
`getWeight`, and the older normal/bold constructor.

Natural room-light fade comfort and broader platform performance acceptance
remain open. This issue is a functional/appearance correction, not a new
whole-platform speed claim.
