# Portal+ (`cipher`) hardware and rendering budget — 2026-10-30

**Decision:** budget for the historically measured **Qualcomm QCS605 / Adreno615v2**, Android 10/API 29, 2160×1440 Family Area device. A bounded native photo stage, static matte, one prepared transition, optional cached local face metadata, and clocks driven by visible changes are realistic. Full-resolution real-time blur, original-resolution photo decoding, album-sized decoded caches, and concurrent inference/decode warming are outside this budget.

This is a bounded research memo, not an implementation or a new performance trial. Only this document was authored. No builds, installs, device commands, Home Assistant operations, GitHub authentication, commits, or additional agents were used. Parent owns current sensor confirmation, implementation, and night-clock decisions.

## 1. Evidence classes and identity

- **Device evidence:** historical measurements recorded in this repository, plus the current Fleet values supplied for this task. Historical results retain their original artifact/workload limitations.
- **Source inspection:** current code behavior and constants; reading them does not establish that the installed APK has identical behavior.
- **Public specifications / online benchmarks:** vendor documentation and published results on other hardware. These establish context, not this Portal's sustained performance.
- **Calculated:** explicit arithmetic from dimensions and pixel formats, not measured allocation or bandwidth.
- **Proposed targets:** acceptance criteria for subsequent implementation. They are not current passes.

### Historical device facts, October 11

