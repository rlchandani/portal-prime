# Native music player goal

Status: active managed goal; implementation in progress. Family Area has a verified native UI checkpoint in its existing debug package; real playback and final deployment remain pending.

Approved by the user on October 18, 2026: implement the selected music player as a goal including functionality, performance and quality.

Branch: `codex/portal-music-player-20261018`
Workspace: `~/.codex/worktrees/portal-music-player/portal-prime`
Accepted base: `69fd617bbde009e0a7982dba1443b392529c77c9`

The initial goal registration was rejected because the older UX goal was unfinished. The user ended that older goal, and the music goal was successfully registered as active on October 18, 2026. Functionality, performance and quality gates below remain required.

## Outcome

One native Immortal music experience: full player, compact player and photos beside music; real queue selection, optional lyrics and source-qualified room controls; settings and phone/browser management parity. Preserve the existing audio and photo foundations and accepted navigation behavior.

YouTube Music Premium and Home Assistant on Synology are confirmed. The existing host was inspected through its signed-in UI: Home Assistant OS 18.3, Core/Supervisor 2026.10.2. Stable Music Assistant 2.10.7 and YouTube PO-token helper 2.0.1 are installed and report Running. The host choice is the existing Home Assistant OS app system; no NAS-model question is needed. Family's connected Snapcast client is selected and authenticated in Immortal. YouTube Music cookie authorization and actual playback remain pending; Google cookie material stays server-side and must not enter the Portal app or chat.

## Completion gates

| Area | Required evidence | State |
|---|---|---|
| Clean baseline | Debug unit tests pass | 2,969 tests, 0 failures/errors/skips across 318 suites |
| Native functionality | Real play/pause/skip/seek, queue selection and supported room changes; radio/local unsupported controls absent | Pending |
| Lifecycle | 30 full/compact/photo-return cycles preserve player/track and do not create duplicate audio sessions or listeners | Setup-only 30 cycles passed; 43 → 43 threads. Live session/socket gate pending |
| Interruptions | Meta Calls/voice release audio correctly; user-paused playback never resumes automatically | Pending |
| Input response | p95 visible feedback ≤100 ms over at least 30 commands | Target; unmeasured |
| Server response | p95 server-confirmed command ≤500 ms on a healthy LAN; failures reported separately | Failed for measured idle volume: baseline p95 558.3 ms; shorter-poll candidate 587.56 ms, both 30/30 with 0 failures. Poll experiment reverted; further investigation parked |
| Frame quality | Record frame timing/jank for repeated warm open/minimize/photo-return; target janky frames ≤5%, no blank flashes or theme jumps | Failed: baseline 14.25% / p95 57 ms; candidate 20.47% / p95 73 ms. Investigation parked at 30-minute limit |
| Memory | Artwork network reads bounded at 8 MiB; sampled decode; bitmap cache ≤16 MiB; no listener/connection growth across reconnects | Pending |
| UI quality | Compare with approved mock at Portal dimensions, light/dark, setup/idle/offline/busy/radio/no-lyrics and long metadata; controls clear the dock | Pending |
| Settings/remote | Registry-backed scalar settings, no direct preference writes from UI/routes; phone/browser parity | Registry/native/PWA implemented and tested; separate Android companion exposure pending |
| Verification | Relevant JVM tests, debug assembly, real Family Area playback, reconnect and interruption evidence; independent branch review | Pending |
| Rollout | Signed accepted artifact; Family first, Living after final validation; rollback retained | Pending |

A passed browser mock check is not native playback or performance evidence. A successful socket write is not server confirmation. Native Sendspin, direct Sonos/AirPlay, a new native library browser, audiobook chapters and acoustic synchronization are excluded from the first release.

Each unresolved investigation/fix is limited to 30 minutes including cleanup. Record a concrete outcome and move to independent work when the limit is reached. Preserve user data and unresolved cleanup checkpoints; never reset the limit by renaming the issue.

## Progress ledger

