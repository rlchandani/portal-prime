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

## Shared Home/Apps/Tools animation clock — 19:47 UTC batch

The shipped Compose tab renderer previously kept three `Animatable` instances
and launched a separate animation coroutine for each visible page. It now owns
**one `Animatable` and one running animation coroutine** for the slide. Moving
pages derive their positions from the same progress value during placement;
hidden retained pages do not subscribe to the running clock.

The existing **160ms FastOutSlowIn** slide, entrance direction, theme, visited
page retention and input/lifecycle gates remain. An interrupted plan first
records both displayed positions. Alternating clock endpoints avoids a reset
still visible to outgoing placement; the replacement plan then starts from those
positions. Existing transition ownership rejects obsolete completion. No
hardware layer or Compose dependency change was enabled: the earlier native Home
candidate remains disabled after its failed device timing gate.

Five JVM regressions cover bidirectional movement, interrupted reversal, repeated
three-page interruptions, hidden-page exclusion and arbitrary interrupted clock
values. The initial RED run could not compile because the new animation model
was absent. The fresh integrated GREEN gate passed **3,454 tests / 359 suites**,
with zero failures/errors/skips, and assembled debug in **48 seconds**.

This reduces animation scheduling ownership. It does not remove placement or
accessibility geometry work, and no physical Portal FPS improvement is claimed.

The native debug-only fixture invokes the **actual shipped Compose fallback**,
using generated Home/Apps/Tools pages. **165 fixture checks and five accessibility
checks passed in 25.717 seconds** on the dedicated API 34 AVD. Three interrupted
reversals preserve both displayed offsets; sixteen observed animation frames
cause **zero generated-page recompositions**. Other checks cover direction,
settlement, normalized resize, exactly one creation/disposal of each retained
page, selected-page lifecycle/input, cancellation of a held touch across
hide/show, fresh-touch recovery and terminal cleanup.

Five PNG/XML checkpoint pairs verify that only the active page exports its
accessible labels, including no page labels while content is inactive. Native
layout observations are not independently presented-pixel measurements. The
fixture controls animation time and uses a synthetic parent lifecycle; it does
not establish real Activity background/resume behavior or household-content
performance. Its cache was removed, its dedicated AVD stopped, and the foreign
AVD remained untouched.

Evidence lives in
`mocks/portal-premium-player-20261030/evidence/launcher-tabs-native/`. The fixture
build passed in **11 seconds** after the full JVM gate; production hashes match
that gate. Native tested/installed APK SHA-256 is
`3684b50a648f45936d3da488a46969573c4cee55a1ed14e1c210d719180f8a56`
(**48,965,062 bytes**).

Family Area's installed APK was independently read back and matches those exact
tested bytes. Its signing certificate matches the preceding Filmstrip build,
retained privately as a rollback. One upload/install was used; installed-byte
readback resolved the interrupted install HTTP response without repeating
installation. The bounded **Home → Apps → Home → screensaver** route smoke was
acknowledged, and all **97 setting values** match both pre-install and
post-navigation profiles. Those acknowledgments do not verify visible
transitions or physical input-to-display latency.

The collector was inactive with zero listeners before installation and remains
inactive afterward. No timing capture was started. Verification/deployment
completed by **19:58 UTC**, within the thirty-minute issue window. Living Area
remains unchanged. Actual Portal frame improvements, physical night-clock
acceptance and the broader performance goal remain open. Music backend wiring
stays deferred, and no Home Assistant operation belongs to this batch.

## Shared photo-motion option — 20:00 UTC batch

The existing `photo_motion` registry control previously affected Smart frame and
Portrait pair. It now also appears for Shuffle and the nine legacy styles with
continuous photo movement: Ken Burns, Diptych, Framed Print, Cinematic Push,
Memories, Panorama Drift, Mosaic Wall, Cinema Letterbox and Hero + Sidebar.
Already still or transition-only styles hide the redundant control. Filmstrip
track navigation remains intact.

Turning motion off keeps the admitted photos still between changes. It preserves
photo selection, cadence, incoming fades, next/previous, retained pause/resume and
cleanup. Panoramic crops use their middle or cached face anchor; Mosaic also
stops spotlight scaling. No face detection, additional decode or new persisted
field was introduced. Device and remote visibility use the same registry spec.
The existing preference is frozen once at engine startup and passed through the
bridge and scheduler, with no per-frame preferences access. Missing host support
defaults to the preceding enabled behavior.

| Gate | Evidence |
| --- | --- |
| Fresh Android JVM/build | **3,454 tests / 359 suites**, zero failures/errors/skips; **39 seconds** |
| Slideshow source regressions | **277 passed**, including **56 new motion regressions** and six new bridge cases |
| Actual engine fixtures | **14/14 passed** |
| Existing Chrome renderer gate | **255 assertions passed**, zero script errors; **167.442 seconds** |
| Still/default Chrome renderer | **337 assertions passed**, zero script errors; **155.933 seconds** |
| Geometry and imagery | Nine styles, decoded extreme-aspect and cached-focal checks; four still-mode **2160 × 1440** PNGs |
| Default compatibility | Frozen pre-change scene-output hashes and random-selection counts match; explicit true matches absent preference |
| Packaged source | All eight slideshow assets match the built APK and browser source hashes |

The still-mode checker samples after two animation frames because CSS pause
commits at the next frame boundary. Its retained-pause checks then require stable
transforms. A separate diagnostic also records a valid Hero + Sidebar case where
navigation advances the scene/cursor but revisits the same three eligible photos.
Sorted URL sets therefore do not define navigation identity. Both initial
checker failures and the corrected final run are retained in
`slideshow/evidence/renderer-calm-motion/`; no production patch was made to
accommodate either observation.

The existing renderer run leaves eleven prior PNG files byte-identical; Cinema
captures a different valid synthetic fixture in its scope. This is not an
all-captures pixel-identity claim. New still-mode captures were visually reviewed
for the Mosaic composition and anchored Panorama window.

The functional preview adds a **Photo motion: Gentle / Still** control at
`http://127.0.0.1:8891/?mode=panorama&motion=still&review=1`.
Its bridge and artwork are local fixtures; it changes no Portal preference.
Browser results do not establish Portal GPU memory, FPS, startup latency or
physical night-clock acceptance. Those broader acceptance items remain open.
Music backend wiring stays deferred, and no Home Assistant operation belongs to
this batch.

Family Area's installed APK was independently read back and matches tested
SHA-256 `4c00e39eae3b84373d55d7d9e79d0dfe849991258776786c92f5cf072eb8ce99`
(**48,965,301 bytes**). Its certificate matches the previous installed build,
retained privately as a rollback. One upload/install was used; installed-byte
readback resolved the interrupted HTTP install response. All **97 settings**
match before installation, after installation and after the bounded
**Home → Apps → Home → screensaver** navigation smoke. The saved Shuffle mode
and enabled motion value remain intact. Navigation acknowledgments do not prove
visible transitions. The collector remains inactive with **zero listeners**.
Verification/deployment completed by **20:18 UTC**, within the thirty-minute
issue window. Living Area remains unchanged; the broader goal stays active.

## Retained widget-page work — 20:19 UTC batch

Home retains neighboring widget pages to prepare smooth swipes. Previously only
their touch permission was scoped to the selected page: built-in polling
producers still inherited the active Home tab, and photo widgets inherited its
RESUMED lifecycle. Hidden neighbors could therefore refresh feeds, tick timers
or run the photo worker.

Both touch and TV paging now use `ApprovedWidgetPageScope`. It reuses the
existing tab lifecycle owner logic for each retained child, scopes the active
flag and preserves the enclosing tab's input-gate identity. Hidden children stay
CREATED, while a selected child is capped by its parent lifecycle. Remembered
values and owner identities survive hide/reveal within the retained subtree.
The existing bounded neighbor-retention window is unchanged.

