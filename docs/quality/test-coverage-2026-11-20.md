# JVM unit-test coverage push (2026-11-20)

Base: main 1.282 (versionCode 276). Branch `coverage`. Everything here is JVM unit tests only
(`testDebugUnitTest`); no device was used.

## How to measure

```bash
./gradlew :app:createDebugUnitTestCoverageReport -PjacocoReport=true
python3 scripts/coverage_report.py --gaps        # summary + ranked pure-logic gaps
```

- `-PjacocoReport=true` turns on AGP's `enableUnitTestCoverage` for the debug build type only.
  Without the property, builds are unchanged and nothing is instrumented. The HTML and XML reports
  land in `app/build/reports/coverage/test/debug/`.
- `scripts/coverage_report.py` (tests in `scripts/test_coverage_report.py`) splits the JaCoCo
  numbers **per compiled class**, using each class's constant pool. Classifying whole files would
  mislabel pure policy objects that live next to an Activity.
  - **glue:component** – subclasses an Activity, Service, Receiver, View and so on.
  - **glue:compose** – references the Compose `Composer`. This covers composables, their lambdas
    and `ComposableSingletons`.
  - **glue:platform** – references any other `android`/`androidx` type: Context,
    SharedPreferences, Bitmap, Handler, Compose snapshot state, MediaCodec, and so on.
  - **pure logic** – none of the above: policies, parsers, reducers, codecs, wire formats,
    sync/merge, math. Annotations, `android.util.Log`, `SystemClock` and Compose's pure-Kotlin
    value types (geometry, unit, Color) are allowed.
- Risk areas come from file-name prefixes (`AREAS` in the script).

## Results

| | Before lines | Before branches | After lines | After branches |
|---|---|---|---|---|
| **Overall** (95.6k lines) | 38.1% | 40.4% | 38.5% | 43.4% |
| **Pure logic** (28.7k lines, 874 files) | 89.0% | 78.6% | **90.4%** | **86.1%** |
| Glue, all (66.9k lines) | 16.2% | 15.5% | 16.3% | 15.7% |
| – compose (31.0k) | 1.5% | 1.7% | 1.5% | 1.7% |
| – component (4.3k) | 5.2% | 4.9% | 5.2% | 4.9% |
| – platform (31.7k) | 32.1% | 27.4% | 32.2% | 27.7% |

Line coverage of pure logic was already above 80% at the start. Branch coverage was the real gap,
so the work went into decision points: rejections, limits, malformed input, boundaries and clock
edges.

### Pure logic by risk area

| Area | Before lines | Before branches | After lines | After branches |
|---|---|---|---|---|
| security | 92.2% | 83.3% | 95.6% | 90.9% |
| sync | 96.1% | 78.7% | 97.6% | 91.7% |
| money (Tesla, currency, crypto, stocks) | 87.4% | 74.3% | 94.7% | 89.3% |
| Control Center policy | 92.2% | 71.6% | 96.1% | 88.8% |
| calling | 79.4% | 75.3% | 81.6% | 84.2% |
| reminders / routines | 90.5% | 79.3% | 92.1% | 91.3% |
| alerts | 94.5% | 89.4% | 94.5% | 89.4% |
| OTA | 88.8% | 84.9% | 90.4% | 88.9% |
| settings scope | 94.1% | 88.2% | 94.8% | 94.5% |
| other | 87.8% | 80.2% | 88.2% | 82.8% |

### All code by risk area (glue included)

| Area | Before lines / branches | After lines / branches |
|---|---|---|
| security | 64.3% / 59.4% | 66.2% / 63.8% |
| sync | 48.1% / 55.1% | 48.8% / 63.5% |
| money | 44.8% / 52.3% | 48.0% / 61.8% |
| Control Center | 38.3% / 37.7% | 39.9% / 46.6% |
| calling | 23.8% / 29.0% | 24.4% / 32.4% |
| reminders | 35.3% / 42.1% | 35.9% / 48.4% |
| alerts | 35.1% / 40.1% | 35.1% / 40.1% |
| OTA | 43.5% / 42.7% | 44.0% / 44.2% |
| settings | 40.1% / 34.1% | 40.3% / 35.5% |

