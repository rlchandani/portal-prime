# Family Area black screensaver handoff — reopened November 1

The [second authorized slot](portal-black-handoff-second-slot-2026-11-02.md)
supersedes this handoff: builds succeed, but no candidate meets device acceptance.
Original software and configuration are restored within that fresh thirty-minute slot.

**Incomplete; no runtime fix is deployed.** The current optimized software reproduces
six near-black encoded frames when an OS Dream retires a recovered screensaver
continuation and reveals Home. A proposed Activity-transition change was prepared,
but its build failed because the Mac ran out of disk space. The user interrupted
the turn; the next continuation arrived after the unchanged thirty-minute deadline.
The candidate was never installed or verified.

## Current-device evidence

Family Area is Android 10 / API 29, QCS605 / Adreno 615 v2, 2160 × 1440.
The installed baseline APK is
`872b6af7354b6616320b0081f0525d8c27c0bad29759c3bcc7a02a4a5d76ae10`.
The fixture uses one generated photo, Gallery print and a real SystemUI Dream,
with a software-injected tap. No household photo or credential is published.

| Check | Result |
| --- | --- |
| Interrupt owned Dream without a touch | `REDREAM`; recovered continuation appears |
| Tap Dream above that continuation | One exit; continuation retired; no subsequent recovery |
| Home lifecycle | Original Home identity and process retained; one live Home in all **30** observations |
| Observed stable state | **9.269 seconds**; first complete Home observation **300 ms** after the ADB tap command finishes |
| Fully decoded recording | **16** frames: **4 photo, 6 near-black, 6 Home** |
| Content after first Home | **0** non-Home frames |
| Independent activity parser | **30/30** observations corroborate the same retained Home and absent continuation |

The recording spans **3.990011 seconds** of encoded timestamps, with a largest
gap of **2.815256 seconds**. The first Home timestamp is **0.925422 seconds**.
These sparse encoded frames establish a visual defect, not optical black-frame
duration, delivered panel FPS or tap-to-visible latency. Classification uses the
same central ROI and thresholds as the preceding
[warm-return check](portal-warm-dream-return-2026-11-01.md).

## Prepared change and build failure

The [archived candidate](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-black-handoff-reopened/unverified-candidate.patch)
would call `overridePendingTransition(0, 0)` immediately after
`PhotoFramePreviewActivity.finish()`. The existing Dream suppresses its own window
animation, while the recovered Activity still inherits Android's default close
transition. That is a plausible separate source of the visual gap. Source review
confirms the proposed API call is supported on API 29; it does not prove causation
or a successful visual fix. The archived comment contains that original hypothesis.

`./gradlew :app:assemblePerformance :app:testDebugUnitTest --no-daemon`
failed in `:app:minifyPerformanceWithR8` with
`java.io.IOException: No space left on device`. No fresh complete unit-test pass
is claimed. No candidate installation, candidate recording or performance
improvement is claimed. The original runtime source is restored.

Old private generated captures were preserved on the external volume, with
symlinks maintaining their existing paths and private directory permissions.
A subsequent disk read reports **44 GiB available**. The build was not restarted
after the issue deadline.

## Deadline and restoration

The explicit slot was **23:58:52 UTC November 1–00:28:52 UTC November 2**.
After the user interruption, the next continuation was observed at
**00:50:20 UTC**. Restoration completed at **00:51:25 UTC**.
This is after the deadline; it is cleanup, not an extension of the fix attempt.

The [restoration receipt](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-black-handoff-reopened/restoration.json)
verifies all **97 settings**, photo sources, both face caches, persistent pin,
accessibility configuration, system process, original APK and test APK.
The original photo-preview Activity is restored and generated device fixtures
are removed. Instrumentation and the performance collector are inactive.
Automatic-brightness policy is unchanged; its raw brightness register differs
under automatic mode and was not forced. Living Area was not targeted.

The issue remains parked under the user's thirty-minute rule. To accept the
candidate, a newly authorized slot must build it successfully, record this same
handoff on Family Area, and verify paused-viewer and ordinary preview exits.
No result here closes cold startup, physical-finger routing, optical timing,
the separate legacy night-motion gap or the overall platform goal.

## Evidence

- [Baseline visual classification](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-black-handoff-reopened/baseline-visual.json)
- [Every decoded frame](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-black-handoff-reopened/baseline-encoded-frames.json)
- [Lifecycle check](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-black-handoff-reopened/baseline-lifecycle.json)
- [Independent activity check](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-black-handoff-reopened/independent-baseline.json)
- [Failed build](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-black-handoff-reopened/build-failure.json)
- [Bounded outcome](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-black-handoff-reopened/outcome.json)
- [Evidence hashes](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-black-handoff-reopened/sha256.json)