| Native generated-subtree observation | Old wiring replay | Production scope |
| --- | ---: | ---: |
| Active polling bodies, three retained pages | **3** | **1** |
| RESUMED photo-like lifecycle workers | **3** | **1** |
| Paused parent, polling/lifecycle workers | — | **0 / 0** |
| Concurrent generated requests per page | — | **≤1** |

These numbers come from actual Compose `produceHomeState`, the production scope
and `OnResume`, with generated delayed IO and a generated RESUMED worker. The
baseline is a replay of the old provider wiring in the tested APK, rather than a
physical comparison of two Portal builds. It trips the intended maximum-one
producer assertion. The corrected scope passes **67 native checks and four host
checks**, in **5.467 seconds**; cleanup finishes at **6.473 seconds**.

Checks include page 0/1/2 and reverse selection, retained cache/deadline reuse,
hidden key changes, cancellation-resistant IO without duplicate admission,
selected-only resume hooks, parent STARTED/CREATED/RESUMED caps, shared input
gate preservation and complete disposal. An in-flight blocking request may need
to finish after hiding; this is not an instant-cancellation claim.

The full Android gate passes **3,454 tests / 359 suites**, zero failures/errors/
skips, with a successful build in **54 seconds**. All five production source
hashes match the native-tested APK. Evidence and five generated PNGs live in
`mocks/portal-premium-player-20261030/evidence/widget-pages-native/`. The fixture
is registered only in the debug source set; no release APK was inspected.
Its cache was removed, the owned API 34 AVD stopped and the foreign AVD untouched.
There are no household source reads or physical Portal FPS measurements.
Third-party AppWidget provider background updates are outside this verification.
The preceding slideshow/browser and player checks cover unchanged sources and
were not repeated.

Family Area's installed APK was independently read back and matches SHA-256
`d45b1a4f601d1f8dbbb9981e494723a84590fbca625297688abff83a2de8a601`
(**48,994,322 bytes**), matching the native-tested APK and existing certificate.
The preceding installed APK is retained privately as a rollback. One upload/
install was used; independent byte verification resolved the interrupted HTTP
install response. All **97 setting values** match before/after installation and
after Home → Apps → Home → screensaver acknowledgments. Those acknowledgments
do not verify presented pixels or input latency. The collector is inactive with
**zero listeners**. Verification/deployment completed by **20:33 UTC**, within
the thirty-minute issue window. Living Area remains unchanged.

This reduces unnecessary retained-widget work without changing the page layout
or swipe transition. The Home/Apps frame-time outlier, physical night acceptance
and overall performance goal remain open; the earlier capture-admission issue
stays parked. Music backend wiring remains deferred.

## Optimized Family build and real Home timings — 20:34 UTC batch

The recent Family deployments used unoptimized debug APKs. The earlier accepted
Settings improvement used native R8 optimization. This batch adds a separate
`performance` build using those existing rules, release defaults, the same
`.debug` package and signing key, and no debug-only fixtures. Native Filmstrip
stays enabled; native Home and all UI diagnostic feature gates stay disabled.
No Kotlin UI, asset, animation duration, preference or OS configuration changed.

The first navigation preflight stopped before starting any collector because an
initial same-package accessibility observation did not retain its Window ID
through warmup. It admitted **zero captures**. A settled Home Window was then
observed three times before the first control. The subsequent driver establishes
its Window owner after warmup, allowing the exiting photo Activity's initial
same-package observation to retire. Twelve virtual safety cases cover ownership,
unapplied/lost/pending starts, lost/unapplied stops, expiry, configuration drift,
warmup handover and a Window change during capture. There is no blind start retry.

Three unsampled physical blocks completed **30 Home → Apps → Home cycles each**,
at a four-second minimum cadence. Each owned collector was stopped once and
independently confirmed inactive with zero listeners. Full metrics were read
every five cycles, with light ownership checks before commands. Only Home's
Window produced nonempty frame samples; all first-draw, dropped-callback, invalid
and overflow counts were zero.

| Home Window callback metric | Debug control before | Optimized build | Debug control after |
| --- | ---: | ---: | ---: |
| Frames | 778 | 781 | 777 |
| Mean | 16.982ms | **13.368ms** | 17.486ms |
| p95 | 60.00ms | **37.00ms** | 62.75ms |
| p99 | 83.50ms | **53.75ms** | 85.25ms |
| Maximum | 103.102ms | **66.979ms** | 117.294ms |
| Above 16.667ms proxy budget | 19.15% | **17.03%** | 19.56% |

Against the two controls, optimized p95 improves **38.33–41.04%**, p99
**35.63–36.95%**, mean **21.28–23.55%**, and maximum **35.04–42.90%**.
All nine gates frozen before candidate timing pass: both tails at least 20%
better than each control, mean no more than 5% worse, control-tail drift within
15%, and no candidate warmed frame over 250ms. Control p95/p99 drift is
4.58%/2.10%. This accepts the focused build improvement; **37ms p95 still fails
the broader 16.667ms target**, and these callbacks do not measure delivered FPS.

This is a small warmed-process cohort with uncontrolled JIT, thermal and
background state. R8, non-debuggable mode and debug-fixture exclusion change
together, so the result does not isolate R8 alone. Optimized/control-after
preflight adds three stable Window observations before collection; measured
cadence is unchanged. No screenshots or hierarchy dumps were taken during
timing. Window ownership and command receipts do not establish visible tab
readiness, physical input latency or complete optimized-APK UI compatibility.

The integrated debug/unit/performance command succeeds in **1m 45s**, including
R8 and vital lint. The unchanged JVM task is **UP-TO-DATE**, retaining
**3,454 tests / 359 suites** with zero failures/errors/skips; it was not rerun.
Manifest/configuration checks confirm the same package/certificate, a
non-debuggable artifact, disabled diagnostic/native Home flags and no debug
fixture Activities. All **53 non-profile, non-fixture assets** match the control
and main sources. Ten exact omitted artwork/video paths belong to the debug
source set; the initial verifier's blanket asset comparison was corrected to
check that expected exclusion explicitly. A separate source review found no
new R8 reflection/JavaScript keep-rule blocker; runtime coverage remains bounded.
The actual optimized DEX retains **13 public runtime-annotated JavaScript
bridge methods**, including `getPhotoMotion`. This verifies their compiled
visibility/annotation boundary, not execution of every bridge method.

The optimized APK is **25,702,091 bytes**, versus **48,994,322 bytes** for debug.
This is an APK-size result, not a measured RAM saving. Family's final installed
bytes were independently verified as
`34889ea71b3b2a4d9c2beb7da9a454389ff2254015627857d50f14a76d289052`.
Each deliberate comparison installation used one upload/install; response loss
was reconciled by byte readback without repeating installation. The exact debug
control remains privately available for rollback.

All **97 settings**, screensaver configuration and dormant photo-source groups
match across all blocks and the final optimized promotion. Final Home/Apps/Home/
screensaver requests are acknowledged, and the collector is stopped with zero
listeners. Deployment and evidence were verified by **20:59 UTC**, within the
thirty-minute issue window. Scalar-only evidence is in
`mocks/portal-premium-player-20261030/evidence/family-optimized-home/measurement.json`;
private helpers, configurations and rollback APKs stay outside Git.

Use `:app:assemblePerformance` for the optimized Family iteration APK, while
debug-only native fixtures continue using `:app:assembleDebug`. Do not overwrite
the measured optimized installation with a fixture APK as a speed improvement.
Living Area remains unchanged. Physical night-clock acceptance, the remaining
Home frame tails and the broader goal stay open. Music backend wiring remains
deferred; no Home Assistant operation belongs to this batch.

## Native Filmstrip steady-motion observation — 21:02 UTC batch

