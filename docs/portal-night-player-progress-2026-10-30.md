# Portal clock, performance and player design — October 30

Work is on `codex/portal-night-player-20261030`, based on the preserved
`codex/portal-music-player-20261018` branch. Music services on Home Assistant remain
cancelled. This change contains no server installation or provider credentials.

## Delivered implementation

- An optional light-sensor clock uses separate dark/bright thresholds, a 20-second
  dark dwell and a 5-second bright dwell. An on-change sensor can settle with one
  deadline callback; it does not need to keep sending identical readings.
- A black native clock shows large time and the local weekday/date, with warm or
  red ink and window-scoped 1–15% brightness. It formats once per minute and stops
  its callbacks when hidden. Devices without an exposed light sensor retain photos;
  scheduled night mode remains available.
- Ambient night pauses retained photo animations, video playback, face/calendar
  callbacks, camera gestures and soundscape work. It resumes the retained scene
  instead of loading the album again. An already admitted image/source operation
  may finish into the paused callback queue; this is not a claim of instant
  cancellation of every third-party operation.
- Scheduled night mode builds the clock directly, with no photo, calendar,
  weather, voice, soundscape or face-renderer startup.
- Video warming stops admitting downloads/transcodes while hidden. A blocked
  download is disconnected off main; an active export checks cancellation every
  250 ms. Native cancellation acknowledgment is awaited off main for at most
  two seconds. Unconfirmed cancellation retains exact file ownership and blocks
  replacement exports. It is not reported as successful cleanup.
- Video workers have unique temporary paths. Process-wide ownership prevents a
  replacement cache or purge from deleting files still owned by native export.
- Existing standalone digital clocks with seconds off format once per minute
  instead of once per second. Preview callbacks drop from 60 to approximately
  one per minute. Dreams retain 20 separate drift callbacks per minute; their
  total scheduled callbacks drop from 60 to approximately 21. Seconds-on clocks
  retain 120 ticks/minute and reuse those ticks for 20 drift updates, adding no
  separate timer. These are scheduling counts, not measured CPU savings.

All four new settings use the existing registry. The lux threshold is Advanced;
brightness and colour appear only when a sensor clock or scheduled clock is
enabled. Sensor mode defaults off on other devices. It is enabled on Family Area
after confirming the exposed light sensor; no other screensaver setting changed.

## Player design

The functional preview is at `http://127.0.0.1:8890/#now`; night-clock design is at
`#night-clock`. It includes full/compact views, artwork, transport, seek, queue,
speaker controls, library and playlists, plus unavailable and interruption states.

Its 21 browser checks and screenshots are in
`mocks/portal-premium-player-20261030/evidence/`. Metadata, playback, playlists,
speakers and clock presence are fixtures. In-page library/playlist APIs are not
already supplied by the native adapter; their provider wiring is deferred.
The mock README maps every control and explains that distinction.

## Evidence and remaining acceptance

The [hardware budget](portal-hardware-budget-2026-10-30.md) distinguishes
historical QCS605/Adreno615 device evidence, current Fleet memory readings, external
benchmarks and proposed acceptance targets. Online scores are not Portal frame-time
measurements. Current Fleet memory at 14:17 UTC was 2,848,792 KiB total and
543,832 KiB available.

The final integrated gate passed **3,369 JVM tests in 347 suites**, with zero
failures, errors or skips, and assembled the debug APK:

```sh
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
  ./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon --offline
```

The candidate is installed on **Family Area**. Reading the installed APK through
the authenticated Fleet file API confirmed its bytes match the tested local APK:

- SHA-256: `66c2c53e8417e8c295266f6e1c9f5c815627bcf49d4236bc402717ac1535194f`
- Size: 48,762,887 bytes.
- Signing certificate: the existing debug certificate, matching the rollback build.

The rollback APK was rebuilt from the preserved branch at `76499faa`; it is not a
byte-for-byte backup of the previously installed APK. Both APKs were uploaded to
unique device paths before installing the candidate. Private deployment receipts
are kept outside Git and contain no material intended for publication.

Post-install diagnostics confirm eight CPU cores and an Android light sensor named
`light sensor`, vendor `tcs34x0`, on-change reporting mode, reported power 0.5 mA.
At 14:42 UTC, Home and Screensaver navigation requests were accepted. Full registry
snapshots before and after enabling the sensor clock differ only at
`darkRoomClock`. These dispatch acknowledgments do not prove a visible transition.
The bounded recent log sample contained zero fatal-exception markers and no
night/day transition entry. No new performance capture was left running.

An independent review found and prompted corrections for clock stacking order,
hidden controls intercepting touches, retained camera/video work, cancellation
ownership across controller replacement, blocked downloads on terminal stop, and
temporary-file cleanup under an unrelated export.

Remaining acceptance is explicit:

1. Verify real dark/bright transitions, actual backlight comfort, tap return and
   retained-photo continuity on that hardware.
2. Verify camera/video cancellation with the relevant optional features enabled.
3. Compare the affected active rendering paths against the hardware budget.
4. Refine additional existing slideshow styles. Native core-player layout and the
   new Gallery print option are delivered in the follow-up below; real playback
   and provider-backed library/playlist views remain outside visual acceptance.

The sensor-clock investigation and implementation batch is closed within its
30-minute limit. Physical acceptance is pending, rather than an invitation to
repeat diagnosis without new evidence. No Living Area rollout or phone modification
belongs to this development pass.

## Native player and Gallery follow-up — 15:05 UTC

The core native player now has an artwork-led layout, one-row header, quieter
transport, thin seek/volume tracks, a queue with bounded thumbnails and a compact
strip. Full and compact views retain the existing controller and selected track.
Artwork remains bounded at 480dp; queue thumbnails decode at no more than 128px.
The existing lifecycle-aware progress ticker remains isolated from the full UI.
No music backend, credentials, Home Assistant service or audio worker was added.

The debug-only preview renders the production player, night clock and Gallery
stage with original local artwork and silent fixtures. It is absent from release
builds and does not create a controller or connect to household media. Native
captures found a clipped play circle; layout reserves were corrected and a
screenshot pixel assertion now checks that the circle's bottom remains visible.

- The final integrated Gradle gate passed **3,392 tests in 350 suites**, with zero
  failures, errors or skips, and assembled the debug APK.
- **17 native checks** passed on an owned API 34 emulator at 2160 × 1440 and 240dpi,
  including 48dp targets, queue selection, compact/expand continuity, unavailable
  controls, light mode, 1.8 font scale, the actual weekday clock and Gallery stage.
