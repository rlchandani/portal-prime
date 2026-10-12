# Portal source-photo preview sizing validation

The source-folder picker previously decoded a 4000 x 3000 photo to 1000 x 750 pixels,
then displayed a centered crop in a much smaller cell. On Family Area the physical
cell is 528 x 352: the 1920-dp screen minus the 368-dp sidebar, 96-dp pane padding and
48-dp column gaps, divided by three and multiplied by the 1.125 display density.

The picker now supplies each cell's measured physical dimensions. On Portal API28/29,
ImageDecoder uses power-of-two sampling; a filtered Canvas draws the centered crop
into one cell-sized bitmap and the intermediate is recycled immediately. The bitmap-cache key includes the target size, so a different layout
cannot reuse an incorrectly sized image. Small originals are not enlarged during decode.
The plan bounds intended scaled/crop geometry using the existing slideshow limits.
Power-of-two sampling keeps enough input pixels for that drawing geometry.
API24-27 keeps the original sampled compatibility path. Video decoding is unchanged.

This preserves the centered ContentScale.Crop presentation. Software allocation allows
normal Compose drawing and scalar generated-pixel verification. The decoder requests
sRGB; normal bitmap-cache ownership, cancellation handling, two cold decoder slots and
the 16-MiB cache remain in place. Cached results still return before waiting for a slot.

## Verification

- Seven of nine new regressions failed against the extracted original sampling rule.
- Full gate: 1843 tests / 214 suites;
  zero failures, errors or skips. Debug and optimized benchmark builds passed.
- Nine geometry and three bitmap-ownership checks cover landscape, portrait, wide and matching-aspect originals,
  changed physical cell sizes, small images, invalid dimensions and extreme-aspect bounds.
- 76 offline native-runner checks passed. A parallel focused static review found no blockers.
- Two native Family checks used the actual approvedSourcesThumbnail loader with one generated
  4000 x 3000 JPEG each. Colored top/bottom bands must disappear from the centered crop;
  all four output corners and the center are checked against the generated center color.
- Both final checks returned 528 x 352 pixels, reused the same cached bitmap on the warm request,
  and removed both generated cache entries and the fixture file.

| Native check | Legacy returned bytes | New returned bytes | Legacy cold decode | New cold decode | Center crop verified |
|---|---:|---:|---:|---:|---|
| 1 | 3000000 | 743424 | 26 ms | 30 ms | True |
| 2 | 3000000 | 743424 | 26 ms | 25 ms | True |

For this generated image, returned bitmap allocation falls from 3,000,000 to 743,424 bytes:
75.22% fewer returned/cache bytes. A 16-MiB cache can hold 22 such cropped previews instead
of five legacy previews, ignoring other entries. This is arithmetic capacity, not a measured
large-library cache-hit rate or UI timing result. Codec-internal peak memory is not measured.

Candidate retained, Home restored, collector inactive, configuration/source unchanged.
No setting edits and no Living Area or phone operations. Exact APK/bundled/reference profile
mapping was verified; this does not prove compilation of each individual method.

## Research and limits

The first fused resize/crop candidate failed the generated center-color check. A bounded
follow-up retained only the fixed generated scalar schema: dimensions/bytes/cache identity
and fixture cleanup all passed, while centerCropVerified was false. Both failed trials
restored the preceding accepted build and verified Home/configuration/collector state.
The recorded preview decode was 80 ms versus 23 ms for its legacy generated decode.

Online research found [Android issue 139258905](https://issuetracker.google.com/issues/139258905),
whose public report describes correct output dimensions but a full image squeezed into
the target when setTargetSize and setCrop are combined, and working setTargetSampleSize
behavior. The Portal's failed crop is consistent with that reported failure. The final
implementation avoids the combined operation entirely and performs the filtered crop
in Canvas. Failed candidate evidence remains in sibling source-photo-crop and
source-photo-crop-proof diagnostic directories; final admission hashes both postflights.

The official [ImageDecoder reference](https://developer.android.com/reference/android/graphics/ImageDecoder)
documents API28 setTargetSampleSize and software allocation.
This implementation is not a region decoder and does not prove avoidance of all
intermediate native allocations. Reported native cold times are only two generated
samples, not a statistically supported speed improvement, picker FPS or total UI latency.
The native pipeline was verified on Family API29; API28 behavior shares the documented API,
and older compatibility behavior was preserved without a new API24-27 device trial.

Evidence: `~/portal-prime/build/fleet/diagnostics/source-photo-crop-fixed-2026-10-11`.
Candidate APK SHA-256: `ec27e7f31cf85ac9a2ec76b7478db97850e1af5185ece21eaac16fdf91ade51e`.
Source inventory SHA-256: `e5270352f00106badd227d87f4559dac69c9d3d78a57130258082a92c7da3d07`.
Postflight SHA-256: `6071247b9abc9d5a01e3f95b37a9236357a6fbb2bc97abe799eb83b8a8561490`.
Recorded at 2026-10-12T00:55:41.030030+00:00.