The older Filmstrip outlier reports concern different artifacts and rendering
paths. This batch measures the currently enabled native renderer on Family's
optimized APK before considering another visual or rendering change. The
installed APK was independently read back after observation and still matches
`34889ea71b3b2a4d9c2beb7da9a454389ff2254015627857d50f14a76d289052`
(**25,702,091 bytes**). No production code changed and no installation occurred.

The settings registry temporarily selected Filmstrip with a ten-second interval.
The native deck was ready before collection, with a further three-second settle.
One unsampled collector then observed ninety seconds and **nine automatic
advances**, including the return to the first card. Native readiness remained
active/unpaused with **nine cards**, **2160 × 1440** viewport and stable retained
pixels of **33,285,312 bytes (31.74 MiB)**. Those state counters do not prove that
each photo was presented on the panel.

| PhotoFramePreviewActivity Window metric | Observation | Existing steady-frame gate |
| --- | ---: | ---: |
| Frames | **837** | Nonempty native host |
| Mean | **9.049ms** | Informational |
| p95 | **12.50ms** | ≤16.667ms — pass |
| p99 | **14.75ms** | ≤33.333ms — pass |
| Above 16.667ms | **2 / 837 (0.239%)** | ≤1% — pass |
| Above 33.333ms | **0** | Informational |
| Maximum | **19.747ms** | <100ms — pass |

The retained Home Window contributed zero frames; hosts were not pooled.
First-draw, dropped-callback, invalid and overflow counts are all zero. No
collector stack sampling, screenshots, hierarchy dumps or full metrics reads
ran during the timed interval; checkpoints used light ownership/readiness reads.
Seven virtual driver cases cover normal completion, lost start/stop responses,
an unapplied start, a pre-existing collector, foreign ownership and settings
drift. There is no blind capture retry.

The owned collector received one stop and was independently confirmed inactive
with **zero listeners**. The original mode and interval were restored through
the registry. All **97 values**, complete screensaver configuration and
photo-source groups compare unchanged; source groups received no writes.
Independent installed-byte verification followed collection, so APK transfer
did not affect the timed interval. Scalar-only evidence is in
`mocks/portal-premium-player-20261030/evidence/family-native-filmstrip/measurement.json`;
the capture driver and detailed reports remain private.

This is a pass for this warmed native preview cohort, not a measured
before/after Filmstrip gain, delivered FPS or universal slideshow acceptance.
Cold startup, system DreamService timing, retained-app recovery, visible
composition, longer runs and larger libraries are outside the observation.
Physical night-clock acceptance and the Home/Apps tails remain open.

A parallel source review identified a possible redundant outgoing backdrop
after an opaque dissolve. Transparent backdrops still need their underlying
contributor; hiding it unconditionally would change pixels. The cost and benefit
were not measured, and the current warmed path passes its gates, so that
candidate was not implemented. Previously rejected offscreen-card/layer
experiments were not repeated. Existing blur, shadows, geometry and motion
remain intact. Living Area is unchanged; music backend work remains deferred.

## Home settings-snapshot trial — withdrawn, 21:10 UTC batch

Home cards reload the large `ScreensaverConfig.Settings` snapshot when their
retained page resumes. One bounded candidate read current preferences on every
call, decoded changed scalar maps through a frozen read-only view and reused
the preceding immutable Settings object when the source identity and values
matched. It retained one bounded entry with a weak preference reference. No UI,
animation, layout, dependency, setting definition or persisted value changed.

The candidate passed **13 new JVM checks**: ten cache cases and three checks
against the actual screensaver decoder. They cover typed/default/coerced values,
unchanged object reuse, edits/deletion/restoration, atomic generated connection
fields, mutation during decode, single-entry/source identity, bounded/bypass
maps, failed publication and concurrent reads. A generated 64-scalar test
decodes once across 21 loads and verifies one source `getAll` per call. This is
an operation-count fixture, not a physical Home workload or latency result.
The complete candidate gate passes **3,467 tests / 361 suites**, with zero
failures/errors/skips. Debug/performance build succeeds in **1m 48s**; the final
three decoder tests were then included in a fresh full JVM invocation.

Three physical blocks used the same unsampled thirty-cycle Home/Apps/Home
driver and four-second minimum cadence. The policy was frozen before timing:
p95 and p99 at least 20% better than each control, mean no more than 5% worse,
control tails within 15%, and no warmed frame above 100ms.

| Home Window metric | Optimized control before | Snapshot candidate | Exact control restored |
| --- | ---: | ---: | ---: |
| Frames | 780 | 782 | 782 |
| Mean | 12.630ms | 13.273ms | 12.986ms |
| p95 | 33.25ms | 34.75ms | 34.25ms |
| p99 | 46.50ms | 48.00ms | 47.25ms |
| Maximum | 58.841ms | 57.684ms | 63.421ms |

The candidate's p95 is **1.46–4.51% worse**, p99 **1.59–3.23% worse** and mean
**2.21–5.09% worse** than the surrounding controls. Both 20% tail gates fail;
the first mean gate also fails. Control p95/p99 drift is **3.01% / 1.61%**.
All first-draw, callback-drop, invalid and overflow counts are zero; only the
Home Window contributes nonempty samples. This small warmed cohort does not
causally identify the cost of preference reading, decoding or allocation.
It establishes that this implementation did not earn retention.

The cache integration and all three new source/test files were withdrawn.
Their prototype, APK and reports remain private to avoid repeating the same
trial. A fresh restored gate passes **3,454 tests / 359 suites**, zero
failures/errors/skips, with debug/performance build success in **1m 21s**.
The rebuilt optimized APK is byte-identical to the original control, including
DEX; the prototype marker is absent. Family's independently verified restored
APK remains
`34889ea71b3b2a4d9c2beb7da9a454389ff2254015627857d50f14a76d289052`.
Candidate APK
`ea514e90a3bf1e21b03bf79676a9634a3a28c7b27ca7100176d871831898593d`
has the same certificate, identical manifest and all 53 non-profile assets.
Install response loss was resolved by byte readback without repeating installs.

Each block closes its owned collector with zero listeners, acknowledges the
known Home/screensaver return and preserves all **97 settings**, full
screensaver configuration and source groups. Visible content readiness,
physical input latency and delivered FPS remain unverified. Living Area
received no operations. Scalar evidence is in
`mocks/portal-premium-player-20261030/evidence/family-settings-snapshot-trial/measurement.json`.

No new speed improvement is retained from this batch. Home/Apps still misses
the broader steady-frame target; further Home cache trials are parked.
The original optimized build and preceding accepted improvements remain
installed. Slideshow startup/recovery and physical night acceptance remain open;
music backend wiring stays deferred.
Final installed-byte, source and idle-collector verification completed by
**21:32 UTC**, within **22 minutes** of this issue's start.

## Retained continuation tap dismissal — 21:35 UTC batch

The source review found a recovery defect rather than a supported startup speed
fix. A continuation `PhotoFramePreviewActivity` can survive underneath a later
Immortal Dream. The ordinary unpinned tap previously finished only the Dream;
the default configured dismiss target launches no Activity. The still-owned
continuation could then become visible and resume instead of returning to the
underlying task.

The unpinned branch now retires that continuation before launching the configured
target and finishing the Dream. Retirement requires the current Dream identity
and a preview whose **current intent-derived** state identifies a continuation.
It clears preview ownership and marks intentional exit before `finish()` can
deliver lifecycle callbacks. Deliberate photo viewers and scheduled night-clock
previews remain retained. Both admitted and failed pinned-return branches retain
their existing behavior; this operation neither releases a pin nor relaunches
the pinned app.

The behavioral regression first failed with the old exit ordering:
`[launch, dream]` instead of `[preview, launch, dream]`. After the missing
retirement invocation was added, all **six new checks** pass. They exercise
retirement before configured launch and Dream finish, no recovery from a
reentrant owned stop, repeated retirement, deliberate-viewer retention, stale
Dream identity, late old-preview detach and pinned-return success/failure.
The full fresh JVM gate passes **3,460 tests / 360 suites**, with zero failures,
errors or skips. Debug and optimized performance builds succeed in **1m 28s**.
These tests use Android boundary doubles; they do not execute actual Activity
intent/lifecycle transitions or an OS Dream window.

