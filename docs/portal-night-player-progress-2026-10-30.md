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
4. Port the finalized music appearance to native UI and refine additional
   existing slideshow styles. Browser samples do not close these items.

The sensor-clock investigation and implementation batch is closed within its
30-minute limit. Physical acceptance is pending, rather than an invitation to
repeat diagnosis without new evidence. No Living Area rollout or phone modification
belongs to this development pass.

## Sources

- [Android sensor monitoring and lifecycle](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview)
- [Android light sensors](https://developer.android.com/develop/sensors-and-location/sensors/sensors_environment)
- [Android window brightness override](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#screenBrightness)
- [Hardware specifications, benchmarks and rendering evidence](portal-hardware-budget-2026-10-30.md)
- [HA-free music alternatives](music-without-home-assistant-2026-10-19.md)