### Per package

| Package | Pure logic before (lines / branches) | Pure logic after (lines / branches) |
|---|---|---|
| launcher (flat) | 88.8% / 78.6% | 90.2% / 86.2% |
| launcher.settings | 97.3% / 92.3% | 97.3% / 97.0% |
| launcher.youtube | 90.6% / 70.0% | 91.7% / 80.0% |
| launcher.jarvis | 96.4% / 85.4% | unchanged |
| launcher.jarvis.wake | 92.5% / 90.7% | unchanged |

The per-file numbers come from `python3 scripts/coverage_report.py --json out.json`.

## Tests added

- **Count:** 751 behaviour tests in 72 new `*CoverageTest.kt` files, plus 14 regression tests for
  the fixes below. The suite went from 7,429 to 8,194 tests (1 skipped: the `/proc` path in
  `FleetFileReadCoverageTest`, which runs only on Linux).
- **Rules followed:** no existing test file was edited, and no production code was changed except
  the bug fixes.
- **Highlights by area:**
  - **Security:** `YtOAuth`, `FleetFs` (listing, `Content-Disposition` injection), `FetchUrlPolicy`,
    `CallingSignalAuth` (SDP candidate filtering), `BoundedRead`, `IntercomCipherStreams`,
    chore/household peer auth.
  - **Sync:** chore, household-list, reminder, room-seen, call-message, music handoff and history
    codecs; household-list commands, ops, migration and sync state; the settings-sync catalog.
  - **Control Center:** the ask/kids-mode policy, the voice command resolver and route, phone
    remote actions, slices, entities and state, the HA parser, the model, the security list,
    camera liveness, alerts, and Tesla text, charger and calls.
  - **Calling and reminders:** call copy, moves, tiles, history, outbox, peer codec, dinner-call
    model; routine model and schedule; reminder requests and visibility.
  - **OTA and settings:** `OtaState`, OTA URL rejection, settings UI probe policy, reveal plan,
    settings tree.
  - **Phone remote and network parsers:** scene and layout policy, companion sources and photos,
    shared-album parsing, MQTT framing, Music Assistant WebSocket framing, YouTube parsers, remote
    image cache.

## Bugs found and fixed

Each fix is its own commit with a regression test:

1. **Valve-like switch shut off the water without asking**
   (`ControlCenterCalls.kt`, `CcPolicy.ask`). This one is safety-relevant.
   - A water main wired as a switch (e.g. `switch.water_main_valve`) is the valve everywhere
     else: kids mode, Security, leak alerts and voice.
   - But the "Ask before shutting off the water" rule only checked the `valve` domain, so the
     screen, the phone remote and voice turned it off with no confirm.
   - Test: `ControlCenterValveSwitchConfirmTest`.
2. **"Turn on &lt;scene&gt;" by voice failed** (`ControlCenterJarvisCommand.kt`).
   - The resolver matched the scene, but no call was built for the ON verb, so the reply was
     "I can't turn on Movie time."
   - Test: `ControlCenterJarvisSceneOnTest`.
3. **A weekly routine behind a skipped week never got its alarm** (`RoutineSchedule.kt`).
   - The fixed 14-day lookahead (today + 13 days) found no occurrence, so `nextWake` returned
     null and no alarm was armed.
   - The routine started only if something else re-ran the engine first.
   - Test: `RoutineScheduleSkippedWeekTest`.
4. **New Year's Eve calls showed under "Earlier" on 1 Jan** (`CallingCopy.kt`).
   - Day keys were `YEAR * 400 + DAY_OF_YEAR`, so 31 Dec and 1 Jan were 36 keys apart.
   - Test: `CallingCopyNewYearTest`.
