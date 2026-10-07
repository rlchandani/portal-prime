# Portal+ slideshow: face-aware cropping evidence memo

Research date: **2026-10-07**. Reviewed the working tree on `codex/portal-plus-approved-ux`, including its existing changes. **Observed** means source inspection, package inspection, or an explicitly attributed published/documented finding. **Inferred** means a consequence of that evidence, without a new device measurement. **Recommended** means a proposed policy, not an implemented change.

**Recommended decision:** retain bundled ML Kit provisionally; fix the image-coordinate, cache, and crop/motion contracts before considering replacement. Preserve whole-photo framing as the dependable default. Detection can improve composition, but neither the current focal point nor any detector guarantees that every person's face will be found and protected.

Scope was read-only research plus this memo. No application/settings changes, device access, keys, personal photos, image uploads, recognition, builds, or mocks. Kotlin file references below are relative to `app/src/main/java/com/immortal/launcher/`; JS references are relative to `app/src/main/assets/screensaver/`. Test and other paths are stated explicitly.

## What the existing implementation does

**Observed — code:**

| Stage | Actual behavior | Evidence |
| --- | --- | --- |
| Dependency | Bundled `com.google.mlkit:face-detection:16.1.7`. App minimum API 24, target 36; ABI filters include `arm64-v8a` and `armeabi-v7a`. AndroidX EXIF support already exists. | `app/build.gradle.kts:33–44,123,138–141`; `gradle/libs.versions.toml:17,22` |
| Input | Reads the entire compressed file or HTTP response into memory, then uses bounds decoding and power-of-two sampling targeting a 320px long edge. Accepts `file://` and strings starting with `http`; no `content://` or SMB stream support. | `FaceFocusStore.kt:142–155` |
| Detector | FAST mode, minimum desired face size `0.06`; landmarks, contours, classification, and tracking remain at their disabled defaults [S1]. Uses `InputImage.fromBitmap(bmp, 0)` and waits up to 10 seconds for each inference. Reads only bounding boxes. | `FaceFocusStore.kt:91–96,126–137,160–162` |
| Reduction | Discards boxes whose width **or** height is below 4% of the bitmap's short side. Focus is halfway between the union-box center and the area-weighted face centroid, normalized and clamped to `[0,1]`. | `FaceFocus.kt:31–40,62–79` |
| People score | `clamp(faceCount × 0.25 + summedFaceArea/imageArea × 4, 0, 1)`. It counts retained boxes; overlapping areas are summed, not unioned. Four retained faces already saturate the count term. This is a heuristic, not detector confidence or recognition. | `FaceFocus.kt:47–55` |
| Stored result | App-private `filesDir/face_focus.json`: exact URL → `"fx,fy,weight"`; `""` means analyzed with no retained faces. No boxes, confidence, source revision, orientation, or model version survive. | `FaceFocusStore.kt:35–54,107–109,130–135` |

The SDK's requested 6% minimum is relative to **image width**, whereas the reducer's strict 4% filter uses **short side and both dimensions** [S1]. They are different selection criteria, and lowering either cannot recover detail lost during downsampling.

## Which slideshow paths use it

**Observed — code:** `FaceFocusStore` is constructed only in `PhotoFrameController.startEngine()`. The controller serializes cached focus to three decimals and weight to two, then starts a separate warm-up thread. `getPhotos()` returns that fixed JSON string; `photos.js` reads it once. New results affect a subsequent engine start, including an album-triggered restart, not the current deck (`PhotoFrameController.kt:617–647,1550–1573`; `photos.js:16–21,38–58`).

