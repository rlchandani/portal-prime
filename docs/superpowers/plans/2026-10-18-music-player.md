# Native music player implementation plan

> **For agentic workers:** execute bounded tasks with test-first behavior changes and independent final review. Preserve the approved scope and each file's write ownership.

**Goal:** Deliver the approved native music player with verified functionality, performance and UI quality.

**Architecture:** Reuse existing media-session/MA/Snapcast and photo paths. Add a capability-aware shared controller and native presentation; keep state, artwork, protocol and UI responsibilities separate.

**Tech stack:** Kotlin, Jetpack Compose, existing Android media sessions and MA WebSocket transport, JVM JUnit tests. API 24 minimum; validate Portal+ API 29.

**Spec:** `docs/superpowers/specs/2026-10-18-music-player-design.md`

## Global constraints

- Branch `codex/portal-music-player-20261018`, accepted base `69fd617bbde009e0a7982dba1443b392529c77c9`.
- No new audio protocol or bundled server; no account connection/reconnection to GitHub.
- Scalar settings through the registry; no direct UI/route preference writes.
- Secrets and private runtime payloads must not enter Git, logs or test artifacts.
- All production source uses Starbright Lab/MIT headers.
- Use Family Area for runtime proof; Living only after final accepted validation.
- Every unresolved investigation/fix capped at 30 minutes including cleanup.

## Review focus

- Stale source/track artwork and queue responses cannot replace the selected track.
- Unknown/offline capabilities never enable unsupported commands or fabricated live data.
- Large/malformed artwork and protocol messages cannot cause unbounded allocation.
- Calls and user pause/source changes must prevent unintended music resumption.
- UI hide/reveal and reconnect must not create additional audio sessions/listeners.

### Task 1: Capability/state contract

Files: create `MusicPlayerState.kt`, `MusicPlayerStateTest.kt`.

Interfaces: immutable `MusicPlayerSnapshot`, `MusicCapabilities`, `MusicTrack`, `MusicQueueItem`, `MusicLyricLine`, `MusicRoom`; pure policy for command availability, track identity and elapsed-position estimation.

- [x] Write tests for live/local/offline/busy capabilities, elapsed-position clamping, source identity and lyric timing.
- [x] Run the focused test and observe expected failure.
- [x] Implement the state/policy contract without Android/network dependencies.
- [x] Run focused tests; 10 passed, 0 failures; UI/controller will consume the explicit capability contract.

### Task 2: Bounded artwork

Files: modify `MultiRoomArt.kt`; create `MusicArtworkPolicy.kt`, `MusicArtworkPolicyTest.kt`.

Interfaces: `MusicArtworkPolicy.readBounded(InputStream, Int): ByteArray`, sampling calculation; preserve `MultiRoomArt.fetch(String): Bitmap?`.

- [x] Test oversized/unknown-length/chunked/truncated responses and extreme dimensions before implementing.
- [x] Enforce caps during reads, bounded header/frame parsing and sampled decode; preserve existing card behavior.
- [x] 39 artwork policy tests passed; HTTP/local loading remains off main. Native memory proof is a separate pending gate.

### Task 3: Native source controller and MA adapter

Files: create `MusicPlayerController.kt`, `MaMusicClient.kt`, `MaMusicProtocol.kt` and corresponding tests; modify existing source hooks only as required.

Interfaces: controller observes immutable snapshots and dispatches capability-checked typed commands; MA client publishes version/auth/connection and correlated replies/events.

- [ ] Test correlation, source replacement, stale events, command acknowledgement and reconnect cleanup.
- [ ] Reuse media-session transport; expose real local state with supported capabilities.
- [ ] Add bounded MA player/queue/group/lyrics mapping with stable IDs and explicit failure states.
- [ ] Prove current MA API against server once setup exists; never guess unsupported contracts.

### Task 4: Native player in Home

Files: create `MusicPlayerUi.kt`, `MusicPlayerPhotoPanel.kt`; modify `HomeActivity.kt` entry/presentation wiring.

Interfaces: full/compact/photos presentations consume the controller; presentation changes never instantiate playback.

- [ ] Open within existing HomeActivity; preserve dock/navigation, palette and settings return.
- [ ] Add queue/lyrics/player selection, clear unsupported/error states and bounded lists.
- [ ] Isolate progress updates and shared sampled artwork.
- [ ] Build debug and compare all approved mock states on Portal dimensions.

### Task 5: Settings and remote parity

Files: existing settings domain/spec infrastructure, `RemoteHtml`/Fleet routes, narrowly scoped music configuration; separate companion branch later.

- [ ] Add registry-backed preferences and validated rich connection/player actions.
- [ ] Test apply/schema completeness, typed command validation and authenticated remote controls.
- [ ] Verify mobile layout and Android companion parity without touching frozen companion work.

### Task 6: Server setup and Family proof

- [ ] Resolve Music Assistant host and safe deployment access.
- [ ] Configure compatible MA/server/player/provider path; validate actual audible output.
- [ ] Run real queue/transport/group/reconnect/call tests and 30 lifecycle cycles.
- [ ] Measure p95 input/confirmation latency, frame timing, memory/cache bounds and listener growth.
- [ ] Record results and deficiencies against `docs/music-player-goal.md`; fix within per-issue cap.

### Task 7: Review, acceptance and rollout

- [ ] Run relevant JVM tests, full unit gate and `:app:assembleDebug`; validate real signed rollout artifact.
- [ ] Request one independent review of the integrated branch and fix substantive findings.
- [ ] Confirm Family acceptance, retain rollback, deploy Living and perform a bounded smoke test.
- [ ] Mark complete only when required functionality, performance and UI gates have evidence.

## Progress

Baseline: 2,969 JVM tests passed, 0 failures/errors/skips. Latest native checkpoint: 3,085 JVM tests passed, 0 failures/errors/skips; debug assembly green. Full/compact/real-photo setup-state UI verified on Family. Authenticated MA playback, performance, settings/remote integration and rollout remain incomplete.