Family Area's installed optimized APK was independently read back and matches
SHA-256
`ed60d6d371b1a509d7b1f8ba9b08e8328c47afb55ebbe94dc6865bd449db6b70`
(**25,702,091 bytes**), with the existing certificate. The manifest and all
**53 main non-profile assets** match the preceding accepted optimized build.
The exact preceding `34889ea…` APK is retained privately for rollback.
One upload/install was used; byte readback resolved the interrupted install
response without a repeated install.

All **97 values**, complete screensaver configuration and source groups compare
unchanged after installation and Home → Apps → Home → screensaver acknowledgments.
No new rendering work, worker, setting, scene geometry or animation was added.
The bounded post-install log observation contains **401 lines**, **zero fatal
exception markers** and **zero continuation-retirement messages**. Thus it does
**not** prove that the new stacked-Dream path ran on the device. Wi-Fi ADB refused
the connection, and no Family USB transport was present; the foreign emulator
was untouched. Actual stacked OS-Dream tap dismissal, visible transitions,
delivered FPS and a speed improvement remain unverified.

The collector is inactive with **zero listeners**. Scalar evidence is in
`mocks/portal-premium-player-20261030/evidence/family-dream-tap-continuation/verification.json`.
Build, deployment and runtime verification finished by **21:43 UTC**, before the
existing **22:02 UTC** issue deadline.
No Living Area or phone operations occurred. Startup timing, the Home/Apps
frame-time target, physical night-clock acceptance and the overall goal remain
open; music backend wiring stays deferred.

## Startup observation and recoverable orientation — 21:43 UTC batch

Three new preview hosts were observed on Family's `ed60d6d…` optimized build,
with the unchanged Shuffle setting and photo sources. One capture was started
and stopped. A phase-label mismatch in the private probe initially prevented
reconciliation; the authoritative state confirmed its original session was
still live. The corrected probe reused that **same session**, with **zero
additional starts**, then independently confirmed an inactive collector and
zero listeners. All **97 values**, full screensaver configuration and source
groups compare unchanged.

| Selected first style | Pre-command observation → Main scene admission | Metadata preparation | WebView admission → page-finished event |
| --- | ---: | ---: | ---: |
| Cinema | **1,338ms** | **4.591ms**, off Main | **969ms** |
| Ken Burns | **723ms** | **0.948ms**, off Main | **410ms** |
| Panorama | **663ms** | **2.870ms**, off Main | **305ms** |

The first timing includes Fleet reads, navigation, host construction and page/
image work. It is **not** ready-host-to-visible-photo latency, a pristine cold
start or a controlled style comparison. The three admission records and their
layer observations have zero invalid/discarded records. Metadata is not the
large observed interval in this cohort; the page-finished interval contains
multiple kinds of work and does not isolate a WebView constructor, network,
decode or GPU cost. No speculative startup speed edit was retained.

The parallel JavaScript review found a separate concrete startup-metadata
defect: a failed orientation probe wrote **1600 × 1067** and `measured=true`.
Normal display decoding then refused to replace those fallback dimensions,
keeping a real portrait classified as landscape. The one-condition repair
commits probe metadata only after a successful load. Failed probes still resolve
and release their handlers/source, use the existing safe landscape fallback and
leave dimensions unknown. The normal successful display decode can then supply
the actual dimensions and correct Shuffle's orientation bucket. No retry worker,
extra image load, source/deck change or face-hint change was added.

Both new source regressions first failed against the old condition while the
nine existing lifecycle checks passed. The corrected worker scope passes
**11 tests**, with **268 other source tests** run separately: **279 source tests**
total, plus **14 engine fixtures**. A real Chrome check against the preceding
asset also failed on the erroneous committed dimensions. The repaired scripts
pass **13 targeted browser checks** using an actual HTTP 404 followed by a real
480 × 720 SVG decode. They verify safe fallback, portrait recovery, Shuffle
classification, retained face hints, source/deck identity and released handlers.
The broader production-script browser run passes **255 checks**, zero page errors,
with original/generated local fixtures. Household media was not used in either
browser harness. These are source/browser checks, not physical Portal framing,
FPS or speed comparisons.

Debug and optimized performance APKs build successfully in **29 seconds**. The
JVM command reuses its valid **3,460 tests / 360 suites** result, zero failures/
errors/skips; it is **not a fresh JVM execution**. Family's independently read
installed APK matches SHA-256
`5a3f060b8480463351d0c05e10ab7d05cb34d5c5060895d06d459b2b816ee7b4`
(**25,702,091 bytes**) and the existing certificate. Native DEX and manifest
match the preceding `ed60d6d…` build exactly; **52 main assets** match, and only
`assets/screensaver/photos.js` differs. The preceding exact APK is retained
privately. One upload/install was used; installed-byte readback resolved the
interrupted response without repeating the install.

All **97 settings**, full screensaver configuration and photo-source groups are
preserved after installation and Home → Apps → Home → screensaver acknowledgments.
The bounded post-install log sample contains **402 lines** and **zero fatal
exception markers**. It contains **zero ImmortalEngine lines**, so it cannot
certify JavaScript execution or the failed-probe path on the Portal. The collector
is inactive with **zero listeners**. Scalar evidence is in
`mocks/portal-premium-player-20261030/evidence/family-startup-orientation-recovery/verification.json`;
the reproducible real-image browser check is
`mocks/portal-premium-player-20261030/slideshow/orientation-recovery-browser-checks.cjs`.

This batch delivers orientation-error recovery, **not a measured startup speed
gain**. Startup presentation remains open; this investigation is closed rather
than extending into repeated cache experiments. Physical stacked-Dream tap and
night-clock acceptance, Home/Apps frame tails and the broader goal remain open.
Living Area and the phone received no operations; music backend wiring remains
deferred. Build, deployment and evidence verification finished by **22:00 UTC**,
before the **22:13 UTC** issue deadline.

## Native music selection clarity — 22:01 UTC batch

The native player, Library, Playlists, collection details and search were already
implemented. This audit found a concrete mismatch with the intended segmented
control: the unselected Players tab inherited a standalone button background,
so it looked highlighted alongside the selected Queue tab.

Music tabs now share their container's background and only the current tab has
a selected fill. They expose native tab/group semantics instead of generic
button roles. Existing availability guards, 48dp touch targets, current track,
player ownership and layout are retained. No runtime timer, artwork load,
animation, provider connection or Home Assistant service was added.

The new pixel verifier rejects the defect in both **previous actual native**
dark/light captures at the preceding commit; these are not fresh baseline runs.
It samples label-free interiors against actual container gaps, without assuming
theme colours. A selected native tab can be reported as non-clickable by Android
accessibility, so checks resolve its owner rather than treating clickability as
selection.

- A fresh **3,460 JVM tests / 360 suites** pass with zero failures, errors or
  skips. Debug and optimized performance APKs build in **1m 32s**.
- **106 native acceptance checks** pass in **159.88 seconds** on the dedicated
  API 34 AVD at 2160 × 1440 / 240dpi. They include exactly one selected tab,
  unchanged enabled states, dark/light Players → Queue continuity, offline
  guards, retained queue selection, library, playlists, search, larger text and
  existing native clock/Gallery controls. This is silent fixture evidence,
  not Portal API 29 performance or audible playback.
- All **42 native review capture tabs** load in actual Chrome with zero page
  errors. The review includes dark/light Queue return and light Players captures.
- Family's installed optimized APK is independently read back and matches
  `1c8bfc936274fea69258d883cb8f0a29c2d2f8dbc576b896b05957596563994e`
  (**25,702,091 bytes**) with the existing certificate. All **53 main assets**
  and the manifest are unchanged; native DEX changes contain the UI correction.
  The preceding exact `5a3f060…` APK is retained privately as rollback.
