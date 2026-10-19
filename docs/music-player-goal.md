# Native music player goal

Status: implementation in progress; not deployed or device-verified.

Approved by the user on October 18, 2026: implement the selected music player as a goal including functionality, performance and quality.

Branch: `codex/portal-music-player-20261018`
Workspace: `~/.codex/worktrees/portal-music-player/portal-prime`
Accepted base: `69fd617bbde009e0a7982dba1443b392529c77c9`

The managed goal tracker rejected a new goal because the older UX goal remains blocked and unfinished. This document records the approved music work without falsely completing that goal. Goal registration remains unresolved.

## Outcome

One native Immortal music experience: full player, compact player and photos beside music; real queue selection, optional lyrics and source-qualified room controls; settings and phone/browser management parity. Preserve the existing audio and photo foundations and accepted navigation behavior.

Music Assistant is not installed. The host-choice question is pending. Suitable server/provider setup and real Family Area playback are dependencies of the complete feature.

## Completion gates

| Area | Required evidence | State |
|---|---|---|
| Clean baseline | Debug unit tests pass | 2,969 tests, 0 failures/errors/skips across 318 suites |
| Native functionality | Real play/pause/skip/seek, queue selection and supported room changes; radio/local unsupported controls absent | Pending |
| Lifecycle | 30 full/compact/photo-return cycles preserve player/track and do not create duplicate audio sessions or listeners | Pending |
| Interruptions | Meta Calls/voice release audio correctly; user-paused playback never resumes automatically | Pending |
| Input response | p95 visible feedback ≤100 ms over at least 30 commands | Target; unmeasured |
| Server response | p95 server-confirmed command ≤500 ms on a healthy LAN; failures reported separately | Target; unmeasured |
| Frame quality | Record frame timing/jank for repeated warm open/minimize/photo-return; target janky frames ≤5%, no blank flashes or theme jumps | Target; unmeasured |
| Memory | Artwork network reads bounded at 8 MiB; sampled decode; bitmap cache ≤16 MiB; no listener/connection growth across reconnects | Pending |
| UI quality | Compare with approved mock at Portal dimensions, light/dark, setup/idle/offline/busy/radio/no-lyrics and long metadata; controls clear the dock | Pending |
| Settings/remote | Registry-backed scalar settings, no direct preference writes from UI/routes; phone/browser parity | Pending |
| Verification | Relevant JVM tests, debug assembly, real Family Area playback, reconnect and interruption evidence; independent branch review | Pending |
| Rollout | Signed accepted artifact; Family first, Living after final validation; rollback retained | Pending |

A passed browser mock check is not native playback or performance evidence. A successful socket write is not server confirmation. Native Sendspin, direct Sonos/AirPlay, a new native library browser, audiobook chapters and acoustic synchronization are excluded from the first release.

Each unresolved investigation/fix is limited to 30 minutes including cleanup. Record a concrete outcome and move to independent work when the limit is reached. Preserve user data and unresolved cleanup checkpoints; never reset the limit by renaming the issue.

## Progress ledger

- Isolated workspace created; new branch based on accepted source.
- Baseline `:app:testDebugUnitTest --no-daemon`: BUILD SUCCESSFUL, 2,969/2,969 tests passed.
- Prior device-test PIDs no longer exist; exact earlier preference restoration remains unverified. No device changes made for this goal.
- Server host question pending; no server/account/provider installation performed.

- Task 1 state contract: 10 new tests RED → GREEN, 0 failures; no native/UI/playback verification implied.
- Task 2 artwork: 38 JVM cases observed RED for the missing policy; implementation delegated with disjoint writes.
- Server readiness: current MA requires authenticated API results; Snapserver 0.30.0 unsupported in current official docs.
