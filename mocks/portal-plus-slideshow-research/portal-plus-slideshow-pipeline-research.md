# Portal+ slideshow: source, decode and cache evidence

Read-only research, 2026-10-07, working tree on `codex/portal-plus-approved-ux`.
Scope: acquisition, preparation, persistence and scheduling. Renderer/motion choices and
face detection are separate research; this memo covers the image contract they need.
**Facts** below come from source inspection; **hypotheses** describe possible performance
effects, not device measurements. No application changes, runtime tests or device operations
were performed.

Evidence notation: Kotlin filenames are under `app/src/main/java/com/immortal/launcher/`;
test filenames are under `app/src/test/java/com/immortal/launcher/`; engine asset references
include their repository paths. Line numbers describe the inspected working tree.

## Current source paths

`PhotoFrameSource.from` preserves folder, shared-album, Immich, WebDAV, SMB, default-feed and
web-page selections, carrying shuffle/video flags and authentication. Incomplete configuration
selects the default feed; reachability is handled later (`PhotoFrameSource.kt:64-103`).

| Source | Discovery and classic playback | Engine path / persistence |
|---|---|---|
| Local folder | Extension-based recursive scan, up to 2,000 matching items; optional videos. Images use bounds decode and EXIF correction on `io`; videos play original files through `VideoView`. | Images only, up to 300; first 80 candidates receive oriented header dimensions. Originals load until a scaled copy exists; warming starts after 20 seconds. (`LocalMedia.kt:81-110`; `PhotoFrameController.kt:329-385,1337-1435,1464-1494,2258-2272`) |
| Shared album | iCloud legacy/CloudKit, Google Photos and Synology resolve photo URLs; no video playback path. iCloud chooses covering derivatives, Google requests a screen-long-edge bounding box, Synology requests `xl`. Classic downloads on demand, refreshes on the configured interval and attempts at most three consecutive re-resolutions after a failed loop. | URLs go to WebView; no `MediaCache` activation. Engine refresh compares URL sets and recreates the WebView when they change. Browser caching is separate from the native cache. (`RemoteAlbum.kt:62-73,209-231,358-432,489-556,583-601`; `PhotoFrameController.kt:387-416,1522-1583,1717-1742`) |
| Immich | Paged metadata search, default cap 1,000. Images request `thumbnail?size=preview`; videos use playback endpoints; API-key headers accompany requests. Actual preview dimensions depend on the server. | Engine filters out videos and intercepts HTTP GETs with headers. Classic can persist image bytes and video derivatives when caching is enabled. (`ImmichSource.kt:46-62,89-141`; `PhotoFrameController.kt:418-441,586-605`) |
| WebDAV | Recursive `PROPFIND Depth:1`, default cap 1,000, with Basic auth; classified file URLs include videos when requested. Image GETs fetch the original file, without a client resize endpoint. | Same engine/classic split as Immich. (`DavSource.kt:31-38,65-116,142-152`; `PhotoFrameController.kt:451-473`) |
| SMB | Stateful SMB session; image-only recursive listing capped at 1,000. Each image stream is fully buffered before sampled decode; caption metadata opens a second stream. | Always follows native remote playback, including when an engine mode is selected. No persistent image cache. (`SmbSource.kt:48-112`; `PhotoFrameController.kt:480-505,793-800,1382-1391`) |
| Default / bundled | Classic tries optional Unsplash, then Picsum, Wikimedia and APK JPEGs; failed providers cool down for five minutes. Keeps six decoded bitmaps in session history. | Default engine receives 17 Picsum URLs, without the classic bundled fallback chain. Bundled assets themselves persist in the APK, but classic decoded history does not survive the controller. (`PhotoFrameController.kt:1779-1827,1962-2051,2274-2277`) |
| Web page | Selected URL owns its content; outside slideshow preparation. | Separate WebView branch (`PhotoFrameController.kt:325-328,519-557`). |

