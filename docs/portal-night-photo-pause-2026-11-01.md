# Family Area native night-photo pause — November 1, 2026

**Verified behavior; no production playback change.** Family Area passes
**125/125 native assertions/observations** across three controlled playback
scenarios. The original optimized APK `28735244…` was restored at
**17:19:53.897 UTC**, under twelve minutes from the conservative slot start
of **17:08:11 UTC**. Living Area was not targeted.

| Scenario | Observed result |
|---|---|
| Automatic playback, shortest five-second interval | Enter night two seconds into the first photo. All **41** samples over a **10.313-second** settled-clock hold retain the same bitmap, source and generation. |
| Return from clock | All **58** return samples retain the photo through the cover fade. The recorded dwell remainder is **2,979 ms**; the next request and next photo are observed at **2,984 ms** after reveal, a **5 ms** difference. The second generated photo follows in order. |
| Viewer Pause before night | Pause survives the clock, external host cover/reveal and a further **5.5 seconds** after returning to photos. |
| Viewer Resume under the clock | Resume does not expose or advance photos during another **5.5 seconds** of clock cover. On reveal the same photo returns, then the third generated photo follows in order. |

The production `PhotoFrameController`, loader, callback queue, stage and
night-cover transition run in a debug-only Activity on Family Area's
Android 10 / QCS605 / 2160 × 1440 hardware. Generated files and preferences
are isolated into a fresh UUID tree. The probe enters the clock and viewer
pause through their existing controller methods, bypassing the sensor dwell
and physical buttons. The controller work takes **51.372 seconds**.
Timer dispatch and image admission are recorded separately; their equal
sampled times do not establish zero decode latency.

The final debug fixture build and complete JVM gate pass **3,765 tests
across 395 suites**, with zero failures/errors/skips, in **1 minute
33 seconds**. A subsequent instrumentation-only guard change builds in
**22 seconds** and passes the final native run. There is no new runtime
speed, FPS or CPU-savings claim.

## First fixture correction and cache limitation

The first run has **119/120 passing checks**. Its failed ordered-photo
assertion compared a pathname suffix with production's full
`local:<path>:<length>:<mtime>` key. That was a test defect, not a playback
regression. The discarded results and restoration receipt are retained.

The first fixture also isolated preferences but inherited the shared
`filesDir`. Its `cacheEnabled=false` setting allowed the controller's cache
housekeeping to purge `files/screensaver-media-cache`. That directory was
absent afterward; its pre-test contents were not inventoried, so this
record cannot establish whether downloaded cache files were deleted.
The corrected fixture keeps caching enabled and redirects internal and
external file/cache directories into its owned tree. Stop precedes fixture
deletion. Original settings/source configuration and face-cache bytes are
verified, but preservation of the earlier downloaded-media cache is **not
claimed**.

## Restoration and remaining acceptance

The final receipt verifies the original optimized APK and test APK,
all **97 settings**, source configuration, accessibility services, persistent
pin, face-cache bytes, unchanged `system_server`, original Immortal Dream,
and no active instrumentation or performance collector. Automatic-brightness
policy is unchanged; its raw level differs under the active automatic policy.
Generated evidence/files/preferences are removed from the device.

This accepts the controlled **native Smart Frame** pause/dwell path.
Natural sensor entry/exit, optical fade comfort, physical touch routing,
motion-on styles, legacy WebView styles, in-flight decode presentation and
delivered panel FPS are not established by this check. An already requested
automatic decode may finish while covered and be presented after reveal;
that separate admission behavior is not classified as a failure here.
The user cannot perform the room-light check now; it remains pending.
The overall performance/night/music goal remains incomplete.

The later [native motion check](portal-night-photo-motion-2026-11-01.md)
accepts controlled Smart Frame pan/zoom and an interrupted dissolve with
**357/357 observations/assertions**. Its fixed-size, synthetic-metadata scope
does not replace the physical room-light check or establish other renderers.

[Native checks](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-night-photo-pause/checks.json),
[restoration](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-night-photo-pause/verification.json),
[unit results](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-night-photo-pause/unit-results.json),
[discarded first checks](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-night-photo-pause/discarded-fixture-checks.json),
[source/evidence hashes](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-night-photo-pause/sha256.json).
