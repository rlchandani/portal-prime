# Family Area music photo continuity

**Fixed and deployed:** the real Photos layout retains its controller, paused
photo and pause choice through Full and Compact music layouts.

| Physical check on Android 10 / API 29 | Before | After |
| --- | --- | --- |
| Paused photo region after Full → Photos | maximum channel difference **66**; an in-progress dissolve advanced | **0**, exact RGB match |
| Photo region after Compact round trip | not reached by failing baseline | **0**, exact RGB match |
| Pause held longer than five-second interval | not reached | **0**, exact RGB match |
| Manual photo Next / Previous while paused | not accepted by baseline | both pass |
| Resume preserves fresh dwell, then advances | not accepted by baseline | both pass |

The successful workflow passed **17/17 checks**, captured **16 native frames**
and took **112.282 seconds**. The controller identity remains unchanged through
both round trips. Creation stays lazy in Full, the selected Players tab and
current track remain selected, and the same real photo remains held for the
eight-second hidden interval and subsequent six-second paused hold.
One returned Photos capture was visually reviewed: real photos and music
controls occupy their separate panes without observed overlap.

## Delivered changes

- Native automatic decode completion waits behind explicit Pause. Resume
  delivers that pending photo without skipping it; manual navigation still
  works and cancels an unseen automatic cursor.
- User pause and host visibility independently govern finite dissolves. A
  paused blend stays frozen when the retained photo view is remounted.
- Held pixels participate in the existing 48 MiB shared budget. Cancellation
  and stop release their ownership. Fresh scene dwell is retained on Resume.
- Video Resume leaves its hidden photo layer suspended. Outstanding album
  recovery preserves a later manual request on both success and fallback.

The pending-photo and dissolve tests originally failed **7 and 4 cases**.
The recovery-intent tests additionally failed **2 cases** before correction.
The final gate passed **3,652 tests / 386 suites**, with zero failures, errors
or skips, and debug, Android test and optimized performance builds passed.
Independent review's two findings were corrected and re-reviewed.

## Evidence and restoration

The test-only music fixture now optionally mounts the production
`rememberMusicPhotos` / `MusicPhotoHost` and reports a controller identity.
Synthetic music remains silent; household photos remain private. Its debug
APK was `3632ab4d…`. This tests the real native photo integration on the
Portal, not provider authentication, audio or release-build FPS.

The initial attempt aborted before capture because the focused window was
still being created. The second attempt proved the paused-dissolve defect.
Its first restoration check caught accessibility reconciliation; a separate
recovery restored the original APK, test APK, **97 settings, 22 source fields**
and exact accessibility list. Both original failed receipts remain private.
The final attempt used a bounded startup check and settled reconciliation,
then passed all restoration checks without restarting the system process.

Optimized non-debuggable APK `8cd58604…` is installed on Family Area with the
existing signing certificate. The deployment's initial whole-pin-response
comparison failed on transient foreground diagnostics. The original receipt
is preserved; the subsequent check confirms unchanged persistent pin fields
and exact restored Dream state. No second installation was needed.

See the [sanitized receipt](../mocks/portal-premium-player-20261030/evidence/family-music-photo-pause/verification.json).
Raw photographs, recordings, credentials, profiles and APK backups stay
outside Git. No Living Area deployment occurred.

## Remaining scope

This accepts the tested photo pause/layout path, not every music/photo action
or source. The physical test uses local static photos and silent music; album
recovery is covered by deterministic tests and source review. Two separate
findings remain parked: ignored settings can recreate the music photo host,
and pending hidden SMB startup needs stronger resource ownership.
Natural room-light comfort, cold startup, animated-image residency,
Home/Apps tails and the broader platform goal remain incomplete.