Engine routing uses photos where available; video-only local/Immich/WebDAV manifests continue
through classic playback (`PhotoFrameController.kt:365-379,427-440,459-472`).

`RemoteMedia` is the phone remote's now-playing/artwork service, **not** a slideshow source
(`RemoteMedia.kt:17-24,68-83`). Its tests do not validate photo loading.

## Cache and preparation facts

- **Native disk cache:** `MediaCache` lives in app `filesDir`, survives restarts, hashes the
  complete URL, stores fetched image bytes under `.jpg` names without recompression, and stores
  video derivatives under `.mp4`. Hits update mtime; commits use temporary-file rename and
  size-based LRU eviction. It neither validates source changes nor caches decoded bitmaps
  (`MediaCache.kt:30-115`; `PhotoFrameController.kt:2101-2122`).
- **Budget:** caching defaults off, with a default 4 GiB ceiling adjustable within 1–32 GiB
  (`ScreensaverConfig.kt:87-88,165-170`). Classic Immich/WebDAV compute
  `min(configured ceiling, max(0, currently free bytes − 2 GiB))`; below 256 MiB they disable
  caching. Video warming stops at 90% occupancy. Hidden download/transcode temps are excluded
  from resident accounting (`MediaCache.kt:119-152`; `PhotoFrameController.kt:2082-2093,2237`).
  This is a disk policy, not a RAM budget.
- **Local engine copies:** separate `cacheDir/ss_scaled`, keyed by path + size + mtime;
  2,400-pixel long edge, JPEG quality 88, 160 MiB disk budget. Copies are generated only for
  originals above 3,000 pixels on the long edge, with orientation baked in. Cache hits do not
  touch age; eviction is oldest-written, and trimming occurs after the warm loop, **not after
  each write**. This cache is independent of the Immich/WebDAV cache switch
  (`PhotoFrameController.kt:79-81`; `EngineScaledCache.kt:45-105,127-169`).
- **RAM:** classic local/remote photos decode again on each visit; their two display layers hold
  outgoing/incoming images during a transition and clear the outgoing reference afterwards.
  Only the default feed has six-image bitmap history. The controller has no decoded-byte
  admission budget shared with the engine
  (`PhotoFrameController.kt:1337-1351,1615-1634,1779-1788,1847-1874`).
- **Video warming:** one separate worker snapshots upcoming videos in playlist order, skips the
  current item, downloads a source temp, transcodes, then commits. Derivatives preserve displayed
  aspect inside 1,200×800, remove audio, target 1.2 Mbps and allow ten minutes per export.
  This does not prefetch still images (`PhotoFrameController.kt:2151-2205,2246-2251`;
  `VideoTranscoder.kt:56-63,103-125,128-177`).

## Findings: retain and fix

1. **Fix the claimed native decode bound. Fact:** `highestOneBit(longest / 2560)` selects
   sample 1 for a 4,000×3,000 photo, leaving all 12 MP decoded; an 8,000-pixel edge gets sample 2
   and still yields 4,000 pixels. Native decoding has no final resize
   (`PhotoFrameController.kt:1369-1391,2253-2258`). Local EXIF transformation allocates a second
   bitmap before recycling the first (`PhotoFrameController.kt:1430-1435`).
   **Hypothesis:** transient allocation/GC and texture upload can exceed the intended safety
   margin. Retain bounds-first decoding, but enforce both output dimensions and pixel bytes,
   including transformation overlap.

2. **Unify orientation before handing off framing metadata. Fact:** classic local files handle
   all eight EXIF orientations; SMB streams, HTTP images and disk-cache hits call sampled decode
   without that correction (`PhotoFrameController.kt:495,1405-1435,2063-2070,2101-2111`).
   WebDAV serves arbitrary originals, so the controller comment that all HTTP sources strip
   EXIF is not a reliable contract. Engine local dimension seeds account for quarter-turns and
   its loader delegates pixel orientation to Chromium (`PhotoFrameController.kt:582-585,1444-1461`).
   **Hypothesis:** rotated/mirrored originals produce inconsistent displayed dimensions and
   framing coordinates across paths. Retain the whole image; publish upright dimensions and
   an explicit coordinate transform with prepared pixels.

