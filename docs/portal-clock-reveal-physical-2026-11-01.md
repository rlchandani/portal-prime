# Current photo time throughout the night-clock return

**Fixed and installed on Family Area's actual Android 10 / API 29 Portal+.**
The retained photo clock now shows the current minute during the return fade,
instead of exposing its old minute until the three-second fade completes.

| Renderer | Photo time before night | Actual return minute | Time throughout the recorded reveal | Retained photo difference |
| --- | --- | --- | --- | ---: |
| Native Whole Photo | 12:24 | 12:25 | 12:25 | **0**, exact RGB match |
| WebView Ken Burns | 12:25 | 12:26 | 12:26 | **0**, exact RGB match |

Both native recordings were visually reviewed at six samples per second
through their first 4.667 seconds. The earliest legible returning clocks
already show the new minute and continue to show it as they fade in. Sampling
does not establish every panel frame or optical brightness behavior.
The complete successful hardware workflow took **116.210 seconds**.

## Implementation

`PhotoFrameController` refreshes the retained face's time and date before the
returning cover becomes transparent. A minute guard publishes one snapshot
per minute during the finite fade, including a new minute crossed during it.
It resets when the transition settles, changes direction, changes host
visibility or stops.

`FaceRenderer.refreshRetainedTime` updates only the clock and date. It does
not reactivate the renderer, post its repeating timer, resume media or start
metadata listeners. Existing photo suspension remains in force until the
cover finishes. The split-flap clock has a dedicated snapshot update that
cancels obsolete flips and sets the current digits without starting an
animation while hidden.

The new tests cover minute coalescing, clock correction, transition reset,
midnight date refresh while inactive, disposal and inactive flip-clock
behavior. **3,625 tests in 383 suites passed, with zero failures, errors or
skips.** Debug and optimized performance APK builds passed. Independent static
review reported no actionable findings.

## Hardware procedure and restoration

The existing local folder stayed selected. Each case temporarily used static
photos, a 120-second interval, no video, and the real ambient sensor at
**3 lux** with a 20-lux dark threshold. The clock remained covered until the
device's real time crossed a minute; neither the system clock nor sensor
readings were injected. The registered night-clock disable setting supplied
the exit.

All **97 settings and 22 source fields** were restored exactly, the device
remains unpinned, its original Dream was resumed, and the performance
collector has zero active listeners. Optimized, non-debuggable APK
`36188b2c…` is installed with the existing signing certificate. Only Immortal's
two accessibility services were temporarily detached for installation to
avoid the firmware's known update/unbind failure; the exact original service
list was restored and the system process did not restart.

The first deployment assertion compared the complete pin response while on
Home with a snapshot taken during Dream. Foreground diagnostics differed,
so that assertion failed. Its original receipt is preserved privately.
A separate check confirmed unchanged persistent pin fields; the successful
hardware workflow then verified the original unpinned state and all
restoration checks. This was an assertion-scope correction, not another
installation.

See the [sanitized receipt](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-clock-reveal/verification.json).
Household photographs, recordings, credentials, APK backups and full device
profiles remain outside Git.

## Remaining scope

The physical tests accept this minute-reveal fix in the two static local-photo
cases. They do not accept every slideshow style, moving photos, videos,
animated-image residency, panel FPS or natural bright-room exit.
The user cannot perform the room-light check now, so natural transitions and
brightness comfort remain unverified. The broader platform/night/player goal
remains incomplete.
