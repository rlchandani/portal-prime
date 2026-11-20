# Main-thread hygiene sweep — 2026-11-20

Family Portal+ (Android 10). Branch `offmain-connectivity`, from main 1.288 (282), merged with
main 1.289 (283) before the final gate. Goal: find disk/network I/O on the main thread with
StrictMode, fix the frequent sites, prove nothing on screen changed.

## Instrumentation

`StrictModeDiagnostics`, installed first in `ImmortalApp.onCreate`, only when
`BuildConfig.PORTAL_STRICT_MODE_DIAGNOSTICS` is true:

- thread policy: `detectDiskReads/DiskWrites/Network/CustomSlowCalls`, `penaltyLog`
- VM policy: `detectLeakedClosableObjects/LeakedSqlLiteObjects/LeakedRegistrationObjects`, `penaltyLog`

The flag is false in `defaultConfig`. Only the **debug** build type reads
`-PportalStrictMode=true` (`./gradlew :app:assembleDebug -PportalStrictMode=true`). Gradle refuses
the property on any release, performance, benchmark or dev task. `StrictModeDiagnosticsTest`
pins the gate and checks that ordinary builds compile it off.

## Method

- Scratch debug builds (StrictMode on) of the base and the branch, `adb install -r -d`, no
  force-stop. The same scripted tour on each: Home resume, Apps/Tools/Home tabs ×2, all 20
  Settings panes, Control Center (Home, Alerts, Security, vehicle; no cameras, no scenes run),
  Calls (Rooms, Recent, Messages), Music, the Jarvis card, the screensaver on the operator test
  source (generated scenes, 40 s), Tools (Reminders, Timers, Countdowns, Converter, Alarm, Note,
  Reminders again), 40 s idle on Home, then two forced GCs (SIGUSR1 through `run-as`) so the
  VM policy could report leaks. Step markers in logcat attribute each violation to a step.
- Violations are grouped by their first app frame. `~duration` is the time StrictMode measured
  from the violation to the end of that main-thread message. It is an upper bound on what the
  I/O itself cost: on a debug build it includes the rest of the message, e.g. all of
  `Application.onCreate` at cold start.
- Frame cost: `dumpsys gfxinfo` on **performance** builds, official 1.288 against the branch
  (both the same variant and signing). Each scenario was run 6 times after a warm-up.
- Pixels: screencaps of Calls Rooms, Calls Messages, Settings › Display and Settings › Sounds
  (the header clock band is masked). Home was compared by its uiautomator layout dump, not by a
  capture: no captures of Home, photos or camera.

## Before: what fired (ranked by count × blocked time)

| # | Site | When | Count (one tour) | Max blocked msg | What it is |
|---|---|---|---:|---:|---|
| 1 | `RemoteCompanionStore.id()` via `HouseholdBus.send`, `DinnerCall.selfId`, `FollowMeBroadcaster`, `CallsRooms`, `RoomSignals`, `SharedTimers.selfId` | every bus message, presence tick, Calls/CC open | 111 | 223 ms | opens, UTF-8 decodes and JSON-parses the whole companion store, operation history included, just to read the device id |
| 2 | `PortalHomeAmbientGuard.restore` via `DreamPolicy.clearBridge` | **every Home resume** | 51 | 23 ms | AtomicFile read of the ambient handoff token, normally the "-" tombstone |
| 3 | `QuickBar.refresh` → `QuickBarConfig` | every system-bar show/hide | 43 | 7 ms | prefs-directory stat through the accessibility service's context, whose path cache is cold |
| 4 | `CallMessageStore.ensureLoaded/prune` (Calls, Messages tab, Settings › Calls, Calls widgets) | Calls open, every Messages visit | 16 | 70 ms | index read, directory listing, prune, index rewrite check |
| 5 | Home connectivity (`ConnectivityManager.activeNetwork`) | every Home resume and every 15 s | not visible to StrictMode (binder) | 1–6 ms (known) | IPC into system_server |
| — | first `getSharedPreferences` of each prefs file, per process or per new Activity context | cold start; first open of each screen | ~60 one-off sites | 1.6 s at cold start (whole `onCreate`) | XML load or a prefs-dir stat; once per process or Activity |
| — | `PhotoFrameController.<init>` getCacheDir, `ScreensaverConfig` | screensaver start | 4–6 | 255–333 ms (whole `onCreate`) | path stats inside the frame Activity's `onCreate` |
| — | `YtKeystoreSecureStore.load`, `CallAutoAnswerStore`, `YtAndroid.create` | first Music/Calls open | 1–2 each | 92–230 ms (whole message) | one-time store loads |

