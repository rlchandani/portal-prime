# Receiver focus preview.2 receipt

This is a built, public-release-based alternative receiver, ready for parent review and trusted-key re-signing before a query-only Family check. It is **not installed or runtime verified**. The canonical source is `/private/tmp/portal-music-receiver-build/source`; the earlier feasibility patch and music worktree document predate this APK and its mandatory signer check.

## APKs and identity

| Artifact | SHA-256 |
|---|---|
| `artifacts/immortal-musicreceiver-focus-preview.2-unsigned-debug.apk` | `afa205885185a8c60aec5ebbed03850aac3b0e909a62eeaf1413405dd7e57072` |
| `artifacts/immortal-musicreceiver-focus-preview.2-debug.apk` | `89ef52547b66c8b92b55a603582558786f364cfa04032efc1c4055d9f879bb26` |

Application ID: `com.immortal.musicreceiver`. Java namespace/classes: `de.badaix.snapcast`. Version: `0.29.0.2-focus-preview.2`, code `2902002`. APK manifest minimum API **21**, target **34**, compile **35**. Packaged native ABI: **arm64-v8a only**. Both candidates are the debug variant; the unsigned one deliberately has no signing block and cannot be installed until signed.

The isolated preview signer is RSA 2048, certificate DN `CN=Portal Music Receiver Preview, OU=Debug, O=Starbright Lab, C=US`, certificate SHA-256 `48bbe3dfa7d18090d507287c502c67212505abef43223c52ba256556cf0ba4b6`. `apksigner` verifies v1 and v2 signatures. It emits warnings about META-INF entries outside v1 coverage; v2 verification succeeds. This certificate has **not** been compared with Family Immortal's debug certificate. Its local test key is retained with mode 0600; no user's existing key or password was read.

The parent should re-sign the preserved unsigned APK with the existing trusted Family Immortal debug key, then compare APK certificate digests before querying. The launcher adapter must verify the receiver certificate as well. Separate release keys would fail the current shared-signer protocol and require a separately reviewed authentication design; a package allowlist alone is insufficient.

## Exact public source and native evidence

