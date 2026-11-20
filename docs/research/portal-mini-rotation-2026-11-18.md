# Portal Mini rotation spike — 2026-11-18

Device: Portal Mini `<mini-serial>` (model "Portal", device `omni`, hw `atlas`), Android 10,
800×1280 @ 160 dpi. Hearthpane 1.251 during the physical rotation rounds, 1.253 afterwards.
Raw evidence (logcat, 1 Hz `dumpsys window` poll, getevent) is private:
`portal-workspace/private-captures/portal-mini/rotation-capture-2026-11-18/`.
Spike APK source: `portal-workspace/spikes/minirot/` (`dev.spike.minirot`).

## Verdict

**The Mini is not firmware-pinned to portrait.** The handover's §4 is wrong: with Hearthpane's
`HomeActivity` (no `screenOrientation`) in front, physically turning the Mini rotated the display to
landscape within ~1 s, and back to portrait, on every attempt. The tester never rotated the device.

There is one real platform limitation: **the sensor path only ever proposes ROTATION_0 and
ROTATION_90.** Turning the Mini the other way (ROTATION_270 pose) leaves the UI in portrait, sideways.
The panel/compositor *can* show 270 — an app that requests `reverseLandscape` gets ROTATION_270
immediately — so Hearthpane can cover the second landscape direction itself.

## Evidence

### Platform state (static)

| Item | Value |
|---|---|
| `settings get system accelerometer_rotation` | `1` (auto-rotate on) |
| `user_rotation` | `0` |
| DisplayRotation | `mSupportAutoRotation=true`, `USER_ROTATION_FREE`, `mFixedToUserRotation=false` |
| Dock | `mDockMode=EXTRA_DOCK_STATE_DESK`, `mDeskDockEnablesAccelerometer=true`, `mDeskDockRotation=-1`, uiMode `desk` |
| Sensor | `3-axis Accelerometer` (vendor mq3416, handle 0x1), WindowOrientationListener enabled at 66 ms |
| Mapping | `mLandscapeRotation=ROTATION_90 mSeascapeRotation=ROTATION_270 mPortraitRotation=ROTATION_0` |
| Props | `ro.boot.orientation=0`; no `ro.sf.hwrotation`; no `persist.*rotation*` except AOSP `persist.demo.hdmirotationlock=false` |
| Vendor hooks | `services.jar` strings contain no Meta/vendor rotation property; only AOSP `persist.demo.*` |
| RRO | Only `com.facebook.aloha.rro.niu.android` enabled on `android` |

**Why the tester's commands did nothing:** the Mini reports a desk dock with
`mDeskDockEnablesAccelerometer=true`. In Android 10 `DisplayRotation.rotationForOrientation`, the
desk-dock branch (`preferredRotation = sensorRotation`) is evaluated *before* the user-rotation-lock
branch. So `settings put system user_rotation …` and `cmd window set-user-rotation lock N` are
recorded (`mUserRotation=ROTATION_90/180/270`) but never applied. Reproduced today: lock 1/3/2/0 →
`mRotation` stayed `ROTATION_0` each time (device upright). `WindowOrientationListener` proposing 0 in
the tester's run was simply correct — the device was upright.

### Physical rotation rounds (owner turned the Mini, 2 rounds)

Gravity components from `AccelSensorJudge.mLastFiltered{X,Y,Z}` (m/s²), one sample per second:

| Time | Pose | X | Y | Z | Proposed | Display |
|---|---|---|---|---|---|---|
| 19:27:18 | portrait (on stand) | -0.6 | 8.3 | 3.0 | 0 | ROTATION_0 |
| 19:27:09 | turned left | +8.9 | -0.8 | 2.9 | **1** | **ROTATION_90** (1280×740 dp) |
| 19:27:24–33 | turned right | -10.2 | -1.2 | 0.7 | **0** (predicted 0) | ROTATION_0 |
| 19:27:40–44 | turned left | +9.2 | -0.9 | 1.2 | **1** | **ROTATION_90** |
| 19:27:50–54 | turned right | -10.2 | -2.0 | 0.0 | **0** (predicted 0) | ROTATION_0 |

