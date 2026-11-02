# Family Area black handoff — second authorized slot

**Incomplete and parked. No runtime change is retained.** Four candidate builds
covering three approaches were installed on Family Area: suppression of the recovered
Activity's close animation, deferred renderer cleanup, and a retained-caller frame observer.
The last recording contains no near-black frames, but the observer finishes through
its one-second fallback. That delay fails the performance acceptance condition.
Original optimized APK **872b6af7…**, production source and configuration are restored.

## Time and scope

The human authorized “Reopen black flash for 30 minutes.”
The unchanged slot is **01:01:54–01:31:54 UTC November 2**, or
**5:01:54–5:31:54 PM PST November 1**. Restoration completes at
**01:24:31 UTC**, **22 minutes 37 seconds** into the slot.
Remaining time is used for documenting and preserving evidence, without another
candidate or a different investigation. Living Area is not targeted.

Family Area runs Android 10 / API 29 on QCS605 / Adreno 615 v2 at
2160 × 1440. Wireless ADB is used. The generated single-photo fixture exercises
an actual OS Dream over a recovered continuation, followed by a software-injected
tap. Credentials and household images remain private.

## Measured outcomes

| Implementation | Encoded frames | Near-black frames | Lifecycle / acceptance |
| --- | ---: | ---: | --- |
| Fresh original-software baseline | 196 | **9** | 30 observations retain the original Home; visual check fails |
| Activity close animation disabled | 174 | **5** | Same Home and process retained; visual check fails |
| Deferred cleanup plus animation suppression | — | — | Final screenshot references are not separable; invalid visual fixture |
| Caller observer combined with deferred cleanup | — | — | Steady-photo readiness fails; no exit recording |
| Caller observer plus animation suppression, with original cleanup restored | 262 | **0** | Same Home retained; **one-second fallback**, rejected |

The baseline contains **15 photo, 172 Home and 9 transition** frames. Its first
complete Home observation is **418 ms** after the ADB tap command finishes.
The animation-only capture contains **4 photo, 165 Home and 5 transition** frames;
the first complete Home observation is **235 ms**. These are individual captures,
not statistically established latency improvements.

The final valid capture contains **89 photo and 173 Home** frames, with no transition
classification or non-Home frame after the first Home. The first complete Home
observation is **1,169 ms**, versus 418 ms in this slot's baseline. Device logs show
`ready=caller-timeout` **1,026 ms** after the exit begins. The required fresh caller
frame signal is not observed; its absence is unresolved. A successful observer
completion was an acceptance condition, so the visual result cannot qualify this
implementation for deployment.

The final recording spans **10.556622 seconds** of encoded timestamps and has a
largest gap of **5.516922 seconds**. The sparse recorder cannot establish optical
black-frame duration, panel FPS or exact tap-to-visible latency. All reported
content counts come from decoding every encoded frame using the same central ROI
and classification thresholds. No physical-finger or optical acceptance is claimed.

## Code and verification

The first candidate uses `overridePendingTransition(0, 0)` after the preview
Activity finishes. Its valid capture still contains black frames.

The second candidate pauses rendering during owner teardown and releases resources
after view detachment. Android's
[ActivityThread destruction sequence](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android10-release/core/java/android/app/ActivityThread.java)
calls Activity destruction before removing the decor view. That ordering informed
the experiment; it does not prove the experiment fixes this Portal's flash.
Four focused cleanup tests and **3,769 fresh JVM tests** pass, but its visual
precondition fails. The helper and tests are archived and removed.

The final candidate observes the retained caller's fresh Window frame while keeping
the Dream's last photo. It has a bounded one-second fallback and cancels all
observers on completion or Dream detachment. Independent tests expose stale
callbacks after pause, destruction and caller replacement; the revised candidate
checks both the current window and listener identity. All **11** focused tests pass,
alongside **3,776 fresh tests / 396 suites**, zero failures, errors or skips.
Both debug and optimized builds pass. The on-device fallback result rejects the
candidate despite these test results. The helper, tests and runtime edits are
archived and reverted.

Two earlier visual cases have non-separable screenshot references. Three subsequent
readiness attempts fail their bounded steady-photo prerequisite before recording
an exit. They remain in the evidence and are not counted as fix passes.
The final valid case allows up to thirty seconds for the photo to paint steadily
for 1.5 seconds. This is fixture readiness, not a change to production startup.
Paused-viewer and ordinary-preview regressions are not run because the primary
candidate fails acceptance first.

## Restoration

The [restoration receipt](../mocks/portal-premium-player-20261030/evidence/family-black-handoff-second-slot/restoration.json)
verifies all **97 settings**, sources, both face caches, persistent pin,
accessibility configuration, test APK and unchanged system process. Brightness
policy and raw brightness match the snapshot. Generated device fixtures are removed,
the original photo-preview Activity is restored, and instrumentation and the
performance collector are inactive.

Installed software is exactly
`872b6af7354b6616320b0081f0525d8c27c0bad29759c3bcc7a02a4a5d76ae10`.
Candidate APKs are verified against the existing signing certificate before
installation. Production Kotlin files match the slot's starting commit.
The local optimized APK also contains the original software. Rejected debug and
optimized APKs are preserved privately; the rejected debug APK is removed from its
normal deployment path.
The issue stays parked under the human's time limit. The broader platform goal,
legacy night-motion issue and previously parked performance work remain incomplete.

## Evidence

- [Baseline visual check](../mocks/portal-premium-player-20261030/evidence/family-black-handoff-second-slot/baseline-visual.json)
- [Animation-only visual check](../mocks/portal-premium-player-20261030/evidence/family-black-handoff-second-slot/animation-only-visual.json)
- [Final caller-gate visual check](../mocks/portal-premium-player-20261030/evidence/family-black-handoff-second-slot/caller-gate-visual.json)
- [Final lifecycle observations](../mocks/portal-premium-player-20261030/evidence/family-black-handoff-second-slot/caller-gate-lifecycle.json)
- [Fresh build and test receipt](../mocks/portal-premium-player-20261030/evidence/family-black-handoff-second-slot/unit-results.json)
- [Rejected caller-gate implementation](../mocks/portal-premium-player-20261030/evidence/family-black-handoff-second-slot/rejected-caller-frame.patch)
- [Bounded outcome](../mocks/portal-premium-player-20261030/evidence/family-black-handoff-second-slot/outcome.json)
- [Evidence hashes](../mocks/portal-premium-player-20261030/evidence/family-black-handoff-second-slot/sha256.json)
