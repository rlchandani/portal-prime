# Family Area warm Dream return — November 1

**Lifecycle and paused-photo continuity pass; seamless visual return does not.**
On the unchanged optimized APK, a software-injected tap retires a recovered
screensaver continuation and reveals the original Home once. The recording
also exposes six near-black frames during that handoff. This slot adds
verification tooling and evidence, not an Android runtime fix or a measured
performance improvement.

## Actual hardware checks

Family Area is Android 10 / API 29, QCS605 / Adreno 615 v2, 2160 × 1440.
Wireless ADB drove a real SystemUI `Somnambulator` Dream, rather than opening
the screensaver preview and calling that an OS Dream. Tests temporarily used
an anonymous generated photo and Gallery print, then restored the original
configuration and Dream.

| Case | Actual result | Acceptance |
| --- | --- | --- |
| Dream above deliberately paused photo viewer | Same activity and app process; Paused / Resume retained; photo ROI maximum channel difference **0**; **24** observations over **7.157 s**, with Dream stopped in every observation | Lifecycle / sampled continuity pass |
| Recover an interrupted Dream without app touch | No root touch marker; `REDREAM` verdict; continuation appears | Recovery path pass |
| One tap on Dream above recovered continuation | One user-exit verdict; continuation retired; original Home identity/process retained; **30** observations over **8.767 s**, no subsequent recovery or Dream reappearance | Lifecycle pass |
| Independent activity-dump interpretation | All **54** observations corroborated by a separate parser; one live Home throughout; preview retained only in the deliberate case | Pass |
| Paused-viewer recording | **8** encoded frames over **9.754 s**; all photo, no near-black frames | Encoded-content pass only |
| Continuation-to-Home recording | **14** encoded frames: 4 photo, **6 near-black**, 4 Home; no photo or other content after first Home | **Visual gate fails** |

The first complete Home state was observed **270 ms after the ADB tap command
completed**. That is an OS observation delay, not tap-to-visible latency.
The six near-black frames occur at recording timestamps **0.989756–1.136733 s**,
a **146.977 ms** first-to-last span. First Home follows at **1.153722 s**,
**163.966 ms** after the first near-black frame. Those timestamps neither
establish optical duration nor delivered panel FPS.

## Recording and test boundaries

Both recordings were decoded completely, with decoded-frame count matching
`ffprobe`. Classification uses a 540 × 360 image, central ROI
`[108, 54, 432, 306]`, maximum reference mean channel error 16 and required
class advantage 12. Near-black means at least 99% of ROI pixels have every
channel at most 8. A captured near-black frame was also visually inspected.

Recordings are sparse: the paused clip's largest encoded timestamp gap is
**7.983 s**. The continuation recording ran for 12 seconds but contains only
**1.369 s** of timestamp span, with a largest gap of **0.886 s**. Therefore the
recordings cannot establish continuous wall-time coverage. The separate OS
observations support stable activity state over the longer sampled interval.

The first offline decoding invocation failed because the installed ffmpeg
removed `-vsync`; using `-fps_mode passthrough` resolved decoding. The subsequent
visual failure is preserved rather than relabeled as a decoder failure.

The initial Home test had an invalid precondition: a package-relative debug
activity shorthand failed to launch Home, leaving Android's resolver underneath
Dream. The corrected component is
`com.immortal.launcher.debug/com.immortal.launcher.HomeActivity`.
The default HOME preference was not changed. That initial attempt does not
establish a Home-return defect.

The new [pure parser](https://github.com/starbrightlab/immortal/blob/main/scripts/portal_warm_dream_contract.py) accepts only
current `mResumedActivity` and live `Hist` rows. It excludes historical
references and compares complete package/class identities.
[Ten synthetic tests](https://github.com/starbrightlab/immortal/blob/main/scripts/test_portal_warm_dream_contract.py) pass.
No Android source changed, so the preceding fresh **3,764-test / 395-suite**
gate is retained, not represented as a new run.

## Restoration and time bound

The original issue slot is **15:36:45–16:06:45 UTC**, November 1. The device
was restored at **15:54:20 UTC**, about **17 minutes 35 seconds** into that slot.
There was no APK installation or instrumentation run.

All **97 settings**, source records, face-focus cache bytes, accessibility
configuration, persistent pin, APK/test APK and brightness policy match their
initial state. The system process is unchanged; instrumentation and the
performance collector are inactive; owned device fixtures are removed.
Raw brightness differs under automatic mode, so its previous raw register
was not forced. Original Dream restoration passes.

Installed APK SHA-256:
`4ea7f4e83b9c333fc9554f723723dc1931088725701d032cdd773d2fa0e1e06f`.
Living Area was not targeted. Credentials, household configuration, full
recordings, raw dumps and rollback APKs remain outside Git.

## Evidence and disposition

- [Paused-viewer receipt](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-warm-dream-return/paused-checks.json)
- [Continuation receipt](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-warm-dream-return/continuation-checks.json)
- [Independent parser receipt](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-warm-dream-return/independent-activity-checks.json)
- [Failed visual gate and recording limits](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-warm-dream-return/video-checks.json)
- [Restoration receipt](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-warm-dream-return/verification.json)
- [Twelve independent checks after restoration](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-warm-dream-return/health.json)
- [Attempt history](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-warm-dream-return/analysis-attempts.json)
- [Bounded issue outcome](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-warm-dream-return/outcome.json)
- [Evidence hashes](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-warm-dream-return/hashes.json)

The warm API 29 software-input lifecycle gap is now checked. The black handoff
remains unresolved within this bounded issue slot; no further capture or
speculative runtime change is made here. Physical-finger routing, cold startup,
delivered panel timing and natural room-light fade comfort remain unaccepted.
The user is unavailable for the natural-light check. The broad goal remains
incomplete.
