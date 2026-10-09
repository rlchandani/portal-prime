# Legacy slideshow WebView recovery

Branch: `codex/family-slideshow-recovery`. Validation date: 2026-10-09.

## Defect and correction

Family Area's Portal+ stayed on “Loading your photos…” in saved legacy `shuffle`
mode after a previous Dream stopped and another started in the same process.
The local library and scaled copies were readable; storage was available.

`PhotoFrameController.stop()` called `WebView.pauseTimers()`. Android documents
that method as a process-wide suspension of layout, parsing and JavaScript
rather than a pause of one view. No production path called `resumeTimers()`.
The next engine's dimension deadline, image admission and animation promotion
could therefore remain suspended indefinitely.

Cleanup now calls only the owned view's `onPause()`, followed by its existing
`stopLoading()` and `destroy()`. Both engine and arbitrary-webpage startup call
`resumeTimers()` and `onResume()` before loading. The bundled engine emits one
first-scene readiness log with mode, photo count and elapsed page time; it logs
no photo URLs and adds no per-frame logging or polling.

Reference: [Android WebView pauseTimers](https://developer.android.com/reference/android/webkit/WebView#pauseTimers()).

## Verification

The debug app and Android-test APK built successfully. All **818 JVM tests**
passed with zero failures/errors/skips.

A bounded real-WebView instrumentation probe on an Android 34 arm64 emulator at
2160 × 1440 reproduced the original failure: the first slideshow displayed its
owned photos and its controls worked, but closing it stopped a separate
WebView's 100 ms heartbeat.

With the fix, the strengthened probe passed **97 assertions** in 24.7 seconds:

- Four fresh Activity/WebView pairs, Ken Burns twice and filmstrip twice.
- Three exit/re-entry cycles, with first-photo visibility and loading-overlay
  dismissal checked from the actual DOM rather than the presence of a bridge.
- Pause/resume, next/previous, a static visible paused scene and bounded live
  composition counts.
- The independent heartbeat continued after every exit.
- A deliberately seeded global pause before session two was recovered by
  startup; its scene and the independent timer both resumed.

Run after installing both debug APKs:

```sh
adb shell am instrument -w -e command slideshow -e legacyOnly true \
  com.immortal.launcher.debug.test/com.immortal.launcher.PortalUxInstrumentation
```

The test preserves registry/source/secure Dream settings and removes its owned
photo fixtures. At revision `5d4ba15`, legacy face warm-up workers were untracked
and could persist synthetic entries in the shared face cache. Teardown avoided
rewriting that shared cache, which would race workers holding older snapshots.
These regression runs used an emulator rather than household data. The subsequent
[legacy analysis and Wi-Fi diagnostics validation](portal-plus-wifi-frame-validation.md)
records the cancellation/cache correction and another complete regression run.

## Portal validation

The fixed debug APK was deployed to Family Area over the existing Fleet Agent.
Deployment verified the installed APK SHA-256:
`cad53eeb4c134e5b0b7b9bb1ac4f98fc04488e404f543c1ba9419f9ecdb518b8`.
The signing certificate matched the previous installed debug build.

Three normal preview sessions in the same app process admitted a decoded scene
from the actual 12-photo local library. Two entries followed Home, and the third
followed the normal remote-injected tap-to-exit path. The tap callback finished
the preview and updated presence to INTERACTIVE.

| Session | Page start to scene admission | Activity request to scene admission |
|---|---:|---:|
| First, cold WebView | 172 ms | 3,264 ms |
| Second | 580 ms | 1,867 ms |
| Third | 993 ms | 2,185 ms |

The page timing excludes WebView creation, source discovery and the subsequent
loading/scene fade. These numbers measure readiness, not delivered FPS or
complete visual-transition latency. Saved shuffle mode chooses varying scenes,
so the sessions are not a controlled performance comparison.

A finite 246-second health observation collected nine snapshots without input.
The app retained the same PID and 71 threads throughout. App RSS varied between
129.0 and 210.1 MiB, ending at 157.2 MiB versus 206.2 MiB at the start.
Captured logs contained no matching app fatal exception, ANR or out-of-memory
error. This is a short stability observation, not proof of leak freedom or
continuous DOM/GPU progression. The photo preview was left running.

## Limits

The actual Portal checks used its Fleet API and readiness/lifecycle logs.
They do not provide a current device screenshot or GPU/compositor frame times.
Normal OS Dream attach/detach and unattended idle initiation were not repeated
in this run; both preview and Dream share the corrected controller. The earlier
native two-hour test remains documented separately and is not evidence of a
long legacy-shuffle soak. Private device logs stay outside the repository.
