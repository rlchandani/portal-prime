# Portal source-preview cache fast path validation

The photo-source picker acquired a decode semaphore before checking its bitmap cache.
An already loaded preview could consequently wait behind both active cold decoders.
The loader now checks the thread interruption flag and current file/cache key before
acquiring a permit. A cache hit returns immediately; only cold work waits for capacity.

After a wait it checks interruption and recomputes file metadata/cache state before
decoding, so queued requests can reuse work completed by another loader. The bitmap
cache remains at 16 MiB and active cold decoding remains capped at two workers.
Photo/video rendering and normal bitmap ownership are otherwise preserved, including
the existing bounded video-frame decode and canceled-decode recycling behavior.

## Verification

- Two of six new JVM tests failed with the original slot-first policy after extraction
  into the production helper: a warm hit waited behind exhausted capacity, and a queued
  request did not perform an initial lookup.
- All six pass with the fast path. They cover occupied slots, current-cache refresh after
  waiting, two cold decoders, interruption, decoder exceptions and missing images.
- Full gate: 1831 tests / 212 suites,
  zero failures/errors/skips. Debug and optimized benchmark builds passed.
- 45 offline native-runner checks passed. A parallel static review found no blocker.
- Family Area only: each native check used the actual approvedSourcesThumbnail loader
  with three generated 128 x 128 JPEGs in private cache. One preview was prewarmed,
  then both cold decoder callbacks were paused while a third worker requested the warm
  item. The identical cached bitmap returned before either cold worker was released.

| Native check | Warm request during occupied slots | Cached dimensions | Cached bitmap bytes | Cold decode callbacks | Identity reused |
|---|---:|---|---:|---:|---|
| 1 | 1 ms | 128 x 128 | 65536 | 2 | True |
| 2 | 1 ms | 128 x 128 | 65536 | 2 | True |

Both checks verified worker termination, fixture file/cache removal and recycling of
caller-owned generated bitmaps. A timed-out diagnostic group retains its lease and
bitmap ownership until all its workers terminate; no extra cleanup thread is created.
Normal user cache entries are not explicitly removed by the fixture cleanup.

Candidate retained, Home restored, collector inactive, source/configuration unchanged.
No settings, phone or Living Area operations. Exact APK/bundled/reference profile mapping
was verified; individual method compilation is not inferred from whole-profile evidence.

## Research and limits

Android's [LruCache reference](https://developer.android.com/reference/android/util/LruCache)
explicitly documents thread-safe individual get/put operations and byte-based sizeOf
limits. The fast lookup uses that existing cache rather than adding another lock around
pixel decoding. It does not make a compound lookup/decode/put atomic; two simultaneous
cold misses for the same item may still decode together, as previously allowed.

The native measurements are generated, prewarmed fixtures under deliberately occupied
capacity. They establish the cache-path fix, not total picker UI latency, large-library
scroll FPS, network throughput, CPU percentage or a peak-memory reduction.

Evidence: `~/portal-prime/build/fleet/diagnostics/source-cache-fastpath-2026-10-11`.
Candidate APK SHA-256: `d81a057a830ed888eab51c1d90a56a545a7b9838d59a01174d9d52c54f737c9f`.
Source inventory SHA-256: `ec35ebd47b6d8d71bd0a71be7156a863a56dcc9a53fb46f50b9935e98f116c9f`.
Postflight SHA-256: `c20f96e339b6998fd438ae45dbb87a5bc97b4de715cb21ac962c9415b9458c3e`.
Recorded at 2026-10-12T00:27:36.390011+00:00.
