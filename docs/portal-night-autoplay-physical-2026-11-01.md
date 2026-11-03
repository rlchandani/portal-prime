# Family Area short-interval night retention

**The two tested autoplay cases pass on the actual Android 10 / API 29 Portal+.**
This closes the earlier short-interval gap for static local photos in native
Whole Photo and legacy Ken Burns. It is a hardware verification of the installed
implementation, not a new production speed improvement.

| Case | Photo interval | Settled clock hold | Returned photo-region difference | Autoplay resumed |
| --- | ---: | ---: | ---: | --- |
| Native Whole Photo | 5 s | 22.429 s | **0**, exact RGB match | yes |
| WebView Ken Burns | 5 s | 22.404 s | **0**, exact RGB match | yes |

The complete successful workflow took **129.305 seconds**. Both native videos
were visually reviewed. They show photographs advancing before darkness,
the warm clock with Sunday/November 1, the retained photo returning, and
subsequent advancement.

The native logs report five scene admissions before night, **zero while photos
were covered**, and the next admission **3,782 ms after the light-return
notification**. That notification begins the three-second reveal, so this
sequence is consistent with retaining the remaining photo dwell rather than
immediately skipping it. This is not a direct measurement of the internal
deadline. The WebView readiness log does not report its internal photo swaps;
its scope is supported by matching returned pixels and subsequent advancement,
not a complete hidden-JavaScript trace.

## Test and restoration

The existing local folder stayed configured throughout. Temporary registry
settings selected the renderer, five-second interval, static photo motion,
no video, no anti-burn-in or now-playing overlay, the real sensor's 20-lux
dark threshold and 3% warm clock. The observed sensor reading was **3 lux**.
The registered clock-disable setting supplied the exit; no synthetic sensor
event or system-wide brightness setting was used.

The initial attempt stopped before changing settings because launching Home
through ADB did not retire the existing Dream. Its failed receipt remains
preserved. The successful attempt used one ordinary tap to retire each
existing owned Dream before starting the next case.

All **97 settings and 22 source fields** were restored exactly. The original
Dream was resumed, optimized APK `199aa8a1…` stayed installed, and the performance
collector has zero active listeners. No APK installation, instrumentation,
force-stop, accessibility change or firmware change occurred.

See the [sanitized receipt](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-night-autoplay/verification.json).
Household photo captures, source credentials, profiles and recordings stay
outside Git.

## Scope and next finding

These cases do not accept every slideshow style, moving-photo geometry, video,
animated-image residency, natural bright-room exit, optical comfort or panel
FPS. Source inspection also identifies a possible legacy pause-command boundary
race; these two observations do not prove that every asynchronous boundary is
race-free.

The recordings exposed a separate quality issue: after the night clock crossed
a minute boundary, the returning photo's smaller clock initially displayed the
old minute before refreshing. The [subsequent clock-reveal fix](portal-clock-reveal-physical-2026-11-01.md)
is installed and passes both physical renderer checks. The broader
platform/night/player goal remains incomplete.
