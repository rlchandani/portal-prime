# Gradual night-clock transition — 2026-10-31

The user reported continued brightness jumps after the earlier window fade.
Two source paths explained abrupt changes: the opaque clock view switched
visibility immediately, and the pinned-app overlay still assigned its brightness
directly. This patch coordinates a finite content fade with brightness on
Dream/preview windows and applies the same brightness ramp to pinned overlays.

| Behavior | Previous implementation | Current implementation |
| --- | --- | --- |
| Automatic photos → clock | Opaque black clock appeared immediately | Retained photo fades under the clock over 3 seconds |
| Automatic clock → photos | Clock disappeared immediately | Clock fades out over 3 seconds, then photo playback resumes |
| Brightness on Dream/preview | 1.8-second linear-space fade, suppressing changes below 1/255 | 3-second perceptual power-law approximation, preserving smaller float commands near darkness |
| Brightness over a pinned app | Immediate explicit brightness assignment | Shared finite brightness ramp, with captured-overlay ownership guards |
| Tap to leave | Direct exit | Direct exit preserved; departing fades cancel without delaying navigation |
| Photo identity / user pause | Retained | Retained; no photo reload added |

The clock draws its translucent black cover and text directly on its existing
Canvas. It does not allocate a screenshot or a separate full-screen alpha bitmap.
One uninterrupted content transition schedules at most 188 updates; one
brightness ramp at most 91 ticks. Both stop at completion and cancel on hidden
or departing hosts. The settled clock retains its minute-boundary refresh.
Live reversal starts at the current blend/brightness. Disabling night mode before
its first frame releases the suspended photo. A surviving lost-pin recovery
panel restores brightness rather than retaining a night override.

## Verification

- Full JVM gate: **3,615 tests / 381 suites**, zero failures, errors or skips.
  Includes six transition and 15 brightness tests.
- Debug and optimized performance builds pass; final combined build took
  **88 seconds**.
- [Native API 34 checks](../mocks/portal-premium-player-20261030/evidence/native-night-crossfade/checks.json):
  **430 passed / zero failed**, **83.349 seconds**. Real registry updates,
  emulated light-sensor input, native rendered video, actual window attributes,
  same process/activity/window, unchanged photo ROI and retained user pause.
- Recorded brightness progression: **2.961 seconds down / 2.996 seconds up**,
  with **38 / 30 distinct intermediate values**. These are sampled window
  commands, not physical backlight readings.
- Rendered content: **22 intermediate frames in each direction**, sampled at
  12 fps; the 10–90% intermediate range spans **1.75 seconds** in each direction.
  No intermediate bright/black flash in the tested probe. An independent
  endpoint-trim recheck confirms the same result before later Home navigation.
- [Family delivery](../mocks/portal-premium-player-20261030/evidence/family-night-crossfade/verification.json):
  optimized APK `f602ba024021eb0a8c5c36d1b3478c07a02fd0aacb765328bca1e1574bcbc517`,
  **25,842,558 bytes**, independently read back after installation; signing key
  unchanged, all **97 settings** and photo-source/screensaver configuration
  unchanged. Upload bytes reclaimed, performance collector inactive, zero listeners.
- Eight emulator preference files restored byte-for-byte and synced; the owned
  forward was removed, the owned recording was removed from Android, and the
  owned emulator exited. The other emulator was untouched.

## Limits

Family Area has Fleet access but no available ADB connection. This delivery
does **not** claim physical API 29 fade comfort, panel FPS, or rendered acceptance
of the pinned-overlay path. Android 9/10 exposes the selected system brightness
but not a public measurement of the Portal's actual adaptive backlight. Restoring
Android's default brightness policy can therefore still depend on firmware.
The finite software transitions and native API 34 pixels are verified; the
earlier human comfort check predates this change.

Android documents why brightness changes are more noticeable near darkness:
[Getting screen brightness right for every user](https://android-developers.googleblog.com/2018/11/getting-screen-brightness-right-for.html).
This implementation uses its own power-law approximation and no hidden Android
brightness APIs. The wider performance/music goal remains incomplete.