| Path/style | Face-data effect today |
| --- | --- |
| Native Classic, native SMB playback, native videos | **None.** Native photos use `FIT_CENTER` or `CENTER_CROP`, with centered 1.12× zoom/vertical pan in fill mode. SMB takes the native path even when an engine style was selected. Evidence: `ScreensaverConfig.kt:52–81`; `PhotoFrameController.kt:480–499,962–968,1889–1934,2212`. |
| Gallery wall | Calls `faceBg()` for tile backgrounds. Random zoom/translation can still clip faces; the layout may place outer columns beyond the screen. Evidence: `mode-impls.js:95–110`; `styles.css:57–61`. |
| Mosaic | Cover tiles use focus percentages. Mismatched aspect ratios use `panStart()` to choose the initial animation phase, then traverse the whole image. Evidence: `mode-impls.js:233–249`; `modes.js:127–155`. |
| Diptych / hero+sidebar | Active foreground paths use **contain**, without face data. Diptych's face-aware `coverCell()` exists but is never called. Evidence: `mode-impls.js:57–65,304–323`; `styles.css:41–42`. |
| Ken Burns / push / Memories singles | Contain foregrounds with motion; no focus anchoring. Contain before zoom does not ensure the full photo remains visible throughout a clipped viewport. Memories collages use cover without focus. Evidence: `mode-impls.js:20–27,40–45,129–133,147–153`; `styles.css:3,20–29,78–86`. |
| Panorama / cinema / other display styles | No face-driven composition. Panorama tours the image; wide cinema images use animated cover. Framed/polaroid/filmstrip size photos by aspect, but do not protect detected face bounds. Evidence: `mode-impls.js:80–85,165–172,205–213,285–298`. |
| People ordering | At each mode beat, host presence can sort a copy of the deck by `pw`; the rotating cursor and orientation selection still govern picks. This is reordering, not guaranteed people frequency. Evidence: `PhotoFrameController.kt:661–665`; `scheduler.js:76–83,111`; `modes.js:81–101,117–125`. |

An arbitrary configured web page has its own rendering, outside this pipeline (`PhotoFrameController.kt:519–544`). The engine bridge also sends no `fit` value: the native fit/fill choice does not select the engine's crop policy (`PhotoFrameController.kt:645–665`; `index.html:88–93`).

## Crop, orientation, and group edge cases

- **Observed / inferred — wrong coordinate frame for EXIF images.** Detection does not read EXIF and always passes rotation zero. Local engine dimensions are explicitly oriented; scaled copies bake in all rotation/mirror cases. The controller comments expect Chromium to orient originals. Consequently, any detection from raw sensor-oriented pixels is not mapped into the displayed coordinate system; rotated faces may also be missed. Actual behavior of this Portal's WebView was not measured. Normalize pixels once, including mirrored/transposed EXIF cases, and express both boxes and dimensions in that upright frame (`FaceFocusStore.kt:129,142–155`; `PhotoFrameController.kt:582–585,1405–1461`; `EngineScaledCache.kt:89–123`). For example, a raw point `(x,y)` becomes `(1−y,x)` after a clockwise quarter-turn; dividing by swapped dimensions alone does not perform that transform.
- **Observed / inferred — a point is not a protected region.** The union/centroid blend can favor a large foreground face over a distant person; the union's extent is discarded. A focus on empty space between two people cannot tell whether either face fits a narrow tile. The 4% filter can remove real small group faces. Out-of-bounds boxes are not clipped/rejected before filtering or area weighting; only the final point is clamped (`FaceFocus.kt:47–79`).
- **Observed / inferred — percentage positioning is not centering.** `background-position: fx% fy%` aligns the image's fractional point with the **same fractional point of the container**, per the CSS specification [S16]. It does not put that point at container center or protect a box. On one axis, with visible image fraction `v`, today's crop origin is `f(1−v)`; centering would require `clamp(f−v/2, 0, 1−v)`. Motion needs additional bounds checks. Also, `bg()` writes inline `"center"`, overriding `.ss-img`'s `center 25%`: the documented top-biased fallback is not what these callers use (`mode-impls.js:15–18`; `styles.css:35`).
- **Observed / inferred — pan phase and travel differ.** `panStart()` computes a linear spatial fraction, but mosaic applies it as a negative time delay to an `ease-in-out` animation. Nonlinear easing changes the actual starting location. Even an accurate start would protect only the initial point: alternate pans deliberately leave it later (`modes.js:133–138`; `mode-impls.js:238–245`; `styles.css:64–69`).
- **Observed / inferred — coarse input limits recall.** Sampling usually produces a long edge around **320–639px**, not exactly 320; smaller originals remain smaller. Google's guide recommends at least 480×360 input and roughly 100×100 pixels per face [S1]. A face 6% of a 500px image width is only 30px wide. Small/distant faces, profiles, blur, and partial faces remain uncertain; `""` cannot mean “safe to crop anywhere.”
- **Observed — tests cover reduction only.** `app/src/test/java/com/immortal/launcher/FaceFocusTest.kt:19–91` checks empty/single/pair/dominant faces, clamping, tiny boxes, dimensions, and scoring. It does not exercise ML Kit, EXIF/mirrors, cache freshness, CSS geometry, group containment, or motion safety. These tests were read, not run.

