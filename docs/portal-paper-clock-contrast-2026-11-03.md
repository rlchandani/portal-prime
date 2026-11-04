# Paper widget contrast

Optimized Immortal **1.130/code124** is installed in Family Area and Living Area.
Both complete installed APK readbacks match the candidate: **25,884,988 bytes**,
SHA-256 `4647ba1d06e9aca7b0bc7a9b660ac9f39ced14f371dceb9950ad03ad4f6b02a6`.
The package and iteration signing certificate remain unchanged.

The Paper photo renderer previously put every native widget on the same dark
plate. That kept white clocks readable, but a configured dark green clock could
have about 1.1:1 contrast. A light plate for the entire position would instead
wash out its independently white photo caption or music card.

Each existing direct child now selects a light or dark backing using the actual
configured ARGB, including opacity. A paint-only Drawable merges adjacent matching
bands, so clock/date share a plate while differently colored caption/music groups
keep their own. The view hierarchy, metadata alignment and bucket padding remain.
Paper-only metadata dividers also follow the backing and restore their former ink
on exit. Visible wrappers with every descendant hidden produce neither a backing
nor a photo-space reservation. Saved foreground colors, shadows and opacity remain.

## Checks and native scope

The extracted old fixed-dark policy fails four of six contrast assertions, with
two controls passing. Those same six tests pass after correction; three additional
band-geometry tests and three production retained-bucket boundary tests pass.
This RED is an extracted-policy reproduction, not execution of the old Android
renderer. All **4,371 JVM tests / 455 suites** pass without failures, errors or skips.
Debug, optimized/R8 and vital lint pass; all nine screensaver assets in both APKs
match source.

An owned framework-only Activity loads the exact candidate debug APK's production
classes on Family's Android10/API29, with generated preferences and inactive
workers. Actual FaceRenderer clock/date and caption share a measured native bucket.
Its Drawable samples are **#f6f2e8** behind the dark clock and **#3b423a** behind the
white caption. Padding/background exit and re-entry pass. Independent source and
image review finds no blocker.

The generated image is a native view drawn into a **software Canvas bitmap**,
not PixelCopy. Exit/re-entry occurs without intervening layout, so it proves
state restoration only. The exact loaded debug APK is **46,726,883 bytes**,
SHA-256 `6d4ba3b768b63fd5fc5df0d20c4f7a6e53c0ece60e4e1bb2b681edec25f03fc3`.
R8 removes methods needed for this isolated construction in the optimized APK;
zero optimized probe calls are claimed. The owned fixture was removed afterward.

Native music, divider and hidden-wrapper cases, low-opacity colors, gradients,
API28, Living visuals, optimized FaceRenderer/slideshow/Shuffle coupling and
hardware performance remain open. Very low opacity can still make text unreadable;
the implementation preserves the user's choice. Prior Paper geometry acceptance
and its limits remain in the earlier receipt.

## Delivery and state

Family's single optimized install returned Success. Living's single invocation
lost its response; the transport-aware observer reconciled six read-only
observations to the complete installed hash, with ACK uncertain and no retry.
All **43 Family and 30 Living preference files** and four complete maps match
their pre-install captures. Room names and Dev Mode match. Family font,
accessibility, original Chromium activity, resumed frame and awake display remain.
Bounded logcat responses contain 1,620/1,705 lines and no fatal/ANR markers.

The first fixture admission treated expected `pm path` exit1 as an error before
installing anything; it was corrected. An initial preservation reader expected a
structured device field instead of `/info`'s top-level name; the corrected read
checks passed. Neither adapter error is product evidence.

Original registry, public release manifest/catalog and native fleet CLI remain
unchanged. Copied registry files are removed at closure. No phone, live photo
transfer, provider/account action, push or publication occurs.

Separate contrast unit: **05:10:43–05:40:43 UTC November4**, including discovery,
implementation, build, native check, installation and closure. Earlier review,
Paper implementation, native geometry and parked issue clocks remain. The unified
photo task stays delivered; other task objects and raw suffix remain, with Weather
as default. The whole-product goal remains active.

Evidence: `mocks/portal-photo-layout-20261103/evidence/clock-contrast-verification.json`
and `native-contrast/`.