Logcat confirms three display rotations to 90 and none to 270 across the whole session
(`ActivityTaskManager: Config changes … w1280dp h740dp land … mRotation=ROTATION_90` at 19:23:38,
19:27:08, 19:27:39, each followed by `AudioService.RotationHelper: publishing device rotation =1`).
For the right turn, AOSP's judge would compute an orientation angle of ~263° → nearest rotation 3
(or clear to -1). Instead the judge reports a *predicted* rotation of 0, so the 270 pose is filtered
inside the judge in Meta's build. It isn't a tilt problem: the tilt was 0–4° and the tolerance is
`[-25, 65]`. No property or setting toggles this.

### Meta's own stack

Meta does not drive rotation; it follows it. `aloha.RotationManager` / `OrientationStrategyManager` /
`AlohaCameraPipeline` (pid 5211) log `Screen orientation changed: screenToUprightRotation=ROTATE_90`
*after* each platform rotation and re-plan the camera (`BroadcastStreamManager … renderSize: 1280x720`
in landscape, `720x1280` in portrait). Rotation of Meta's own UI with Meta's launcher in front was not
separately tested; every round had Hearthpane in front. No broadcast or property is involved — the standard WindowManager rotation is the mechanism. `getevent` shows no
accelerometer node (the sensor goes through the sensor HAL, not input).

### App-requested orientation (spike `dev.spike.minirot`, device upright)

| Activity `screenOrientation` | Result |
|---|---|
| `landscape` | ROTATION_90, 1280×740 dp |
| `reverseLandscape` | **ROTATION_270**, 1280×740 dp |
| `sensorLandscape` | ROTATION_90 |
| `reversePortrait` | ROTATION_0 (desk dock keeps the sensor's portrait) |
| `fullSensor` / `sensor` / `fullUser` | follow the sensor = same as unspecified here |

## Mechanism for Hearthpane

1. **Keep `HomeActivity` `unspecified`, or set `fullSensor` explicitly.** The two behave the same on
   the Mini because of the desk-dock branch. Portrait ⇄ landscape (turned left, ROTATION_90) then
   works with no code. Add
   `configChanges="orientation|screenSize|screenLayout|smallestScreenSize"` to avoid recreate (handover
   §7.4 state-loss list still applies).
2. **Remove the hard `landscape` locks** on the 7 locked activities (WebApp, ControlCenter, Lamp,
   GuestCard, BedtimeStory, CallsActivity, CallActivity). Opening any of them from portrait currently
   forces ROTATION_90. Use `unspecified`, or `sensorLandscape` where landscape is truly required
   (calling capture is fixed 1280×720).
3. **Cover the second landscape direction in-app.** Use one small `OrientationEventListener`-based
   controller, active only on a Mini-class display (`smallestScreenWidthDp < 1000`, not a model gate).
   It maps the raw device angle (≈90° → `SCREEN_ORIENTATION_REVERSE_LANDSCAPE`, ≈270° → `LANDSCAPE`,
   ≈0° → `PORTRAIT`/unspecified) with a ~400 ms hold and ±30° windows, and calls `setRequestedOrientation`.
   Prototype: `spikes/minirot/src/dev/spike/minirot/Follow.java`. Forced `reverseLandscape` is proven
   to give ROTATION_270. **Still to confirm with the owner:** the right-turn round with `Follow`
   in front, to check that `OrientationEventListener` sees the 270 pose (raw `mLastFilteredX=-10.2`
   says it should) and that the transitions feel right.
   - If step 3 is skipped, document "turn the Mini to the left for landscape".
4. No provisioning setting is needed: `accelerometer_rotation=1` is already the default, and
   `user_rotation` is ignored while docked. Don't rely on `cmd window set-user-rotation`.

## Owner steps still needed

1. With `MiniRot › Follow` in front (`adb shell am start -n dev.spike.minirot/.Follow`), turn the
   Mini: portrait 10 s → **right** 10 s → portrait 10 s → left 10 s → portrait. Capture with
   `adb logcat -s MiniRot:I` plus the `dumpsys window` poll.
2. After that: `adb uninstall dev.spike.minirot`.
