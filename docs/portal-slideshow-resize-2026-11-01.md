# Slideshow resize continuity — Family Area, November 1

**Delivered:** resized native slideshow compositions retain their animation phase
and receive measured image holders. The optimized build is installed on Family
Area. The same ordered **172 native assertions** change from **72 failures to
zero**, across six cases and 24 resize samples.

## What changed

`SlideshowStage.onSizeChanged` previously rebuilt only the current composition,
restarting its animation. Rebuilding children after their parent's measure pass
also left image holders at **0 × 0** after two native frames. An outgoing
crossfade composition retained its old geometry.

Both retained compositions now rebuild with the same bitmaps, preserve motion
time by photo index, and explicitly measure replacement children before layout.
Saved motion time survives a temporarily still geometry and a crop cancellation
at a different viewport. A completed animator retains its endpoint instead of
using Android's reset frame clock. On API 26–28, the restored geometric pose is
applied immediately because a paused, started `AnimatorSet` queues its seek.
The existing API 24–25 fallback remains unchanged.

## Evidence

| Check | Before | Installed fix |
|---|---:|---:|
| Identical ordered resize assertions | 100/172 pass | 172/172 pass |
| Native fixture exceptions | 0 | 0 |
| First covered pair's animation times | 800/3,200 → 0/0 ms | 800/3,200 → 800/3,200 ms |
| First covered pair's holders at 1,200 × 800 | 0 × 0 | 589 × 800 each |
| Native fixture body | 5.244 s | 5.342 s |
| Maximum accounted retained bitmap bytes | 7,680,000 | 7,680,000 |
| Crop Save regression | — | 73/73 pass |
| Night-clock motion regression | — | 359/359 pass |
| Fresh JVM tests | — | 3,765 pass; 395 suites; zero failures/errors/skips |

The six cases cover a paused pair, user-paused single photo, interrupted paired
crossfade, completed motion, manually cropped first photo with moving companion,
and crop cancellation after resize. Each uses **1,200 × 800 → 64 × 1,440 →
1,440 × 1,000 → 2,160 × 1,440**. Checks include actual measured image/holder
bounds, geometry, per-photo times, pause state, retained bitmap identity,
pixel-budget accounting, crossfade ownership and later motion continuation.
Generated images and synthetic face boxes keep household media out of evidence.

The regression clock holds moving photos for **10.353 seconds**, with a retained
dwell of **2,974 ms** and the next photo observed **2,993 ms** after reveal.
This exercises the production native controller under a controlled clock cover;
it does not stimulate the physical light sensor or verify panel brightness.

A preliminary readiness probe timed out while waiting for measured child bounds.
It was restored and discarded as a completion result. The definitive baseline
instead observes two native frames and explicitly asserts child measurement,
exposing the production zero-size defect. Its 172 assertion labels match the
final run exactly. Earlier exploratory artifacts remain private.

## Build and deployment

Debug, optimized performance and instrumentation APKs were rebuilt alongside
the full JVM suite: **123/123 tasks executed, 4 minutes 12 seconds**. Signing
certificate SHA-256:

`4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6`

Installed optimized APK SHA-256:

`dcc2898e69e5c2bb13ca70baaf83d30739702f088f8ebc99a11b1f8ae329ed32`

Wireless ADB verification finished at **18:09:09 UTC**, **23 minutes 29 seconds**
after the original issue start at **17:45:40 UTC**, within its unchanged
30-minute limit. All **97 settings**, source configuration, accessibility
services, persistent app pin, brightness configuration/value, both face caches,
and original Dream were restored. The system-server PID was unchanged; the
previous test APK was restored; no instrumentation or collector remained.
Owned device evidence was removed. Living Area was not changed.

## Scope and remaining acceptance

This verifies actual native Android 10/API 29 behavior on Family Area's
QCS605/Adreno 615 Portal+. API 28 seek compatibility was checked against
[Android 9 AnimatorSet source](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-9.0.0_r1/core/java/android/animation/AnimatorSet.java),
not a physical Android 9 device.

No new decoding, pixel allocation, network request, persistent setting or
per-frame observer was added. Equal bitmap accounting is a regression guard,
not a memory reduction. Fixture duration is not panel FPS or a speed claim.
Physical rotation/input, natural room-light fades and optical comfort remain
unverified. The user cannot perform the room-light test now. Previously parked
cold-start, renderer and brief Dream-to-Home handoff work remains parked.
Music provider/audio wiring remains outside the current goal.

[Native baseline, final checks, regression reports and deployment receipt](../mocks/portal-premium-player-20261030/evidence/family-slideshow-resize/)
contain the recorded evidence. `sha256.json` pins the evidence and source files.
