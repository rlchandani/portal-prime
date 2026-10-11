# Wallpaper previews: sample before allocation

Wallpaper previously started three separate IO decodes of the full bundled JPEGs, then resized
each to 240 × 145. The decode already ran off the UI thread, but the full-size pixel allocation
was unnecessary and the concurrent jobs could increase peak memory. The actual assets are
1280 × 850, 1280 × 853 and 1280 × 853; their EXIF orientation is normal.

The page now uses the existing SlideshowDecoder with a 480-pixel maximum decode edge before
its final 240 × 145 resize. The shared decoder reads bounds, applies power-of-two sampling,
limits pixels/encoded input, normalizes EXIF and requests ARGB_8888/sRGB. All three bundled
assets select sample size four, retaining at least the preview dimensions without upscaling.
One IO batch serializes the decodes and publishes them together. Completed previews stay in
the retained pane, so Back/Forward does not start another decode. Loading is gated on pane
activation; leaving cancels an unpublished batch, releasing its completed preview bitmaps and
stopping before the next asset. Successful images remain drawable for Compose ownership/GC.
The temporary sampled bitmap is released after resize, including resize failure.

The existing swatches, final dimensions, placeholder fallback, wallpaper configuration and
selection behavior are preserved. No new image library, global cache, setting or credential
was added. A fixed accessibility marker on the visible choices container signals ready only
when all three decoded ImageBitmaps have been published. It exports neither image pixels nor
stored settings. The failure marker lets the native trial stop instead of treating a heading
as proof that images loaded.

## Quantitative evidence

| Pixel buffers | Before | After |
|---|---:|---:|
| Sum of full/sampled source ARGB buffers | 13,086,720 bytes | At most 820,480 bytes |
| Same sum in MiB | 12.48 | At most 0.79 |
| Final three 240 × 145 preview buffers | 417,600 bytes | 417,600 bytes |

The source pixel-buffer reduction is 93.73%. It is calculated from the actual bundled JPEG
headers and production sampling limit, with four bytes per pixel and conservative rounding.
It is not a measured reduction in whole-process heap or frame time. Sequential loading further
bounds concurrent pixels; encoded buffering, codec scratch, GPU memory and other app allocations
are not included in this table.

## Local checks

The actual-asset budget test failed with the old full-resolution limit before implementation.
The final full debug and optimized/non-debuggable benchmark builds passed. The JVM gate passed
1,779 tests in 203 suites, with zero failures, errors or skips. Four preview tests cover the
bundled-asset memory/quality budget, ordered decoding off the caller thread, partial asset failure,
and cancellation that discards completed results without decoding the next asset or publishing.
The existing slideshow decoder and budget tests also pass.

The native driver passed 22 offline tests, including nine preserved safety guards. It admits only
navigation targets, never a wallpaper choice or setting row, requires two stable observations of
the real ready marker on each Wallpaper visit, checks header Back/Forward in the same window, and
restores Home. Failure permits at most one restoration to the previously accepted APK.

The APK's profile maps 8,635 methods and 2,565 classes, including 276 app methods and 73 app
classes. Decoder/DEX mapping is validated. The historical required-symbol mapper gate is not
admission because R8 renames those symbols; complete bundled/reference profile sets are compared
on the device. Individual method compilation and universal 60 FPS are not claimed.

## Android references

- https://developer.android.com/topic/performance/graphics/load-bitmap
- https://developer.android.com/develop/ui/compose/graphics/images/optimization

These official recommendations explain sampling before allocation and providing target dimensions.
The existing decoder implements them, so the fix reuses that implementation.

## Family Area result

Four native checks passed in 79.32 seconds, including installation and exact
compilation/profile verification. Actual decoded preview readiness was verified twice, initially
and after Back/Forward. The same Settings window remained active throughout those transitions.
The candidate remains installed, Home is restored, the collector is inactive and configuration/
photo-source snapshots are unchanged. There were zero setting edits and zero Living Area or
phone operations. Exact bundled/reference profile sets passed; individual method compilation
is not claimed.

Evidence resides at:
`~/portal-prime/build/fleet/diagnostics/settings-wallpaper-previews-2026-10-11`.
Postflight SHA-256: `347cd9020da0f52abf045041eb278330894cade3043df23581b1ab5c93a3fc47`.
Installed candidate SHA-256: `05cb0dabc8a78150db4875e0eb6b31f4b74892e635fbfe9effdcae4142d113fd`.
This bounded issue finished within 30 minutes; the overall performance goal remains open.