- Isolated workspace created; new branch based on accepted source.
- Baseline `:app:testDebugUnitTest --no-daemon`: BUILD SUCCESSFUL, 2,969/2,969 tests passed.
- Prior device-test PIDs no longer exist; exact earlier preference restoration remains unverified. This does not prove earlier preference restoration; subsequent music UI installation is recorded below.
- Initial checkpoint: server host was unresolved; no installation had been performed at that point. The current host/setup outcome is recorded below.

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

### Integrated connection/browser checkpoint (October 18, late evening)

- Persistent MA client, configured API token/selected player, registry-backed fields and native connection UI compile. Legacy MA transport now also requires authenticated token replies. Username/password token minting is unsupported. The later token-only presentation checkpoint below removes these legacy fields from the active UI without erasing saved values.
- One retained music controller is shared across native layouts and authenticated browser reads. UI owners and a renewable remote lease govern its connection; reads capture immutable state on Main and serialize JSON on the HTTP worker.
- `/remote/music` and `/remote/music/command` use the existing paired/fleet auth boundary. Explicit command key/type validation binds source revision, track and group leader. A 202 response means admitted, not confirmed; timed-out mutations report unknown admission and require readback without automatic replay.
- Two scoped independent review findings were fixed and rechecked: settings/source reconciliation at command admission, and uncertain timeout semantics.
- Latest complete JVM gate: **3,199 tests / 335 suites, 0 failures/errors/skips**; debug assembly succeeded. Permanent browser command tests cover 15 meaningful scenarios. Parent full JavaScript/browser results and final artifact are recorded at the next checkpoint.
- Actual Family HTTP check: unauthenticated GET 401; authenticated GET 200 (16.05 ms for one setup-state read); intentionally stale command 409; malformed command 400. Both writes were rejected. This is not command latency, authenticated MA playback or queue/group proof.
- Family installed artifact for the performance repeat: SHA-256 `ac35391ca7096e567d2e1bfeabb0a8d708ad429229ec2b5ca451e1c0913e7f30`. This is a development checkpoint, not a passed final performance release.
- Thirty setup-only full/compact/photos cycles completed in 103.84 s with actual local photos. Candidate: 928 frames, 190 janky (20.47%), p95 73 ms, p99 121 ms; threads 43 → 43, PSS 271,841 → 210,031 KiB. Earlier baseline: 1,326 frames, 189 janky (14.25%), p95 57 ms, p99 109 ms; threads 38 → 37. Multiple integrated runtime changes differ between builds, so this does not isolate retention as a cause. **No frame improvement is claimed.** No MA/audio session was active.
- Photo retention is functionally checked but not accepted as a performance improvement. The issue's 30-minute limit included cleanup; returned to Home and verified the secure screensaver component matched the precheck. No further investigation was performed in that issue window.
- Private local receipts/screenshots live under `/private/tmp/portal-music-native-check/`; private photos and account material are not committed.
- Remaining: source/provider authentication, actual Portal speaker connection/playback, server-confirmed commands/reconnects, Calls/voice behavior, full independent branch review, performance targets, native companion exposure where required, and Living rollout. Goal remains active.

### Current code and server checkpoint (October 18, 23:20 Pacific)