The [accepted Appearance report](design/portal-settings-hardware-and-appearance-candidate.md#actual-hardware) records a read-only observation at **2026-10-11 15:02:22 UTC**. The supplied original JSON was also read during this research:

`~/portal-prime/build/fleet/diagnostics/settings-appearance-direct-rows-2026-10-11/hardware-facts.json`

SHA-256 of that JSON, freshly calculated:
`e29810368e438a68b35410dd65b4c2a01b7f7793a3287e0781bfec00e90ea1cf`.
Its timestamp is `2026-10-11T15:02:22.892907+00:00`. The report separately records functional/performance postflight hashes.

| Property | Historical device evidence | Interpretation |
| --- | --- | --- |
| Model / device | `Portal+` / `cipher` | Use this identity rather than inferring hardware from a product nickname |
| OS | API 29; companion hardware reports Android 10, arm64 | No Google services; API availability follows installed OS |
| CPU hardware | `Qualcomm Technologies, Inc QCS605` | Actual hardware record; no Snapdragon 660/835 assumption |
| Topology | Eight logical cores: six `0x803`, two `0x802` | Six smaller and two faster cores; UI work still has a serial main-thread path |
| Sampled maximum frequencies | CPU 0/4: 1,708,800 kHz; CPU 7: 2,515,200 kHz | Maximum-frequency properties, not evidence of clocks sustained during rendering |
| GPU | `Adreno615v2` | Historical slideshow report also records OpenGL ES 3.2 |
| Kernel-visible RAM | Rounded 2,782 MiB | Usable memory, not proof of physical DRAM package size |
| Available RAM / swap | 649.7 MiB; swap total/free 1,536/577 MiB | October 11 snapshot only; swap occupancy does not prove paging activity |
| App resident memory | 172.9 MiB RSS | RSS is neither PSS nor exclusive allocation |
| Display / frame budget | 2160×1440; historical 60 Hz / 16.666666 ms | Reconfirm current display mode before declaring a deadline-based result |

**Current values supplied for this task, October 30:** Android 10/API 29, 2160×1440, `totalRAM=2,848,792 KiB` and `currentMemAvailable=543,832 KiB`. These convert to **2,782.02 MiB total** and **531.09 MiB available**, consistent with the historical total. Available memory is shared system headroom, not an app allocation allowance. This sidecar did not independently reread the device; current clocks, thermal state, memory class, GPU frequency and paging activity remain unconfirmed here.

**Generation-label discrepancy:** the task calls this “first-gen Portal+.” Contemporary 2018 reporting describes a 15.6-inch rotating **1920×1080** Portal+ [S3]. Meta's September 2021 announcement describes a new 14-inch tilting Portal+ [S4], and contemporary specifications give it **2160×1440** [S5]. The reported resolution matches the latter specification. This does not independently identify the physical unit's retail generation; product-label/current display confirmation belongs to parent. The budget below follows measured `cipher` properties and does not import a first-generation Snapdragon claim.

## 2. Matching public hardware references and benchmarks

Qualcomm's QCS605 page specifies eight Kryo CPU cores, up to 2.5 GHz, Adreno 615 up to 780 MHz and OpenGL ES 3.2 [S1]. Qualcomm's QCS605 AI Box table gives **2×2.5 GHz + 6×1.7 GHz** and Adreno 615 [S2, PDF page 5, zero-indexed page 4]. Those match the historical device topology/frequency class. The reference board's 8 GB RAM, HDMI mode and software configuration are not Portal facts. Neither the published GPU maximum nor the advertised AI capability proves runtime clock, delegate availability or app throughput on Portal.

| Online result | Published values | Match and limits |
| --- | --- | --- |
| QCS605 reference result, Geekbench 4.2.0 Android AArch64; uploaded May 4, 2018 [S6] | Single-core **2,019**, multi-core **5,437**; Gaussian blur single-core **30.3 Mpixels/s** | Motherboard `qcs605`, eight cores, Android 8.1, 5.55 GB reported memory. User-submitted reference result, not Portal or an aggregate population |
| `QUALCOMM DCV02`, Geekbench 5.1.0 Vulkan; uploaded August 24, 2026 [S7] | Vulkan score **791**; Gaussian blur **44.3 Mpixels/s**; face-detection workload **3.32 images/s** | Android 10, `qcs605`, Adreno 615, six cores at 1.71 GHz and two at 2.52 GHz; reported RAM 3.70 GB. Matching chip/GPU class, different board and workload |
| Second `DCV02` Vulkan upload, July 15, 2026 [S8] | Score **795**; blur **44.1 Mpixels/s**; face workload **3.33 images/s** | Similar reported topology. No proof of independent uploader/device or controlled thermal conditions |

Do not compare Geekbench 4 scores with Geekbench 5 scores, translate a Vulkan compute score into Compose/WebView FPS, or equate Geekbench's face workload with ML Kit. No controlled public `cipher` slideshow benchmark was established by this research. These exact-chip results are more relevant context than guessing a nearby Snapdragon model, but historical **on-Portal** rendering evidence should decide feasibility.

**Calculated rendering scale:** this panel has 3,110,400 pixels, **1.5×** a 1920×1080 panel. One panel-sized pass at 60 Hz visits **186.624 million pixels/s**. In a simple uncompressed traffic model, reading two RGBA images and writing one result each frame amounts to **2.086 GiB/s**, before additional passes/composition. Actual GPU caching, tiling and compression change traffic; this is bookkeeping, not a measured bandwidth bottleneck. It explains why every extra full-screen effect matters without claiming a specific GPU limit.

## 3. What previous Portal measurements actually support

| Repository evidence | Result | Consequence |
| --- | --- | --- |
| [Native slideshow validation](design/portal-plus-slideshow-native-validation.md#sustained-and-lifecycle-validation), two-hour owned-photo Smart frame loop | 149,470 host frames; mean/p95/p99 **6.02/9.25/11.5 ms**; maximum **44.87 ms**; 129 frames over 16.667 ms and two over 33.333 ms. Shared pixel admission peak **33,676,224 bytes**; app PSS peak **178.5 MiB** | Simple native photo rendering can fit a 60 Hz host-frame target on this hardware. This cohort used owned sources and simplified overlays; it is not a universal FPS, current-build or household-album pass |
| [Family Home/Apps comparison](design/portal-apps-preparation-family-performance.md#family-area-result-and-remaining-work) | Retained preparation reuse: median Window p95 **60.75→44.50 ms**; candidate p99 **62.75–70 ms**; largest **98.20–105.13 ms** | Known navigation tails remain an implementation priority despite improved averages |
| [Family Filmstrip blur diagnostic](design/portal-filmstrip-render-cost-family.md#backdrop-blur-cost-diagnostic) | Baseline-before / blur-off / restored-baseline p95 **54/47.75/55 ms**, p99 **127.25/95.25/119.25 ms** | Blur contributed to tails in that bounded diagnostic; it was not proved the dominant cost. Removal changed appearance and was not accepted as a production replacement |
| [Filmstrip native-layer diagnostic](design/portal-filmstrip-native-layer-family.md#device-comparison) | Explicit hardware layer failed its paired tail thresholds; p95 **51.5 ms**, p99 **112.25 ms** | Do not repeat layer-hint experiments or assume an extra offscreen layer fixes rendering |
| [15-minute Family stability observation](design/portal-family-stability-2026-10-11.md) | Photo Window p95/p99 **35.75/59.25 ms**, overall maximum **1,804.583 ms**; startup not fully separated | A documented severe outlier deserves attention. It does not contradict the isolated native cohort: artifact, style and workload differ |
| [Accepted native R8 Settings candidate](design/portal-settings-native-r8-candidate.md) | Median largest Appearance-opening frame **27.779 ms** | Main-thread/code cost can improve, but this specific first-open tail still exceeds one 60 Hz frame |
| [Current native Filmstrip observation](portal-night-player-progress-2026-10-30.md#native-filmstrip-steady-motion-observation--2102-utc-batch), optimized Family build | Warmed 90-second preview: **837** host frames, mean/p95/p99 **9.049/12.50/14.75 ms**, maximum **19.747 ms**, **2 / 837 (0.239%)** above 16.667ms | This native preview cohort passes the existing steady-frame gates. It does not measure a Filmstrip before/after gain, delivered FPS, cold startup or system DreamService/recovery |

The [outlier attribution](design/portal-family-outlier-attribution.md) identifies animation/draw/semantics work in Home/Apps and render synchronization in Filmstrip. Stack-family samples overlap and measure wall-time observations, not CPU execution time or complete tail attribution. They support focused work, not disabling accessibility or declaring a thermal/GPU root cause.

**Priority without new trials:** first address known frozen/large-frame paths and work admitted during transitions; then bound decode/upload residency and eliminate new real-time blur; then reduce avoidable clock/calendar callbacks. Reuse existing bounded subsystems and approved visuals. Do not spend this research window on broad healthy-feature sweeps, another room's benchmarks, driver/dependency changes, or new speculative layer experiments.

## 4. Photo memory: explicit arithmetic and admission

Android documents ARGB_8888 as four bytes/pixel and RGBA_F16 as eight [S9]. On Android 8+, ordinary bitmap pixels occupy native memory; moving pixels outside the Java heap does not remove their cost [S10].

`MiB = width × height × bytesPerPixel / 1,048,576`.

| Allocation, calculated | Bytes | MiB |
| --- | ---: | ---: |
| One 2160×1440 ARGB image | 12,441,600 | 11.87 |
| Two images for a dissolve | 24,883,200 | 23.73 |
| Two images plus one prepared lookahead | 37,324,800 | 35.60 |
| Two images plus a reserved 24 MiB decode/EXIF workspace | 50,049,024 | 47.73 |
| One full-resolution 4000×3000 original | 48,000,000 | 45.78 |
| Original plus same-size orientation copy | 96,000,000 | 91.55 |
| One 2400×2400 image, despite an edge cap | 23,040,000 | 21.97 |
| One 640×640 analysis derivative | 1,638,400 | 1.56 |
| One panel image using RGBA_F16 | 24,883,200 | 23.73 |

**Observed code limits:** [SlideshowDecodeBudget](https://github.com/starbrightlab/immortal/blob/main/app/src/main/java/com/immortal/launcher/SlideshowDecodeBudget.kt) caps each decoded bitmap at **3×1,048,576 pixels / 12 MiB**, each edge at 2400, encoded input at 32 MiB, and source-plus-EXIF transform pixels at 24 MiB. This pixel cap admits a full 2160×1440 bitmap. Power-of-two sampling may produce a smaller derivative from a large original; the code intentionally bounds the initial allocation rather than decoding the original and shrinking afterward, consistent with Android's bounds-first decoding guidance [S11].

[SlideshowPixelBudget](https://github.com/starbrightlab/immortal/blob/main/app/src/main/java/com/immortal/launcher/SlideshowPixelBudget.kt) already admits retained display/preparation/lookahead pixels and decoder/EXIF reservations under **48 MiB**. [SlideshowLoader](https://github.com/starbrightlab/immortal/blob/main/app/src/main/java/com/immortal/launcher/SlideshowLoader.kt) reserves scratch before decode. Keep this as the working pixel budget. Three full images leave only **12.40 MiB**, insufficient for a new worst-case **24 MiB** transform reservation. Delay preparation or retire a no-longer-visible slot; never claim that three retained photos plus arbitrary concurrent decoding fit.

**Proposed operating budget:**

- One decode/EXIF operation in flight, one latest pending visible request, at most one lookahead. Visible work takes precedence over optional analysis and album warming.
- At most two scene layers during a dissolve. A pair/gallery/Filmstrip uses tile-sized derivatives and a bounded visible working set; account aggregate pixel area and outgoing/incoming residency rather than allocating one panel-sized bitmap per card.
- Keep the 48 MiB admission ceiling, 12 MiB per bitmap and 24 MiB transform reservation. Count actual allocation bytes and shared bitmap identity; an edge limit or compressed disk-cache limit alone is insufficient.
- Account face-analysis derivatives separately: the inspected store admits one inference and up to two pending owned derivatives, each no larger than 640×640 ARGB (**≤4.69 MiB** retained payloads). Its serialized acquisition can create one more derivative before queue eviction, adding **≤1.56 MiB** transiently: plan for **≤6.25 MiB** derivative payloads. These are outside the display/scratch ceiling, as are model/runtime allocations.
- Encoded input retains the existing **32 MiB hard limit**. Prefer streaming/spooling and reuse acquired bytes. A ByteArray-to-ByteArray copy can add another 32 MiB; queue bounds alone do not bound that transient.
- Retire references after ownership ends; do not recycle a bitmap still used by a visible scene or uncancelled inference task.

For scale, a conservative subtotal of **48 MiB admitted pixels/scratch + 32 MiB one encoded buffer + 6.25 MiB analysis derivatives including acquisition + two panel-sized RGBA GPU copies (23.73 MiB)** is **109.98 MiB**. This is a planning subtotal, not a measured peak or complete process cap: SDK allocations, codec/native scratch outside reservations, driver padding/mipmaps, additional encoded copies and retained Home/services remain. GPU resources may share storage, so do not add this subtotal directly to PSS or count every graphics category twice.

One additional panel-sized RGBA render target costs approximately 11.87 MiB of payload. Three such compositor buffers would total 35.60 MiB, but their existence, format, attribution and whether they are already in baseline memory were not established here. Avoid adding offscreen targets by default. A fixed total-app PSS ceiling inferred from 531 MiB available would be unsupported; compare the feature's bounded allocations and settled process memory against a matched baseline.

## 5. Rendering contract and photo-style feasibility

| Style/work | Realistic policy for this hardware |
| --- | --- |
| Whole photograph / framed print | Native contain over a static matte, static hold, one readiness-gated dissolve. Lowest crop uncertainty and ongoing rendering demand |
| Smart frame / portrait pair | Use existing upright geometry and cached face bounds; bounded matrix/alpha motion only. Total photo area and both transition scenes share admission |
| Gallery / Filmstrip | Preserve approved composition using card-sized pixels, bounded visible/nearby slots and lifecycle ownership. Known Filmstrip tails remain unresolved; no claim that a one-photo renderer is an equivalent replacement |
| Ken Burns | Reuse one prepared image and verified transform endpoints; no per-frame decode, layout rebuild, inference or image replacement |
| Background / glow | Static color/gradient or an already prepared small derivative; **no real-time photo/backdrop blur** |
| Video | Separate decoder/surface workload; do not inherit the still-photo frame or memory pass |

Android `RenderEffect` blur requires API 31; runtime-shader effects require API 33 [S12]. Neither is available on this API-29 device. A custom/WebView blur being technically possible does not admit it to this budget. If an approved style needs a blurred still background, a **proposed** off-thread derivative capped at 320×320 ARGB uses ≤0.391 MiB of pixel payload; cache it per asset, count processing scratch, and draw it as a static image. A matte needs no photo duplicate.

The entire frame deadline is 16.667 ms at 60 Hz; it is not 16.667 ms independently for Compose, decode and GPU work. **Proposed steady-frame design headroom:** keep main-thread animation/layout/draw work around **≤8 ms p95**, leaving room for scheduling and downstream work. This is an internal design target, not a measured main-thread execution budget, and component durations must not be naively summed if they overlap.

## 6. No-GMS face and saliency feasibility

**Keep the proven bundled path.** The app declares `com.google.mlkit:face-detection:16.1.7`. Official guidance distinguishes that bundled model from the Play Services downloaded model, documents offline availability and a roughly 6.9 MB app-size increase [S13,S14]. API 29 meets its documented API 23 minimum. Build-time Google Maven dependencies or `Tasks` namespace imports do not imply an installed Google Play Services requirement.

The October 7 [face memo](design/portal-plus-slideshow-face-research.md) recorded an older initialization concern. Later [native validation](design/portal-plus-slideshow-native-validation.md#device-findings-and-corrections) actually exercised bundled inference: FAST missed the licensed astronaut portrait, ACCURATE found it, and the pipeline switched to ACCURATE with metadata invalidation. This later evidence supersedes “inference unverified” for that fixture. It does not establish album-wide/group recall, inference p95 or sustained model memory.

| Option | Feasibility and recommendation |
| --- | --- |
| Existing bundled ML Kit ACCURATE, boxes only | Best-supported choice here. Analyze an independent upright derivative once per asset revision/policy, off Main, on the existing low-priority worker; cache geometry for future scenes |
| MediaPipe Tasks Face Detector, bundled asset, CPU / IMAGE mode | Documented API 24+ setup, local model assets, CPU default [S16,S17]. Eligible replacement if the existing path has a demonstrated problem; integration, arm64 artifact compatibility and Portal cost remain unmeasured |
| Static saliency, e.g. spectral residual / fine-grained contrast | OpenCV documents local static methods that produce visual-attention maps [S19,S20]. Plausible optional landscape/object-composition hint on a small cached derivative; not face protection or proof of subject semantics. Android/contrib packaging and Portal latency were not evaluated |
| Play Services model-download variant | Unsuitable for this no-GMS firmware. Do not install Google services to enable it |

Google's MediaPipe benchmark reports short-range BlazeFace **2.94 ms CPU / 7.41 ms GPU on Pixel 6** [S18]. Those are external averages on a different SoC, model and pipeline, not a Portal latency target. Full-range models are more relevant than selfie-optimized short-range models for ordinary albums, but no superiority over the current ACCURATE path is established here. CPU inference also avoids assuming that sharing Adreno with rendering improves the result.

For ML Kit, guidance calls for at least 480×360 input and approximately 100×100 pixels per face [S13]. A 640-long-edge landscape derivative leaves a distant face only tens of pixels wide; lowering a minimum-face-size option cannot recreate missing detail. Preserve whole-photo framing for uncertain/no-face results, large groups, scans, text/artwork and panoramas. Detection is optional composition metadata, never a startup prerequisite or a guarantee that everyone is protected.

**Proposed analysis contract:** no additional photo download solely for analysis; no full-resolution analysis; no inference during each animation frame; no visible crop jump when late metadata arrives. Cache revision, EXIF coordinate frame, model/policy and explicit pending/failure/no-face status. On timeout, reject future admission and keep the one task's owned pixels until completion is known; cancelling a wait does not prove native inference stopped. The inspected [SlideshowFaceStore](https://github.com/starbrightlab/immortal/blob/main/app/src/main/java/com/immortal/launcher/SlideshowFaceStore.kt) already has a ten-second timeout and this ownership distinction.

Saliency must never override protected face bounds or a saved user crop. A **proposed** ≤256×256 working image and one cached saliency result per asset are reasonable admission limits, not measured runtime/memory guarantees. Defer a new saliency dependency while the known rendering outliers remain open; a whole-photo matte supplies a complete fallback.

Local inference means the photographs need not be uploaded. ML Kit nevertheless documents operational metrics and possible server contacts [S15]; offline photo processing should not be advertised as zero SDK networking.

## 7. Reduce clock callbacks without sacrificing behavior

**Source inspection:** [DigitalClockDreamService](https://github.com/starbrightlab/immortal/blob/main/app/src/main/java/com/immortal/launcher/DigitalClockDreamService.kt) and [DigitalClockPreviewActivity](https://github.com/starbrightlab/immortal/blob/main/app/src/main/java/com/immortal/launcher/DigitalClockPreviewActivity.kt) schedule every **500 ms with seconds**, every **1,000 ms without seconds**. The Dream also applies anti-burn-in translation. [PhotoFrameController](https://github.com/starbrightlab/immortal/blob/main/app/src/main/java/com/immortal/launcher/PhotoFrameController.kt)'s calendar loop wakes every second but rebuilds only when its minute changes. These are app callback cadences, not proof of hardware wake-from-sleep counts or measured CPU savings.

**Proposed policy for parent:**

- Minute-only digits, no blinking separator: update on the minute boundary, relevant settings/time/time-zone changes and reactivation. Android's dynamically registered `ACTION_TIME_TICK` is minute-based [S21]. Minute text updates fall from 60 to roughly one per minute.
- Integer seconds: one aligned callback per second. Keep 500 ms only where a visible separator blink actually changes every half-second; do not refresh all formatters/layout for a blink.
- Calendar labels/occurrences: align to minute boundaries and actual data changes rather than second polling.
- Night/static clock: no continuously scheduled frame callback. Paint only changed content; stop owner callbacks when hidden, detached or stopped, and refresh immediately on resume.
- Keep anti-burn-in independent of digit formatting. The existing [AntiBurnIn](https://github.com/starbrightlab/immortal/blob/main/app/src/main/java/com/immortal/launcher/AntiBurnIn.kt) uses 311/421-second periods and a 14-pixel Dream radius. Moving only once per minute can jump many pixels. A **proposed three-second translation-only cadence** limits maximum axis steps to approximately **0.85/0.63 px**, calculated from `radius × 2π × Δt / period`. That permits ≤20 drift callbacks plus one minute-text update per minute, rather than rerendering the digits every second. Parent owns whether this cadence fits the approved visual behavior.

Use wall time for displayed time and recompute the next boundary after time changes; use monotonic time for transition durations. Handler delays use uptime and can be delayed by deep sleep [S22]. A screen clock should correct itself on resume, not acquire a wake lock or exact alarm solely to maintain an invisible display.

## 8. Concrete acceptance criteria, all proposed

The native soak demonstrates that the steady-photo targets below are achievable for a bounded workload. It does not pass a new APK, every style or photo+music/overlay concurrency. These criteria define a reviewable result for parent; this memo initiates **no device trials or benchmark campaign**.

| Area | Proposed acceptance |
| --- | --- |
| Active steady photo motion / dissolve, separately scoped | With confirmed 60 Hz, host-frame p95 **≤16.667 ms**, p99 **≤33.333 ms**, **≤1%** over the nominal budget, and no warmed active-frame sample **>100 ms**. Report maxima and affected actions even if percentiles pass |
| Frozen/severe frames | Zero unexplained **>700 ms** frames in the affected path; eliminate the known >1-second class before polishing averages. Android uses 700 ms as the frozen-frame threshold [S23] |
| Known Home/Apps and Filmstrip outliers | Home/Apps remains open. Current native Filmstrip warmed preview passes its steady-frame gates; startup, system DreamService/recovery and wider workloads remain unverified. Do not transfer one host/style pass to another path |
| Local input acknowledgement | p95 **≤100 ms**, maximum **≤250 ms**; local navigation/control usable p95 **≤300 ms**. Input acknowledgement and content readiness have distinct endpoints |
| First local/cached photo | Usable photo/fallback p95 **≤750 ms after a ready host**; no analysis wait or blank transition while replacement loads. Fully cold startup is separate; retain the existing quality-gate cold-shell **≤2,000 ms** target |
| Pixels and encoded data | Shared retained+decode/EXIF admission **≤48 MiB**, decoded bitmap **≤12 MiB / 3,145,728 pixels**, transform reservation **≤24 MiB**, encoded input **≤32 MiB**; fail before original-size allocation |
| Work concurrency | One decode operation; latest pending request only; at most one lookahead; one analysis task with at most two small pending derivatives and one serialized derivative acquisition (≤6.25 MiB payloads including transient copy). Obsolete jobs cannot publish into a later session |
| Optional analysis | Successful no-GMS/offline fixture inference; whole-photo fallback for failure/no-face/uncertain input; zero extra analysis-only downloads and zero per-frame inference |
| Visual composition | EXIF 1–8 upright; protected regions and overlays respected for the entire motion path; no late metadata crop jump; no duplicated portrait partner or lost user adjustment |
| Clock/calendar | Seconds-off text: **≤1 periodic update/minute**, plus actual change/resume events; integer seconds **≤1/s** unless declared blink; drift budget separate; no hidden clock redraw loop |
| Memory lifetime | No growth in owned slots/queues across navigation. Investigate monotonic settled PSS growth over five cycles or a sustained increase exceeding **both 10% and 20 MiB**, matching the existing audit screen. This is an investigation threshold, not automatic leak proof |
| Cancellation / exit | Exit response meets local acknowledgement target; no new owner-controlled decode/analysis admission or stale UI publication after closure. An uncancellable task may retain only its admitted lease until safe disposal; no retry storm |

The [repository quality gates](design/portal-plus-quality-gates.md#measurement-definitions-and-acceptance-policy) already distinguish geometry, host frames, presentation, acknowledgement, readiness, startup and cadence. API 29 `FrameMetrics` cannot supply API-31 `GPU_DURATION` or `DEADLINE` [S24]. Host totals/render-command duration are not isolated GPU execution, delivered FPS or input-to-photon latency. A nominal “over 16.667 ms” fraction is a proxy unless actual presentation deadlines are available. Sparse idle frames demonstrate quiescence only.

For any later final-artifact evidence parent chooses to collect, preserve installed artifact/compilation identity, workload, overlay/music state, sample counts, first-draw versus steady phase, timing exclusions and maximum-frame timestamps. Avoid screenshots, hierarchy sampling and per-frame logs during timing. Compare process PSS separately from pixel admission and account for a separate WebView renderer where used. Neither online benchmarks nor stale successful cohorts close current acceptance.

## Public sources checked on 2026-10-30

- **[S1] Qualcomm QCS605 specifications:** https://www.qualcomm.com/processors/application-processors/products/qcs605
- **[S2] Qualcomm QVIC 2024 hardware platforms, QCS605 AI Box, PDF page 5:** https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/QVIC-2024-HW-platforms.pdf
- **[S3] 9to5Google, October 8, 2018 Portal/Portal+ announcement:** https://9to5google.com/2018/10/08/google-home-hub-facebook-portal/
- **[S4] Meta, September 21, 2021 new Portal+ announcement:** https://about.fb.com/news/2021/09/introducing-two-new-portals-including-first-portable-version/
- **[S5] Tech Advisor, September 21, 2021 Portal+ specifications and predecessor comparison:** https://www.techadvisor.com/article/744760/portal-plus-2021-all-you-need-to-know-about-the-big-screen-portal.html
- **[S6] Exact QCS605 Geekbench 4 CPU result:** https://browser.geekbench.com/v4/cpu/8169124
- **[S7] Exact QCS605/Adreno 615 Geekbench 5 Vulkan result:** https://browser.geekbench.com/v5/compute/6954759
- **[S8] Second exact QCS605/Adreno 615 Vulkan result:** https://browser.geekbench.com/v5/compute/6948282
- **[S9] Android bitmap pixel formats:** https://developer.android.com/reference/android/graphics/Bitmap.Config
- **[S10] Android bitmap memory locations and allocation:** https://developer.android.com/topic/performance/memory/guide/bitmaps
- **[S11] Android bounds-first, sampled bitmap decoding:** https://developer.android.com/topic/performance/graphics/load-bitmap
- **[S12] Android RenderEffect API-level requirements:** https://developer.android.com/reference/android/graphics/RenderEffect
- **[S13] ML Kit Android face detection, model artifacts/input guidance:** https://developers.google.com/ml-kit/vision/face-detection/android
- **[S14] ML Kit bundled versus downloaded model installation:** https://developers.google.com/ml-kit/tips/installation-paths
- **[S15] ML Kit input/output locality and metrics:** https://developers.google.com/ml-kit/terms
- **[S16] MediaPipe Android setup, API minimum and CPU default:** https://developers.google.com/edge/mediapipe/solutions/setup_android
- **[S17] MediaPipe Android face detector, local assets and IMAGE mode:** https://developers.google.com/edge/mediapipe/solutions/vision/face_detector/android
- **[S18] MediaPipe models and Pixel 6 task benchmark:** https://developers.google.com/edge/mediapipe/solutions/vision/face_detector
- **[S19] OpenCV static saliency API:** https://docs.opencv.org/4.13.0/d8/d65/group__saliency.html
- **[S20] OpenCV spectral residual algorithm:** https://docs.opencv.org/4.13.0/df/d37/classcv_1_1saliency_1_1StaticSaliencySpectralResidual.html
- **[S21] Android minute tick/time/time-zone broadcasts:** https://developer.android.com/reference/android/content/Intent#ACTION_TIME_TICK
- **[S22] Android wall/monotonic clocks and Handler uptime:** https://developer.android.com/reference/android/os/SystemClock
- **[S23] Android slow/frozen rendering definitions and priorities:** https://developer.android.com/topic/performance/issues/render
- **[S24] Android FrameMetrics definitions and added API levels:** https://developer.android.com/reference/android/view/FrameMetrics