- All **97 settings**, full screensaver configuration and photo-source groups
  are unchanged after install and navigation acknowledgments. A bounded
  **402-line** runtime sample contains zero fatal exception markers. The
  performance collector is inactive with zero listeners.

Scalar evidence is in
`mocks/portal-premium-player-20261030/evidence/family-music-tabs/verification.json`.
Build, native/browser checks and Family deployment are verified by **22:10 UTC**,
before the original audit's **22:29 UTC** deadline. The owned test AVD is shut
down after its checks; Living Area, the phone and Home Assistant receive no
operations.

Actual Portal music pixels/interaction, Home/Apps frame tails, physical night
transitions, stacked-Dream tap and slideshow startup presentation remain open.
The overall goal remains active; this batch does not claim an FPS improvement.

## Home producer lifecycle candidate withdrawn — 22:11 UTC batch

A generated native probe isolated visibility-only producer recompositions. The
original implementation passed its 67 previous checks but performed four extra
recompositions on unchanged-key hide/reveal. Gating producer work through the
existing stable page lifecycle instead of reading the active composition local
reduced that count to zero; 68 native and five host checks passed. Cached values,
hidden-worker suspension, cadence and serialized request ownership stayed intact.
These were synthetic API 34 fixture checks, not physical Portal timing.

The acceptance gates were frozen before the physical comparison: at least a 10%
p95 reduction against both controls, no more than 5% p99/mean regression against
either control, at most 15% control p95/p99 drift and a warmed maximum below 250ms.
Family ran 30 Home/Apps cycles in each block, unsampled, with a four-second minimum
cadence. Only the known HomeActivity window is compared; first-draw, dropped,
invalid and overflow counts are zero. These are Window FrameMetrics timings,
not delivered FPS or visible navigation latency.

| Build | Frames | Mean | p95 | p99 | Maximum |
| --- | ---: | ---: | ---: | ---: | ---: |
| Exact control before | **782** | **12.609ms** | **30.25ms** | **38.50ms** | **56.051ms** |
| Lifecycle candidate | **780** | **12.421ms** | **32.50ms** | **43.50ms** | **52.575ms** |
| Exact control restored | **781** | **12.981ms** | **33.00ms** | **43.25ms** | **52.595ms** |

The candidate fails the frozen acceptance gates and is **withdrawn**. Its source,
debug probe and assertions are removed; the previous accepted source is restored.
Family's independently read installed APK and the restored local optimized build
both match `1c8bfc936274fea69258d883cb8f0a29c2d2f8dbc576b896b05957596563994e`. All **97 settings**, full
screensaver configuration and source groups remain unchanged in all three blocks.
A fresh **3,460 JVM tests / 360 suites** pass with zero failures/errors/skips;
debug and performance APKs build in **1m 14s**. No speed improvement is claimed
from this batch. The probe/recomposition success is retained as private experiment
evidence, not as a delivered feature.

The collector is inactive with **zero listeners**, and the owned API 34 AVD is
stopped. Scalar evidence is in
`mocks/portal-premium-player-20261030/evidence/family-home-producer-lifecycle/measurement.json`.
The batch closes at **22:34 UTC**, before its original **22:41:47 UTC**
deadline. Living Area, the phone and Home Assistant receive no operations.
Home frame tails, slideshow startup presentation, physical night/wake acceptance,
stacked-Dream tap and actual Portal music interaction remain open; the overall
goal remains active. No further candidate is started in this issue window.

## Music layout continuity and preview metadata — 22:35 UTC batch

Physical music UI verification was attempted first. Family responds through Fleet
on API 29, but USB debugging is absent and the current optimized build does not
expose music pixels or a control hierarchy through its Wi-Fi API. That gate is
parked rather than reported as passed; no replacement capture subsystem is built.

The native/source review found a production presentation defect: Players selection
was stored inside the full-size pane. Compact and photo layouts removed that pane,
so returning to the full player silently reset it to Queue. The existing state is
now owned by the stable player container and read only inside the side panel.
Layout changes retain selection without adding a preference, timer, animation,
artwork request or controller. Closing the player still ends its presentation state.

A parallel, debug-only correction removes the hardcoded first-album label from the
silent preview snapshot. Each selected track now uses the title of its existing
sample album, keeping now-playing and library metadata aligned. The sample albums
were already coherent; their IDs, tracks and mixed playlists are unchanged.
Production provider metadata, backend wiring and audio commands are untouched.

- The original APK fails **six of 18 new native checks**: compact/full and photo/full
  selection retention plus album identity, in both palettes. The repaired APK passes
  **18/18 in 42.708s**, retaining current track, paused transport and Queue
  availability. Existing native acceptance also passes **106/106**: **124 checks**
  total on the owned API 34 AVD. This is generated silent fixture evidence.
- The first broad-suite invocation used system Python without Pillow and failed at
  import before running assertions. The terminal process was reconciled; the bundled
  Python invocation then ran the full 106 checks successfully. No app change was
  made in response to that runner error.
- A fresh **3,460 JVM tests / 360 suites** pass, zero failures/errors/skips.
  Debug and optimized APKs build in **1m 24s**. The AVD's installed debug APK matches
  `0eec46aea13cc3280da018801ec04da7d5057d5e2b2837d80b7d2604f29eced1` exactly and both tested source hashes are recorded.
- The review adds six continuity/album captures. Actual Chrome loads and selects all
  **48 native capture tabs**, with **zero page and console errors**. Generated images
  remain 2160 × 1440; no household media is included.
- Family's independently read installed optimized APK matches
  `7871c213a34c79cb34bb3c87241d4f5a343b821df5fb6ce35a36d1a03a2f4fc9`
  (**25,702,091 bytes**), with the existing certificate.
  All **53 main assets** and the manifest are unchanged. Native DEX contains the
  tab-retention change; the sample-album fix remains in debug source only.
- All **97 settings**, full screensaver configuration and photo-source groups are
  preserved. The bounded **402-line** post-install log sample has **zero fatal
  exception markers**. The collector is inactive with **zero listeners** and the
  owned test AVD is stopped. Living Area, the phone and Home Assistant are untouched.

Evidence:
`mocks/portal-premium-player-20261030/evidence/family-music-continuity/verification.json`;
reproducible focused checks: `music-continuity-native-checks.py`; review:
`http://127.0.0.1:8890/native-review.html`. Verification closes at
**22:52 UTC**, before the original **23:05:33 UTC** deadline.
This batch claims two UI/preview corrections, **not a physical FPS improvement**.
Actual Portal music interaction, Home frame tails, slideshow startup presentation,
physical night/wake acceptance and stacked-Dream tap remain open. The overall
objective remains active and backend wiring stays deferred.

## Legacy slideshow face warm-up admission — 22:54 UTC batch

Original issue window: **22:54:35–23:24:35 UTC**, including build, deployment,
documentation and commit.

The legacy WebView controller previously started uncached face analysis immediately
after loading its page, while first-scene images and orientation were still being
prepared. Cached focal positions and people weights already exist in the prepared
photo metadata; the first scene does not require that new analysis.

- New analysis now waits for the existing scheduler callback after its decoded
  scene is promoted. This is a **decoded-scene promotion**, not a measurement of
  the first physical pixels.
- `LegacySlideshowWarmupGate` admits work once for the current controller, source
  revision and WebView. Duplicate bridge reports share one Main delivery. A hidden
  or paused presentation retains readiness for resume; retirement and stale-source
  callbacks cannot start analysis.
- Failed transport delivery releases ownership for a later legitimate signal.
  Callback failures fail closed; warming failure is not retried by this gate.
- The production trigger works independently of optional performance diagnostics.
  No polling timer, extra image load, setting, JS asset or standalone native
  Filmstrip behavior was added or changed. Existing cached face hints remain in
  the first-scene metadata.