- Snapdroid release [v0.29.0.2](https://github.com/snapcast/snapdroid/releases/tag/v0.29.0.2), release commit `28529b546c48b7eeec5f0ae90b7ec330c4828dcb`, annotated tag object `25daf3dfc999aa2f4fa88a171f72069f26d9aaa6`.
- Native Snapcast submodule commit `208066e5bb3f77482a62301283a8075912a7e22c`.
- Public `Snapcast-signed-v0.29.0.2.apk` SHA-256 `cd835c12ae3bf084ef06064333599040559a871fee48e83f3539e89224b40010`.
- Both final APKs contain the unchanged public `lib/arm64-v8a/libsnapclient.so`: **1,851,392 bytes**, SHA-256 `a62a8589c502acf302dadaf778ff90b45b2341bf4dcc496a8497d33c917ccda1`.
- The exact native source archive is retained as `source-evidence/native-source-208066e.tar.gz`, SHA-256 `a71c5bba3573a58356b068c2f099b1746dbf60624147bc08409c75f85620e00c`.
- The parent reports the actual installed Family baseline APK SHA-256 `680c9588cfca40d67b36e5bd73726729e99400095d3c154610c998e437a1cd9c`, certificate SHA-256 `ae7747f4e8c88296c482aeb1c0fe9bd033a00cb91ae785bd40a6f468a2db75cc`; its arm64 binary is **1,851,504 bytes**, SHA-256 `875ebafe7b0de967060b0c112d057ef6ea6e3e285ea0aed808a25c4642cba0fc`. These are parent-provided observations, not independently read here. **The candidate native bytes differ from the installed baseline.** No substitution was made.

`artifacts/receiver-focus-preview.2.patch` applies directly to the exact pristine Snapdroid source; every resulting changed text file was compared byte-for-byte with the canonical source. Patch SHA-256: `3580d71176614c3586b1fc38450e0a222943b8676a0ae38443a782143193d311`. The binary is intentionally separate from this text patch, with its path, size and digest in `artifacts/native-input.json`.

Upstream GPL-3.0-or-later notices and `source/LICENSE` remain intact. The new endpoint carries Starbright Lab's MIT notice and `source/LICENSE-MIT`; the combined receiver remains subject to the upstream GPL. Public source, native commit/archive and prior native license evidence are preserved. A complete third-party native dependency/source and distribution compliance audit has **not** been completed. This is not a claim of reproducible native compilation or release distribution readiness. No Meta copyright or assets were added.

## Output suppression implementation

Explicit user Start requests music/media audio focus. Delayed focus grants are disabled; ducking requests cause full local suppression. Permanent loss, transient loss and duck loss invalidate pending starts/restarts and stop the receiver's native child. Gain callbacks never resume playback. User Stop/Suppress clears playback intent and abandons focus; a later fresh explicit UserStart is required.

Before native execution, a shell emits its PID and waits for a `GO` acknowledgment. Java admits it only for the current playback epoch and child; shell `exec` preserves that PID. Suppression uses public same-UID `android.os.Process.killProcess(pid)` rather than relying on Android 10's Java `destroyForcibly`, whose implementation does not guarantee SIGKILL. A loss before PID admission cannot release the native execution gate. A 250 ms termination timeout reports `termination_unconfirmed` and prevents launching a second renderer.

This code acts on the **local child only** and makes no MA/Snapserver pause or group-volume call. `process_running_unverified` means a process passed the execution gate; it does not prove audible playback. `stopped_confirmed` means the local child exit was observed, not that audio buffers have been physically measured.

## Query and launcher contract

Bind an explicit component:

```text
ComponentName("com.immortal.musicreceiver",
              "de.badaix.snapcast.FocusControlService")
```

The optional action is `com.immortal.musicreceiver.FOCUS_CONTROL`. The service is exported **without a signature permission in the manifest**. Binding can initialize its components. Actual commands are authorized in `SnapclientService.handleControlMessage` using the OS-supplied `Message.sendingUid`: its package list must contain `com.immortal.launcher` or `com.immortal.launcher.debug`, **and** `PackageManager.checkSignatures(sendingUid, Process.myUid())` must return `SIGNATURE_MATCH`. The endpoint forwards the original Message without replacing its UID.

| `Message.what` | Command | Required payload beyond authentication |
|---|---|---|
| 1 | Query | `replyTo` Messenger and `requestId` for a correlated response |
| 2 | Suppress | No fresh instance/epoch required; optional reply |
| 3 | UserStart | Exact fresh `instance` and `epoch`, `host`, `port` in 1..65535; default port 1704 |
| 4 | UserStop | No fresh instance/epoch required; optional reply |

Protocol is **1**. A successful Query returns `instance`, `epoch`, `observedAtElapsedMs`, `clientId`, `outputState`, `reason`, `processAlive`, `focusHeld` and `playbackRequested`, plus the echoed `requestId`. Errors include `not_ready`, `forbidden`, `stale`, `invalid_endpoint`, and `unsupported`. A caller may receive `not_ready` while the internal service bind completes; retry Query with a new correlation ID. Start acknowledgments must be interpreted through the returned state/reason and do not prove output.

Only UserStart is instance/epoch guarded. Stop and Suppress deliberately remain authenticated emergency operations without a fresh observation. A delayed authenticated Stop/Suppress can stop a newer explicit start; the receiver does **not** claim to reject stale mutation dispatch for these commands. Epoch/child guards on receiver-internal callbacks still prevent old focus callbacks or native readers from restarting/stopping a new explicit session.

A Query can initialize services, create a notification channel, and persist a new candidate-local UUID. Source inspection and the host regression show it does **not** request audio focus, launch a child, acquire playback wake/WiFi locks or connect to a stream. This is query-only implementation evidence, not an Android runtime measurement. The exported bind surface also permits unauthorized component initialization; the per-message check restricts state disclosure and playback mutation.

For parent integration: pin/verify the receiver certificate and explicit component; accept only protocol 1; correlate replies; require fresh observations for UserStart; never infer Start from focus gain, reconnection, boot or a snapshot flag. Treat `termination_unconfirmed`, dead Binder, stale state, mismatch, timeout or missing readiness as unavailable/unproven. The launcher itself must suppress its own competing audio according to the parent policy; that work is outside this sidecar.

## Coexistence inspection

The merged APK manifest contains the distinct app ID, no `sharedUserId`, fully qualified original Java components, and provider authority `com.immortal.musicreceiver.androidx-startup` (non-exported). The renderer service and fork STOP receiver are non-exported. The receiver STOP action, notification channel and generated AndroidX dynamic-receiver permission are fork-prefixed; boot/legacy auto-start registrations are removed. The control service is the only exported receiver-control endpoint. AndroidX's profile installer receiver is separately exported with `android.permission.DUMP`.

The candidate has its own app data and client UUID. Identical Java class names across APKs do not share process/data identity. These manifest/source checks support coexistence; simultaneous installation, firmware behavior and actual Binder UID/signature enforcement remain untested on Family. The retained upstream multi-room settings UI is not a focus callback and remains capable of explicit server configuration if a user opens it; parent query-only validation should bind the endpoint without launching that UI.

## Build requirements and completed gates

The native source build expects NDK `27.2.12479018`, CMake `3.22.1`, prefab, and the upstream Oboe/Boost/FLAC/Ogg/Opus/SoXR/Tremor/Vorbis AARs from GitHub Packages. The anonymous Oboe POM read returned **401**. This candidate removes that native compilation/dependency route and packages the pinned public release binary. No native executable was rebuilt.

Java/APK build: JDK 21 at `/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home`, Java 17 source/target, SDK `/opt/homebrew/share/android-commandlinetools`, build tools/compile platform 35, AGP 8.7.2, Gradle 8.11.1. Only anonymous public Java/AndroidX dependencies were fetched into a fresh task-local Gradle home. `build-preview.py` uses an explicit minimal environment and task-local Gradle/Android homes; no Immortal Gradle command or user signing configuration was used.

| Gate | Result / evidence in `logs/` |
|---|---|
| Unsigned debug assemble | PASS, `final-unsigned.log`, exit 0 |
| Signed debug assemble | PASS, `final-signed-gates.log`, exit 0 |
| Debug lint | PASS, 0 errors / 80 warnings; XML/HTML preserved in `artifacts/reports/` |
| Release lint | PASS, 0 errors / 78 warnings; XML/HTML preserved in `artifacts/reports/` |
| `:Snapcast:check` | PASS; debug/release Gradle unit-test tasks are **NO-SOURCE** |
| Actual service host regressions | **17 passed / 0 failed**, `host-final.txt` and exact source command |
| Official Android API 35 javac | PASS for service/endpoint/receiver; project/AndroidX symbols stubbed |
| Packaged DEX inspection | PASS: actual UID, shared-signer calls, allowlist, original-message forwarding present |
| APK badging / merged manifest | PASS: version/app ID/min SDK/ABI/components/authority checked |
| Signature / alignment | Signed v1/v2 verifies; unsigned correctly fails signature verification; both zipalign checks pass |
| Packaged native bytes | Both APKs exactly match pinned public binary size and SHA-256 |
| Consolidated patch application | PASS against pristine stock; text bytes match build source |

The host harness compiles the **actual Java service**, not a state-machine copy. Android services, Messenger transport, AudioManager, PackageManager and locks are stubs; a real host shell/native fixture ignores SIGTERM and is killed through the stub's host process implementation. It covers all three focus losses/no-gain-resume, denied/delayed focus, queued restart/stale callbacks, PID admission, failed termination, foreign UID/wrong signer, UserStart staleness, emergency Stop/Suppress and query without renderer/focus/locks. It does **not** validate Android AudioTrack/Oboe/OpenSL, SELinux, Android process killing or real Binder identity.

Reproduce host checks with `host-tests/run.py` against `source/Snapcast/src/main/java/de/badaix/snapcast/SnapclientService.java`; then run `host-tests/api_check.py`. Rebuild APKs using `build-preview.py`; its public dependency cache is retained for offline rebuilding.

## All open gates and cleanup

1. Parent trusted-key re-signing, APK certificate comparison, and adapter receiver-certificate verification.
2. API 29 Family query-only Binder check, real UID/signature rejection and timing/readiness, with no Start. Stock remains installed and untouched.
3. Physical arm64 execution/ABI and firmware policy/SELinux compatibility of the **different public native binary**, shell PID/exec handshake, and same-UID SIGKILL on Family.
4. Actual focus callback delivery for media/calls/navigation/ducking; audible suppression latency/buffer drainage; no automatic resume after user stop/pause; no zombie/duplicate process through real lifecycle/reconnect/foreground restrictions.
5. Playback interoperability with actual Snapserver 0.34.0 / MA 2.10.7, receiver UUID/room selection and confirmation that other rooms keep playing.
6. Launcher integration/policy, error handling, reply freshness and certificate checks; no launcher code was changed here.
7. Full native reproducibility, native dependency/license/source distribution audit and release signing/install/update review.
8. Remaining lint warnings, newer Android behavior, and physical coexistence of stock and candidate. No Android instrumentation tests ran and stock upstream supplies no Gradle unit tests.

Cleanup stops only this task's Gradle home and removes disposable host/API/patch-check classes and module build intermediates after preserving the two APKs and reports. Source, patch, logs, GPL evidence, the local preview signer and the fresh public dependency cache remain for review/rebuild. The final cleanup result is recorded in `logs/cleanup.txt`. No device install, device/auth/credential reads, deployed app change, household configuration change, primary/Calls/frozen companion edit or music worktree document edit occurred this round.

## Changed paths this round

All application source changes below are relative to `/private/tmp/portal-music-receiver-build/source/`:

- `LICENSE-MIT`
- `Snapcast/build.gradle`
- `Snapcast/src/main/AndroidManifest.xml`
- `Snapcast/src/main/java/de/badaix/snapcast/BroadcastReceiver.java`
- `Snapcast/src/main/java/de/badaix/snapcast/FocusControlService.java`
- `Snapcast/src/main/java/de/badaix/snapcast/MainActivity.java`
- `Snapcast/src/main/java/de/badaix/snapcast/SnapclientService.java`
- `Snapcast/src/main/jniLibs/arm64-v8a/libsnapclient.so`
- `Snapcast/src/main/res/layout/dialog_server.xml`
- `Snapcast/src/main/res/values-de/strings.xml`
- `Snapcast/src/main/res/values-ja/strings.xml`
- `Snapcast/src/main/res/values/strings.xml`
- `gradle.properties`

Additional deliverables under the build root: `build-preview.py`, `host-tests/`, `RECEIPT.md`, `receipt.json`, `artifacts/` (two APKs, consolidated patch, source inventory, native hash and reports), `logs/`, `source-evidence/`, isolated `signing/` and fresh public `gradle-home/`/`android-user/`. The generated SDK path is in `source/local.properties` and is excluded from the upstream patch. In the focus workspace, only `LATEST_RECEIVER_PREVIEW.json` was added during finalization. No Immortal or music worktree document was edited this round.