3. **Prioritize first-image work. Fact:** one `io` worker handles discovery, image reads,
   calendar and weather. `start()` invokes calendar/weather scheduling before source discovery
   (`PhotoFrameController.kt:79,263,320,329-330,1229-1234,1284-1292`). Local engine startup waits
   for the complete enumeration and up to 80 header/EXIF reads. Shared albums finish their
   crawl before publication: CloudKit permits 50 pages, Google 100 continuation pages, Synology
   100×500 items (`PhotoFrameController.kt:329-365`; `RemoteAlbum.kt:244-245,295-339,406-430,480-554`).
   Engine startup also awaits up to six `Image` loads, with concurrency six, to measure dimensions
   before starting the first scene; those loads use original URLs
   (`app/src/main/assets/screensaver/photos.js:60-78`;
   `app/src/main/assets/screensaver/index.html:76-93`).
   **Hypothesis:** slow metadata, large folders or one slow image delay first paint. Prioritize a
   usable first item; fill the remaining manifest and dimensions incrementally.

4. **Make offline readiness explicit. Fact:** the classic bundled fallback sits after network
   providers, whose reads/connects have multi-second timeouts; the default engine supplies only
   Picsum URLs. The native photo root starts black
   (`PhotoFrameController.kt:749-761,1802-1827,1962-1986,2035-2070`).
   **Hypothesis:** an offline cold start can show overlays/loading over an empty photo surface
   before fallback completes; engine has no equivalent native bundled handoff. Prepare one
   local fallback early and keep the current image until its replacement is ready.

5. **Bound queued work and own cancellation. Fact:** controller executors use
   `newSingleThreadExecutor`, with no queue admission or image-job cancellation; generation checks
   reject results only after decode. `loadFresh` has no generation guard. `stop()` interrupts
   executors/removes callbacks but neither invalidates `gen` nor clears local/remote mode, so
   late workers can post again (`PhotoFrameController.kt:79,84,192,709-743,1337-1351,1615-1634,1779-1788`).
   `PhotoSourceWork` offers one worker + queue capacity two, transport ownership and cancellation,
   but its checkpoints/connection tracking require its thread-local request. The slideshow
   controller never calls `load`; its downloads open raw connections, and SMB therefore takes the
   default client branch (`PhotoSourceWork.kt:28-108`; `SmbSource.kt:49-65`;
   `PhotoFrameController.kt:2063-2070,2115-2137`). Home uses the wrapper
   (`ApprovedHomePhoto.kt:90-97`).
   **Hypothesis:** rapid navigation accumulates obsolete decodes; stalled transports or late
   callbacks outlive exit. Extend the ownership pattern to session/asset jobs, with bounded
   queues, stale-job removal and checks before work, cache commit and UI publication.

6. **Move cache housekeeping off the UI path. Fact:** Immich/WebDAV call `enableMediaCache`
   inside `ui.post`; it reads free space, constructs the cache, sweeps temps and enforces its
   budget. Video cache hits stat/touch files directly in `advanceRemote`
   (`PhotoFrameController.kt:423-441,456-473,1603-1611,2082-2092`; `MediaCache.kt:37-45,59-63,107-115`).
   **Hypothesis:** large cache directories cause startup/navigation stalls. Prepare cache state
   on a worker and publish a ready handle; retain atomic commits and corrupt-video recovery.