What did **not** fire in any tour: no `DiskWriteViolation` (so no main-thread `commit()`), no
`NetworkViolation`, no custom slow calls, and no leaked closable, cursor, receiver or
service-connection, even after the forced GCs.

## Fixes

| Commit | Change |
|---|---|
| `chore: opt-in StrictMode diagnostics for scratch debug builds` | the instrumentation above |
| `perf: Home's connectivity check leaves the main thread` | `HomeConnectivity.isConnected` runs the probe on `Dispatchers.IO`; Home reads the same Boolean every 15 s |
| `perf: device id served from memory on household-bus hot paths` | `RemoteCompanionStore.cachedId()` remembers the id after one strict read, keyed by the application context. The null-tolerant hot callers use it. Routes, sync and `IntercomAuth` keep the strict, integrity-checked `id()`. A backup import forgets the memo under the store lock |
| `perf: Home resume no longer re-reads the ambient handoff token` | `AmbientTokenMemo`. The guard is the file's only writer, so the last token read or durably written stays in memory. A failed write keeps the old token; a corrupt token is never cached and still fails closed |
| `perf: Calls message index loads on IO; QuickBar reads prefs via app context` + follow-up | every composable `CallMessageStore` load goes through `ensureLoadedOffMain` (IO); `QuickBar` uses the application context |

JVM tests: `HomeConnectivityTest` (probe off the caller thread, failure reads as disconnected),
`RemoteCompanionStorageTest` (+3: the memo serves without disk, the strict `id()` still rejects
corruption, a restore forgets, an unreadable store gives null and is left untouched, the memo is
per context), `AmbientTokenMemoTest` (5: one read per process, write-through, a failed write keeps
the old token, a corrupt token is not cached, the handoff begin/restore round trip), and
`StrictModeDiagnosticsTest`. There is no JVM test for `ensureLoadedOffMain`: it is a one-line
`withContext` over Android `AtomicFile` code.

### Prefs `commit()` audit

None of the 14 `commit()` calls ran on the main thread in any tour (no DiskWrite violations). All
of them stay `commit()` because the result or the durability is load-bearing:

- `CrashGuard`: the process is about to die.
- `DreamPolicy` bridge: callers proceed only after a durable write; already on
  `DreamBridgePersistence`'s executor or an explicit commit path.
- `ControlCenterPinTries`: kids PIN lockout counter; a lost write would reset the lockout.
- `FleetBackupKeyStore`, `FleetBackupRoutes`: backup keys and import, fleet HTTP thread; failure is reported.
- `HouseholdKey`, `GuestConfig.dropLegacyDoorCode`: secrets set/removed; the Boolean is returned.
- `ScreensaverTestSource`, `BridgeWriteProbe`, `RoutineRepository`: the Boolean is the API result.

## After: identical tour

Same steps on both scratch builds. Each process was already running before logcat was cleared,
so this table has no cold start in it.

