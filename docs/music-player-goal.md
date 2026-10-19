# Native music player goal

Status: active managed goal; implementation in progress. Family Area has a verified native UI checkpoint in its existing debug package; real playback and final deployment remain pending.

Approved by the user on October 18, 2026: implement the selected music player as a goal including functionality, performance and quality.

Branch: `codex/portal-music-player-20261018`
Workspace: `~/.codex/worktrees/portal-music-player/portal-prime`
Accepted base: `69fd617bbde009e0a7982dba1443b392529c77c9`

The initial goal registration was rejected because the older UX goal was unfinished. The user ended that older goal, and the music goal was successfully registered as active on October 18, 2026. Functionality, performance and quality gates below remain required.

## Outcome

One native Immortal music experience: full player, compact player and photos beside music; real queue selection, optional lyrics and source-qualified room controls; settings and phone/browser management parity. Preserve the existing audio and photo foundations and accepted navigation behavior.

Music Assistant is not installed. The user chose YouTube Music as the provider and requested help choosing the server host. Synology is the first recommendation, conditional on model, Container Manager support and spare RAM. Premium/account eligibility and the Home Assistant host have been requested. YouTube Music is a server-side provider: Google cookie/token material must not enter the Portal app or chat. Suitable server/provider setup and real Family Area playback remain dependencies.

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
- Prior device-test PIDs no longer exist; exact earlier preference restoration remains unverified. This does not prove earlier preference restoration; subsequent music UI installation is recorded below.
- Server host question pending; no server/account/provider installation performed.

- Task 1 state contract: 10 new tests RED → GREEN, 0 failures; no native/UI/playback verification implied.
- Task 2 artwork: 38 JVM cases observed RED for the missing policy; implementation delegated with disjoint writes.
- Server readiness: current MA requires authenticated API results; Snapserver 0.30.0 unsupported in current official docs.

### Native implementation checkpoint (October 18, evening)

- Native full/compact/photo layouts compile inside the retained Home host; Music opens from Home/Apps without adding a player Activity.
- State ownership, local advertised transport mapping, stale-source generations and authoritative command confirmations have JVM coverage. Commands additionally bind to the original Android session token and track.
- Native Family debug UI checked: full setup state renders; covered Home content is now absent from accessibility. Initial legacy Shuffle startup and a native calendar assumption were found and repaired. The repaired Photos layout displayed an actual local image with no own-process fatal/uninitialized-property exception in the scoped log check.
- Music photos project to native Whole photo with still images only. This does not write the saved mode/source/video choices. No ambient audio, camera gestures, clock/calendar fetch loop or dream navigation is started by that embedded host.
- Independent review identified four concrete findings (calendar initialization, video audio focus, session identity, local URI art); all have patches and an independent scoped code recheck found no remaining problem in those four fixes. Parent full tests/build and photo-device evidence passed; this is not a complete branch review or real playback proof.
- Bounded artwork policy's 39 cases passed in the first complete test run. That run had four Music Assistant protocol failures, subsequently repaired by its worker; parent rerun passed all 3,085 tests across 326 suites with zero failures/errors/skips and debug assembly successful.
- Current MA adapter is pinned to released 2.10.7 / schema 65 / models 1.1.205. It is a pure parser/command builder, not a connected MA backend. Server socket/auth/event lifecycle integration, settings/remote parity and real music/room control remain open.
- Local artwork now has bounded content/file/resource URI resolution without a second bitmap cache; denied provider access keeps a placeholder.
- Hardware sanity check: Family available RAM 632,888 KiB (~618 MiB), total 2,848,792 KiB. This is a momentary reading, not a memory-growth result.
- APK SHA-256 `5b2bb979a38ae6c012613c44b72b798db1706307d0ecca5e2f0bbfb5a6e905b7` updated Family's existing `com.immortal.launcher.debug` package without clearing data. Package inventory proved there is no separate `com.immortal.launcher` release installed; a prior assumption that the packages were side by side was incorrect. No Music Assistant server, provider or account has been installed. Earlier unrelated preference-restoration proof remains unresolved; this checkpoint does not claim to resolve it.

### Verified native checkpoint and remaining work

- Full setup and compact layouts rendered at 2160 × 1440. Compact controls clear favorites and dock; covered Home widgets are absent from full-player accessibility.
- Photos rendered an actual local still image using the embedded native engine. This is photo-host proof only; no music was playing.
- Music settings opens the existing multi-room settings page. Its Back follows settings history before exit. Direct Music Back closes the player and shows Home.
- After checks, returned to the installed default debug Home without the Music launch extra. The secure screensaver component matched the precheck (`com.immortal.launcher.debug/com.immortal.launcher.PhotoDreamService`); no claim about restoration of earlier unrelated preferences.
- Screenshots and nonsecret test receipts: `/private/tmp/portal-music-native-check/latest-*`. Private photos are not copied into the repository.
- Native command latency, jank, playback, two-room audio, authenticated reconnection and 30 live layout cycles remain unmeasured. Bounded artwork tests do not prove runtime memory growth. Larger cached-cover reuse is deferred unless a measured issue warrants it.
- Authenticated persistent MA client and registry/native connection fields are now being implemented in disjoint worker scopes.
- Current authoritative YouTube provider page: https://www.music-assistant.io/music-providers/youtube-music/ — Premium required, cookie/PO-token setup, no lyrics from this provider, unofficial/best-effort integration. Validate the PO-token version against the actual deployed MA release; do not blindly use a stale documentation version.
