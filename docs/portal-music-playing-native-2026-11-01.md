# Family playing music controls — bounded result

**One debug-preview defect is fixed and supported by native evidence. The full
eight-case matrix remains incomplete.** Changing volume while the sample player
was playing reset its clock anchor without carrying elapsed position forward.
The observed rewind was **15.112 seconds**.

Source commit **05a127fb** carries the current visible position forward before
handling an action, then stores the same monotonic action timestamp as its new
anchor. Seek and track changes still deliberately replace the position.
This changes `MusicDesignPreviewActivity`, which is excluded from the installed
optimized build. The production music UI/store and provider wiring are unchanged.

The original slot began **12:18:37 UTC** on November 1 and expires **12:48:37**.
The issue was parked, recovery completed and fresh health verified by
**12:41:32**, **22 minutes 55 seconds** into that slot. The limit was never reset.
Family Area retains optimized APK **bfc9430d…**. Living Area was untouched.

## What the hardware evidence establishes

Tests use actual Compose controls, native accessibility ranges and continuous
touch input on **Android 10 / API 29, 2160 × 1440, 180 dpi**, over wireless ADB.
The content is generated artwork and silent sample data. The fixture's
“Music Assistant” label does not represent an installed server or a connection
to Home Assistant.

| Scoped result | Evidence | Result |
| --- | --- | --- |
| Playing seek, dark theme, font 1.8 | Final normal capture | **19/19 assertions** |
| Playing volume, dark theme, font 1.8 | Late native capture, analyzed afterward | **20/20 analysis assertions** |
| Preview volume clock rewind | Native before/after receipts | **15.112 s backward → continued forward** |
| Release-only UI actions in those two gestures | 24 MOVEs per gesture | **48 MOVEs, 2 release actions**, none during drag |
| Held thumb | Native midpoint and sample after more than 2 s | Same draft value while held |
| Larger-text visual review | Two native generated-content images | No row overlap observed |
| Debug/unit build | Fresh unit execution after the clock change | **3,722 tests / 392 suites**, zero failures/errors/skips |
| Full planned matrix | Eight release/cancel cases in both themes | **Incomplete; parked** |

For the corrected volume case, the track position advances from **108.305 s**
before release to **118.814 s** in the released observation, across a **10.222 s**
observer interval. This is consistent with the advancing half-second ticker;
it is not a rewind. The volume draft remains **48.856415%** throughout its
**2.272 s** hold, reaches the endpoint, then confirms **85%** after exactly one
volume callback. Seek callbacks stay at zero.

These are observations of UI state, not provider acknowledgements or audible
playback. Observer intervals include substantial test overhead and are not user
input latency, panel FPS or an optical-smoothness measurement.

![Actual larger-text playing volume screen, generated sample content](https://raw.githubusercontent.com/starbrightlab/immortal/main/mocks/portal-premium-player-20261030/evidence/family-music-playing-native/playing-volume-late.png)

## Failed attempts remain failed

Four original runs are retained separately. Their completed-case counts are
**1, 2, 1 and 1**, with snapshot failures or 20-second observer deadlines.
The second run also records the reproduced volume rewind. The observer was
changed to poll native Play/Pause state, refresh the specific button and avoid
application-wide idle waits for a continuously updating player.

The final volume capture completed after the observation deadline. Its exact
owned JSON and PNG were retrieved only after that observer had terminated.
Offline analysis applies the focused range, clock and action-count checks to
this existing receipt. It sends no new input and does **not** turn the original
run into a pass, register another normal case, or accept the eight-case matrix.
Light-theme reverse gestures and user cancellation remain unverified.
No further observer investigation was continued in this slot.

## Recovery and final state

The final parent's cleanup stopped when its expected detached accessibility
list differed from the live list. Immortal's baseline services are reconciled
at startup. A separate guarded recovery confirms no active instrumentation or
Dream before detaching owned services and restoring the original APKs.

The original parent receipt still records failed restoration. Follow-up receipts
independently establish recovery and cleanup; they do not rewrite that receipt.
The recovery preserves Meta's services and restores the observed baseline
BarWatch/InstallConfirm configuration. The initial literal service list was held
only in the terminated parent, so the final service equality assertion is
against the recovery baseline rather than that unavailable initial string.

Fresh health verifies:

- Original optimized **non-debuggable** APK and original test APK hashes.
- All **97 settings** and **22 source records**, brightness policy and global
  font scale match their captured originals.
- Baseline accessibility matches the recovery snapshot; the system process
  remains unchanged through recovery and subsequent cleanup.
- Original Immortal Dream running, no pin cover, instrumentation or active
  performance collector/listeners.
- All four exact owned snapshot prefixes absent. Eight registered generated
  files left by the failed parent were removed in a cleanup-only install cycle,
  without starting another observer or music test.

## Remaining scope

This delivers a preview clock fix and two focused observations of playing
controls. It does not accept cancellation, both themes, every touch pixel,
all rendered intermediate frames, real audio/providers, speaker groups,
production Home chrome or whole-platform performance.

Physical brightness comfort remains pending because the user cannot test now.
Provider/server wiring remains outside scope. The broader goal is incomplete.

[Sanitized evidence and original attempt summaries](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-music-playing-native/summary.json).
Credentials, configuration snapshots, raw native trees, rollback APKs and the
guarded recovery scripts remain private outside Git.
