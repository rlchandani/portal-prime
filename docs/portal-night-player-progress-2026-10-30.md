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

## Sources

- [Android sensor monitoring and lifecycle](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview)
- [Android light sensors](https://developer.android.com/develop/sensors-and-location/sensors/sensors_environment)
- [Android window brightness override](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#screenBrightness)
- [Hardware specifications, benchmarks and rendering evidence](portal-hardware-budget-2026-10-30.md)
- [HA-free music alternatives](music-without-home-assistant-2026-10-19.md)
