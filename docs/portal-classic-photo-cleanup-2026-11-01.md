# Classic photo cleanup — November 1

**Delivered to Family Area.** Closing a Classic photo host now recycles
unpublished decoded photos and releases its displayed drawable/history references.
The same actual Android check changes from **3 failures / 16 checks to 16/16 passing**.

| Measured behavior | Before | After |
|---|---:|---:|
| Unpublished 720 × 720 bitmap recycled by production stop | No | Yes |
| Allocation owned by that unpublished bitmap | 2,073,600 bytes | Disposed on stop |
| Drawable references retained by the two image views after stop | 2 | 0 |
| Pending UI callbacks after stop | 0 | 0 |
| Ordinary queued photo admitted without recycling | Pass | Pass |
| Displayed bitmaps prematurely recycled | 0 | 0 |

The zero pending-callback count already passed before the fix: clearing a queue
does not by itself establish cleanup of the resources its callbacks carry.
The baseline fixture recycled its captured, never-displayed bitmap only **after**
recording the failed production assertion. The passing run requires no fixture
recycling, so teardown cannot mask the improvement.

## Change

The Classic local, remote and default-feed bitmap callbacks now use the existing
`PausableFrameCallbacks.postOwned` API. Its discard hook runs if the queue closes,
rejects a late post, or removes an unclaimed callback. Dispatch transfers ownership
and clears that hook, preserving normal photo admission and avoiding double disposal.
The stale remote-mode branch also disposes its unadmitted bitmap.

On stop, the controller cancels animations, clears both legacy image drawables
and clears Classic history. It does **not** recycle displayed pixels: Android may
still retain their display-list references. The native loader, pixel budget and
stage are unchanged.

## Validation and deployment

The fixture uses the real `PhotoFrameController`, an isolated generated folder,
the real decode executor, the actual paused callback queue and native image views
on Family Area's Android 10/API 29 hardware. It proves ordinary admission, then
closes with a second decoded photo queued. Both runs execute the same ordered
16 checks with zero harness failures. Baseline and final native checks take
**4.185 and 4.329 seconds**, respectively.

Remote/default-feed ownership was reviewed and uses the same tested queue API;
those complete source flows and the default-feed history were **not** exercised
by the local-folder device fixture. These figures establish resource ownership,
not a PSS, panel FPS or decode-cancellation speed benchmark.

All **3,765 fresh JVM tests across 395 suites pass**, with no failures, errors
or skips. Debug, instrumentation and optimized builds pass; the final combined
build takes **2 minutes 8 seconds**. The independent review finds no ownership,
double-recycle or displayed-pixel correctness issue.

Optimized APK
**8bc77c2c397d2687343c7ced8056f30f8dab3d4d1c0d7ec4da10ce5161113bf4**
is installed with the existing signing certificate. Installation and preservation
checks complete at **18:51:09.594 UTC**, **10 minutes 47.594 seconds** after the
unchanged **18:40:22 UTC** issue start.

All 97 setting values, sources, accessibility services, persistent pin,
brightness policy/value and both face caches match the baseline. The original
test APK and Immortal Dream are restored; no instrumentation or performance
collector remains. The system-server PID is unchanged. Living Area is untouched.

[Before/after evidence](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-classic-cleanup/comparison.json)
includes full baseline/final checks, configuration guards, unit totals, source
hashes and a SHA-256 manifest. The separate seven-style legacy night-motion gap
remains parked, and the user's natural room-light fade check remains pending.
This delivery does not complete the broader goal.