## Cache freshness and likely costs

**Observed — code:** successful three-field entries and empty negative entries persist indefinitely. Only missing entries and older two-field positives are reanalyzed. URL reuse after image edits yields stale data; rotating signed URLs create new entries and repeat work. The store has no TTL, pruning, schema/model revision, or validation of parsed finite/range values (`FaceFocusStore.kt:35–73,82–88`). In contrast, the existing scaled-image cache uses path, length, and modification time (`EngineScaledCache.kt:45–50,96`).

**Observed / inferred — scheduling and persistence:** the cache loads/parses synchronously while the startup JSON is built. Warm-up starts immediately, before `wv.loadUrl()`, at ordinary thread priority. It has no cancellation/ownership in `stop()`, so restarts can overlap work. `persist()` rewrites the whole map after each attempted item; synchronization is per store instance, with a direct non-atomic write. Separate instances can overwrite newer entries; interruption during a write can force a full reload/reanalysis. Across a growing cache, total serialization/writing can grow quadratically (`FaceFocusStore.kt:40–54,99–114`; `PhotoFrameController.kt:621–642,702,709–743`).

**Observed / inferred — cost limits:** each pass performs a full compressed read/download, a small decode, inference, and cache serialization. It does not reuse scaled derivatives or WebView/MediaCache image bytes. Network work may duplicate display downloads and compete with first paint. Local engine lists cap at 300 photos, but `warmUp()` itself has no batch cap; the local scaled cache waits 20 seconds and uses low priority, unlike face warm-up (`PhotoFrameController.kt:348,372,2265–2267`; `EngineScaledCache.kt:57–76`; `photos.js:60–78`; `index.html:76–78`).

An **arithmetic estimate**, not a memory measurement: a 4000×3000 image samples to approximately 500×375, or **0.72 MiB** at four bytes/pixel. Compressed bytes, detector native state, temporary buffers, and display textures are additional and unmeasured. The 10-second timeout covers the inference wait only, not initialization/download/decode; eight consecutive failures abort a pass, then can recur next start. No explicit task cancellation accompanies a timeout, yet the bitmap is recycled in `finally`—a lifetime concern to verify, not an observed crash (`FaceFocusStore.kt:91–114,126–155`).

## Bundled offline options for API 29 / arm64

**Observed — official sources/package; recommendations explicitly identified:**

| Option | Compatibility and tradeoff | Decision |
| --- | --- | --- |
| Existing bundled ML Kit | Current official release table still lists **16.1.7**, last updated 2024-08-07 [S3]. Guide body requires API 23 and documents bundled offline use, approximately 6.9 MB app-size impact [S1,S2]. Read-only inspection of the existing cached `face-detection-16.1.7.aar` found model assets and `jni/arm64-v8a/libface_detector_v2_jni.so`; this establishes packaging, not Portal execution. `com.google.android.gms.tasks.Tasks` imports do not establish a dependency on an installed Play Services app. | **Retain provisionally.** Already integrated; no published newer face version to upgrade to. Fix pipeline correctness and investigate the historical stall. |
| Play Services ML Kit variant | `com.google.android.gms:play-services-mlkit-face-detection:17.1.0` relies on Play Services to download/manage the model [S1,S2]. Offline-after-download is insufficient for this firmware. | **Reject** for a Portal without Play Services. |
| MediaPipe Tasks Face Detector | Android setup requires API 24; CPU is the default delegate. Model files can be packaged as assets and processed in IMAGE mode [S5,S6]. Official build mappings support Android arm64 [S8]. Current official sample pins `tasks-vision:1.0.0` [S17]; validate the exact selected AAR rather than using the guide's floating `latest.release`. Full-range BlazeFace is the more relevant candidate for ordinary album photos; short-range is optimized for selfies [S7]. | **First replacement candidate if needed.** No inference key or Play Services model installation. Native runtime/model integration and group recall need evaluation; no supported claim that it is faster or more accurate here. |
| OpenCV `FaceDetectorYN` + YuNet | Java detector API accepts local models and CPU backend/target selection [S13]. OpenCV's 4.x Android defaults include arm64 at API 21; its AAR builder defaults to minimum API 21 [S11,S12]. Bundle the native library and ONNX model, load locally with `initLocal()` [S10]. Pin a compatible pair: current Zoo notes distinguish the 2023 model for OpenCV 4.x from the May-2026 dynamic model for OpenCV 5.x [S14]. | **Second replacement candidate**, especially for a controllable open-source pipeline. Model plus OpenCV runtime is the footprint, not model alone. Avoid adopting a source-build configuration with a native API floor above 29. |
| Android `android.media.FaceDetector` | Platform API since level 1, no added model/service. Requires even image width and RGB_565. Returns eye midpoint/distance, confidence and pose, rather than modern face rectangles [S15]. | **Do not replace with this by default.** Estimating protective boxes adds heuristics; documentation does not establish adequate album/group recall. Static contain is the safer fallback. |

