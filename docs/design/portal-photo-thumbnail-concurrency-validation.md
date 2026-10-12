# Portal thumbnail concurrency validation

Thumbnail cache misses previously decoded images and compressed JPEGs while holding
the photo read/delete/publication lock. Thumbnail requests now capture an opened file
and cache ticket under that lock, then validate, decode and compress outside it.
The opened descriptor preserves file identity across cooperating delete/reupload routes.

Pixel decoding is limited to two workers. The encoded-byte LRU remains bounded to
4 MiB, 32 entries and 512 KiB per thumbnail. Generation tickets captured with the
file prevent an in-flight old request from restoring cached bytes after deletion.
Cache hits still validate image magic, dimensions and the 100-million-pixel limit;
these checks now use the opened descriptor outside the photo mutation lock. Cold
loads reuse the validated bounds instead of parsing the bounds a second time.
The existing invalid_image and thumbnail_unavailable responses remain distinct.

## Verification

- Eight new JVM tests cover cache-hit access during slow decoding, concurrent misses,
  two decoder slots, clear/in-flight races, stale tickets, byte/entry eviction,
  exceptional/oversized loads, and fixture-only cache removal.
- Four tests failed against the initial extracted serial cache policy; all eight pass
  after moving decoding outside the cache monitor and adding bounded slots.
- Final full gate: 1825 tests in 211 suites,
  zero failures/errors/skips. Debug and optimized benchmark builds passed.
- 45 offline native-runner checks passed. Parallel static review caught a response-code
  regression, which was fixed and covered by an actual invalid-image device request.
- Family Area only: two generated 1024 x 768 JPEG fixtures, through the actual paired
  photo routes. The pixel-decode hook paused the cold thumbnail while a full photo read
  finished, then released the decode and checked a second request reused the cached bytes.

| Native check | Original JPEG bytes | Read while decode paused | Thumbnail dimensions | Thumbnail bytes | Cache reused |
|---|---:|---:|---|---:|---|
| 1 | 5448 | 4 ms | 512 x 384 | 1992 | True |
| 2 | 5448 | 1 ms | 512 x 384 | 1992 | True |

Both checks verified read bytes, thumbnail dimensions, HTTP415/invalid_image for a
malformed fixture, terminated workers and removal of fixture files/cache entries.
Other cached photos are preserved by the fixture-only discard. Timed-out diagnostic
workers retain their lease until termination and cleanup, preventing pool accumulation.

The candidate remains installed, Home restored, collector inactive and exact
configuration/source equality verified. Zero settings, phone or Living Area changes.
Whole-profile/bundled profile/DEX mapping passed; individual method compilation is not
inferred from these profile checks.

## Research and limits

Android's [BitmapFactory reference](https://developer.android.com/reference/android/graphics/BitmapFactory)
provides decodeFileDescriptor since API1 and specifies that descriptor position remains
unchanged. The route owns and closes the descriptor and explicitly rewinds before header
inspection and sampled decoding. This is supported on the Portal's API29 hardware.

These two generated fixtures establish functional concurrency and cache behavior, not
UI latency for a large library, sustained 60FPS, network throughput, peak process memory
or a CPU percentage reduction. The Fleet HTTP worker-pool capacity is unchanged.
The two-slot decode limit can still permit duplicate work on two simultaneous cold
requests for the same image; warm hits and later queued requests reuse cached bytes.

Evidence: `~/portal-prime/build/fleet/diagnostics/photo-thumbnail-concurrency-2026-10-11`.
Candidate APK SHA-256: `57cf955f0fede7d530f08a658e713da6cf4415b5745ce58d8ad739e7b8b8c509`.
Source inventory SHA-256: `456c9b620971d77df342ee97a05de7e7526964bfa21c8e046d6f7826af9a5e4f`.
Postflight SHA-256: `cfdeb618cf410b6ee0509a0f3e213dfd7025f531460d3d09478f68f8d811a6bd`.
Recorded at 2026-10-12T00:15:21.834199+00:00.