- The [native review](http://127.0.0.1:8890/native-review.html) loads all ten capture
  tabs without page errors. Captures include Android emulator taskbar chrome;
  these are actual Android renders, not photographs of a Portal.
- Family Area's installed APK was read back and matched local SHA-256
  `65820d2b22bf0d7584d84b74f2a73e649a07b5f395fa25ede50bc32e814fab9d`
  (48,796,663 bytes). Its signing certificate matches the actual preceding
  installed APK, which is retained privately as a byte-for-byte rollback.
- Full screensaver registry snapshots before/after installation have **zero
  changed setting values**. Home and screensaver requests were acknowledged.
  The process replacement interrupted the install HTTP response; installed-byte
  verification resolved that ambiguity without repeating installation.

[Gallery print](portal-gallery-print-2026-10-30.md) adds a restrained whole-photo
matte and reuses existing decoding, pixel ownership and dissolves. Late calendar
layout changes reposition retained layers without decoding or restarting the
dissolve. All five observer lifecycle tests passed in the integrated gate.

Family Area retains its saved **Shuffle** mode, routed through the legacy engine.
This native rendering evidence therefore does not establish performance gains for
its currently active Shuffle path. No new physical FPS, audio, dark/bright sensor,
backlight-comfort or retained-photo wake result is claimed. The owned emulator was
stopped after capture; no ongoing performance recording was left running.

This player batch closes within 30 minutes. The broader goal remains active:
physical clock acceptance, hardware-based rendering measurements and additional
existing-style refinements remain. Music provider wiring stays deferred.

## Shuffle startup, paired photos and native browsing — 15:31 UTC

The next bounded batch changes the production legacy renderer used by Family
Area's saved Shuffle setting:

- A paused startup now admits **zero** orientation image loads or scenes.
  Active orientation preparation uses **two workers instead of six** and asks
  for a ready 320px derivative. When no derivative exists it retains the original
  URL fallback. Pause, page close and the 1.5-second deadline release the image
  handlers and source; late results cannot mutate metadata.
- A successful normal display decode recovers dimensions for photos whose initial
  probe timed out. A bounded URL-to-metadata map holds at most 64 aliases and no
  pixels. Initial Filmstrip widths and Gallery Wall rectangles are recomputed
  from those recovered dimensions before publication, using the same prepared
  photos and URLs, with no additional decode admission.
- Diptych now displays whole photos on a quiet charcoal matte. A portrait pair
  prepares **two resources instead of four**, with **zero blurred background
  images instead of two**. It keeps the existing dissolve and adds no continuous
  decorative work. Other mode IDs and saved preferences are preserved.
- Native Library, Playlists and collection-detail components now render lazy
  grids/lists, bounded local covers, collection metadata and tracks. They are
  connected to the debug-only silent design host. Live provider data, network
  cover loading and music backend integration remain deferred.

Verification:

| Gate | Result |
| --- | --- |
| Integrated Android build/JVM outputs | Build successful; 3,392 tests / 350 suites, zero failures/errors/skips |
| Targeted production slideshow source suite | 107 passed, zero failures/skips |
| Engine source fixtures | 14/14 passed |
| Actual-source desktop Chrome checks | 13 passed, including real stalled HTTP cancellation and delayed dimension recovery |
| Native API 34 layout checks | 29 passed, including scrolling, collection navigation, track selection, empty/offline, light and 1.8× text |

The browser review at [8891](http://127.0.0.1:8891/) uses the actual production
renderer with two original sample portraits. The [native capture review](http://127.0.0.1:8890/native-review.html)
includes Library, Playlists and collection details. Both are explicitly silent
visual previews, not evidence of audible playback or Portal GPU performance.

A bounded **pre-change** Family Area capture recorded 326 valid frame callbacks,
zero callback drops, 18ms p50, 33ms p95 and 109ms p99. Mean frame duration was
22.601ms and maximum 125.706ms. Command submission accounted for 110.018ms of
that worst frame. The capture did not identify a style cohort and did **not**
measure delivered FPS. It cannot establish a before/after improvement for
Diptych, Shuffle or an individual GPU path. The capture auto-ended; collector
state was inactive with zero listeners.

The tested candidate was installed on **Family Area only**, then read back
through Fleet to verify the exact bytes:

- SHA-256: `ff9ee93e5bb19dcf9d876360149ed9be9b6a7af4bf4574d8311083335f9931b8`
- Size: 48,861,358 bytes; signing certificate matches the preceding installed build.
- **97 full-profile setting values compared; zero changed.**
- Home and screensaver requests were acknowledged; visible transitions remain
  unverified. An actual byte-for-byte rollback APK is retained privately.

The owned emulator was stopped after acceptance. Preview servers remain available
for review. This batch closes within 30 minutes; no music service or configuration
was deployed to Home Assistant. Remaining goal work includes physical night-clock
acceptance, affected-path rendering measurements and further style refinement.

## Native search and Gallery spread — latest batch

The music Library and Playlists now include native metadata search, clear controls,
result counts and honest no-result/unavailable states. Matching a nested track
keeps the entire original collection. The host retains separate queries and
lazy-grid positions across detail navigation. This is still visual implementation:
no live library adapter, service connection or Home Assistant music deployment
was added.

The new **Gallery spread** setting uses the existing native stage and decoder:
two whole portraits, a 3vmin matte and 2vmin tile gutter, with a whole-landscape
fallback. Normal presentation is static, does not crop or analyze faces, and
shares the existing 48MiB bitmap ownership budget. Overlay refresh updates both
retained compositions during a dissolve without decoding the photos again.
An explicit adjustment preview retains existing position metadata; save/cancel
returns to the complete spread. Saved positions remain available for other styles.
Gallery spread does not expose the ignored motion toggle on either settings
surface. All previous mode IDs and the saved Shuffle selection are preserved.

Verification:

- **3,405 JVM tests in 351 suites** passed with no failures, errors or skips.
  The integrated debug APK assembled successfully.
- **44 native acceptance checks passed in 115.1 seconds**, including search
  keyboard visibility, album/back continuity, separate page queries, unavailable
  states and spread save/cancel/overlay behavior.
- All **33 native review captures** loaded without page errors. These are
  silent fixtures on the owned API 34 emulator, not Portal timing or audio proof.
  The emulator was stopped after acceptance.
- Family Area's installed APK was read back and matched SHA-256
  `2bd5e75a0f76f93faf221b6ebfc40f47a0a006f2c8310445af11e267c17543f7`
  (48,881,040 bytes). The signing certificate matches the previous installed APK.
- All **97 full-profile setting values were preserved**. A byte-for-byte backup
  of the actual preceding installed APK was retained privately. Living Area was
  not modified.

A separate fixed-mode **Diptych** device capture measured 343 valid frame
callbacks, zero callback drops, 28ms p50, **31ms p95**, **37.5ms p99**, 26.973ms
mean and 46.639ms maximum. 317 callbacks exceeded 16.667ms; six exceeded
33.333ms. Command submission averaged 14.076ms and accounted for 40.822ms of
the worst frame. This misses the proposed 16.667ms rendering target. It is not
delivered FPS or a measured before/after speedup.

The first Gallery spread capture setup hit the existing Main-status timeout
before recording; cleanup restored all 97 settings and left no collector running.
A subsequent bounded readiness check succeeded. The new native style was then
admitted on Family Area at 588ms into its capture with zero Main admission delay.
This proves scene admission only, not visible presentation or vsync.

The subsequent 30-second Gallery spread capture recorded **142 valid frame
callbacks**, zero callback drops and one first-draw callback. Its distribution,
including that first draw, was **9.25ms p50, 16.75ms p95, 62.5ms p99**, 11.515ms
mean and 134.641ms maximum. Eight callbacks exceeded 16.667ms and five exceeded
33.333ms. Two further native spread scenes were admitted during the capture;
neither required a Main admission delay. The p95 still misses the proposed
16.667ms target. This is a different style from Diptych, not a measured
before/after gain, a delivered-FPS measurement or evidence that Shuffle improved.
Process PSS was 271,991KiB; the 48MiB budget bounds retained photo bitmaps,
not total app or graphics memory.

All 97 saved setting values were restored after the capture, including Shuffle
and its 30-second interval. The collector was inactive with zero listeners.
The new option is available for deliberate selection; it did not replace Shuffle.
This batch closes within 30 minutes including its deployment and cleanup.

Remaining goal work includes physical dark/bright and backlight acceptance,
the affected rendering targets and further existing-style refinement.

## Bounded video ownership and clock lifecycle correction

The preceding Gallery spread timings came from an album containing both photos
and videos. They are not a pure still-photo benchmark. One bounded attribution
capture observed `MediaPlayer._release`, `_start` and audio-attribute work on Main
through the old `VideoView`. A 67.778ms callback contained 63.601ms of queued time
and 1.199ms of command submission. Attribution used a 10ms sampler and carries
profiling overhead; this does not establish a before/after rendering gain.

Slideshow playback now retains a hardware `SurfaceView`, with every MediaPlayer
operation on one process-wide background Looper. Commands hold one running and
one latest pending request. Source/surface identities reject obsolete callbacks;
blocked release work retains no strong View or Activity reference. Main reads
immutable cached positions tagged to their source, so a late old read cannot
seek a replacement clip to the old position. API 26+ precise seeking avoids
backward keyframe jumps; older Android retains its existing fallback.
SurfaceView layout/surface operations still belong on Main. This change does
not claim that every source of scheduling or GPU stalls has been eliminated.

The active night clock now refreshes immediately after clock/time-zone changes,
with receiver registration limited to the active attached view. Stale broadcasts
are rejected and minute ticking retains one scheduled callback.

Verification of this exact build:

- **3,422 JVM tests in 355 suites**, zero failures, errors or skips; debug APK built.
- **11 native playback checks passed in 28.72 seconds** on the dedicated API 34
  emulator: visible video, advancing position, pause, paused seek, retained
  resume, stop, 100 rapid replacements, and close. The original silent debug
  clip includes widely spaced keyframes. This is functional acceptance, not
  Portal decoder throughput, audible playback or a physical FPS measurement.
- Source-position regression tests failed before the fix and passed afterward.
  Parallel static review confirmed the correction. Clock/sensor lifecycle tests
  are included in the full suite.
- Family Area's installed APK was read back and matched SHA-256
  `dc6594bf025c1725af1880a87a64102537e4e232b16e29a81d5c2f6a08e54c4d`
  (**48,901,257 bytes**). The signing certificate matched the preceding installed
  APK, which was backed up privately before installation.
- All **97 full-profile setting values were preserved**. Living Area was not
  modified. Home/screensaver dispatch acknowledgments do not prove visible
  transitions.

Physical dark/bright/backlight/tap acceptance and a comparable measured Portal
speedup remain open. The previous 44 native design checks and 33 review captures
belong to the preceding UI acceptance run, not this video's runtime timing.

The post-install timing run was not admitted: `/perf` returned HTTP 503
`capture_command_not_applied` when starting the admission capture. No new
performance distribution is reported. Cleanup independently verified all 97
settings restored, collector inactive and zero listeners. The owned test
emulator was stopped. This measurement is parked within the batch's 30-minute
limit; deployment and functional acceptance above are independently verified.

## Diptych dissolve and accurate viewer controls — 16:26 UTC batch

Diptych retains its existing photo-format, pairing, shuffle, source and composition
contracts. A native replacement was not used to trade those contracts for an
unmatched timing result. The production WebView renderer now keeps the outgoing
scene opaque while only the incoming scene fades. Initial readiness publishes an
opaque scene because the scheduler already supplies the whole-beat startup fade.
This removes the two simultaneous opacity fades that exposed the dark matte.

Two reusable scene slots retain the existing bounded preparation. Outgoing image
DOM resources are released only after the incoming scene is opaque. Pause captures
and holds the current opacity without requiring `getAnimations`; resume consumes
the remaining active duration. Cleanup rejects a prepared scene even when it
arrives between decode admission and DOM publication. An independent review
reproduced both the older-WebView pause failure and late-publication failure;
the final regression suite covers both. This is a functional and visual correction,
not a measured GPU-memory or Portal frame-time improvement.

Gallery spread now omits **Adjust**, since its normal whole-photo composition does
not use stored crop positions. Navigation, pause and exit remain available.
Gallery print and other eligible native styles retain their existing editor.
The production controller supplies the current mode to the controls; an additional
guard prevents entering editing when the current style cannot use it.

Final acceptance of the tested APK:

| Gate | Result |
| --- | --- |
| Integrated debug build and JVM suite | Successful; **3,425 tests / 356 suites**, zero failures or errors |
| Actual-source slideshow browser suite | **23 passed**, including dissolve, pause fallback and publication cancellation |
| Native design/control suite | **50 passed in 129.1 seconds** on the owned API 34 emulator |
| Native capture review | **36 tabs loaded**, zero page errors |
| Family Area installed-byte readback | Exact local/installed APK match |
| Full-profile settings comparison | **97 values; zero changed** |

Family Area's installed SHA-256 is
`4ffba1c2e83004d0fab75b17b3ad3d5111d918d6f3f93236f6a1b55602190c2b`
(**48,903,278 bytes**). Its certificate matches the preceding installed APK, which
was downloaded and retained privately as a byte-for-byte rollback.
Home/screensaver dispatches were acknowledged; those acknowledgments are not
visible-transition proof. Living Area was not changed.

The native captures use original silent fixtures and do not establish physical
Portal timing, sensor behavior or audio. The owned emulator was stopped after
acceptance. The earlier rejected timing-capture admission remains parked;
it was not retried in this batch. Physical night-clock acceptance and comparable
rendering measurements remain open. Live music wiring remains deferred and no
music operation was deployed to Home Assistant.

## Ken Burns rendering work and queue refinement — 16:53 UTC batch

The existing Ken Burns style now shows its prepared photo against a quiet charcoal
matte with a 3vmin inset, preserving its source selection and gentle motion for
photos without a cached face hint. It creates **zero full-viewport blurred duplicate
photos**. A cached face hint preserves the complete image statically, without a
permanent transform compositor hint; a point alone cannot prove a safe moving crop.
Animated-source and Shuffle contracts were not replaced with a native still-only path.

Ken Burns and Diptych share one incoming-only dissolve owner. Initial readiness
publishes an opaque scene, the outgoing photo stays opaque during replacement,
pause freezes motion and alpha without `getAnimations`, and outgoing URLs and
motion are released after the replacement is opaque. The dissolve measures elapsed
active time with a monotonic clock where available. Cleanup rejects publication
between completed decode admission and the continuation that constructs a scene.

The selected music queue row now retains its artist, with a separate smaller
playback-status line. Only confirmed playing/paused states show those indicators;
stopped, idle, pending, restricted local and offline states do not imply active
playback. Command guards, bounded artwork and stable lazy-list keys remain intact.
No backend or Home Assistant operation was added.

The startup regression failed against the preceding renderer before the change.
Fresh independent review found no actionable dissolve defects after the correction.
The source-fixture gate was updated to permit legitimate terminal resource release
while still rejecting any subsequent decode publication. The finite-style-ID check
now explicitly distinguishes the two native Gallery styles from WebView styles.

| Final gate | Evidence |
| --- | --- |
| Integrated JVM/debug build | **3,434 tests / 357 suites**, zero failures/errors; APK assembled |
| Actual-source Chrome renderer | **31 passed**, including old-WebView motion/alpha pause and publication cancellation |
| Slideshow source tests | **132 passed**, zero failures/skips |
| Engine source fixtures | **14/14 passed** |
| Native design/control acceptance | **51 passed in 129.58 seconds** on the dedicated API 34 emulator |
| Native browser review | **36 captures loaded**, zero page errors |
| Family Area profile preservation | **97 values**, zero changed |

The installed Family Area APK was independently read back and matched SHA-256
`b04b8a9d242dac4aab033f67d1082edcb156a5b1836b4ac6ae3220021431aeac`
(**48,905,248 bytes**). Its certificate matches the preceding installed APK,
retained privately as an exact rollback. Home/screensaver requests were acknowledged;
visible transitions were not established by those acknowledgments. Living Area
remains unchanged.

No physical FPS or GPU-memory gain is claimed from removing the decorative layers.
Physical night-clock acceptance is pending a real dark/bright check. Family Area is
reachable through Fleet; the current ADB inventory contains only the two emulators,
and the inspected Fleet routes expose no screenshot endpoint. Native and Chrome
captures therefore remain scoped to their test surfaces.
The owned emulator was stopped after acceptance. Family Area's collector was
independently confirmed inactive with zero listeners; no new timing capture was
attempted in this batch.

## Framed Print refinement and bounded capture check — 17:05 UTC batch

Framed Print now uses the same incoming-only dissolve owner as Diptych and Ken Burns.
Initial scene readiness is opaque; each incoming scene paints the existing room
gradient behind the print, covering the outgoing frame even when the two photographs
have different dimensions. Original frame geometry, matte, lighting, interval and
gentle breathe motion for photos without a cached face hint remain intact. Cached
face hints retain a complete still print rather than introducing a speculative crop.

Pause freezes opacity and breathe without depending on the newer WebView animation
API. Retirement and terminal cleanup release image URLs and animation hints. A
completed decode cannot repopulate a scene after its owner has been closed.

The added actual-source Chrome startup check failed against exact preceding commit
`c82edd2839e82c0bd97c0c42c07df5d85f348fd0` and passes with the refinement. Eight new
browser checks also cover room coverage, outgoing opacity, pause, resource retirement,
face preservation and cancellation immediately before publication. The synthetic
Framed screenshot was visually inspected.

| Current gate | Evidence |
| --- | --- |
| Integrated JVM/debug build | Build successful; existing outputs contain **3,434 tests / 357 suites**, zero failures/errors/skips |
| Actual-source Chrome renderer | **39 passed**, zero production-script errors |
| Slideshow source tests | **146 passed**, zero failures/skips |
| Engine source fixtures | **14/14 passed** |
| Family Area profile preservation | **97 values**, zero changes after install and after capture cleanup |

The preceding **51 native UI checks** remain evidence for unchanged Kotlin UI code;
they were not rerun in this JavaScript/CSS batch. No emulator was started.

Family Area's installed APK was independently read back and matches SHA-256
`f2ed045fa62f3097bf27fcd4806cb3caba6532fe8078e168cd2310c5c76ae7f9`
(**48,905,248 bytes**). The signing certificate matches the preceding installation,
which is retained privately as an exact rollback. Home/screensaver dispatches were
acknowledged; these are not proof of visible transitions. Living Area remains unchanged.

One initial ten-second diagnostic capture started with HTTP **200 in 294.4 ms** and
expired cleanly. It recorded ten Window callbacks and no slideshow style events;
that sparse, unscoped sample cannot establish slideshow smoothness or delivered FPS.
The later fixed-Framed start returned HTTP **503 in 2,061.6 ms**, before navigation
into the intended capture. The two-second deadline is consistent with the existing
command admission boundary, but the exact underlying cause is not established.
No performance-capture software fix is claimed. There was no blind retry.

Temporary mode/interval changes were restored through the settings registry. All
97 profile values and the photo-source/folder-path values match the pre-capture
state. The night-clock setting remained enabled throughout. Final capture state is
inactive, with the collector stopped and zero listeners. The capture-admission
investigation is parked at this batch's time limit.

Physical dark/bright night-clock acceptance, actual Portal visual transition
acceptance and reliable presentation-performance measurements remain open. Removal
of redundant rendering work and the tested lifecycle corrections do not establish
a measured physical FPS gain.

## Cinematic Push and speaker chooser — 17:19 UTC batch

Cinematic Push now presents a whole photograph on a quiet matte, with a slow 4%
push inside a 5% inset. At the final zoom the complete photo container remains
inside the screen. The existing configured interval and photo-resolution request
remain intact. Cached face-hinted photographs stay whole and still.

The style uses the shared incoming-only dissolve controller. Its first ready scene
is opaque, pause freezes motion and opacity without the newer WebView animation
API, and retirement/cleanup release image URLs and animation hints. A closed owner
rejects publication even between decode admission and the continuation that builds
the scene. Background photo draws drop from four to two; full-screen blurred fills
drop from two to zero. These are code-level rendering changes, not measured texture
allocation, GPU execution or delivered-FPS savings.

The native speaker chooser identifies the selected source separately from its group
leader, disables redundant selection, displays eligible group actions and explains
unavailable states. Buttons stack at narrow widths or large text while retaining
48dp touch targets. Existing selection/group command guards remain authoritative;
no provider, audio backend or Home Assistant service was added.

A new native selection regression found a real navigation defect: selecting another
speaker changed `sourceId`, which reset the remembered tab to Queue. Tab state now
belongs to the current UI composition rather than the source lease. The chooser
remains open across selection, and each pane still reads the current snapshot.
The debug-only silent `speaker-groups` fixture exercises compatible, incompatible,
offline and selected players without connecting to household media.

| Final gate | Evidence |
| --- | --- |
| Integrated JVM/debug build | **3,445 tests / 358 suites**, zero failures/errors/skips; APK assembled |
| Speaker presentation policy | **11 tests**, included in the JVM total |
| Slideshow source tests | **151 passed**, zero failures/skips |
| Engine source fixtures | **14/14 passed** |
| Actual-source Chrome renderer | **49 passed**, zero production-script errors |
| Native UI acceptance | **56 passed in 151.09 seconds**, including chooser continuity and stacked large-text actions |
| Browser native review | **39 captures loaded**, zero page errors |
| Family Area profile preservation | **97 values**, zero changes |

The five added Cinematic Push source regressions fail against the preceding renderer
and pass against the refinement. The native selection test failed before removing
the source-keyed tab reset and passes afterward. A verifier initially read enabled
state from a text label; it now checks the actual clickable ancestor. The large-text
speaker screenshot and the actual-source Cinematic Push screenshot were visually
inspected. Native evidence is from the dedicated API 34 emulator, not a physical
Portal performance benchmark.

Family Area's installed APK was independently read back and matches SHA-256
`5b4dbbdf9b7b0799495f6f0de58bd6158807c71b2e7b0970251a9fe167e709a0`
(**48,910,412 bytes**). Its signing certificate matches the prior installed APK,
retained privately as an exact rollback. All 97 settings match the pre-install
profile. Home/screensaver dispatches were acknowledged; visible transitions are
not established by those acknowledgments. Living Area remains unchanged.

The existing performance-capture admission issue remains parked; no further timing
capture was attempted in this batch. Physical night-clock acceptance and reliable
Portal presentation measurements remain open. Music provider wiring stays deferred.
The owned emulator was stopped after acceptance. Family Area's collector is
independently confirmed inactive with zero listeners. The broader goal remains active.

## Living Gallery Wall — 17:42 UTC batch

Living Gallery Wall now fits its packed rows within both screen dimensions.
Row sizing follows screen height; shorter final rows are centered and all ten
selected photographs are retained, including uniform portrait/landscape collections.
Tiles show whole photographs on a quiet matte without continuous pan/zoom.

Every cell owns two fixed image slots and uses the shared incoming-only dissolve.
The outgoing photo stays opaque until its replacement is ready and fully visible;
retirement and terminal cleanup release its URL and animation hints. The incoming
slot paints an opaque matte so a differently shaped replacement does not expose
the outgoing image in its margins. Pause freezes opacity without the newer WebView
animation API. Closed owners reject decoded work immediately before publication.

Independent review reproduced duplicate repeat scheduling when pause/resume occurs
during a pending decode. The shared timer now tracks an executing callback
separately from its timeout ID, preserving one cadence across resume. A controlled
clock regression and a real asynchronous Chrome decode check both exercise that
failure. This affects timer ownership shared by the slideshow modes, so the full
source and browser regression suites were rerun after the correction.

| Final gate | Evidence |
| --- | --- |
| Slideshow source checks | **150 passed**, zero failures/skips; **11 Gallery Wall checks** included |
| Engine fixtures | **14/14 passed** |
| Actual-source Chrome renderer | **59 passed**, zero production-script errors; five styles captured |
| Integrated debug build | Successful; unchanged JVM task was **UP-TO-DATE**, retaining **3,445 passing results / 358 suites** |
| Native UI scope | Prior **56 checks / 151.09 seconds** cover unchanged Kotlin; not rerun in this asset batch |
| Visual review | Actual-source Gallery Wall screenshot inspected at 2160 × 1440 |

The browser preview includes twelve original mixed-aspect SVG fixtures with verified
dimensions and a finite asset allowlist. No household photographs or credentials
are exposed by the preview server. The previous performance-capture admission issue
remains parked; no new capture was requested. These rendering and timer corrections
do not establish measured physical Portal FPS, texture memory or GPU savings.

Family Area's installed APK was independently read back and matches SHA-256
`e98b85c2ac9f3fd232aeb297018d41af5e26fc3fadb1f208352717fd4960bad7`
(**48,910,412 bytes**). Its signing certificate matches the exact preceding APK,
retained privately as a rollback. There was one upload/install; the process
replacement interrupted the HTTP response and installed-byte verification
resolved that ambiguity without reinstalling. All **97 settings** match the
pre-install snapshot. Home/screensaver dispatches were acknowledged; those
responses do not establish visual transitions.

Capture state is independently confirmed inactive, with the collector stopped
and **zero active listeners**. Living Area remains unchanged. Physical night-clock
acceptance, actual Portal visual acceptance and reliable presentation measurements
remain open. No music provider/backend or Home Assistant service was deployed.
The implementation, review, regression checks, deployment and commit completed
within this batch's thirty-minute limit.

## Hero + Sidebar — 17:59 UTC batch

The three-pane composition now shows whole static photographs on matte, preserving
the larger landscape hero and two portrait side panes. One-photo libraries use a
full-width hero instead of leaving an empty sidebar. The existing orientation
preferences, unique-photo selection and configured swap interval remain intact.

Initial size-resolution requests drop from **six to three**: one 1800px hero request
and two 1100px sidebar requests. Three duplicate blurred-fill draws are removed.
This is structural rendering/preparation evidence, not a claim of measured decode
time, texture memory, GPU cost or physical FPS.

Each pane owns two fixed scene slots and uses the tested incoming-only dissolve.
Its outgoing photo stays opaque until the replacement is ready and opaque.
Retirement and cleanup release URLs and animation hints; pause freezes a live
fade without the newer WebView animation API. Closed owners reject both display
publication and requests for the next sidebar derivative.

Independent review reproduced a resume/re-pause race during asynchronous decode.
The shared preparation gate now rechecks current playback state before admitting
decode and after it completes. Hero's derivative resolution and DOM publication
also use a synchronous action inside that gate. A stale resume grant waits for
the next valid resume rather than starting hidden photo work. Source regressions
cover 25 handoff checkpoints each for initial and replacement preparation; actual
Chrome checks exercise both phases with real asynchronous image decode.

| Final gate | Evidence |
| --- | --- |
| Slideshow source checks | **161 passed**, zero failures/skips; **11 Hero + Sidebar tests** included |
| Engine fixtures | **14/14 passed** |
| Actual-source Chrome renderer | **71 passed**, zero production-script errors; six styles captured |
| Integrated debug build | Successful; unchanged JVM task was **UP-TO-DATE**, retaining **3,445 passing results / 358 suites** |
| Native UI scope | Prior **56 checks / 151.09 seconds** cover unchanged Kotlin; not rerun in this asset batch |
| Visual review | Actual-source Hero + Sidebar screenshot inspected at 2160 × 1440 |

The finite preview allowlist reuses the existing twelve original gallery fixtures.
Music backend wiring remains deferred and no Home Assistant operation belongs to
this batch. No new physical timing capture was requested; its admission issue stays
parked. Physical night-mode acceptance and reliable Portal presentation measurements
remain open.

Family Area's installed APK was independently read back and matches SHA-256
`d8b868cb981a64da9f11955367f6204ef4b047da8b61736bdba02965d2afe453`
(**48,910,412 bytes**). Its signing certificate matches the exact preceding
Gallery Wall APK, retained privately as a rollback. One upload/install was used;
installed-byte verification resolved the interrupted install HTTP response.
All **97 settings** match the pre-install profile. Home/screensaver dispatches
were acknowledged; these responses do not establish visible transitions.

Capture state is independently confirmed inactive, with the collector stopped
and **zero active listeners**. Living Area remains unchanged. Implementation,
independent review, regression checks, deployment and commit completed within
this batch's thirty-minute limit. The broader objective remains active.

## Memories — 18:14 UTC batch

Memories keeps its five individual photographs followed by a collage on every
sixth successful beat. Each single photograph is complete on a quiet matte, with
a gentle 4% push only without a cached face hint. Cached face hints retain a
complete still photograph. Collages now use photo-shaped paper frames containing
whole static photographs, with one, two or three unique images according to the
library size. The small neutral heading remains `Photo memories`; no date, place
or event is inferred from a photograph.

Two fixed reusable scene roots replace two single scenes plus a separate retained
collage. At most **four photo elements** own URLs during a single/collage dissolve,
compared with up to **seven** in the preceding renderer. Duplicate blurred
single-photo fills and independently moving collage tiles are removed. This is
structural ownership/rendering evidence, not a measured Portal RAM or FPS gain.

The shared incoming-only dissolve keeps the outgoing scene opaque until its
replacement is ready and opaque. Retirement clears owned URLs, animation and
promotion hints. Manual pause freezes both the dissolve and single-photo motion
without relying on newer WebView animation APIs. Failed collage preparation
retains the current photo and retries the collage beat rather than skipping it.
Current-playback gates reject late publication and derivative work after pause
or close, including resume/re-pause during asynchronous decode.

| Final gate | Evidence |
| --- | --- |
| Slideshow source checks | **173 passed**, zero failures/skips; **12 new Memories tests** included |
| Engine fixtures | **14/14 passed** |
| Actual-source Chrome renderer | **102 assertions passed**, including **31 Memories assertions**, zero production-script errors |
| Integrated debug build | Successful; unchanged JVM task was **UP-TO-DATE**, retaining **3,445 passing results / 358 suites** |
| Native UI scope | Prior **56 checks / 151.09 seconds** cover unchanged Kotlin; not rerun in this asset batch |
| Visual review | Actual-source single and collage screenshots inspected at 2160 × 1440 |

Browser checks exercise the five-single/sixth-collage cadence, both layout
transitions, opaque outgoing URLs, old-WebView pause beyond the fade deadline,
retirement, terminal cleanup, small libraries, cached face hints, whole-photo
card proportions, final zoom bounds and cancellation. The prior six renderer
screenshots remain unchanged.

The finite review server now includes Memories using its existing original local
gallery fixtures. An optional five-second review pace reaches the collage quickly;
normal review remains thirty seconds. The query does not alter Portal settings.
No household photographs or credentials are served by the preview.

Family Area's installed APK was independently read back and matches SHA-256
`3d68fb10606ad3aa36f0afcfb2f58449ef7c2e260e90e90ce56d3d0de8c52a36`
(**48,910,412 bytes**). Its signing certificate matches the preceding Hero +
Sidebar APK, retained privately as a rollback. One upload/install was used;
installed-byte verification resolved the interrupted install HTTP response.
All **97 settings** match the pre-install profile. Home/screensaver dispatches
were acknowledged; these responses do not establish visible transitions.

Capture state is independently confirmed inactive, with the collector stopped
and **zero active listeners**. No new physical timing capture was requested;
its admission issue remains parked. Living Area remains unchanged. Physical
night-clock acceptance and reliable Portal presentation measurements remain
open. Music backend wiring stays deferred, and no Home Assistant operation
belongs to this batch. Implementation, parallel browser verification, deployment,
cleanup and commit completed within the thirty-minute limit. The broader goal
remains active.

## Panorama Drift — 18:28 UTC batch

Panorama Drift retains its slow horizontal sweep and existing preference for wide
photographs. The moving strip is now capped at **two viewport widths**; its height
fits the photo's aspect ratio and is centered vertically on matte. This preserves
the complete vertical image while allowing both horizontal edges to pass through
the window. Landscapes that already fit remain whole and static. Cached focal
points hold a static panoramic window around the anchor, without a permanent
promotion hint.

For a **10:1 input at 2160 × 1440**, the preceding nominal strip geometry was
approximately **14,400 × 1,440**; the new geometry is **4,320 × 432**. This is a
calculation from element bounds, not a measured texture allocation or physical
Portal FPS result. Only genuinely moving strips request transform promotion.

The two fixed scene roots and two strips now use an incoming-only **1.8-second**
dissolve. The outgoing URL remains opaque until the incoming scene is ready and
opaque; retirement and terminal cleanup clear image URLs, animation, transforms
and promotion hints. Current-playback publication gates reject new work after
pause or close. Manual pause freezes strip motion and a live dissolve without
newer WebView animation APIs.

Source inspection found that the shared scheduler gives zoom motion an extra
2.4 seconds. A panorama using that duration could retire before completing its
sweep. Panorama now limits its motion duration to the selected photo interval,
so the last horizontal edge is reached before outgoing URL retirement. A source
test also caught an inherited fade-duration mismatch; the style explicitly
matches its CSS transition to the 1.8-second owner.

| Final gate | Evidence |
| --- | --- |
| Slideshow source checks | **185 passed**, zero failures/skips; **12 new Panorama tests** included |
| Engine fixtures | **14/14 passed** |
| Actual-source Chrome renderer | **151 assertions passed**, including **49 Panorama assertions**, zero production-script errors |
| Geometry coverage | Four viewports × six photo aspects; **24 actual decoded-SVG cases** |
| End-of-pan rendering | Original 8:1 landscape's right-edge marker pixels confirmed in Chrome |
| Integrated debug build | Successful; unchanged JVM task was **UP-TO-DATE**, retaining **3,445 passing results / 358 suites** |
| Native UI scope | Prior **56 checks / 151.09 seconds** cover unchanged Kotlin; not rerun in this asset batch |
| Visual review | Actual-source Panorama screenshot inspected at 2160 × 1440 |

Source regressions cover 25 checkpoints each for initial close, initial
resume/re-pause and replacement resume/re-pause. Browser checks exercise the
actual pan endpoints before retirement, incoming-only fades, retained pause,
remaining-time resume, static landscapes, five cached focal anchors, cleanup
and decode-admission cancellation. Prior renderer PNG evidence remains
byte-identical. The finite review server adds original 3:1 and 8:1 landscape
fixtures with matching dimensions; it serves no household media or credentials.

The implementation follows Google's [high-performance animation guide](https://web.dev/articles/animations-guide)
and [compositor layer guidance](https://web.dev/articles/stick-to-compositor-only-properties-and-manage-layer-count):
use transforms/opacity for movement and fading, avoid per-frame layout/paint
properties, and release unnecessary layer hints. These authoritative documents
support the rendering strategy, not a device performance pass.

Family Area's installed APK was independently read back and matches SHA-256
`8873059eaf3aac200b053315f2eaf2560d6e417570cf21f114544df812a13a2b`
(**48,910,412 bytes**). Its signing certificate matches the preceding Memories
APK, retained privately as a rollback. One upload/install was used; independent
installed-byte verification resolved the interrupted install HTTP response.
All **97 settings** match the pre-install profile. Home/screensaver dispatches
were acknowledged; these responses do not establish visible transitions.

Capture state is independently confirmed inactive, with the collector stopped
and **zero active listeners**. No new physical timing capture was requested.
Living Area remains unchanged. Physical night-clock acceptance, reliable Portal
presentation measurements and the remaining style refinements stay open.
Music backend wiring remains deferred, with no Home Assistant operation.
Implementation, parallel verification, deployment, cleanup and commit completed
within this batch's thirty-minute limit. The broader goal remains active.

## Mosaic Wall — 18:39 UTC batch

Mosaic retains its varied fixed BSP tiles, roaming spotlight and in-place photo
exchanges. A nine-tile wall now owns **eighteen fixed image slots** instead of
appending replacement images. Each cell uses the shared incoming-only
**1.1-second dissolve**, retaining opaque outgoing pixels until the new pane is
opaque. Retirement and terminal cleanup clear photo URLs, motion and explicit
promotion hints.

Only the spotlighted tile runs photo motion: **at most one photo in steady
state**, with up to two during that tile's live dissolve. The spotlight visits
every tile in order without reshaping the wall. Paused photos use
`animation-play-state: paused` and `will-change: auto`; these are verified CSS
states, not measurements of actual GPU layer allocation.

Mismatched-aspect pan strips fit their cross axis and are capped at **two tile
widths/heights**, with quiet matte behind them. Compatible photos remain whole
inside a 92% inset box with a gentle maximum 4% push. Cached focal anchors stay
static and visible rather than running a pan across them. Existing 1200-edge
derivative requests are retained; this batch does not add adaptive derivative
sizing or face inference.

Initial decode admission proceeds in **batches of two**, with all selected tiles
ready before the outer scene is published. Unseen-photo replacement changes one
cell; full-library exchanges prepare both resources before atomically swapping
two cells with matching layout appetite. Current-playback gates prevent further
derivative requests or DOM publication after close or a second pause.

Manual pause holds the cell's spotlight transform, photo animation and any live
fade without relying on newer WebView animation APIs. Resume continues the
remaining fade and cadence. Cleanup removes the wall and releases every retained
image reference, including when initial decode completion races with close.

| Final gate | Evidence |
| --- | --- |
| Slideshow source checks | **198 passed**, zero failures/skips; **13 new Mosaic tests** included |
| Engine fixtures | **14/14 passed** |
| Actual-source Chrome renderer | **174 assertions passed**, including **23 Mosaic assertions**, zero script errors |
| Initial composition | Nine unique opaque tiles, eighteen fixed panes, maximum two concurrent decode admissions |
| Motion and geometry | Spotlight visits every tile; photo motion/hints remain ≤1 steady / ≤2 during a fade; bounds and layout remain fixed |
| Integrated debug build | Successful in **5 seconds**; unchanged JVM task **UP-TO-DATE**, retaining **3,445 passing results / 358 suites** |
| Native UI scope | Prior **56 checks / 151.09 seconds** cover unchanged Kotlin; not rerun for this asset batch |
| Visual review | Actual-source Mosaic screenshot inspected at **2160 × 1440** |

Source regressions close or resume/re-pause at 25 asynchronous checkpoints for
each of three initial/replacement cases. Browser coverage includes the complete
production scheduler, a held two-decode initial batch, atomic full-library
exchange, incoming-only fades, manual retained pause, cleanup/cancellation,
one/two/five-photo libraries, and real decoded extreme-aspect SVGs with static
cached anchors. All **nine prior renderer PNGs** remain byte-identical. The finite
review server adds Mosaic using the existing twelve synthetic gallery fixtures;
it serves no household photos or credentials.

Family Area's installed APK was independently read back and matches SHA-256
`681ae44c02c6d97b77a848cc26d1bf0f9f79b725691a641d135c97ccd5e50819`
(**48,910,412 bytes**). Its signing certificate matches the preceding Panorama
APK, retained privately as a rollback. One upload/install was used; installed-byte
verification resolved the interrupted install HTTP response. All **97 settings**
match the pre-install profile. Navigation dispatch acknowledgments do not prove
visible device transitions.

The performance collector is independently confirmed inactive, stopped and
holding **zero active listeners**. No new physical timing capture was requested.
Living Area remains unchanged. Physical night-clock acceptance and reliable Portal
presentation measurements remain open; the source/browser checks do not establish
a physical FPS improvement. Music backend wiring remains deferred, and no Home
Assistant operation belongs to this batch. The broader goal remains active.

## Polaroid Scatter — 18:55 UTC batch

Polaroid retains its scattered paper prints, small tilts and in-place replacement
of the oldest print. It now sizes the complete photograph and paper border using
the **rotated envelope**, then places that envelope inside a distinct screen
region. Photo proportions stay intact, including extreme portraits and panoramas.
Borders adapt to the region size, the blank chin is 2.5× the side border, and tilt
is bounded to ±8°. Cached face photos remain whole; missing metadata produces no
invented caption.

A six-print composition owns **seven reusable card/image slots**. Smaller
libraries retain their unique-photo count plus one incoming slot. Initial cards
are opaque before the scheduler publishes the composition, so there is no second
per-card startup fade. A replacement scales from 90% to 100% over **900ms** while
only its incoming opacity fades. The outgoing print retains its exact opaque URL
until the replacement is opaque, then releases its URL, transform and hint.

**Resting promotion hints: six → zero.** Only the incoming card asks for
`transform, opacity` during a replacement. Rapid replacement settles the preceding
incoming card before reusing its outgoing slot; stale retirement callbacks cannot
release a later owner. These are verified DOM/CSS ownership limits, not measured
GPU allocations or physical Portal FPS gains.

Manual pause retains incoming opacity/transform and stops further requests and
cadence timers. Resume continues the remaining fade and interval. Cleanup removes
the table and clears all seven retained URLs and hints. Derivative acquisition and
DOM publication recheck current playback ownership across close and resume/re-pause.
Existing sequential 1400-edge derivative requests are retained.

| Final gate | Evidence |
| --- | --- |
| Slideshow source checks | **209 passed**, zero failures/skips; **11 new Polaroid tests** included |
| Engine fixtures | **14/14 passed** |
| Actual-source Chrome renderer | **206 assertions passed**, including **32 Polaroid assertions**, zero script errors |
| Rotated whole-photo geometry | Five viewports, **320×240 to 2160×1440**; six aspects from **0.025:1 to 40:1**, both tilt directions |
| Composition and ownership | Six unique opaque prints, seven fixed slots, one incoming hint / zero resting hints |
| Integrated debug build | Successful in **5 seconds**; unchanged JVM task **UP-TO-DATE**, retaining **3,445 passing results / 358 suites** |
| Native UI scope | Prior **56 checks / 151.09 seconds** cover unchanged Kotlin; not rerun for this asset batch |
| Visual review | Actual-source Polaroid screenshot inspected at **2160 × 1440** |

Source regressions cover 25 checkpoints each for initial close, initial
resume/re-pause and replacement resume/re-pause. Browser checks hold the sixth
initial decode, verify exact admitted resources, retain an opaque outgoing print,
pause beyond drop/fade deadlines without newer animation APIs, resume the remaining
fade and cadence, and exercise rapid slot reuse and cleanup. Small cached-face
libraries retain one/two/five complete, unique prints. Immediate same-task fade
pause/resume was not separately tested. All **ten prior renderer PNGs** remain
byte-identical. The finite review server adds Polaroid using the existing twelve
synthetic gallery fixtures; no household media or credentials are served.

Family Area's installed APK was independently read back and matches SHA-256
`2d55e3f96ef63b663eb1ce4924db484b9d0ffbff1cf32c979768a9162bf293b3`
(**48,910,412 bytes**). Its signing certificate matches the preceding Mosaic APK,
retained privately as a rollback. One upload/install was used; installed-byte
verification resolved the interrupted install HTTP response. All **97 settings**
match the pre-install profile. Navigation acknowledgments do not prove visible
device transitions.

The collector is independently confirmed inactive, stopped and holding **zero
active listeners**. No new physical timing capture was requested. Living Area
remains unchanged. Physical night-clock acceptance and reliable Portal presentation
measurements remain open. Music backend wiring remains deferred; no Home Assistant
operation belongs to this batch. The broader goal remains active.

## Cinema Letterbox — 19:15 UTC batch

Cinema retains its black **11% letterbox bars** and wide-photo preference. Wide
photographs keep their immersive cover crop, using a gentle maximum **4% zoom**
without a cached focal point. Cached focal points remain still and are centered
within the crop where its edges permit. A point does not establish the extent of
a face, so this does not promise that a wide photograph or an entire face is
uncropped. Ordinary landscapes and portraits remain complete, centered and static.

Two fixed photo scenes use the shared incoming-only **2.2-second dissolve**. The
outgoing URL stays opaque until the incoming scene is opaque, then loses its
photo URL, motion and promotion hint. Manual pause retains both photo animation
and fade opacity without newer WebView animation APIs; resume continues the
remaining fade and photo cadence. Cleanup removes the bars/scope and clears
photo/grain owners. Current-playback gates reject work after close or re-pause.

A deterministic **128×128 grayscale PNG, 12,657 bytes**, replaces the previous
full-screen SVG turbulence filter and overlay blend. Each static grain tile
belongs to its photo rectangle and uses ordinary blending; bars and matte remain
black. It requests no independent animation or promotion hint. The APK contains
the exact tested asset, and the native file-asset loading path needs no server.
This removes a rendering mechanism; it does not establish measured GPU cost or
physical Portal FPS.

| Final gate | Evidence |
| --- | --- |
| Slideshow source checks | **222 passed**, zero failures/skips; **13 new Cinema tests** included |
| Engine fixtures | **14/14 passed**, including runtime Cinema dwell/motion checks at 5/30/120 seconds |
| Actual-source Chrome renderer | **255 assertions passed**, including **49 Cinema assertions**, zero script errors; **167.921 seconds** |
| Photo geometry | Three viewports × seven decoded aspects; **21 cases**, plus eight cached focal-point cases |
| Integrated debug build | Successful in **5 seconds**; unchanged JVM task **UP-TO-DATE**, retaining **3,445 passing results / 358 suites** |
| Native UI scope | Prior **56 checks / 151.09 seconds** cover unchanged Kotlin; not rerun for this asset batch |
| Visual review | Actual-source Cinema capture inspected at **2160 × 1440** |

Source regressions close or resume/re-pause at 25 asynchronous checkpoints.
The close test allows readiness that completed before closing, while requiring
no later publication, derivative requests or timers. Browser coverage includes
the real decoded grain, black bars, whole ordinary photos, cached crop anchors,
full 4% zoom bounds, opaque outgoing URLs, retained manual pause, remaining
cadence, rapid reuse, one-photo admission and terminal cleanup. All **eleven prior
renderer PNGs** remain byte-identical. The finite local preview adds Cinema using
original synthetic panorama/gallery fixtures and serves no household photos.

Family Area's installed APK was independently read back and matches SHA-256
`fd81830d8c93ce26d370e791a1cd59813cf6a0a516ac3bdb8bdfb1b956e0b7bb`
(**48,910,493 bytes**). Its signing certificate matches the preceding Polaroid APK,
retained privately as a rollback. One upload/install was used; installed-byte
verification resolved the interrupted install HTTP response. All **97 settings**
match the pre-install profile. Navigation acknowledgments do not prove visible
device transitions.

The performance collector is inactive, stopped and holding **zero active
listeners**. No new physical timing capture was requested. Verification and
deployment completed by **19:29 UTC**, within the batch's thirty-minute limit.
Living Area remains unchanged. Physical night-clock acceptance and reliable
Portal presentation measurements remain open. Music backend wiring remains
deferred, and no Home Assistant operation belongs to this batch. The broader
goal remains active.

## Native Filmstrip scratch reuse — 19:30 UTC batch

Native Filmstrip was already enabled in the installed build. This change targets
repeated backdrop preparation: one worker-owned session now reuses the small
bitmap, Canvas, Paint, two RenderScript allocations and blur script across the
deck. Each finished backdrop remains a separate caller-owned bitmap. Clearing
transparent input and resetting ping-pong direction preserves the preceding
algorithm's pixels, including after even-pass photos or interruption.

For twelve valid photos, allocation-construction calls fall **24 → 2** and blur
script constructions **12 → 1** per preparation batch. This does not establish
equivalent reductions in latency or frame time. Android already caches process
RenderScript contexts from API 23, so no native-context reduction is claimed.
The existing **256 MiB** Filmstrip deck budget is unchanged; **3 MiB** of scratch
admission stays charged until the worker closes its session. The measured deck
below does not establish a universal memory limit for larger libraries.

| Final gate | Evidence |
| --- | --- |
| Fresh Android JVM gate | **3,449 tests / 358 suites**, zero failures/skips; full test/build invocation succeeded in **49 seconds** |
| Native backdrop fixture | **233 API 34 checks passed**, including **60 exact pixel comparisons** against the frozen pre-change algorithm |
| Input and pass coverage | Twelve generated opaque/transparent sources × five scenarios with **1/2/3/6/8** blur passes |
| Host pixel evidence | Twelve oracle/session PNG pairs match byte-for-byte |
| Resource ownership | Two stable allocation handles, one stable blur handle, independent returned pixels, thread ownership, closed-state rejection and explicit handle destruction |
| Family Area runtime smoke | Native host ready with **9 cards**, advanced **0 → 1**, retained pixel bytes stable at **33,285,312 (31.74 MiB)**, viewport **2160 × 1440** |
| Smoke restoration | All **97 settings** restored, zero changed values; collector inactive with **zero listeners** |

The native fixture additionally checks lazy initialization, deterministic
pre-interruption and reuse after interruption without transparent residual pixels.
Interruption inside a native blur pass was not measured. The debug-only activity
uses generated artwork; its fixture cache was removed and its dedicated AVD
stopped after testing. Evidence lives in
`mocks/portal-premium-player-20261030/evidence/filmstrip-backdrop-native/`.
The earlier **222 source checks / 14 engine fixtures / 255 Chrome assertions**
cover unchanged slideshow assets and were not rerun for this worker-only change.

Family Area's installed APK was independently read back and matches SHA-256
`897126651957786667ae89df9f51b6102e7183ff4f689fce83611af1badea6b0`
(**48,933,019 bytes**), also matching the APK used by the native fixture. Its
signing certificate matches the preceding Cinema build, retained privately as a
rollback. The final fixture-only ownership correction was rebuilt after the full
JVM gate; production source hashes remain identical to that gate. One upload/install
was used; installed-byte verification resolved the interrupted HTTP install
response without repeating installation.

The live smoke temporarily selected Filmstrip and a five-second interval through
the settings registry. It read native readiness and advance counters without
starting a performance capture, then restored the original mode and interval.
The complete settings profile matches its pre-smoke state. Readiness and advance
establish the running native stage; they do **not** verify presented pixels,
startup latency or physical FPS. No new timing capture was requested.

Living Area remains unchanged. Physical night-clock acceptance, reliable Portal
presentation measurements and remaining Home/Apps performance outliers stay open.
Music backend wiring remains deferred; no Home Assistant operation belongs to
this batch. The broader goal remains active.

## Sources

- [Android intrinsic blur contract](https://developer.android.com/reference/android/renderscript/ScriptIntrinsicBlur)
- [Android Allocation bitmap synchronization and lifecycle](https://developer.android.com/reference/android/renderscript/Allocation)
- [Android RenderScript process-context caching](https://developer.android.com/reference/kotlin/android/renderscript/RenderScript)

- [Android MediaPlayer thread and seek contracts](https://developer.android.com/reference/android/media/MediaPlayer)
- [Android SurfaceView versus TextureView tradeoffs](https://developer.android.com/reference/android/view/TextureView)

- [Android sensor monitoring and lifecycle](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview)
- [Android light sensors](https://developer.android.com/develop/sensors-and-location/sensors/sensors_environment)
- [Android window brightness override](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#screenBrightness)
- [Hardware specifications, benchmarks and rendering evidence](portal-hardware-budget-2026-10-30.md)
- [HA-free music alternatives](music-without-home-assistant-2026-10-19.md)