Verification:

- The focused gate stub compiled but failed **18 of 22** behavioral tests before
  implementation. The completed gate passes **22/22**, covering admission,
  duplicate/concurrent reports, pause/resume, stale owners, shutdown and failures.
  These are new gate JVM tests; they do not execute the original controller on a
  physical Portal.
- Fresh full JVM verification passes **3,482 tests across 361 suites**, with zero
  failures, errors or skips. Debug and optimized performance APK builds succeed.
  All **12 legacy JS mode identifiers** are accepted by the existing Kotlin
  whitelist of **14 native/legacy style identifiers**.
- Family Area's independently read installed APK matches
  `36f1a0d448705937bd61e0ac6d130a2fc6ce9407825ab55bd4f17c3d2ca0e0b7`
  (**25,702,091 bytes**), with the existing signing certificate. All **53 main
  assets** and the manifest remain unchanged; the native DEX changes.
- All **97 settings**, full screensaver configuration and photo-source groups
  are preserved. The bounded post-install log response contains **418 lines** and
  **zero fatal exception markers**. The collector is inactive with **zero
  listeners**. Living Area, the phone and Home Assistant are untouched.

Evidence:
`mocks/portal-premium-player-20261030/evidence/family-slideshow-warmup/verification.json`.
This batch removes eager analysis admission from legacy startup. It makes **no
claim of measured startup latency or FPS gain**; physical execution of this
legacy gate, first-photo timing, Home frame tails, night/wake acceptance and
stacked-Dream tap remain open. The overall objective stays active.

## Retained face-analysis suspension — 23:07 UTC batch

Original issue window: **23:07:34–23:37:34 UTC**, including verification,
deployment, documentation and commit.

Entering the night clock already suspended the photo renderers, but did not
suspend an admitted `FaceFocusStore` warm-up. The same gap existed when its
retained host was hidden or user-paused.

- The controller now passes effective host visibility and user pause to the
  store, for both legacy scenes and standalone native Filmstrip.
- `FaceFocusWork.setActive(false)` invalidates its captured request promptly.
  Network cleanup remains off the caller, and late cache results are rejected.
  The existing SDK-disposal rule remains intact: an unfinished ML Kit task must
  surrender its bitmap before replacement analysis can run.
- Resume creates a fresh request and uses the existing shared cache to skip
  completed photos. Normally completed analysis never restarts on subsequent
  hide/wake cycles. Each finalizer checks its captured request identity so it
  cannot cancel a resumed job.
- Hidden registration does not interrupt a visible peer. A superseded retained
  host can return and resume; explicit shutdown remains terminal. The global
  single-worker/latest-pending-job limit is preserved, with no new executor or
  timer. The displayed photo, styling, brightness and settings are unchanged.

Verification:

- Original implementation plus a compiling no-op activity method: **24 tests,
  9 behavioral failures**. The original worker/store/cache tests stayed green.
- Review exposed two candidate lifecycle errors: hidden registration stole a
  visible peer's slot, and supersession permanently closed a retained host.
  Both were reproduced before deployment: **26 tests, 2 failures**.
- Corrected implementation: **26/26 focused tests pass**, including **14 new
  activity tests** and all **12 existing worker/store/cache tests**. These
  exercise real worker/request/cache behavior on the JVM, not physical Portal
  sensor or host transitions.
- Fresh full verification passes **3,496 tests in 362 suites**, with zero
  failures, errors or skips. Debug and optimized performance APK builds succeed.
- Family Area's independently read installed APK matches
  `125f6ac526e70fc4377a72c4fbd4b639aab987e6cd330d1de7c61d5c904b28d4`
  (**25,702,091 bytes**), with the existing signing certificate. All **53 main
  assets**, the manifest, **97 settings**, full screensaver configuration and
  photo-source groups remain unchanged.
- The bounded post-install log response contains **402 lines** and **zero fatal
  exception markers**. The performance collector is inactive with **zero
  listeners**. This batch's verified upload copy is cleared to zero bytes,
  reclaiming **25,702,091 device bytes**; local candidate and rollback APKs are
  retained.

Evidence:
`mocks/portal-premium-player-20261030/evidence/family-face-visibility/verification.json`.
This closes the face-worker suspension gap in code and JVM verification. It does
not establish a physical darkness/brightness transition, instant SDK cancellation
or a measured CPU/FPS gain. Those claims remain open. Living Area, the phone and
Home Assistant are untouched; the overall objective remains active.

## Native night continuity and foreground-service startup — 23:21 UTC batch

Original issue window: **23:21:18–23:51:18 UTC**, including verification,
deployment, documentation, resource cleanup and commit.

Family Area exposes a TCS34x0 on-change light sensor. Its registered settings
already enable the dark-room clock at 3 lux, warm ink and 3% window brightness.
These device capability/configuration readings do not establish a physical
light transition or comfortable panel brightness.

The dedicated API 34 emulator exposed an actual
`MissingForegroundServiceTypeException` when the Fleet Agent started. Its
manifest now declares `connectedDevice` and the normal
`FOREGROUND_SERVICE_CONNECTED_DEVICE` permission. The existing
`CHANGE_WIFI_MULTICAST_STATE` permission meets Android's prerequisite; no new
interactive permission or device OS configuration is needed. This fixes the
observed Android 14 service-startup crash, not a reproduced Android 9 crash.

Verification:

- Debug and optimized performance APK builds succeed. The relevant follow-up
  native test uses the real `PhotoFramePreviewActivity`, light observer,
  controller, clock view and Android window, not a mock controller.
- **66 assertions pass, zero fail, in 45.901 seconds** on the owned API 34
  emulator. A single dark reading produces the suspend event after **20.080
  seconds**; a single bright reading produces resume after **5.052 seconds**.
  Early observations confirm that each dwell is respected.
- Window brightness follows **system default → 0.03 → system default**.
  The same window identity is retained, the native clock exposes time,
  weekday and date, and its captured pixels contain warm ink on black.
  The returning photo region has exactly the original pixels; the scene
  presentation counter does not increase during night/resume.
- All **seven** temporarily modified settings are restored, and the owned
  emulator's light is reset before shutdown. Its forwarding port is removed
  and the owned emulator exits. The unrelated emulator is untouched.
- Family Area's independently read installed APK matches
  `b5e773b6eb4c919814993b655d9e845c64696d137c2e533a4a29d262c0403054`
  (**25,702,123 bytes**) with the same signing certificate. Native DEX and
  all **53 main assets** are byte-identical to the predecessor; only the
  foreground-service type and its normal permission change in the manifest.
- All **97 settings**, full screensaver configuration and photo-source groups
  compare unchanged after installation and navigation dispatch.
  The bounded runtime response contains **402 lines** and **zero fatal
  exception markers**. The collector is inactive with **zero listeners**.
  Clearing this batch's verified upload copy reclaims **25,702,123 bytes**;
  the local candidate and rollback remain available.

The previous batch's full JVM gate remains **3,496 tests in 362 suites**.
It was not rerun for this manifest-only production change. Public receipts are
in `mocks/portal-premium-player-20261030/evidence/family-night-readiness/verification.json`;
native screenshots and the 66-assertion receipt are in
`mocks/portal-premium-player-20261030/evidence/native-night-recovery/`.

Physical Family darkness/brightness behavior, actual backlight comfort, cold
first-photo timing, Home frame tails and physical music UI acceptance remain
open. This native test does not measure delivered FPS, videos, hidden-host or
user-paused night scenarios. Living Area, the phone and Home Assistant are
untouched; the overall objective remains active.

## Fixed-style startup probe bypass — 23:42 UTC batch

Original issue window: **23:42:59–00:12:59 UTC**, including investigation,
verification, deployment, documentation, cleanup and commit.

A bounded Family-only capture first observed three fresh preview hosts with
the existing Shuffle setting and sources. One capture was started and stopped;
its collector is independently confirmed inactive with zero listeners.

