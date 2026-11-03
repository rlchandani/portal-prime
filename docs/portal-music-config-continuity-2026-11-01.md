# Music photo settings continuity — Family Area

**Fixed and deployed:** unrelated screensaver settings no longer recreate the
music photo controller. Source commit **c88d9c91** is on
`codex/music-photo-config-continuity-20261101`.

| Physical Android 10 / API 29 check | Before | After |
| --- | --- | --- |
| Calendar setting change, Full → Photos | controller identity changed | same controller |
| Photo pixels after calendar change | not accepted by the failing baseline | maximum channel difference **0** |
| Night-clock tone change, Full → Photos | not reached | same controller, pixel difference **0** |
| Photo interval change | not reached | controller reconfigured; current music retained |

The successful configuration workflow passed **14/14 checks**, captured
**11 native frames**, and took **84.281 seconds**, including restoration.
Current track and selected Players tab remain selected. The photo remains
pixel-identical through both unrelated settings changes and the subsequent
hold. Photos still allocate lazily when first opened.

## Why the change is needed

The music host previously used the complete screensaver settings snapshot
as its Compose retention key. A calendar, clock or sleep change therefore
disposed the photo controller and started another, even though those settings
are not used by the music photo pane.

The new typed key follows the active `PhotoFrameSource`, photo interval,
shared-album refresh interval, cache cleanup toggle, and applicable cache
budget. Inactive source credentials and suppressed Whole Photo presentation
choices do not trigger a rebuild. Persistence and source configuration are
unchanged. Settings still refresh on layout/resume boundaries, rather than
on playback progress ticks.

## Verification and delivery

Four focused tests failed against the behavior-equivalent old key. All
**7 focused tests** then passed. The final full gate passed
**3,657 tests / 386 suites**, with zero failures, errors or skips; debug,
Android test and optimized performance builds passed. Independent review
found no missing inputs in the restart key.

Optimized non-debuggable APK **a85761a7…** is installed on Family Area, using
the existing signing certificate. Its installation hash was checked.
Deployment preserved **97 settings, 22 source fields**, persistent pin state,
the exact accessibility configuration, and the original Dream. The system
process stayed unchanged. The deployment took **17.387 seconds**.
Living Area was not changed.

## Limits and failed attempts

Two initial runs timed out in the native observer while capturing the paused
view, before reaching the settings edit. Their failed receipts remain private.
The first needed a separate restoration recovery; the second restored all
state within its workflow. The subsequent configuration check isolates
controller continuity using a settled static photo and a 120-second dwell.
It reproduces the old controller replacement and verifies the correction.

This gate does **not** newly accept paused-view observer capture, provider
authentication, audio, every network source, or panel FPS. The earlier
[17-check photo pause gate](portal-music-photo-pause-2026-11-01.md) remains
separate evidence. The pending SMB startup ownership finding, natural
room-light comfort, cold startup, animated-image residency and Home/Apps
timing outliers remain open. The full goal is incomplete.

See the [sanitized verification receipt](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-music-config-continuity/verification.json).
Household photographs, credentials, original profiles and rollback APKs
remain outside Git.

**Subsequent delivery:** the
[queued SMB connection cleanup](portal-smb-callback-cleanup-2026-11-01.md)
closes the connected-resource ownership gap when a pending UI delivery is
discarded. Actual NAS behavior and connection establishment/listing cancellation
remain unverified.

**Subsequent paused-view check:** the
[focused physical observer run](portal-paused-capture-2026-11-01.md) passed
10/10 continuity checks across nine captures, including Pause and manual
Next. The prior timeout did not reproduce and is not claimed fixed.