- Complete Android gate: **3,209 tests / 336 suites, 0 failures/errors/skips**, `assembleDebug` passed. Full JavaScript gate: **733 passed / 736 total, 0 failed, 3 opt-in fixtures skipped**. The opt-in music Chrome fixture was separately run and passed at 360/402/600 px in light/dark. It uses synthetic state; it is not audible playback proof. Packaged remote JS/CSS exactly match checked source.
- Two native review findings repaired: callbacks capture an ordinary immutable rendered snapshot and its connection revision, so stale taps cannot silently retarget a new track/session/leader or turn shown Pause into Play; keyed AndroidView ownership recreates the photo view when its controller changes. Ten new native observation tests pass. Independent scoped source recheck: no remaining actionable findings in these repairs, token-only settings or MQTT focus chaining. A full live interruption/branch acceptance review is still pending.
- Active connection UI requires an API token; old username/password values remain persisted but their inactive rows are hidden. No Google-provider credential enters Immortal. One compile failure caught an accidental MQTT focus-declaration removal; declarations were restored and the complete gate passed.
- Existing NAS-hosted HAOS inspected without requiring its model. Stable MA **2.10.7 / schema 65** runs on the confirmed LAN host. Built-in Snapserver **0.34.0** runs with initial FLAC/default buffering and 25% client volume.
- Family Area stock Snapcast client installed, configured and started. Real JSON-RPC status reports **one connected Portal+ client** at the Family LAN address and 25% volume. No track was played. This is transport connectivity proof only.
- A named `Family-Area-Immortal` MA integration token was created through the existing signed-in UI and transferred in memory directly to Family's authenticated registry with host/port/master audio switch. The token was not printed or written to an agent file. Google cookies were not obtained. Provider authentication remains pending; the authoritative Family MA player was subsequently selected and its connection verified.
- Closed only the three research Chrome tabs; the user's original two tabs remain. Returned Family to Home; screensaver component matches the precheck. Server setup ended at its bounded checkpoint rather than expanding into source-login investigation.
- Verified development APK: SHA-256 **97966df1a7ab99c7da5ea9d985aad293bca71617ea39b3f495bf580196ee10be**. Installation evidence is in the private local receipt; this does not signify final performance acceptance. Rollback APK remains retained.
- Open gates: YouTube Music cookie/PO-token source setup, live queue/command API behavior and audible playback, real queue/group actions, reconnect/Calls/voice behavior, runtime artwork/socket bounds, latency and the failed frame-quality target, Android companion exposure and Living rollout. Goal remains active; no final release or Living installation is claimed.


### Live MA API checkpoint

The actual saved integration token authenticated successfully with MA. `players/all` returned available players; the unique Snapcast player matching Family's connected client ID/IP was selected through the existing registry. Immortal's real `/remote/music` state changed **connecting → ready**, with **0 queue entries / 10 discovered rooms / no problem**. Native connected-idle inspection is recorded in private local artifacts. No play/queue/group mutation was sent; this does not establish YouTube authorization, audible output or latency/performance acceptance. Initial standalone probe used numeric IDs and received no correlated reply; switching it to the string IDs already used by the production client produced the verified response. No production protocol change or token output was required.

### Connection safety and source setup checkpoint (October 18, 23:52 Pacific)