| First selected style | Pre-command observation → guarded scene admission | Metadata preparation | Native WebView constructor | WebView admission → page-finished event |
| --- | ---: | ---: | ---: | ---: |
| Push | 1,463ms | 0.659ms | 31.770ms | 1,073ms |
| Gallery Wall | 730ms | 0.796ms | 19.714ms | 402ms |
| Ken Burns | 614ms | 3.404ms | 18.765ms | 283ms |

These include Fleet/navigation/host work and existing caches. All eleven image
resolutions found their requested derivative ready. They do not establish
pristine cold-start latency, panel presentation or an isolated WebView cost.
Native metadata is not the large delay in this cohort.

The parallel source investigation found a concrete avoidable wait elsewhere:
every fixed style awaited orientation probes for up to six photos before
starting, with a 1,500ms timeout. **Ken Burns, Framed Print, Cinematic Push,
Memories and Polaroid** select any photo and apply size-dependent geometry
after the actual display decode has supplied dimensions. They now bypass that
probe batch. Shuffle and every other style retain their preparation, protecting
orientation selection, pairing and Filmstrip admission. The existing pause,
decode-readiness, failure/retry and cancellation paths remain in place.

Verification:

- The worker's initial actual-source regressions reported **12 expected
  failures** before the patch. The parent independently verifies the finished
  **29/29 focused tests** against the production entry, renderers, scheduler and
  metadata code. These cover unknown dimensions, portrait/panoramic geometry,
  decode completion, cancellation, pause/resume and failed-load recovery.
- The parallel full source suite reports **1,008 passed, four opt-in browser
  checks skipped and zero failures**; its tool receipt is recorded. That suite
  was not rerun by the parent.
- Independent real Chromium acceptance verifies **61 assertions across twelve
  cases**: all five optimized styles with known and unknown dimensions, plus
  Cinema and Shuffle protection. The fixture uses generated photos, actual
  production HTML/scripts, controlled deck ordering and delayed local HTTP
  probes. Actual browser image decoding and metadata are observed before the
  first scene report.
- The preceding HTML fails the targeted browser condition: Ken Burns performs
  two unrelated probes and reports its first scene at **1,574.2ms**. The repaired
  HTML reports at **67.3ms** in that fixture, with zero probes. Its five optimized
  styles' first reports range **19.0–67.3ms** across the ten cases. Cinema and
  Shuffle retain two in-flight probes and their bounded timeout behavior.
  These are fixture observations, not a measured Portal speedup or FPS.
- Debug and optimized performance APKs build successfully in **28 seconds**.
  The JVM task is **UP-TO-DATE**, retaining the prior valid **3,496 tests /
  362 suites** result; it did not execute a fresh full JVM suite.
- Family Area's independently read installed APK matches
  `49a5fbae2a64cc1fc46155658c2b73776fb6ea77b0de54e5ee81074ecfde4d2e`
  (**25,702,323 bytes**) with the existing signing certificate. Native DEX,
  manifest and **52 main assets** match the predecessor; only
  `assets/screensaver/index.html` changes.
- All **97 settings**, full screensaver configuration and photo-source groups
  compare unchanged. The bounded runtime response contains **418 lines** and
  **zero fatal exception markers**. The collector remains inactive with zero
  listeners. Clearing the verified upload reclaims **25,702,323 bytes**;
  the local candidate and exact predecessor rollback are retained.

Reproducible browser verifier:
`mocks/portal-premium-player-20261030/slideshow/fixed-style-startup-browser-checks.cjs`.
Public scalar receipts are in
`mocks/portal-premium-player-20261030/slideshow/evidence/fixed-style-startup/`.
This closes the unnecessary fixed-style startup gate. Family's configured
Shuffle policy is intentionally unchanged; first panel presentation, pristine
cold startup, physical night-clock acceptance, Home frame tails and physical
music UI acceptance remain open. Living Area, the phone and Home Assistant
receive no operations; the overall objective remains active.

## Minute-aligned calendar and retained night-state acceptance — 23:54 UTC batch

Original issue window: **23:54:05–00:24:05 UTC**, including investigation,
tests, build, deployment, evidence, cleanup and commit.

The photo-frame calendar already rendered only when the minute changed, but
its UI callback woke every second to discover that change. `CalendarMinuteRefresh`
now schedules directly for the next wall-clock minute boundary, reusing
`DarkRoomPolicy.nextMinuteDelay`. When a retained host becomes active, it
immediately checks for a crossed minute and replaces the old frozen callback.
Repeated activation in the same minute neither rerenders nor adds a duplicate
callback. The photos-only pipeline still starts no calendar work.

The six new scheduler regressions fail against the original one-second cadence,
with **six assertion failures and zero errors**. The corrected code passes the
fresh full JVM gate: **3,502 tests / 363 suites**, zero failures, errors or skips,
in **46 seconds**. They cover actual minute boundaries, midnight wake, repeated
activation, backwards clock adjustment and negative epoch time. Scheduled
periodic checks fall from **60 to 1 per minute**; this is a verified scheduling
reduction, not a measured CPU or delivered-FPS gain.

Debug and optimized performance APKs build successfully in **1m 14s**. The
owned API 34 AVD independently confirms the installed debug APK matches the
new local artifact before the final native acceptance:

| Production native scenario | Verified result |
| --- | --- |
| User-paused photo → night clock → bright return | Dark settles in 20.151s; bright in 5.144s. The original photo pixels and paused state remain for another 9.573s, beyond the configured five-second interval. Explicit Resume permits advancement. |
| Hidden before dark settles | Remains hidden past 24.103s with no night event, dimming or additional scene. On return, a fresh dark dwell settles in 20.384s. |
| Hidden while already displaying the night clock | Brightness remains 0.03 while hidden. Returning starts a fresh bright dwell, settling in 5.149s and restoring exactly the original photo region. |

The completed run passes **231 assertions, zero fail, in 153.179 seconds**.
Actual production UI taps, window/activity/process identity, native scene events
and default-photo screenshot regions establish retained presentation. This is
not direct heap/controller identity inspection or physical Portal acceptance.
All seven temporarily modified registered settings are restored; the emulator's
light is reset, its forwarding port removed and the owned AVD shut down.
The unrelated AVD remains present and receives no mutation.

The initial run passed the user-paused case but captured the outgoing Android
cover surface before it completed handover in the hidden case: **177 assertions
passed, one failed**. The verifier now observes the actual slideshow UI before
capturing that region. No product change was made for this fixture race. The
successful final run uses the newly built calendar code.

Family Area's independently read installed optimized APK matches
`c8651adc869e4a998c419e654b87b6a22daece82b13a4b293777e269192ec303`
(**25,702,323 bytes**) and the existing signing certificate. All **53 main
assets** and the manifest match the predecessor; native DEX contains the
calendar change. All **97 settings**, full screensaver configuration and
photo-source groups remain unchanged. The bounded runtime sample contains
**402 lines and zero fatal exception markers**. The collector is inactive
with zero listeners. Clearing the verified owned upload reclaims
**25,702,323 bytes**; the local candidate and exact predecessor rollback remain.

Public deployment evidence:
`mocks/portal-premium-player-20261030/evidence/family-calendar-minute/verification.json`.
Native receipt and captures:
`mocks/portal-premium-player-20261030/evidence/native-night-retention/`.
Reproducible verifier: `night-retention-native-checks.py`.

Physical Family night/backlight comfort, stacked-Dream tap, actual first-photo
panel presentation, pristine cold startup, Home frame-time tails and physical
music interaction remain open. Living Area, the phone and Home Assistant receive
no operations; the overall objective remains active.

## Home resume admission — rejected and withdrawn, 00:11 UTC window

Original issue window: **00:11:41–00:41:41 UTC**, including investigation,
implementation, native checks, physical comparison, rollback, cleanup and commit.