5. **Legacy countdown dates were a day short across DST** (`CountdownConfig.kt`, low severity).
   - The fallback for invalid dates saved by older builds truncated a 23-hour span.
   - Test: `CountdownConfigDstTest`.

### Found, not changed

- **`MqttClient.readRemainingLength` rejects every valid 4-byte MQTT length (2 MiB and up).**
  - Its guard runs after the multiplier is bumped. The comment says it only rejects more than
    4 bytes.
  - In practice this is an accidental 2 MiB packet cap. Fixing the decoder alone would let a
    broker make a Portal allocate up to 256 MB.
  - The right change is a decoder that follows the spec plus an explicit size cap that skips
    oversized packets. That is a design call, so it was left as is.
  - Known side effect: a retained message over 2 MiB on a subscribed topic would make the
    publisher reconnect in a loop.
- **Minor oddities, each pinned by a coverage test:**
  - `RoomSeenWire` encodes a sighting age of exactly the 5-minute cap, which the decoder then
    drops.
  - Chore and reminder codecs accept the version as the string `"1"`.
  - `HouseholdListCommands.useAlt`/`keepName` with an unknown id says `Kept “”.`
  - `CcEntityParser.isoMs` reads `.5` as 5 ms; Home Assistant always sends 6 digits.

## Suite time

| | Before | After |
|---|---|---|
| `testDebugUnitTest --rerun` wall time (warm, M-series Mac) | 45.3 s | 44.1 s |
| Sum of suite times reported by JUnit | 47.4 s | 42.0 s |
| `createDebugUnitTestCoverageReport` (coverage on, warm) | 62 s | – |

The new suites add about 0.2 s in total, so the difference above is noise. They don't sleep and
use no network. The only sockets are loopback ones, in the MQTT framing tests.

## What remains uncovered, and why

- **Compose UI (31k lines, 1.5%) and Android components (4.3k, 5%).** These need a device or
  Robolectric. Robolectric has no instrumented android-all for compileSdk 36 / AGP 9 yet (see
  AGENTS.md); they are verified on device.
- **Platform glue that contains logic (31.7k lines, 32%).** This is the largest remaining real gap.
  - The biggest untested pieces are `PhotoFrameController` (2.2k missed lines), `FleetRoutes`
    (security: the on-device HTTP API, 428 missed), `FaceRenderer`, `MqttPublisher`,
    `SettingsSync` (sync, 236) and `ControlCenterEngine` (230).
  - Their decisions are interleaved with Context, SharedPreferences, Handler or Bitmap calls.
  - Covering them needs small seams (pure functions extracted from the routes and engines, or
    injected storage), which is a production refactor and out of scope here.
  - Recommended next: `FleetRoutes` request validation, then `SettingsSync` and
    `ControlCenterEngine`.
- **Classifier false positives.** These are counted as "pure" but can't run on the JVM:
  - `CallingMediaLink`, `CallingMediaKit` and `CallingKit` reference only `org.webrtc` and native
    code, not `android.*`. They need a device.
  - `WhosThereModels` (TensorFlow Lite), `StarField`, `MusicStageTheme` and `RoutinePictureRes`
    are drawing or resource tables.
  - Small 0% slivers in UI files (`ControlCenterChrome`, `ChoreTiles`, `SettingsComponents`, ...)
    are constants and non-Composer lambdas belonging to composables.
- **Network-bound code with no injection seam.** This covers `RemoteAlbum` fetch paths (iCloud,
  CloudKit, Google, Synology), `Http.kt`, the network side of `YtQueryCompletions`, `OtaHttp`
  downloads, `SpeedTest`/`ApprovedToolsSpeedRunner`, and MQTT over TLS. Only their pure parsing
  and URL-rejection helpers are tested.
- **Left on purpose:**
  - `ControlCenterPlanFade`: drawing math with no safety role.
  - `CcAlertHistory`: a process-wide singleton; testing it would share state between tests.
  - Compiler-generated null-check and `when` branches that the code can't reach.