- Native **Test connection** reported **Authenticated with Music Assistant ✓** using the saved integration token. The live player remained idle; this is authentication proof, not music playback.
- YouTube helper **2.0.1** is installed and Running. The actual MA 2.10.7 provider form requires **Username / Login Cookie**, with default PO-token URL `http://127.0.0.1:4416`. No Google cookie was obtained, source saved or track played. The empty form was cancelled, only the research tab closed, the original two tabs preserved, and Family returned to Home with its screensaver component unchanged before the source-setup window expired.
- Thirty real Family-only idle volume changes 24% ↔ 25% all succeeded. Baseline p95 admission **27.97 ms**, authoritative readback **558.3 ms**; original 25% restored and other room volumes unchanged. Two direct MA probes had acknowledgment/readback **42.21/45.53 ms** and **4.97/8.77 ms**, motivating the worker poll change from 250 to 100 ms. These measurements do not cover native visible feedback, play/seek or YouTube quality.
- The single shorter-poll repeat completed **30/30**, 0 failures, p95 admission **20.1 ms**, authoritative readback **587.56 ms**, restored 25% and other room volumes unchanged. **No server-confirmation improvement was demonstrated.** Default polling was restored to 250 ms rather than retaining extra wakeups on an unproven performance change; liveness and command safety remain. The 500 ms target is still unmet. Further latency investigation is parked at this bounded checkpoint.
- Full branch review found five concrete issues: browser group-operation inversion, idle populated queue selection, silent idle sockets, inactive audio-ownership state, and disappearing asynchronous browser failures. Group admission now binds the displayed membership; safe correlated terminal results survive subsequent reads; idle sockets use a bounded 30-second verified `players/all` liveness probe without queue-item downloads or command replay. Queue selection and production audio-ownership integration remain open.
- Browser failure/group regressions were observed **RED → GREEN**, with **18/18 music cases** passing. Full JavaScript gate **736 passed / 739 total, 0 failed, 3 opt-in fixtures skipped**; the separate synthetic responsive Chrome music check passed. These are browser contract/UI results, not live audio proof.
- Android gate **3,219 tests / 336 suites, 0 failures/errors/skips**, debug assembly passed. An Android-incompatible `JSONObject.keySet` test call was replaced with its supported key iterator before the successful gate. Packaged control-center JS/CSS match source. Candidate APK SHA-256 **26010deaa4a1eaac18d67e96a66a79c7fbd4b40ce0bfeb122c1d258f92f7fe27**.
- After reverting only the poll interval, the same complete **3,219-test gate and debug assembly passed**. The retained safety build **6504308396b988f6f8be0fb3570635085b15aa7d86ed5808cc885154e751e170** is installed on Family without clearing data. This final checkpoint's latency was not rebenchmarked; no new speed claim is made. Independent scoped reviews found no production issues in the parent group/result changes or client liveness changes; two existing test-contract assertions were corrected and the full gate passed.
- Real Family Wi-Fi interruption, observed through a temporary authenticated USB forward: **offline in 302.59 ms**, **ready 15,672.22 ms after Wi-Fi enable**, including network reconnection. The selected player was preserved, no track started and no pending command remained; Family volume was 25%. Wi-Fi restored, forward removed, returned to Home and screensaver component unchanged. This idle recovery check is not live playback, user-paused recovery or silent-half-open hardware proof; those remain separate gates.
- Private receipts: `youtube-helper-receipt.json`, `youtube-source-form-receipt.json`, `youtube-setup-cleanup.json`, `live-volume-benchmark-baseline.json`, `live-volume-benchmark-poll100.json`, `ma-direct-volume-receipt.json`, `safety-default-poll-install.json`, `live-reconnect-receipt.json` under `/private/tmp/portal-music-native-check/`.
- Goal remains active. Actual YouTube authentication/playback, real queue/group/interruption evidence, runtime bounds, Android companion exposure, the parked failed frame gate and final Living rollout remain required.

### Idle queue selection checkpoint (October 19, shortly after midnight Pacific)

- Reproduced two browser failures: a populated idle queue could not select an item without a current track, and an old row could target the same item ID in a replacement queue. Both regressions are now **RED → GREEN**.
- Authoritative queue identity now travels in the immutable native snapshot, rendered-action guard, native dispatch, worker action, command confirmation and browser protocol. Queue selection requires the observed queue and available item; ordinary play/pause/seek/skip retain their track bindings. Legacy worker callers capture their queue under the existing admission lock.
- A stale queue is rejected before any `play_index` write. Selecting an idle item no longer invents a current track. A same-item ID in another queue cannot confirm the original command.
- Complete Android gate: **3,226 tests / 336 suites, 0 failures/errors/skips**, debug assembly successful. Full JavaScript: **738 passed / 741 total, 0 failed, 3 opt-in fixtures skipped**; separate responsive synthetic Chrome check passed. Music-specific browser cases **20/20**. Packaged JS matches source.
- APK SHA-256 **811eafca0b9236d8776404afca76f09b94ce18a0edc03dd0bdeafea58ed302a9** installed on Family without clearing data. Independent scoped queue review: no actionable findings. Real connected-idle readback contains an authoritative queue identity; a deliberately different queue request returned **409/stale / admitted false**, with no track started or pending command. Native connected-idle UI checked; returned to Home, Wi-Fi on and screensaver component unchanged.
- Tests demonstrate the guarded native/browser/worker path with protocol fixtures. Actual YouTube queue selection, audio output and interruption recovery still require provider authorization; this checkpoint does not claim them.
- Private receipts: `idle-queue-live-receipt.json`, `idle-queue-install-cleanup.json` under `/private/tmp/portal-music-native-check/`.
- Interruption source audit is documented in `music-interruption-readiness.md`. The matching published Snapcast receiver lacks audio-focus participation and a cross-process focus output. A launcher-only flag cannot demonstrate safe Calls/Jarvis audio handoff; reliable behavior requires a receiver change and on-device proof. No fabricated audio-ownership state or foreground-based global speaker restriction was added.