| Site (first app frame) | Kind | Before n | Before max ms | After n | After max ms |
|---|---|---:|---:|---:|---:|
| `RemoteCompanionStore.read` | DiskRead | 44 | 202 | 0 | - |
| `RemoteCompanionStore.path` | DiskRead | 9 | 202 | 0 | - |
| `ScreensaverConfig.prefs` | DiskRead | 5 | 333 | 5 | 269 |
| `ImmortalSettings.prefs` | DiskRead | 6 | 177 | 6 | 131 |
| `PhotoFrameController.<init>` | DiskRead | 4 | 255 | 4 | 205 |
| `CallMessageStore.dir` | DiskRead | 9 | 70 | 0 | - |
| `youtube.YtKeystoreSecureStore.load` | DiskRead | 2 | 229 | 2 | 224 |
| `PortalHomeAmbientGuard` | DiskRead | 28 | 14 | 0 | - |
| `WelcomeConfig.prefs` | DiskRead | 2 | 179 | 2 | 133 |
| `ScreensaverTestSource.until` | DiskRead | 2 | 137 | 2 | 96 |
| `CallAutoAnswerStore.repository` | DiskRead | 1 | 230 | 1 | 224 |
| `TimerStore.load` | DiskRead | 3 | 60 | 3 | 34 |
| `CallMessageStore.pruneLocked` | DiskRead | 2 | 70 | 0 | - |
| `PortalHomeAmbientGuard.handoff` | DiskRead | 7 | 14 | 0 | - |
| `youtube.YtAndroid.create` | DiskRead | 1 | 92 | 1 | 115 |
| `FleetConfig.prefs` | DiskRead | 1 | 71 | 1 | 60 |
| `CallMessageStore.save` | DiskRead | 1 | 69 | 0 | - |
| `NotesConfig.prefs` | DiskRead | 1 | 61 | 1 | 82 |
| `CallMessageStore.ensureLoaded` | DiskRead | 4 | 13 | 0 | - |
| `SettingsLastPage.prefs` | DiskRead | 1 | 40 | 1 | 44 |
| `SettingsLastPage.read` | DiskRead | 1 | 39 | 1 | 43 |
| `ChimeConfig.prefs` | DiskRead | 1 | 35 | 1 | 17 |
| `HomeDashboardConfig.prefs` | DiskRead | 1 | 17 | 1 | 29 |
| `SettingsNativeContentKt.IosDebugRow` | DiskRead | 2 | 7 | 2 | 8 |
| `HaConfig.prefs` | DiskRead | 1 | 11 | 1 | 22 |
| `RemotePairing.prefs` | DiskRead | 1 | 11 | 1 | 8 |
| `FaceFocusStore.<init>` | DiskRead | 2 | 5 | 2 | 6 |
| `RemotePairing.loadReconciled` | DiskRead | 1 | 9 | 1 | 6 |
| `CallingConfig.prefs` | DiskRead | 1 | 9 | 1 | 8 |
| `MusicNightConfig.prefs` | DiskRead | 1 | 8 | 1 | 6 |
| `QuickBarConfig.prefs` | DiskRead | 1 | 6 | 1 | 6 |
| `NotesConfig.audioFile` | DiskRead | 3 | 1 | 2 | - |
| `NotesConfig.hasAudioNote` | DiskRead | 2 | 1 | 1 | - |
| `HouseholdKey.get` | DiskRead | 0 | - | 1 | 194 |
| **Total** | | **151** | | **46** | |