The trial deferred the selected tab's `RESUMED` lifecycle until its 160ms paired
slide settled. The page stayed `STARTED` and interactive during motion; initial
settled Home resumed immediately. This moved OnResume hooks and genuinely
RESUMED-scoped work. It did **not** defer Home's active-composition producers.
Rapid reversal never resumed the abandoned destination.

The candidate passed **3,510 JVM tests / 364 suites**, with zero failures, errors
or skips, and built debug/optimized APKs in **2m 3s**. The actual production Compose
fallback passed **242 native fixture assertions plus five accessibility checks**
in **27.262 seconds** on the owned API 34 AVD. Those generated-page checks used a
controlled frame clock and synthetic host lifecycle; they establish functional
contracts, not physical Portal performance or household UI acceptance.

Before capturing, acceptance was frozen at **at least 10% p95 improvement against
both controls**, no more than **5% mean/p99 regression**, at most **15% control
p95/p99 drift**, and warmed maximum below **250ms**. Three identical 30-cycle
Home/Apps blocks compared the exact accepted optimized APK, the candidate, then
the exact restored accepted APK. Main/settings-work sampling was disabled.

| Physical Family block | Home Window frames | Mean ms | p95 ms | p99 ms | Maximum ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| Exact control before | 781 | 13.131 | 36.25 | 51.00 | 77.860 |
| Resume-admission candidate | 780 | 13.395 | 34.00 | 48.50 | 67.301 |
| Exact control restored | 782 | 12.912 | 34.50 | 44.75 | 58.771 |

Candidate p95 reductions were **6.207%** and **1.449%**, both below the required
10%. Candidate p99 regressed **8.380%** against the restored control, exceeding
the 5% limit. Control p95/p99 drift was **4.828% / 12.255%**, within the frozen
bounds. The trial is **rejected**; no accepted performance gain is claimed.
FrameMetrics Window callback timings are not delivered FPS, panel latency or
visual tab-readiness measurements. All 90 cycles completed with zero first-draw,
callback-drop, invalid or overflow samples; all three collectors closed.

The candidate source, policy, tests and debug probe are withdrawn from the branch;
the prototype sources and generated native evidence remain privately retained.
The restored code passes a fresh **3,502 tests / 363 suites**, zero failures,
errors or skips, and both builds succeed in **1m 19s**. Restored R8 mapping contains
no candidate admission policy. The freshly rebuilt restored APK has a different
hash from the original saved control, which was built before the documented
calendar comment removal; executable calendar source was unchanged. The saved
control is used for restoration and the final physical comparison.

Family's independently read installed APK is the exact accepted
`c8651adc869e4a998c419e654b87b6a22daece82b13a4b293777e269192ec303`
(**25,702,323 bytes**). Candidate/control installs retain the signing certificate,
all **53 main assets** and manifest. All **97 settings**, screensaver configuration
and photo-source groups remain unchanged. The final bounded runtime response has
**402 lines / zero fatal exception markers**. The collector is inactive with zero
listeners. Clearing both verified owned uploads reclaims **51,404,646 bytes**.
The owned AVD and its fixture cache are removed; the unrelated AVD is untouched.

Public scalar receipt:
`mocks/portal-premium-player-20261030/evidence/home-resume-admission-trial/verification.json`.
This Home path is parked within its original window. No further candidate is
started in that window. Living Area, the phone and Home Assistant receive no
operations; the full goal and its remaining physical acceptance stay open.

## Physical night-clock failure and live light readout — 00:33 UTC window

Original issue window: **00:33:05–01:03:05 UTC**. The user reports that the clock
**did not appear** after the requested sensor-cover check. This physical failure
remains open; neither the repeat check nor brightness/photo-return acceptance
has been reported.

Family has no current USB/Wi-Fi ADB transport. Its fleet diagnostics confirms the
TCS34x0 on-change light sensor, but the prior API exposed only its capability.
`DarkRoomObserver` now publishes an immutable passive readout of the existing
listener's requested/registered state, latest finite lux, callback count,
receipt age, pending dwell and night state. `FleetDiag.hardware` includes it.
No additional sensor subscription, on-device polling loop, preference or sensor
policy is introduced. At most eight weak observer references are retained;
inactive entries are evicted first and activation restores membership. Readout
age explicitly means **callback receipt age**, not the physical measurement age.

The initial tests fail compilation because the new `diagnosticSnapshot` API did
not exist; this is a test-first readout contract, not reproduction of a rendering
bug. Final fresh validation passes **3,506 tests / 363 suites**, zero failures,
errors or skips; debug and optimized builds succeed in **1m 15s**. Independent
review identified and then verified corrections for active-observer eviction
and misleading age naming. The four new tests cover actual input/settle state,
hidden/reactivated stale-input rejection, failed vs absent registration and
preservation of an active readout through eight unused frame constructions.

After update/relaunch, Family's `PhotoFramePreviewActivity` listener is registered
and receiving readings: the initial readout contains **13 lux / 21 callbacks**,
with the **3-lux** dark threshold and no pending timer. A finite three-minute
read-only client observation sees **9–16 lux**, no below-threshold reading,
no dark dwell and no night transition. This does **not** establish what happened
during the original cover test, prove the correct aperture was covered, or prove
clock pixels/brightness/photo continuity. The repeat physical check is pending.
No threshold is changed to force a passing result.

The independently read installed optimized APK matches
`64dca367e97ea8ce17452080c237c7118512d2392293974d9bed2e8f409436c2`
(**25,702,323 bytes**). Signing certificate, all **53 main assets** and manifest
match the accepted predecessor; native DEX contains the passive readout. All
**97 settings**, screensaver configuration and photo-source groups remain
unchanged. The collector remains inactive with zero listeners; clearing the
verified owned upload reclaims **25,702,323 bytes**. The exact predecessor APK
is privately retained. No emulator is launched and no Living Area, phone or
Home Assistant operation is performed.

Public scalar receipt:
`mocks/portal-premium-player-20261030/evidence/family-night-sensor-readout/verification.json`.
The physical clock issue and full goal remain open. The next evidence must be
confirmed below-threshold light input followed by the actual dim clock and
retained-photo return; working subscription alone is insufficient acceptance.

## Sources

- [Android intrinsic blur contract](https://developer.android.com/reference/android/renderscript/ScriptIntrinsicBlur)
- [Android Allocation bitmap synchronization and lifecycle](https://developer.android.com/reference/android/renderscript/Allocation)
- [Android RenderScript process-context caching](https://developer.android.com/reference/kotlin/android/renderscript/RenderScript)
- [Compose Animatable continuity and cancellation](https://developer.android.com/develop/ui/compose/animation/value-based)
- [Compose placement-phase animation reads](https://developer.android.com/agents/skills/jetpack-compose/jetpack-compose-performance/references/animation)

- [Android MediaPlayer thread and seek contracts](https://developer.android.com/reference/android/media/MediaPlayer)
- [Android SurfaceView versus TextureView tradeoffs](https://developer.android.com/reference/android/view/TextureView)

- [Android sensor monitoring and lifecycle](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview)
- [Android light sensors](https://developer.android.com/develop/sensors-and-location/sensors/sensors_environment)
- [Android window brightness override](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#screenBrightness)
- [Hardware specifications, benchmarks and rendering evidence](portal-hardware-budget-2026-10-30.md)
- [HA-free music alternatives](music-without-home-assistant-2026-10-19.md)
- [Android 14 foreground-service types and connected-device prerequisites](https://developer.android.com/about/versions/14/changes/fgs-types-required#connected-device)
- [Android lifecycle-scoped coroutine restart and cancellation](https://developer.android.com/topic/libraries/architecture/views/coroutines-views)

- [AOSP on-change sensor reporting modes](https://source.android.com/docs/core/interaction/sensors/report-modes)
- [Android 10 SensorManager registration contract](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r1/core/java/android/hardware/SensorManager.java#820)