7. **Limit encoded buffers and improve reuse. Fact:** SMB/default/native HTTP decode paths use
   unbounded `readBytes`; authenticated WebView interception also materializes full response
   bytes (`PhotoFrameController.kt:1382-1391,597-604,2107-2122`; `Http.kt:62-71`).
   Classic SMB/local revisits redo decoding; SMB captioning opens another stream.
   **Hypothesis:** large originals add encoded buffers, allocation copies and repeat network
   traffic to pixel memory. Use bounded streaming/spooling, share one acquired asset between
   decode/metadata consumers, and retain only a small prepared working set.

8. **Complete source-to-consumer header propagation. Fact:** Synology returns the required
   sharing cookie in `Album.headers`, but slideshow startup sets empty headers and engine startup
   omits them; refresh/re-resolution also extract only URLs
   (`RemoteAlbum.kt:483-493,556`; `PhotoFrameController.kt:390-405,1530-1534,1564-1572,1728-1734`).
   This is a confirmed handoff gap; whether a particular server rejects thumbnail loads needs
   integration verification. Carry immutable URL + headers + asset identity together.

9. **Make cache capacity a reservation, not an after-write check. Fact:** video temps are
   uncounted/unbounded, free-space budget is computed only at enablement, and image puts can
   evict despite the video warming stop. Local scaled copies trim only at pass end, and
   URL-only native keys have no ETag/mtime/derivative-version validation
   (`MediaCache.kt:48-51,71-89,107-152`; `EngineScaledCache.kt:64-69,127-146`).
   **Hypothesis:** large clips consume the OS reserve, oversized albums churn, and edited stable
   URLs stay stale. Reserve temp + output space before acquisition, recheck actual free space,
   pin active entries, trim incrementally and version prepared derivatives. Local engine
   `photoUrl` memoizes a successful scaled URL even if its file is later evicted
   (`app/src/main/assets/screensaver/photos.js:90-96`); make readiness revalidatable.

10. **Retain video ordering, fix edge cases. Fact:** an uncached one-item video playlist cannot
    populate its cache because the current item is always skipped. An already-running pass
    does not reprioritize when navigation changes. Interrupting `latch.await` is converted to
    failure without restoring interruption, while controller `stop()` leaves `remoteMode` true
    (`PhotoFrameController.kt:709-743,2166-2195,2246-2251`; `VideoTranscoder.kt:115-125`).
    **Hypothesis:** background work can compete with a now-current stream or continue after stop.
    Add explicit session cancellation checks after download/export, demand-job deduplication
    and a bounded way to populate a single-video cache after playback.

## Pixel memory at 2160×1440

ARGB_8888 uses four bytes per pixel: `2160 × 1440 × 4 = 12,441,600 bytes`.
These are pixel payloads, excluding encoded input, decoder scratch, object overhead, WebView,
GPU textures/compositor buffers and video surfaces; actual process/device use needs measurement.

| Live pixel allocations | Bytes | MiB |
|---|---:|---:|
| One screen image | 12,441,600 | 11.87 |
| Two transition images | 24,883,200 | 23.73 |
| Two transition images + one prepared next | 37,324,800 | 35.60 |
| Six screen-sized history images | 74,649,600 | 71.19 |
| One 4,000×3,000 original, current native sample = 1 | 48,000,000 | 45.78 |
| That original + its same-size EXIF transform | 96,000,000 | 91.55 |
| Engine preparation: that original + 2,400×1,800 output | 65,280,000 | 62.26 |

Even a correctly capped 2,560-square bitmap costs 25 MiB; an edge cap alone is insufficient
for an album-wide memory policy. A compressed disk-cache budget does not bound decoded RAM.

## Proposed bounded preparation contract

Preserve current sources, authentication, shuffle, interval, refresh, video eligibility,
fit/mode selection and cache settings through existing adapters/registry. This is a proposal,
not permission to change source capabilities or persisted defaults.

1. Give each session an immutable source snapshot and cancellation token. Publish manifest
   batches under item, visited-directory, byte and deadline limits. Matching-item caps alone
   do not bound empty/irrelevant directory traversal (`LocalMedia.kt:86-108`;
   `SmbSource.kt:71-94`; `DavSource.kt:88-112`).
