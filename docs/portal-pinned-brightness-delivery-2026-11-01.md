# Current-build pinned screensaver verification

**Verified on Family Area's installed optimized APK `6c1adacc…`:** the pinned
accessibility overlay delivers the night brightness ramps, live setting changes
and default-policy return. Single taps from photos and the night clock reveal
the same Files activity, window and process. The retained default-photo interior
returns with identical pixels.

This closes the changed overlay-delivery path's native verification gap after
[the brightness-update reduction](portal-night-brightness-delivery-2026-11-01.md).
It adds no runtime feature or performance-gain claim. No APK was installed, no
instrumentation was started, and Living Area was not touched.

The issue slot started at **10:06:56 UTC**, with a hard limit of **10:36:56 UTC**.
The actual gate and restoration completed in **75.734 seconds**; the child gate
took **71.213 seconds**. The installed runtime source remains **fa84f968**.
The verifier correction is **d2b925bf** on
`codex/family-pinned-delivery-20261101`.

## Actual overlay checks

The test used the production `TYPE_ACCESSIBILITY_OVERLAY` with Whole Photo,
motion disabled and a 120-second interval. The real sensor reported **3 lux**;
a temporary registered threshold of **20 lux** triggered two clock entries.
Registered disable supplied the photo-return case. Natural bright-room exit
was not tested.

| Case | Measured interval | Measurement |
| --- | ---: | --- |
| First dark entry | 2.972 s | First observed explicit command → target |
| Live 3% → 15% | 3.164 s | Setting request → observed endpoint |
| Live 15% → 3% | 3.159 s | Setting request → observed endpoint |
| Disable clock, return to system policy | 3.196 s | Setting request → observed default sentinel |
| Second dark entry | 2.909 s | First observed explicit command → target |

All five command ramps pass the finite-duration, monotonicity, endpoint and
intermediate-level checks. The two live ramps each expose **14 distinct interior
integer levels** in the samples. The small default/night range exposes its one
interior integer level. These are sampled window commands, not an exact count
of WindowManager calls or a measurement of optical luminance.

Exactly one center tap was admitted per cover. Both returns passed the retained
Files activity/window/process checks and removed the fullscreen overlay with
zero failed cleanup records. This verifies retained app ownership; it does not
verify a browser document, its JavaScript state or live Home Assistant content.

The returned photo region was exactly equal to the stable baseline, using
**951 × 547 native pixels**. A 120-second interval prevents photo advancement
within this test; this result does not independently prove short-interval
autoplay pause.

The native capture shows time, weekday and date without observed clipping:

![Actual Family Area night clock, cropped from a native capture](https://raw.githubusercontent.com/starbrightlab/immortal/main/mocks/portal-premium-player-20261030/evidence/family-pinned-brightness-delivery/night-clock.png)

The capture establishes rendered pixels, not backlight comfort. Accessibility
time/date semantics and a new content-fade video were not tested.

## Hardware-aware verifier correction

The old verifier always demanded four distinct intermediate floats. Android 10
converts window overrides to integer levels; after redundant writes are removed,
the current default/night range has fewer possible interior levels.

The corrected verifier counts integer levels strictly between both endpoints,
using Java-compatible float32 multiplication before truncation. It requires up
to four available interior levels rather than demanding more than the hardware
can represent. Live changes use the setting-request timestamp, since the first
visible coarse step may occur late in an otherwise continuous logical ramp.
An adjacent-level return may have no interior command; it still requires the
exact endpoint and the bounded request-to-endpoint interval.

Verifier syntax and **14 focused capability checks** pass. An additional
supplied-sample regression executes the real verifier ramp with system level
8/255, no visible interior step and a three-second policy return. That is a
Python verifier regression, not a physical device measurement. Review caught
and resolved the float-counting and no-visible-step defects before the native
run. Final review found no concrete blocker within the stated test scope.

The first-entry record includes a **5.354-second** gap between two default-policy
observations during baseline capture. Active-progression sample gaps stay below
**0.194 seconds** in that entry. Neither value is a frame-time or panel-FPS
measurement.

## Restoration and remaining scope

Seven scalar settings and the active photo-source selector were temporarily
changed through the existing API. All **97 settings** and **22 source records**
were restored exactly. Original pin state, accessibility configuration, global
brightness, Android system process and the original Dream were preserved.

Fresh checks confirm the same installed APK, no pin/cover or pending/failed cover
cleanup, no instrumentation, no performance collector/listener and an active
original screensaver. Runtime Kotlin code is unchanged, so the earlier
**3,715-test** build gate remains a recorded prior result; it was not rerun for
this Python-verifier change.

The final optical smoothness/comfort check remains pending because the user
cannot perform it now. Other styles, complete GPU residency, browser retention,
cold startup and parked platform timing gaps retain their existing limits.
The broader goal remains incomplete; music provider/audio wiring stays deferred.

[Sanitized verification receipt](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-pinned-brightness-delivery/verification.json).
Raw captures, native dumps, configuration/source snapshots and credentials remain
private outside Git.
