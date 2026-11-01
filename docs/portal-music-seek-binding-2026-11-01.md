# Family Area held-seek target ownership — November 1, 2026

**Delivered on Family Area.** A seek gesture is cancelled when its player, queue, session/connection, track or duration changes. Continued movement and release from that old gesture cannot become a seek for the new target. A fresh gesture works. Ordinary title/progress updates preserve the existing gesture. No backend or Home Assistant installation is involved.

| Native check | Original | Corrected |
|---|---:|---:|
| Assertions, same ordered set | 18 failures / 74 | 74 / 74 pass |
| Generated scenarios, dark and light | 10 | 10 |
| Player/queue/duration old releases accepted by the rendered-command guard | 6 | 0 |
| Normal and title-only releases accepted | 4 | 4 |
| Fresh gestures after changes | 10 accepted | 10 accepted |
| Held draft after changing player or queue | 64.425% | 12.5%, authoritative position |
| Old stream after a further MOVE | 75.192% | remains at authoritative position |
| Duration-only update from 240 s to 300 s | old fraction reused | 10%, authoritative 30 s position |
| Isolated exceptions | 0 | 0 |
| Native fixture work | 8.563 s | 8.783 s |

## Implementation

The old `MusicProgress` draft was remembered by source and track only. Recomposition could keep the old pointer stream and draft while updating its release callback to a new rendered snapshot. The existing snapshot guard would then accept the old gesture as a fresh command for that new target.

The whole seek control now sits inside a Compose key containing source, local-session token, control player, queue, presentation revision, track and duration. Removing the old control cancels its pointer handling and discards its draft. This replaces one UI leaf, not the player controller, artwork or playback session. Title, artist, elapsed-position and clock-anchor updates are excluded from the key. No timer, observer, bitmap owner, setting or provider is added. No FPS/CPU saving is inferred.

`musicRenderedActionIsCurrent` also rejects a seek whose rendered duration differs from the current track duration. That protects a late callback at the command boundary. A focused JVM red run reproduces **one failure / 15 tests** in the new duration regression; the full fresh final gate passes **3,765 tests / 395 suites**, zero failures/errors/skips. The first eight-case native run remains privately recorded (8 failures / 52 checks); the expanded baseline and final run use the same 74 labels.

## Native evidence

The debug-only host uses generated paused data, the production UI and production rendered-command guard. MotionEvents go through the Activity's native touch dispatch while the injected touch remains down. The test changes only target identity or duration, sends another MOVE, then releases. It also verifies unchanged binding, title-only updates and a fresh gesture after every case. Both palettes use real **2160 × 1440 Android 10** windows. No UiAutomation observer, real controller, provider connection or audio output is created.

Window pixels corroborate the slider reset. The original painted extent moves **648 → 705 px** after continued movement on the new target, in both palettes. The corrected extent moves **648 → 377 px**, returning to the new authoritative position. See [pixel proof](../mocks/portal-premium-player-20261030/evidence/family-music-seek-binding/pixel-proof.json). This establishes native presentation, not physical-finger routing, panel FPS, source acknowledgements or audible playback. Local-session-token/track/reconnection keys are implemented; this native matrix specifically exercises player, queue and duration changes.

## Build, deployment and restoration

The first full build failed during dex conversion because an early `return@key` inside Compose's inline block generated an invalid non-local-return method. The position guard now sits before that block. The repaired fresh debug/performance/instrumentation builds and all unit tests pass in **4m 11s**. Native assertions run on the debug build; optimized-artifact identity/restoration are checked separately.

Optimized APK `2c7dd41302484319965f47f2eb8046e76ed704eed05fa93e82b06d7d5627cc41` was verified installed over wireless ADB **17 minutes 45 seconds** into the original **16:32:39–17:02:39 UTC** slot. The deadline was never reset. All original settings, sources, face-cache bytes, accessibility, pin, brightness policy, test APK and Dream are restored. No instrumentation or performance collector remains active; the system-server PID is unchanged; owned device evidence was removed. Living Area was untouched. Household data, tokens and rollback APKs remain outside Git.

[Original assertions](../mocks/portal-premium-player-20261030/evidence/family-music-seek-binding/baseline-checks.json) · [Corrected assertions](../mocks/portal-premium-player-20261030/evidence/family-music-seek-binding/checks.json) · [Unit gate](../mocks/portal-premium-player-20261030/evidence/family-music-seek-binding/unit-gate.json) · [Restoration proof](../mocks/portal-premium-player-20261030/evidence/family-music-seek-binding/verification.json).

Eight independent [post-restoration health checks](../mocks/portal-premium-player-20261030/evidence/family-music-seek-binding/post-health.json) pass, including optimized artifact identity, original Dream, unchanged settings/sources/system-server PID and no active instrumentation or performance collector.

The natural-light fade/comfort check remains unavailable. The black Dream-to-Home handoff and prior playing-control observer matrix remain parked under their expired caps. Provider/audio installation and wiring remain deferred. The full platform goal remains incomplete.