2. Use one demand preparation worker, at most one pending replacement and one next-item
   lookahead. Coalesce navigation to the latest requested item; remove stale queued work.
   Defer calendar, captions, manifest completion and bulk warming until the first image is
   usable. Keep one independently cancellable video export, admitted only when demand work
   has CPU/codec/disk headroom.
3. Return a prepared record: opaque asset ID/version, ready local handle or bitmap, upright
   dimensions, orientation transform, byte cost and optional externally supplied framing
   metadata in upright normalized coordinates. Preserve full-image extent; detection and
   crop/motion policy belong to their respective research. Missing metadata must not delay
   publication; it must remain distinguishable from an intentional center focal point.
4. Decode to viewport/consumer needs while preserving aspect and enforcing a pixel-byte ceiling.
   Count incoming, outgoing, next and temporary orientation/resize allocations before admission.
   Start evaluation with a **48 MiB total native pixel budget**, maximum three retained prepared
   images, reducing lookahead/resolution when transforms would exceed it. This is a profiling
   candidate, not a measured safe heap limit; renderer/GPU residency needs its own accounting.
5. Keep native media-cache opt-in semantics and the separate local-copy policy explicit.
   Use bounded encoded transfers; stream large files to reserved temps, validate before atomic
   commit, key derivatives by asset version + orientation + preparation size/version, and
   protect files currently displayed. Signed-URL refresh should update access handles without
   treating every signature change as a new asset when a stable provider ID is available.
6. Start with a prepared bundled image or previous valid image; replace only on readiness.
   Use one-item/one-loop failure limits, provider cooldowns and stale-result rejection across
   discovery, decode, video and cache commit. Keep the existing shared-album re-resolution
   cap and aspect-preserving muted video derivatives.

## Coverage and validation gaps

Inspected existing coverage: `PhotoFrameSourceTest.kt:16-75` checks selection/incomplete-source
fallback; `PhotoFrameControllerTest.kt:11-69` checks video geometry and prefetch ordering;
`PhotoSourceWorkTest.kt:35-100` tests fake transport/socket cancellation and cleanup;
`MediaCacheTest.kt:33-143` covers file round trips, LRU, temps, occupancy and budget math;
`EngineScaledCacheTest.kt:18-84` checks sizing/key/eviction math and file resolution, explicitly
without bitmap decoding. `LocalMediaTest.kt:18-55`, `ImmichSourceTest.kt:15-38` and
`RemoteAlbumTest.kt:12-237` primarily exercise classification, URL/auth construction and parsing;
`VideoTranscoderTest.kt:21-60` tests geometry, not codec execution.

Needed before implementation sign-off:

- Real sampled-decode output/byte bounds; all EXIF rotations/mirrors across local, SMB, DAV and
  cache hits; orientation-consistent framing metadata; corrupt/oversized/animated inputs.
- Controller stop during discovery/read/decode/export, callbacks queued after exit, rapid
  navigation/backpressure, stalled transports and no stale cache commits. Wrapper cancellation
  tests do not establish controller cancellation.
- Slow/failed pagination, header propagation including Synology cookies, signed-URL refresh
  identity, partial manifests and first usable image without waiting for the whole library.
- Actual local enumeration caps, empty-directory limits and unreadable-only playlists.
  `ApprovedSourcesBoundedMediaScanTest.kt:16-49` exercises `approvedSourcesSummary`, a separate
  picker scanner, not the slideshow's `LocalMedia.enumerate`.
- Oversized entries, temp-space reservations, concurrent commit/purge, scaled-cache eviction
  during use, cache-disabled behavior and single-video prefetch.
- Cold/offline and warm startup, image-to-video-to-image readiness, peak native/renderer memory,
  main-thread disk work and repeat-loop network/decode counts at 2160×1440. No performance
  numbers or test-pass claims are established by this source-only memo.