**Observed — historical repository evidence:** `docs/superpowers/specs/2026-09-19-screensaver-polish-diagnosis.md:240–250` records no populated cache on a tested Portal and a warm-up stall before the “building detector” stage. Its suggested native/API incompatibility is **inferred, not established**. Today's code can load/filter the cache and return with no pending entries before that log (`FaceFocusStore.kt:80–96`); absent later logs alone cannot localize a native failure. API/ABI eligibility does not resolve this device-specific uncertainty.

**Observed — privacy distinction:** ML Kit says input data **and outputs** stay on-device, while operational metrics may be sent [S4]. MediaPipe likewise documents local input processing and metrics/network contacts [S9]. Offline inference should not be described as zero network activity. None of these recommended detection paths requires photo upload, identity recognition, a cloud API, or an inference key. A direct bundled LiteRT/ncnn model would add preprocessing/postprocessing maintenance; it is not a drop-in fix for this crop contract.

## Practical low-cost crop and motion policy

All numeric values below are **recommended tuning starting points**, not published Portal benchmarks.

1. **Make whole-photo rendering the fallback.** Keep contain for portraits, wide groups, uncertain/missing/stale analysis, and extreme aspect mismatches. Prefer a solid or cached color matte to continuous blurred duplicate imagery when minimizing work. Static foreground plus crossfade is valid; the slideshow must start and exit without waiting for detection.
2. **Analyze the displayed coordinate system once.** Reuse an upright local/display derivative; apply all EXIF rotation/mirror cases before detection and normalize boxes against its actual dimensions. Keep detection in Android, outside JS animation. One low-priority, cancellable worker, one image in flight, bounded batches, delayed until startup settles; reuse available bytes rather than issue a face-only GET.
3. **Trade recall against work conservatively.** Start with a bounded roughly 640px-long-edge derivative, FAST boxes only. For crop-sensitive photos, optionally allow one deferred higher-resolution pass, capped around 1280px, when useful detail exists. Do not upscale tiny sources expecting improved recall. A pass with faces far below the guide's approximately 100px guideline remains uncertain. No detected faces is not proof of no people; avoid aggressive crops on that basis.
4. **Cache a region and its provenance.** Retain normalized boxes, their padded union, upright dimensions, source revision, detector/options/resolution version, status, and analysis time. Distinguish success/no detection, failure, and pending. Use local size/mtime or source asset/revision/validator metadata; do not simply strip signed-URL query parameters. Expire negatives, invalidate on image/model/policy changes, prune unused entries, batch atomic writes, and serialize workers across controller restarts.
5. **Choose a crop that contains every retained face region.** Start with about 20% box padding and additional top headroom for hair; clamp to image bounds. For upright image `W×H`, viewport `Vw×Vh`, and padded union `Bw×Bh`, cover requires `s ≥ max(Vw/W,Vh/H)`, while face containment requires `s ≤ min(Vw/Bw,Vh/Bh)`. If these cannot both hold, use contain or an aspect-compatible tile. Clamp translation against image edges **and** the protected region; calculate pixel geometry rather than copying focal percentages.
6. **Keep the region safe throughout motion.** Prefer static framing for groups/edge faces. Otherwise, initially cap extra zoom near 3% above the selected base scale, reduce it to the safe limit, and use one gentle bounded path. Validate the entire path in a consistent coordinate frame; shared monotonic interpolation of scale/translation avoids independent random drifts. Skip motion when no safe path exists. Do not tour away from a face merely because the opening view was safe.
7. **Apply one geometry policy to native and WebView rendering.** Respect whole-image fit, use actual tile/viewport dimensions, and account for measured overlay occlusion. Publish completed metadata at a future slide boundary, without reframing a visible photo. Keep matte/overlay space or choose another composition when the faces cannot coexist with the overlays.

