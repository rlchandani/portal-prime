# Family Area native photo motion under the night clock — November 1, 2026

**Verified behavior; no production playback change.** Family Area passes
**357/357 native assertions/observations** with Smart Frame photo motion
enabled, including an interrupted automatic crossfade. The optimized APK
`3c15a465…` is restored at **17:44:05.708 UTC**. This is **11 minutes
30 seconds** from the conservative slot start of **17:32:35.411 UTC**;
that earlier timestamp is used because the first clock response for this
slot was lost during context compaction. The 30-minute deadline was not
reset. Living Area was not targeted.

| Scenario | Actual result |
|---|---|
| Moving photo entering night | Motion is running at **2,000 ms** before entry. Animator times, scale/translation and retained bitmap identity stay frozen through the clock cover and **41 samples over 10.352 seconds** of settled night. |
| Returning from night | The photo stays frozen through the returning cover. Motion resumes at **2,049 ms**, then advances **250 ms** in the subsequent observation before the next photo arrives. |
| Partial photo dwell | **2,987 ms** remains at entry. After reveal, the next request is observed at **2,940 ms** and the next photo at **2,992 ms**. Sampling differences are **−47 ms** and **+5 ms** respectively; these do not establish zero decode latency. |
| Night during an automatic crossfade | The blend is caught at fraction **0.037777778**. Both compositions, their image transforms, motion times and blend fraction remain retained while covered. |
| Pause/Resume/Pause and external cover while night is active | Neither animation restarts. After clock reveal, viewer Pause still holds the same two compositions and blend fraction; explicit Resume advances the blend and retires only the outgoing composition. |
| Viewer Pause before a later night cycle | Pause survives night and another **5.5 seconds** after reveal. |
| Viewer Resume under the clock | Motion and the photo stay held for another **5.5 seconds** while covered. Reveal returns to the same photo, then ordered autoplay advances. |

The actual production controller, loader, stage, callback queue and night-cover
transition run in an isolated debug Activity on Family Area's Android 10,
QCS605, 2160 × 1440 hardware. The native body takes **59.309 seconds**.
This bypasses the light sensor and directly invokes the existing controller
night/pause methods. It observes native view state and animator progression,
not optical comfort or delivered panel FPS.

## Fixture and Android 10 details

Smart Frame conservatively keeps a whole photo still without credible face
metadata. Three generated solid-color PNGs therefore receive synthetic,
bounded face boxes in an owned metadata cache. The cache is hydrated before
playback starts, enabling the real moving-crop path without detector inference.
This establishes animation suspension, **not face-detection accuracy**.

The fixture redirects `applicationContext`, internal/external files and cache
directories, and preferences into UUID-owned storage. Caching remains enabled.
Stopping the controller closes its metadata session, which may queue a final
write. The probe waits for that writer to become quiescent before its final
owned-tree deletion, preventing cleanup from racing a later `mkdir`.

Android 10's [ValueAnimator.getCurrentPlayTime](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-10.0.0_r1/core/java/android/animation/ValueAnimator.java)
uses current animation-clock time even while paused. A growing value there
would be an invalid failure criterion for a frozen `ObjectAnimator` blend.
The check uses its retained `animatedFraction`, actual layer alpha, animator
identity and image transforms instead. `AnimatorSet` motion time uses its
last processed frame. After explicit Resume, a subsequent frame must advance
the blend; `isPaused == false` alone is insufficient.

## Validation and restoration

Fresh debug/test APK builds and the complete JVM gate pass **3,765 tests
across 395 suites**, with zero failures/errors/skips. The build takes
**2 minutes 24 seconds**, all **75 tasks execute**. A subsequent five-second
APK freshness check has all **70 tasks up to date**.

The restoration receipt verifies the original optimized APK and test APK,
all **97 settings**, source configuration, accessibility services, persistent
pin, brightness policy and raw brightness, legacy and native face-cache
bytes, unchanged `system_server`, and original Immortal Dream. No
instrumentation or performance collector remains active; owned evidence and
generated storage are removed from the device. No runtime speed, CPU savings,
FPS gain or new production fix is claimed.

This extends the [earlier motion-off check](portal-night-photo-pause-2026-11-01.md)
to controlled, fixed-size native Smart Frame motion and an in-flight dissolve.
It does not establish natural sensor entry/exit, brightness comfort,
physical touch routing, rotation/resize continuity, face inference, other
renderers, or panel timing. The physical room-light check remains pending
because the user cannot do it now. The overall goal remains incomplete.

[Native checks](../mocks/portal-premium-player-20261030/evidence/family-night-photo-motion/checks.json),
[restoration](../mocks/portal-premium-player-20261030/evidence/family-night-photo-motion/verification.json),
[unit results](../mocks/portal-premium-player-20261030/evidence/family-night-photo-motion/unit-results.json),
[source/evidence hashes](../mocks/portal-premium-player-20261030/evidence/family-night-photo-motion/sha256.json).
