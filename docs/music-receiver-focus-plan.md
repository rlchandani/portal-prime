# Receiver audio-focus candidate for parent review

Prepared 2026-10-19 as a bounded sidecar. **Reviewable source candidate; no APK build, installation, or Family playback proof.** Family API 29, no GMS/root, installed `de.badaix.snapcast` 0.29.0.2, actual Snapserver 0.34.0 and MA 2.10.7 are supplied context, not newly inspected device facts.

The candidate stops the **local native renderer**, rather than setting a launcher flag or pausing an MA group. It deliberately requires another explicit user Start after every focus loss, including transient loss/ducking. It also removes legacy automatic Start and boot autostart. This is a behavioral tradeoff for review.

## Exact source and evidence

| Item | Pinned evidence |
|---|---|
| Snapdroid release | [v0.29.0.2 commit `28529b546c48b7eeec5f0ae90b7ec330c4828dcb`](https://github.com/snapcast/snapdroid/tree/28529b546c48b7eeec5f0ae90b7ec330c4828dcb); annotated tag object `25daf3dfc999aa2f4fa88a171f72069f26d9aaa6` |
| Native source gitlink | [Snapcast `208066e5bb3f77482a62301283a8075912a7e22c`](https://github.com/snapcast/snapcast/tree/208066e5bb3f77482a62301283a8075912a7e22c) |
| Current upstream inspected | [service at `c5f622743c14ecb85795ae1ad9dd48d5e30ae3be`](https://github.com/snapcast/snapdroid/blob/c5f622743c14ecb85795ae1ad9dd48d5e30ae3be/Snapcast/src/main/java/de/badaix/snapcast/SnapclientService.java); still no focus request/listener or MediaSession |
| Public release artifact | [Snapcast-signed-v0.29.0.2.apk](https://github.com/snapcast/snapdroid/releases/download/v0.29.0.2/Snapcast-signed-v0.29.0.2.apk), SHA256 `cd835c12ae3bf084ef06064333599040559a871fee48e83f3539e89224b40010` |
| arm64 renderer in public APK | `lib/arm64-v8a/libsnapclient.so`, 1,851,392 bytes, SHA256 `a62a8589c502acf302dadaf778ff90b45b2341bf4dcc496a8497d33c917ccda1`; the only arm64 native entry |

Matching release metadata/source does not prove that the installed APK has identical bytes or signer. No device, credential, signing-store, Gradle-cache or household/account configuration was read.

The [stock service](https://github.com/snapcast/snapdroid/blob/28529b546c48b7eeec5f0ae90b7ec330c4828dcb/Snapcast/src/main/java/de/badaix/snapcast/SnapclientService.java) launches an executable child with Oboe/OpenSL output. It has no focus cooperation. Its `reader` field is permanently null while a local variable shadows it; log callbacks run off the main thread, can mark a stopped player running, and can schedule native restarts. Stop does not cancel those restarts. Thus killing the service's current child without closing those paths is insufficient.

An API-29-specific trap: [Android 10 `UNIXProcess`](https://android.googlesource.com/platform/libcore/+/android-10.0.0_r47/ojluni/src/main/java/java/lang/UNIXProcess.java) has no `destroyForcibly` override, and [its native `destroyProcess`](https://android.googlesource.com/platform/libcore/+/android-10.0.0_r47/ojluni/src/main/native/UNIXProcess_md.c) calls `kill(pid, SIGTERM)`. A passing desktop `destroyForcibly()` test would therefore be inadequate evidence for this device.

## Concrete candidate

The reviewable patches, host-test sources and evidence are preserved in [research/snapcast-focus-candidate](research/snapcast-focus-candidate/). Temporary source copies and the public native binary remain in [/private/tmp/portal-music-receiver-focus](/private/tmp/portal-music-receiver-focus); neither binary nor full third-party source tree is committed. The runtime gates below remain open.

Working artifacts:

- [receiver-focus.patch](/private/tmp/portal-music-receiver-focus/receiver-focus.patch): unified patch against the exact release source, with an applied copy in `patched/` and pristine release in `snapdroid-0.29.0.2/`.
- [host-tests/run.py](/private/tmp/portal-music-receiver-focus/host-tests/run.py) and [ReceiverFocusTest.java](/private/tmp/portal-music-receiver-focus/host-tests/ReceiverFocusTest.java): compile and exercise the actual patched service/endpoint, not a separate focus-state model.
- [native-binary-build.patch](/private/tmp/portal-music-receiver-focus/native-binary-build.patch): optional, separate Java-only build overlay using the unchanged public native executable in `public-native/arm64-v8a/`.
- [verification.txt](/private/tmp/portal-music-receiver-focus/verification.txt), [api35-check.txt](/private/tmp/portal-music-receiver-focus/api35-check.txt), [native-inventory.json](/private/tmp/portal-music-receiver-focus/native-inventory.json), and public source/metadata evidence.

The receiver requests `AUDIOFOCUS_GAIN` with media/music attributes before native output. On API 26+, `setWillPauseWhenDucked(true)` requests the loss callback and delayed gain is disabled. Failed/delayed requests do not launch output. All three loss events terminate the child, cancel retry work, invalidate queued child/focus callbacks and abandon focus. Gain contains no start/resume action.

To obtain a public, reliable PID on Android 10, a shell prints its own PID and waits for `GO` on stdin. Java validates and records that PID on the main handler **before** releasing the gate. The shell then `exec`s the existing native executable, preserving the PID. Arguments are passed positionally to `exec "$@"`; host and client ID are not interpolated as shell code. On loss, `android.os.Process.killProcess(pid)` kills the app-owned renderer without root. If loss occurs before PID admission, the waiting shell is stopped without ever permitting native output. Shell availability/exec behavior on Family remains a runtime gate.

Termination is checked with a bounded 250 ms wait. A timeout is reported as `termination_unconfirmed`, retains the process reference and prevents a second renderer from starting. `stopped_confirmed` means the owned child has exited; it does **not** measure the speaker's final buffered sample or establish another app's focus ownership. A running child is explicitly `process_running_unverified`, not “audible.”

The patch changes the existing user Start button to a same-process service call, makes the renderer service non-exported, retains legacy Stop, ignores legacy Start/boot broadcasts, hides the unsupported autostart checkbox, and uses `START_NOT_STICKY`. The separate bind-only `FocusControlService` keeps the existing LocalBinder intact. [Android caches a service's first Binder](https://developer.android.com/develop/background-work/services/bound-services); returning a LocalBinder or Messenger from the same service based on action would not provide a dependable concurrent interface.

## Launcher integration contract

This is required future launcher work; no companion or launcher implementation was changed.

1. Bind explicitly to `de.badaix.snapcast/de.badaix.snapcast.FocusControlService`, optionally with action `de.badaix.snapcast.FOCUS_CONTROL`. The endpoint is Messenger, in the receiver's own process. It binds the renderer component without starting audio.
2. Supply a reply Messenger and `requestId` for each command. Android-supplied `Message.sendingUid` is checked against installed package names `com.immortal.launcher` and `com.immortal.launcher.debug`; unauthorized callers get only `forbidden`. This is a package/UID allowlist, not a checked certificate pin. Parent review must decide whether to add signer pinning and whether the debug package remains allowed.
3. Query (`what=1`) returns protocol 1, service `instance`, monotonically changing `epoch`, `observedAtElapsedMs`, stable persisted Snapcast `clientId`, `outputState`, `reason`, `processAlive`, `focusHeld`, and `playbackRequested`. `not_ready`, binder death, missing reply or stale data means unknown. Do not interpret unknown as permission to play.
4. Suppress (`what=2`) kills only this receiver, invalidates its epoch and replies with the resulting state. User Stop (`what=4`) does the same with a user-stop reason. Both remain available during interruption; neither sends MA/Snapserver room/group pause, play, volume or mute commands.
5. User Start (`what=3`) requires the latest `instance`/`epoch`, `host`, and stream `port` (default 1704). Missing/stale instance or epoch is rejected. It represents a **new explicit user gesture**, never focus gain, interruption end, startup, observer refresh or recovery retry. Do not automatically retry a rejected Start with a new epoch. A stale query before a suppression cannot authorize a delayed Start afterward.
6. Resolve the queried `clientId` to the verified actual Snapserver client and MA 2.10.7 player/group membership. Guard play/replay/seek only when the resolved target includes this receiver. Keep pause/stop, volume and room selection usable and unrelated rooms playing. Preserve MA's authoritative stream timeline when other rooms continue. Do not reuse the existing broad `audioOwnedByOther` flag as proof of receiver suppression or freeze the whole group's displayed position.
7. Before a user Start, freshly check communication admission and any **verified** Jarvis conversation signal; an API-29 focus grant can otherwise take focus from an app that has not locked it. During a known interruption, send local Suppress even if the other producer does not request focus. Calls and Jarvis adapters must be verified independently. The receiver cannot infer wake listening versus conversation or silence an uncooperative foreign app.
8. When a user pauses/stops the local target through MA, also send receiver User Stop so future transport/stream changes cannot re-enable its output. If a group pause is the user's intent, MA owns that transport decision; interruption suppression itself must not pause the group. External controllers' pause state still needs MA reconciliation before a new explicit user Start.
9. While the music surface is attached, query on connect, after each command and on a bounded observation cadence (e.g. 500 ms, 1 s reply expiry). Reject out-of-order request IDs and old service instances. Detach cancels polling, clears observations and unbinds. Reattach may query but must not start. Interruption end must never issue MA PLAY or receiver User Start.

## Build, license and installation constraints

The [exact release build](https://github.com/snapcast/snapdroid/blob/28529b546c48b7eeec5f0ae90b7ec330c4828dcb/Snapcast/build.gradle) uses AGP 8.7.2, Gradle 8.11.1, Java 17, compile SDK/build tools 35, min SDK 21, target SDK 34, NDK 27.2.12479018, CMake 3.22.1 and a native source submodule. Its prefab dependencies include Oboe 1.9.0, Boost 1.85.0, FLAC 1.4.2, Ogg 1.3.5, Opus 1.1.2, soxr 0.1.3, Tremor 1.0.1 and Vorbis 1.3.7.

The declared GitHub Packages Oboe POM returned **HTTP 401 without credentials**. The stock recipe is therefore not a verified anonymous build route. No sign-in, token, auth reconnect, Gradle invocation or cache lookup was attempted.

The optional overlay removes native CMake/prefab dependencies and the credentialed Maven repository, packages the public release's unchanged `libsnapclient.so` through `src/main/jniLibs`, and requests legacy native extraction needed for the existing executable launch. It leaves application ID, namespace and version unchanged. After parent review, a build workspace can apply both patches and copy `public-native/arm64-v8a/libsnapclient.so` to `Snapcast/src/main/jniLibs/arm64-v8a/libsnapclient.so`. SDK/build tools, public AndroidX/Material dependencies and the Gradle wrapper remain necessary; NDK/CMake are avoided by this route. **The overlay was not built.** It is native-binary reuse, not a reproducible native source rebuild or a demonstrated APK.

Snapdroid and native Snapcast are [GPL-3.0-or-later](https://github.com/snapcast/snapdroid/blob/28529b546c48b7eeec5f0ae90b7ec330c4828dcb/LICENSE). Existing notices are retained and changed files are marked. The new endpoint/test contributions carry MIT grants; `LICENSE-MIT` accompanies the upstream candidate, whose combined application remains GPL. A distributed fork must provide appropriate corresponding source/build instructions and preserve native dependency notices. Dependency/license completeness and the binary-reuse source/relinking obligations have **not** been audited. Do not copy GPL service/native implementations into the MIT launcher; integrate through the protocol.

Keeping `de.badaix.snapcast` does not make a locally signed fork update-compatible. Android requires the installed signer's compatible key/lineage, which was not inspected or acquired. A debug/local signing key cannot normally replace an upstream-signed installation. An uninstall/reinstall or separate fork package needs explicit parent review and a migration/rollback plan; neither was selected. A separate package would not solve the problem while the unpatched stock receiver also produces output. Revalidate persisted client ID and MA mapping after any data/package migration.

## Verification and every open gate

Executed without Gradle/device access:

- Stock service compiled in the host harness; all three baseline regression checks failed, directly demonstrating queued restart, stale-log resurrection and ungated legacy Start.
- Final actual service/endpoint host suite: **14 passed, 0 failed**, including real child teardown, all loss types, denied/delayed focus, no gain recovery, user Stop, stale focus callbacks, stale instance/epoch IPC, legacy Stop while bound, pre-PID loss and fail-closed unconfirmed teardown. The native fixture is a host shell/sleep process that ignores SIGTERM, not the Snapcast executable or audio hardware.
- `javac` checked the actual receiver, endpoint and broadcast receiver against the **official API 35 jar**; exit 0, with deprecated-API notes. AndroidX/project symbols remain stubs. Full activity, resource merge, dexing and manifest/runtime behavior were not built.
- Both unified patches were checked for application to the pristine exact release, and the receiver patch's applied output was compared with the prepared candidate.

Repeat the host logic checks from the artifact directory with `python3 host-tests/run.py patched/Snapcast/src/main/java/de/badaix/snapcast/SnapclientService.java`; then `python3 host-tests/api_check.py` checks the Android API surface. For the expected failing baseline, use `python3 host-tests/run.py snapdroid-0.29.0.2/Snapcast/src/main/java/de/badaix/snapcast/SnapclientService.java baseline`.

Unverified gates:

1. Full APK build/tests/lint, optional overlay DSL/dependency resolution, dex/min-SDK compatibility, native packaging/extraction, and the full MainActivity/resource integration.
2. Installed artifact bytes/signature, signing strategy, migration/rollback, stock-receiver deactivation and parent review of disabling autostart. Package/deployment decisions remain open.
3. Android 29 real Binder UID/admission, concurrent stock UI + control binding, startup `not_ready`, foreground-service deadlines/background restrictions, process-death behavior and detach cleanup.
4. Family shell PID gate/exec/kill permissions, both actual Oboe/OpenSL output paths, true loss-to-silence latency/hardware-buffer drain, process/thread/lock cleanup and kill-timeout handling.
5. Calls and Jarvis focus participation and independent real interruption tests, including non-focus conversation suppression; API-29 cooperation is not system enforcement.
6. Actual Snapserver 0.34.0 / MA 2.10.7 reconnect and stream behavior after renderer death, stable client/player identity, room/group targeting, user-pause/stop during interruption, outside-controller pause, stale commands and unaffected rooms.
7. Launcher protocol adapter, admission reads and reconciliation; no end-to-end “never resumes” claim until that contract and actual user-paused/stopped recovery are exercised.
8. GPL/native dependency notices, corresponding source/build/relinking completeness and any distribution obligations.

Next action: parent review the conservative recovery semantics, IPC and signing/migration choice; then perform an isolated APK build using the anonymous binary overlay or public source-built dependency replacements. Only after that, run actual Family playback acceptance with the reviewed installation and launcher adapter. Source feasibility is useful here; runtime readiness is still open.

## Changed paths and cleanup

The only music-worktree change is this file: `docs/music-receiver-focus-plan.md`. Primary repository, Calls worktree, frozen companion, devices, deployed apps and household/account configuration were not changed.

Receiver patch paths (inside the temporary upstream copy):

- `LICENSE-MIT`
- `Snapcast/src/main/AndroidManifest.xml`
- `Snapcast/src/main/java/de/badaix/snapcast/BroadcastReceiver.java`
- `Snapcast/src/main/java/de/badaix/snapcast/FocusControlService.java`
- `Snapcast/src/main/java/de/badaix/snapcast/MainActivity.java`
- `Snapcast/src/main/java/de/badaix/snapcast/SnapclientService.java`
- `Snapcast/src/main/res/layout/dialog_server.xml`

Optional build overlay changes only `Snapcast/build.gradle`. Remaining new artifacts and source evidence are under `/private/tmp/portal-music-receiver-focus`, inventoried in `artifact-inventory.txt`. Test children are reaped; generated class trees and downloaded archives/APK are removed after verification. Source copies, patches, host test sources, the official API jar, the inspected public native binary and evidence logs remain for review/repetition. No background monitor or automation was created.