**Recommended replacement gate:** first check initialization stages, packaged models/JNI, upright decode, and inference independently on a future authorized API-29 arm64 test. Use licensed/public test imagery covering EXIF orientations, edge faces, pairs/groups, profiles, low resolution, and no-face cases; compare crop containment and full-path visibility before latency. Record cold initialization, warm inference, peak memory, download duplication, and interference with first paint/exit separately. Replace ML Kit only if reproducible compatibility/recall/cost evidence justifies it; prefer MediaPipe CPU, then a pinned OpenCV/YuNet pair. Until then, static contain provides face-safe geometry for the whole photograph without relying on detection.

No new hardware benchmarks or detector executions were performed. Published Pixel-6 or desktop timings were not treated as Portal results.

## Official sources

Accessed 2026-10-07; moving documentation/sample branches can change.

- [S1 — ML Kit Android face detection: versions, input guidelines, options][S1]
- [S2 — ML Kit model installation paths and updates][S2]
- [S3 — ML Kit current versions and release dates][S3]
- [S4 — ML Kit on-device processing and metrics][S4]
- [S5 — MediaPipe Android Face Detector: assets and IMAGE mode][S5]
- [S6 — MediaPipe Android platform requirements and CPU delegate][S6]
- [S7 — MediaPipe face models and intended ranges][S7]
- [S8 — MediaPipe Android arm64 build mapping][S8]
- [S9 — MediaPipe privacy/metrics terms, updated April 2026][S9]
- [S10 — OpenCV Android packaging and local initialization][S10]
- [S11 — OpenCV Android default ABI/native API configuration][S11]
- [S12 — OpenCV AAR builder's minimum SDK default][S12]
- [S13 — OpenCV Java FaceDetectorYN API][S13]
- [S14 — Official YuNet model compatibility notes and MIT license][S14]
- [S15 — Android platform detector][S15] and [Face result API](https://developer.android.com/reference/android/media/FaceDetector.Face)
- [S16 — W3C background-position percentage semantics][S16]
- [S17 — Current official MediaPipe face-detector sample dependency][S17]

[S1]: https://developers.google.com/ml-kit/vision/face-detection/android
[S2]: https://developers.google.com/ml-kit/tips/installation-paths
[S3]: https://developers.google.com/ml-kit/release-notes
[S4]: https://developers.google.com/ml-kit/terms
[S5]: https://developers.google.com/edge/mediapipe/solutions/vision/face_detector/android
[S6]: https://developers.google.com/edge/mediapipe/solutions/setup_android
[S7]: https://developers.google.com/edge/mediapipe/solutions/vision/face_detector
[S8]: https://github.com/google-ai-edge/mediapipe/blob/master/platform_mappings
[S9]: https://developers.google.com/edge/mediapipe/legal/tos
[S10]: https://docs.opencv.org/4.13.0/d5/df8/tutorial_dev_with_OCV_on_Android.html
[S11]: https://github.com/opencv/opencv/blob/4.13.0/platforms/android/default.config.py
[S12]: https://github.com/opencv/opencv/blob/4.x/platforms/android/build_java_shared_aar.py
[S13]: https://docs.opencv.org/4.13.0/javadoc/org/opencv/objdetect/FaceDetectorYN.html
[S14]: https://github.com/opencv/opencv_zoo/blob/main/models/face_detection_yunet/README.md
[S15]: https://developer.android.com/reference/android/media/FaceDetector
[S16]: https://www.w3.org/TR/css-backgrounds-3/#background-position
[S17]: https://github.com/google-ai-edge/mediapipe-samples/blob/main/examples/face_detector/android/app/build.gradle
