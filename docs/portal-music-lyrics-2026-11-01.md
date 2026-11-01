# Music lyrics following — Family Area

**Delivered:** when timed lyrics are supplied, the player keeps the active line
visible. Dragging pauses automatic following; **Follow lyrics** returns to the
current line. A different source or track starts following again.

The original issue slot was **14:38:50–15:08:50 UTC, November 1**.
The optimized installation and restoration were verified at **14:59:50 UTC**,
**21 minutes** into that slot. The 30-minute cap was not reset.

## Implementation and hardware budget

The production change is confined to `MusicPlayerUi.kt`. It reuses the existing
shared progress state and lazy lyric list. A derived active-line change drives
one cancellable scroll; there is no second playback clock, continuous scroll
poll, new source request or artwork decode. The first scroll waits for an
actual measured viewport through a terminating `snapshotFlow.first`.

Actual `DragInteraction.Start` events stop following, including a short
gesture whose start/stop might otherwise collapse into one observed state.
The coroutine respects both `LocalApprovedLauncherPageActive` and the Activity's
`STARTED` lifecycle. Hiding or removing the pane cancels its scroll work.
Source/track keys scope the list and follow preference; ordinary immutable
snapshot replacements preserve manual browsing.

The follow control always occupies footer space, so it does not appear over
the list or insert a new row when browsing starts. Existing typography, palette,
button shape and touch-target conventions are reused.

This remains a lazy list on Family Area's **QCS605 / Adreno 615 / Android 10**
hardware, with no additional image owner or ticker. The checks below establish
presentation and guarded interaction behavior; they do not establish panel FPS,
CPU savings or optical scrolling comfort.

## Evidence

| Check | Result |
| --- | --- |
| Original production component, owned native baseline | **12 failures / 42 assertions**, zero isolated failures |
| Baseline Window captures | **6**, including off-screen active lines 45, 55 and 35 in both themes |
| First implementation check | **82/88 passed**; six semantics-reader overlap failures retained |
| Final Family Area interaction/layout check | **88/88 passed**, **23.121 seconds** |
| Final Window PixelCopy captures | **10**, each **2160 × 1440** |
| Independent active-line foreground pixels | All **8** expected visible states pass, **1,692–1,910 pixels** per active label |
| Intentional manual-browsing states | **2/2** keep the current line off-screen without moving the browsed viewport |
| Fresh JVM gate | **3,761 tests / 394 suites**, zero failures/errors/skips |
| Debug, optimized, Android test builds plus fresh JVM gate | Passed, **4 minutes 8 seconds** |
| Final test-reader rebuild | Passed, **30 seconds** |
| Final native workflow including restoration | **66.712 seconds** |

The same production `MusicPlayerContent` renders 80 generated lyric lines in
one owned debug Activity per palette. PAUSED, coherent metadata provides
deterministic position updates. The native check exercises:

1. Open the actual Lyrics tab at line 45, then update to line 55.
2. Drag the real lazy list. Following stops and the return button becomes active.
3. Update to line 5 while browsing: the visible row coordinates remain unchanged.
4. Press **Follow lyrics**: line 5 is visible and the following control is disabled.
5. Set the existing page-visibility flag false, update to line 65, and verify
   the viewport stays unchanged. Re-enable it and verify line 65 becomes visible.
6. Change track, then source after further dragging; following resets correctly.
7. Verify placed lyric rows and the footer do not overlap in all captures.

The visibility test exercises the component's supplied page-visibility flag.
It does not prove Home's outer visibility wiring or a real Activity stop/start.
Generated PAUSED input verifies presentation responses, not real provider
timing, audio, playback-command dispatch or natural continuous playback.

### Retained first-attempt failure

The original baseline has six active-line visibility failures and six failures
for the newly required following control. Those are assertions about one panel
gap and its control, rather than twelve separate product bugs.

The first implementation rendered the correct active lines and passed all
following/browsing/reset checks. Six overlap assertions failed because the
reader included measured, retained lazy-prefetch nodes that were not placed.
For example, it reported line 56 at **[1156, 440, 2133, 481]** while the actual
Window showed only the correctly spaced lines 39–55.

The test reader now requires public `LayoutInfo.isAttached`, `isPlaced` and
non-deactivated state through its layout ancestors. All **88 original assertions**
remain. The production implementation is unchanged between these two checks;
the reader correction does not disable or relax overlap acceptance.
Both failed reports and the
[first restoration receipt](../mocks/portal-premium-player-20261030/evidence/family-music-lyrics/first-attempt-verification.json)
are retained.

## Installation and restoration

Installed optimized APK:
**f6e8084171b23d60b132064dd2631d483072bcc06e1b15002942d2201f2e9547**.
Physically tested debug APK:
**eca080426223c177722d2f0523b4e524f815e02fd1f83dee6940d06338599113**.
The original-component baseline debug APK is
**2de15dc2e0e1309eada768a9635ef8503b66e56c29aac2038bbedb51b9d2b59a**.
The signing certificate matches the previous installed build.

All **97 settings**, photo-source records, byte-identical face cache,
accessibility configuration, persistent pin, font and brightness policy are
preserved. Raw brightness varied under the unchanged automatic-brightness mode;
the policy/restoration invariant passes. The original Dream and test APK are
restored; the system process is unchanged, instrumentation and the collector are
inactive, and owned files/reports are removed. Living Area was not touched.

These are debug-host component checks. The optimized APK is installed and its
identity verified, but its player was not itself instrumented. Credentials,
household configuration and rollback APKs remain outside Git.

[Native assertions](../mocks/portal-premium-player-20261030/evidence/family-music-lyrics/checks.json),
[independent pixel checks](../mocks/portal-premium-player-20261030/evidence/family-music-lyrics/geometry.json),
[baseline failures](../mocks/portal-premium-player-20261030/evidence/family-music-lyrics/baseline-checks.json),
[first attempt](../mocks/portal-premium-player-20261030/evidence/family-music-lyrics/first-attempt-checks.json)
and [restoration receipt](../mocks/portal-premium-player-20261030/evidence/family-music-lyrics/verification.json)
support the delivery. [The native comparison gallery](../mocks/portal-premium-player-20261030/family-lyrics-review.html)
shows before/after, both palettes and manual browsing.

Implementation references:
[Compose lazy-list scrolling](https://developer.android.com/develop/ui/compose/lists),
[LazyListState interaction source](https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/LazyListState)
and [public layout placement](https://developer.android.com/reference/kotlin/androidx/compose/ui/layout/LayoutInfo).

Natural room-light fade comfort and the broader performance goal remain
incomplete. Provider/backend/audio wiring remains deliberately deferred.
