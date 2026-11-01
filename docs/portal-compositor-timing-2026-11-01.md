# Family Area compositor timing — November 1

**Status: read-only timing capability verified; no runtime fix or FPS acceptance.**
This check closes whether the current Portal exposes fresh, attributable
SurfaceFlinger presentation records. It does not close physical brightness/fade
acceptance, the OS Dream performance gap, or any parked investigation.

The issue slot started at **20:52:03 UTC**, with a **21:22:03 UTC** hard limit.
Evidence checks finished at **20:58:08 UTC**, **6 minutes 5 seconds** into that
unchanged slot; this investigation is closed.
The new capture lasted **30.736 seconds**, on Family Area over wireless ADB.
Living Area was not touched. There were no installs, navigation commands,
settings/source changes, brightness writes, counter clears or collector starts.

## What was actually sampled

The current visible host was **PhotoFramePreviewActivity**, not PhotoDreamService:
Android reported no active OS Dream. The unique content surface was
`com.immortal.launcher.debug/com.immortal.launcher.PhotoFramePreviewActivity#0`.
The start and end window snapshots identify the same window, owner UID,
surface and activity token; both report it visible and ready for display.
These endpoint observations do not prove continuous activity ownership.

The baseline rolling history was excluded. Thirty-two subsequent reads produced:

| Observation | Result |
|---|---:|
| Display-period header | 16,666,666 ns |
| Unique fresh presented-buffer records | 150 |
| Span between first and last fresh record | 30.417112 s |
| Adjacent fresh intervals | 149 |
| Intervals at most 25 ms | 118 |
| Intervals over 25 through 50 ms | 0 |
| Intervals over 50 ms | 31 |
| Longest interval | 1,016.707135 ms |
| Maximum polling gap | 1.036242 s |
| Minimum overlap between adjacent rolling samples | 71 records |
| Maximum new valid rows in one poll | 55 |
| Samples with no overlap | 0 |
| Invalid/unready rows excluded across repeated reads | 4 |

The 127-row dumps overlap throughout; the fresh records are deduplicated by
presented timestamp. The four excluded rows are repeated-sample observations,
not four independently proven dropped frames. The three interval buckets sum
to all 149 intervals. These buckets describe this sample; they are not a
predefined animation success gate.

## Interpretation

Android 10's [FrameTracker source](https://android.googlesource.com/platform/frameworks/native/+/refs/tags/android-10.0.0_r1/services/surfaceflinger/FrameTracker.cpp)
dumps requested presentation, actual presentation and ready time in that order.
Its [header](https://android.googlesource.com/platform/frameworks/native/+/refs/tags/android-10.0.0_r1/services/surfaceflinger/FrameTracker.h)
defines actual presentation from the presentation fence. This capture uses the
second column, rejects zero/INT64_MAX rows and excludes the historical baseline.
It needs no subtraction between device uptime and another clock domain.

The sample includes short bursts and roughly one-second gaps. Animation intent
was not captured independently. A clock update or static photo can deliberately
leave such gaps; therefore **no dropped-frame percentage, animation FPS or
slideshow pass/fail is inferred**. This is compositor buffer timing, not an
optical observation of panel scanout or the backlight ramp.

[Android's presentation-timing guidance](https://developer.android.com/games/optimize/vitals/slow-session)
also requires choosing the appropriate rendered layer. This check used the
observed preview content surface; the globally scoped timestats collector was
not enabled or cleared.

## Handoff

The installed APK remains
**872b6af7354b6616320b0081f0525d8c27c0bad29759c3bcc7a02a4a5d76ae10**.
Fresh `/perf/state` reports `active=false`, `collectorRunning=false` and
`activeListeners=0`. The read-only capture has exited.

Raw windows, timestamps and the temporary helper are private under
`/private/tmp/portal-surface-timing-20261101/`; they are not committed.
The first offline endpoint parser rejected the window because its regex
incorrectly required text after the Activity name. Reading the saved snapshots
with a correctly bounded window block resolved that parser error without another
device capture. A source-fetch attempt also failed local Python certificate
validation; system curl fetched the exact Android 10 sources with certificate
verification enabled.

The user cannot perform the natural room-light check now. It remains pending.
No previously accepted cohort was rerun, and the legacy night-motion,
render-layer, cold-start and black-handoff investigations remain parked.
