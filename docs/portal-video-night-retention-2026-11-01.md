# Retained slideshow video through the night clock

**Delivered on Family Area:** returning from the night clock resumes the
retained video player without seeking to an earlier cached position. Actual
surface destruction still queues position recovery for the replacement player.
The user's deliberate pause choice survives both night and host-cover changes.

The issue started at **09:05:38 UTC**, with a **09:35:38 UTC** hard limit.
Source commit is **9ad4934f**, on `codex/slideshow-video-night-20261101`.
Delivery verification and this record finished at **09:32:29 UTC**,
**26 minutes 51 seconds** after the issue started, within its 30-minute limit.

## Cause and correction

The controller saved its cached position before suspension, then requested a
seek on resume even when the same native player and surface survived. The
position cache samples at 250 ms intervals. That additional seek was unnecessary:
[Android's MediaPlayer documentation](https://developer.android.com/reference/android/media/MediaPlayer)
specifies that `start()` resumes a paused player at its paused position.
Seeking is asynchronous, and `SEEK_CLOSEST` can incur additional decoding work.

Recovery now belongs to `SlideshowVideoView.surfaceDestroyed`. It captures the
cached position with source/surface revisions and carries a recovery seek in
the immutable request until the replacement player prepares. The worker keeps
all MediaPlayer access off Main. Replacing the source resets seek state, so a
new clip cannot inherit the previous clip's position.

This also covers a surface becoming invalid before its destruction callback
arrives: releasing the native player does not clear the position cache or the
request's pending recovery. The subsequent callback still supplies recovery.
Retained night playback, which does not destroy the surface, simply starts again.
No per-frame work, settings or rendering style was added.

## Actual Family Area verification

The baseline exercised the real preview, controller and worker-owned player.
**84/85 checks passed**; its single failure was the extra seek on night return.
The corrected gate passes **92/92**, including seven additional checks for
an actual surface destruction/recreation.

| Measurement or invariant | Baseline | Corrected |
| --- | ---: | ---: |
| Extra seek revision and applied seek on retained night return | **1** | **0** |
| Native position drift during three-second settled clock hold | **0 ms** | **0 ms** |
| Cached position drift during that hold | **0 ms** | **0 ms** |
| Same controller, source, player and surface through night return | Pass | Pass |
| User pause survives night and external host-cover changes | Pass | Pass |
| Position after actual surface recovery | Not in baseline gate | **1,118 → 1,118 ms** |
| Required seek after actual surface recovery | Not in baseline gate | **1** |

The corrected native probe took **19.933 seconds**; its complete guarded
test/install/restore run took **59.752 seconds**. The fixture is a generated,
silent **30-second, 160×90, 12 fps H.264** clip, not household or network media.
The probe reads native playback state on the player's owner thread and verifies
rendering-start admission, preparation's retained photo and release on exit.

The baseline did **not** demonstrate a visible backward jump: its first resume
sample was 41 ms beyond the paused position. The improvement is a verified
removed seek, not a claimed measured reduction in visible flicker or CPU/FPS.
Resume samples are the earliest worker observations, not seek-complete timing.
The external cover uses the actual controller's visibility API; the surface
recreation additionally exercises real SurfaceHolder callbacks.

## Build, review and delivery

- **3,689 unit tests / 390 suites**, zero failures, errors or skips.
- Five new source/surface/position policy tests pass.
- Debug, optimized performance and Android test APK builds pass.
- Review found no blocker in the final lifecycle ownership change.
- Existing video smoke checks now use the current `SlideshowVideoView` rather
  than casting it to the retired Android `VideoView`. Those repaired checks
  compiled; this focused run does not claim the entire smoke suite ran.

Optimized non-debuggable APK **e80660a4…** is installed on Family Area with the
existing signing certificate. Deployment took **16.778 seconds**. All **97
settings**, **22 source records**, pin state and exact accessibility configuration
are preserved; Android's system process stayed unchanged and the original
Dream is active. A fresh hash check passed, with zero performance listeners
and no active instrumentation.

Both valid native runs restored the original APK/test APK, settings, sources,
pin, accessibility and Dream. The probe released its owned player, closed its
activity and removed its two fixture files and directory.

## Initial test failure and acceptance limits

An earlier attempt stopped before video playback because its setup Main
rendezvous exceeded 1.2 seconds. It also reported activity cleanup failure
despite having created no activity. Its APK-restore guard then detected
accessibility re-enablement and withheld replacement. A separate guarded
repair restored the original APK/test APK, settings, sources, accessibility and
Dream, with system-process continuity verified during that repair.
The initial attempt's complete system-process continuity was not recorded.

The test now skips empty activity cleanup, gives startup/configuration an
explicit bounded allowance and reports whether timed-out work began.
The parent waits for application startup and uses verified own-service
detachment when explicitly ending diagnostics. Every prior attempt was terminal
before a corrected run began; no observation timeout alone triggered a restart.

See the [sanitized verification receipt](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-video-night-retention/verification.json).
Private profiles, credentials and rollback artifacts stay outside Git.

This accepts synthetic local-video continuity on the actual Android 10 Portal.
It does not accept remote streaming/camera cancellation, optical night comfort,
full-resolution decoder performance, moving-video pixel equality, GIF/WebP
pixel/GPU residency or delivered panel FPS. The probe invokes the real night
transition but does not physically stimulate the sensor. Its photo-face extras
hide weather, while the controller's existing weather fetch may still run.
Living Area was not changed. Provider/audio wiring remains deferred and the
full goal remains incomplete.
