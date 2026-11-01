# Music browsing continuity — Family Area

**Delivered:** Queue and Players retain their browsing position across tab
changes and full → compact/photo → full transitions. A new queue starts at
the top without resetting Players. A different exposed source resets both.

The original issue slot is **16:00:52–16:30:52 UTC**, November 1.
Family Area's optimized installation and restoration were verified at
**16:11:53 UTC**, **11 minutes 1 second** into that slot. The cap was not reset.

## Root cause and change

The selected music tab was already owned by `MusicPlayerContent`, above the
layout branch. The queue/player `LazyListState` objects were owned by the
conditional lists themselves. Removing a pane for another tab or layout
discarded those states; returning recreated a list at its default position.

The production patch is confined to
[MusicPlayerUi.kt](../app/src/main/java/com/immortal/launcher/MusicPlayerUi.kt).
Two states now live beside the retained tab state, before the layout branch.
Workspace and side-panel functions pass the appropriate state to each lazy list.

| Viewport | Exposed logical identity |
| --- | --- |
| Players | Source ID, local session token, control player ID |
| Queue | Those same fields, plus queue ID |

Track, position, playback and ordinary metadata are excluded. The existing
command `presentationRevision` is also excluded: it advances on queue changes,
which must not reset Players browsing.

This preserves UI preference within the existing player content lifetime.
Dismissal/removal still releases it. A connection recreated with the same
exposed logical identity retains browsing. Intermediate cleared snapshots
that temporarily remove source/target identity can reset it; provider
reconnection sequences were not accepted by this silent component check.
Playback ownership, command guards and host observers are unchanged.

## Hardware and performance boundary

Family Area is Android 10 / API 29, QCS605 / Adreno 615 v2, 2160 × 1440.
The lists remain lazy and use the existing cached occurrence keys. The
change adds retained scroll state, not a hidden list composition, artwork
owner, source request, polling loop or playback clock.

This is a verified UX continuity correction. It is not a measured frame-time,
CPU, heap or whole-platform performance improvement.

## Before and after

The native helper renders the real `MusicPlayerContent` in one owned debug
Activity per palette. It supplies **80 generated queue items and 80 players**,
PAUSED metadata, blank artwork and a generated photo placeholder.
It creates no player controller, provider connection or audio output.

| Gate | Result |
| --- | --- |
| Original component on Family Area | **18 failures / 49 assertions**, zero isolated failures |
| Original completed palette cases / native captures | **2 / 4** |
| Original native work / restoration workflow | **12.326 s / 51.532 s** |
| Corrected original assertions | **49/49**, same ordered assertion names |
| Corrected completed palette cases / native captures | **2 / 4**, zero isolated failures |
| Corrected native work / installation-restoration workflow | **12.378 s / 49.007 s** |
| Full fresh JVM gate | **3,764 tests / 395 suites**, zero failures/errors/skips |
| Debug, optimized, Android test builds + fresh JVM gate | Passed; **4 minutes 46 seconds**, all 123 tasks executed |
| Independent source review | No concrete blockers |

The 18 failures describe one shared state-lifetime gap and dependent checks
in the same sequence; they are not 18 separate product bugs.

Each theme checks actual pane selection, scrolling to index 30, alternate-tab
return, compact/photo layout return and metadata refresh. Visible generated
row identities and integer vertical coordinates must equal the browsed
reference. The original captures show Queue and Players back at row **0**;
the corrected captures retain queue row **30** and player row **29**
(Players has a leading instruction item).

Replacing the queue resets its viewport while preserving Players. Replacing
the source resets Players and Queue. Public placed-layout semantics exclude
unplaced prefetch nodes. PixelCopy captures the owned Window after actual draw
callbacks. All eight before/after images are **2160 × 1440**.

Scrolling and tab selection use public Compose semantics; layout changes use
the debug host's state setter to drive the unchanged production content call.
This does not establish physical finger routing, provider commands, actual
photo-host integration, continuous playback or delivered panel FPS.

## Installation and restoration

Installed optimized APK:
**ba65b2bea51581392d25d6c7fbe19045a196a56a0d311a8f755647cd87c7a60e**.
The physically tested debug APK is
**7aae786eb0a9b82c38a1d1086e99585e9c8e35ad583b87e60452acfde9456fe8**.
All three built APKs match the existing signing certificate.

All **97 settings**, photo sources, face-focus cache bytes, accessibility
configuration, persistent pin, brightness policy and raw brightness match
the private baseline. The original test APK and Dream are restored, the
system process is unchanged, instrumentation and the performance collector
are inactive, and owned reports/captures are removed from the device.

Native checks ran against debug components from the same production source.
The optimized artifact is installed and verified separately; it was not
instrumented. The native check overlapped the remaining JVM work only after
all three APK assembly tasks and signature checks passed. Both processes
completed successfully.

Wireless ADB targeted Family Area only. Living Area was untouched.
Credentials, household configuration and rollback APKs remain outside Git.
Natural-light brightness comfort is still pending because the user is
unavailable. The previously recorded black Dream-to-Home handoff remains
parked and unresolved; this change does not address it. The broad goal remains
incomplete.

## Evidence

[Before/after native gallery](../mocks/portal-premium-player-20261030/family-viewport-review.html),
[original failures](../mocks/portal-premium-player-20261030/evidence/family-music-viewport/baseline-checks.json),
[corrected assertions](../mocks/portal-premium-player-20261030/evidence/family-music-viewport/checks.json),
[fresh unit gate](../mocks/portal-premium-player-20261030/evidence/family-music-viewport/unit-gate.json),
[restoration](../mocks/portal-premium-player-20261030/evidence/family-music-viewport/verification.json)
and [delivery time](../mocks/portal-premium-player-20261030/evidence/family-music-viewport/delivery-time.json)
support this scoped fix.

[Seven independent checks after installation](../mocks/portal-premium-player-20261030/evidence/family-music-viewport/health.json)
confirm Family identity, optimized artifact/flags, unchanged system process,
inactive instrumentation/collector and restored Dream. The comparison gallery's
[four theme/list combinations](../mocks/portal-premium-player-20261030/evidence/family-music-viewport/gallery-checks.json)
load their native before/after images in the browser.