Every remaining site fires once per process or once per new Activity (first prefs-file load, the
frame Activity's path stats, first YouTube/Calls store load). `HouseholdKey.get` appears only in
"after" because that first load landed inside the tour this time. The before run did not record
it the same way.

### Cold start (process start → first tour step)

| Site (first app frame) | Kind | Before n | Before max ms | After n | After max ms |
|---|---|---:|---:|---:|---:|
| `FleetConfig.prefs` | DiskRead | 3 | 1518 | 3 | 1463 |
| `ImmortalSettings.prefs` | DiskRead | 1 | 1626 | 1 | 1569 |
| `WelcomeConfig.prefs` | DiskRead | 1 | 1520 | 1 | 1466 |
| `UserLayout.load` | DiskRead | 1 | 1516 | 1 | 1461 |
| `HomeWidgetStore.migrateDashboardCards` | DiskRead | 1 | 1508 | 1 | 1452 |
| `WallpaperConfig.load` | DiskRead | 1 | 1450 | 1 | 1394 |
| `DisplayControl.prefs` | DiskRead | 1 | 1442 | 1 | 1385 |
| `HaConfig.prefs` | DiskRead | 1 | 1431 | 1 | 1376 |
| `ControlCenterConfig.prefs` | DiskRead | 1 | 1429 | 1 | 1375 |
| `CountdownConfig.prefs` | DiskRead | 1 | 706 | 1 | 661 |
| `RemoteCompanionStore.read` | DiskRead | 45 | 15 | 9 | 13 |
| `PortalHomeAmbientGuard` | DiskRead | 8 | 22 | 0 | - |
| `RemoteCompanionStore.path` | DiskRead | 9 | 15 | 2 | 13 |
| `OtaUpdater.prefs` | DiskRead | 1 | 132 | 1 | 14 |
| `OtaUpdater.refresh` | DiskRead | 1 | 131 | 1 | 11 |
| `HelpActivity` | DiskRead | 2 | 61 | 2 | 64 |
| `DevMode.prefs` | DiskRead | 1 | 103 | 1 | 9 |
| `DevMode.isEnabled` | DiskRead | 1 | 100 | 1 | 8 |
| **Total** | | **127** | | **76** | |

The big rows are cold-start prefs loads inside `Application.onCreate` and Home's first
composition. Their "max" is the whole start message on a debug build, and it is the same on both
sides. The companion-store reads that remain (9) belong to the boot-time strict paths
(`RemoteScenes.rearm`, deferred startup), which keep the integrity gate on purpose.

## Frames (performance builds, 6 repetitions each)

| Scenario | Build | Frames | Janky | p50 | p90 | p95 | p99 |
|---|---|---:|---:|---:|---:|---:|---:|
| Settings → Home (Home resume) | 1.288 | 136 | 118 | 28 | 65 | 77 | 89 |
| | branch | 142 | 116 | 30 | 69 | 73 | 81 |
| Control Center → Home (Home resume) | 1.288 | 95 | 63 | 25 | 113 | 125 | 150 |
| | branch | 106 | 60 | 21 | 105 | 133 | 200 |
| Calls open → Messages → Back | 1.288 | 485 | 65 | 10 | 22 | 57 | 133 |
| | branch | 481 | 63 | 10 | 23 | 57 | 109 |

The frame numbers are within run-to-run noise. That is expected: on a performance build these
sites cost single milliseconds per event (StrictMode's large numbers are whole-message bounds on a
debug build), and they ran mostly on resume, presence ticks or bus traffic, not inside a measured
frame. The win is main-thread time removed per event (Home resume, bus message, presence tick,
Calls open) and less ANR exposure under slow storage, not a visible frame change. Home's
first-frame-after-resume jank is dominated by Settings/CC window transitions (see the perf reports).

## Pixels

Calls Rooms, Calls Messages, Settings › Display, Settings › Sounds: **100.0000 % identical**
(0 px) between 1.288 and the branch. Home layout dump (bounds and labels, clock excluded):
identical.

## Gate

Merged with main 1.289: full unit tests (8449, 0 failures) and `:app:assemblePerformance` pass.

## Incident

During the first after-measurement tour, the scripted "Check for updates" tap on a 282 scratch
build found the published 1.289 and self-installed it (13:43:58). That tour's "before" data was
discarded. The tours were re-run with the update tap removed and dev mode on to pause the
self-updater, then dev mode was turned off. Family ends on official 1.289.

## Not fixed (low value or by design)

- First load of each prefs file per process, and the prefs-dir stat for each new Activity
  context. Inherent to SharedPreferences; a broad move to `applicationContext` saves microseconds.
- `PhotoFrameController.<init>` / `ScreensaverConfig` path stats in the frame Activity's
  `onCreate`: one-time per screensaver start.
- `CallMessageActivity.onCreate` keeps its synchronous `ensureLoaded`, because it needs the
  index to open a message; it is a no-op once loaded.
- `CallMessageStore`'s 6-hourly prune still runs on the main looper (rare).
