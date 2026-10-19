# Music receiver preview.2 and companion parity — October 19, 2026

## What changed

The Android companion pending-panel bug is fixed and installed on the paired Pixel on branch `codex/toolkit-music-20261019`, commit `1b83013`. Capabilities indicate current command admission; an already-open Speakers/Queue/Lyrics sheet remains readable during pending readback, without retaining stale write permissions. Actual source/player/queue/lifecycle changes still retire it. Three new regressions were observed RED → GREEN; **533 JVM / 23 UI tests passed**. Two real Family API tests passed in **3.312 seconds**, including a valid source/revision/request-correlated volume change and explicit original-volume restoration, with unchanged other rooms/group/queue/track. These timings cover whole tests, not p95 command performance. Original test APK/pairings were preserved.

The PWA had the equivalent pending Lyrics withdrawal. Its fix retains known lyrics only for the same source/track/queue/player binding, resets on binding or terminal support changes, and never retains command admission. Worker full Node gate: **744 passed / 748 total, 0 failed, 4 opt-in skipped**. Actual Chrome synthetic-route gate: **2 passed, 0 failed**, zero command POSTs/outside requests, closed-sheet polling stopped. Both environments demonstrated RED → GREEN. This is actual browser rendering with synthetic music data, not household playback evidence.

## Built receiver and launcher client

[Preview.2 source patch/build receipt](research/snapcast-focus-candidate/preview.2/) targets public Snapdroid commit `28529b546c48b7eeec5f0ae90b7ec330c4828dcb`, application ID `com.immortal.musicreceiver`, original Java namespace, minimum API 21 / target 34 / arm64 only. Debug builds/check/signature/alignment/native digest gates passed; Gradle receiver unit tests are NO-SOURCE, **17 actual-service host regressions passed** with Android boundaries stubbed. Debug/release lint report **0 errors / 80 and 78 warnings**. The unchanged public native executable is not a reproducible native rebuild and differs from the installed Family binary.

Actual IPC authorization now checks the OS Messenger UID, Immortal package allowlist and matching signatures. The exported service has no manifest signature permission. UserStart is instance/epoch-bound; Stop/Suppress are authenticated unstamped emergency actions and can stop a newer explicit start. Gain/reconnect/boot never starts output. Full native focus/process behavior remains unverified.

New independent MIT launcher `MusicReceiverProtocol` / `MusicReceiverClient` implement strict protocol-1 types, correlation, freshness, bounded timers, cancellation/unbind, receiver/reply UID/signature checks and Query-only reconnect. Start defaults to denied until the caller supplies verified admission. No MA player mapping, production hook, global ownership flag or automatic recovery was invented. **32 new JVM tests passed** in the complete **3,287-test gate (zero failures/errors/skips)**; debug/test assembly passed. Independent interop review found one availability limitation: a missing receiver is currently classified UNTRUSTED, so installation while attached requires explicit detach/attach. This must be accounted for or refined in real setup integration. No other findings in the reviewed contract scope.

## Actual Family Area query-only evidence

The unsigned receiver was re-signed with the existing Family launcher debug key, without creating/resetting a key. APK certificate SHA-256 `4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6` matched the installed launcher. Trusted candidate APK SHA-256 `e0490269fe9c72427f3a16ac265e281a2245213e1c73729fdffc5601a8f8bdde`.

The first attempt did not run because Gradle packaged only the configured instrumentation runner. Routing through the existing PortalUxInstrumentation fixed the harness. The real API-29 Family probe passed **30 bind/query/unbind cycles, 59 Query requests, 2,647 ms**, with candidate state stopped-confirmed, no process/focus/playback intent, stable candidate-local UUID and **zero Start requests**. It exercised raw Android Messenger from the actual trusted launcher UID; it did not execute the new production client or test a wrong signer/foreign UID on hardware. Host/JVM tests cover those contract cases.

The owned candidate was removed, prior test APK restored, Home requested and screensaver component unchanged. The current launcher and stock receiver were never replaced by this probe. Actual baseline launcher APK remains `1a2618c640451ec4c52d6edb90f41eae7bb131b200915729b35b45482d7db34b`. Private rollback copies/receipts are in `/private/tmp/portal-music-receiver-query-check/`; no credentials, account cookies or signing key are committed.

## Required next gates

Receiver identity-to-MA mapping and safe stock/fork activation; real production-client runtime; actual arm64 execution/PID/kill and focus/no-resume behavior; Calls/Jarvis integration and audible other-room continuity; YouTube authorization/playback; actual transport/seek/groups and playing retention; parked unmet visible/confirmed/frame performance targets; final accepted signed Family/Living rollout. The full music goal remains active and incomplete. Query timing cannot establish audio silence, command p95 or warm frame performance.
