# Camera + mic feasibility spike: results (gates Calling and "Who's there?")

> **Final answer (daytime rerun 2026-11-18):** Calling = **full video via camera 0** (GO). Who's there? = **visible ~1.5 s glance** (GO). Camera 1 = no access. See "Final verdicts" and "Daytime rerun".

November 17, 2026, 23:15–23:36 PST. This follows `video-drop-in-feasibility-2026-11-17.md` and is
the spike that `docs/plans/big-three-2026-11-17.md` step 2 asked for. It ran on the Family Portal
(Portal+, Android 10, `QKQ1.210213.001`, not rooted) over USB adb.

The test app was separate: `com.immortal.camspike`, source in
`portal-workspace/spikes/camspike`, targetSdk 29. It was granted CAMERA and RECORD_AUDIO with
`pm grant`, used only Camera2/AudioRecord/MediaCodec/WebView, and was **uninstalled at the end**.
The launcher was never stopped or changed. No images were saved, so `private-captures/` was not
used. Audio was captured but never stored or played. Raw logcat/dumpsys evidence is in
`portal-workspace/spikes/camspike/evidence/` (local only, not committed).

## The big confounder: the privacy shutter was closed

At 23:03:55, about 11 minutes before the spike started, the physical camera shutter was slid shut
(it looks like the owner did this for the night). Nothing in the spike moved it, and it must not be
worked around:

```
23:03:55.410 I/AppStateContentProvider: Shutter state closed [true]
23:03:56     DISCONNECT device 1 client for package com.facebook.portal.aiservice     (dumpsys media.camera)
23:03:56.063 I/PresenceManager: Shutter state closed, ignoring onCameraAvailable
```

Effects: aiservice let go of the physical camera, Meta presence stopped (its last
`onNotifyPresence presence updated` was at 23:03:50), and **no camera frames could reach us**.
So the API, policy, LED, latency and lifecycle results below are real measurements. Frame rate,
framing, the glance frame latency and camera→encoder throughput still need **one daytime rerun with
the shutter open** (see "Needs the owner").

## Final verdicts (after the daytime rerun, 2026-11-18)

| Feature | Verdict | Why |
|---|---|---|
| **Calling: full video via camera 0 (Meta virtual, AI-framed)** | **GO** | With the shutter open and a visible preview, a 120 s session ran with **no LedPolicy violation**: 3565 frames, 29.9 fps, first frame at 1.37 s, non-black. Camera→HW H.264 ran at 29.6 fps and 2.5 Mbps for 60 s. WebView getUserMedia gave 1280×720 at 29.7 fps for 20 s. aiservice keeps camera 1, the AI director stays active, and Meta presence keeps working. The one hard rule: keep a visible preview whenever the camera is open for more than about 7 s. |
| **Calling: camera 1 (physical)** | **NO-GO** | Still hidden with the shutter open: `ENUM ids=[0]` and `No camera device with ID "1"`. This is a per-client `CAMERA_PRIV` filter, not a shutter effect. |
| **Calling: audio-only fallback** | **GO** (unchanged) | Kept for shutter-closed, privacy-mode and low-memory cases. WebRTC software AEC is required, because the platform effects aren't available. |
| **Who's there?: visible glance** (open → preview about 1.5 s → close) | **GO** | 6/6 glances: open 30–55 ms, first frame 562–664 ms, 45 frames by 2.0–2.1 s, close 141–186 ms. The LED is on for about 2.1 s per glance, with no violations. Auto-exposure settles by about frame 6 (luma 81 → 110). Camera 0 has no face detection, so we need our own model. Glances must run from a resumed Hearthpane activity with the screen awake. |
| Who's there?: hidden glance (no preview) | Technically works (3/3, no violation, because it closes before 7 s), but **don't ship it** | The green LED still lights each time, so it's never silent. Our privacy design requires a visible preview anyway. |

### Original night verdicts (shutter closed, 2026-11-17), kept for the record

| Feature | Night verdict |
|---|---|
| Calling via camera 0 | GO-WITH-CAVEATS, pending a shutter-open rerun (LED policy killed every session at 7.0 s while frames were black) |
| Calling via camera 1 | NO-GO (hidden) |
| Audio-only | GO |
| Who's there? background glance | Not viable silently. Shutter-closed close took about 3.5 s, so the LED was lit about 86% of the time. |

## Daytime rerun (2026-11-18, 17:40–17:48 PST, shutter open)

Preconditions:
- The shutter was opened at `17:39:48 AppStateContentProvider: Shutter state closed [false]`.
- aiservice was back on camera 1 (`Camera ID: 1 … com.facebook.portal.aiservice`).
- Presence heartbeats were flowing.
- The launcher (1.251) was on top: `PhotoFramePreviewActivity`, with the last user activity 7507 s earlier.

The same spike APK and `run.sh` were used (evidence in `spikes/camspike/evidence/day/`). For the
framing run only, the preview was made full screen at the owner's request. The owner was present.
No images were saved, and nothing was played.

### Q1: does camera 0 survive past 7 s with a visible preview getting real frames? **Yes.**

```
RESULT open id=0 openMs=114 firstFrameMs=1372 frames=3565 avgFps=29.9 over 120538ms lifecycle=null
SEC 0 t=2 fps=27 luma=149.3 … t=60 fps=30 luma=120.4 … t=120 fps=30 luma=108.0
LED on 17:40:57.359 -> off 17:42:57.961 (our close); no handleViolation lines
dumpsys media.camera Led Policy Violation log: newest entry still 11-17 23:34:48 (night run)
```

- Sustained 29–31 fps at 1280×720 YUV every second for 120 s.
- Frames are non-black (mean luma 101–150).
- `PreviewState` evidently tracks real preview frames: the same code was killed at 7 s while the
  shutter-closed frames were black.
- A second 30 s full-screen run: `openMs=41 firstFrameMs=788 frames=884 avgFps=30.1`.

### Q2: AI framing

`aloha.TrackAndHoldAiDirector … Forcing brake movement` logged about once per second throughout
our sessions (133 lines in the 120 s run), so Meta's auto-framing director is active on the
stream we receive. The capture-result crop stays `Rect(0, 0 - 1280, 720)`. Framing happens
upstream, as content motion. In the 120 s run, two motion events show the pattern you'd expect
from a reframe:
- t=6–13 s: diff 4–9, with the centroid dipping 0.502→0.478 and returning.
- t=101–110 s: diff spikes to 21. Then the centroid drifts smoothly over about 8 s (x 0.505→0.563,
  y 0.409→0.467) and **settles at the new position** with diff back to noise (about 1.4). That
  slow glide-then-hold looks like a pan/zoom, not a person stepping out of a fixed frame.

During the 30 s full-screen run the scene was still (diff 1.33–1.52, centroid fixed), so nobody
moved. Verdict: **AI framing is active (director logs) and very likely visible in our stream**. To
confirm by eye, the owner can walk across the room during a call build. There's no metadata
signal to assert on.

### Q3: camera 0 → HW H.264, 720p30, 60 s

```
ENCODE codec=OMX.qcom.video.encoder.avc
RESULT encode id=0 1280x720 frames=1777 keyFrames=30 avgFps=29.6 avgKbps=2475 elapsedMs=60001 lifecycle=null
top (800% = 8 cores): 319% idle; camera.provider 129%, aiservice 106%, system_server 81%, cameraserver 19%, VCM 16%
idle-ish open-only run: 379% idle   |   night idle baseline (aiservice stopped by shutter): 666% idle
camspike PSS 61.9 MB (Graphics 30.9 MB);  MemFree 91 MB, MemAvailable 339 MB;  swap 2.9 MB free
```

- The camera pipeline (HAL + aiservice) runs 24/7 anyway. Feeding virtual camera 0 plus the HW
  encoder added roughly 0.6 cores over a plain open. The app itself costs little.
- Memory is the constraint (swap is full), so budget the call UI tightly.

### Q4: WebView getUserMedia with real frames: **survives**

```
WEBJS track video camera2 0, facing front {… 1280x720}
WEBJS WSEC 1 rvfcFrames 29 decoded 35 vw 1280 vh 720 luma 106.1
WEBJS WSEC 20 rvfcFrames 594 decoded 600 vw 1280 vh 720 luma 104.3
WEBJS WRESULT rvfcFps 29.7         LED on 17:46:30.926 -> off 17:46:52.564; no violation
```

The visible element was only 320×180 inside the WebView, which shows that the preview size
doesn't matter.

### Q5: does Meta presence resume after release? **Yes; it never stopped.**

- During our sessions, aiservice kept people detection running: `CameraServiceController:
  Notify people presence` and `PresenceManager: onNotifyPresence presence updated [APPLICATION]`
  three times at 17:41:18–17:42:18, while our 120 s session streamed.
- After uninstall, aiservice still held camera 1 and `17:48:27 onNotifyPresence presence updated`
  appeared. The launcher's own reader logged
  `HearthpaneFollowMe: presence here ABSENT -> PRESENT … broadcast sent=true`.
- As before, `Availability idle since top app is not whitelisted` is logged while any non-Meta
  app is on top (16× during the spike). That's the existing launcher behaviour too.

### Q6: visible glance for Who's there? (45 frames ≈ 1.5 s of preview, every 10 s)

```
GLANCE 0 preview=true open=30ms session=74ms firstFrame=650ms 45frames=2114ms close=148ms complete=true
GLANCE 5 preview=true open=42ms session=99ms firstFrame=562ms 45frames=2004ms close=157ms complete=true
RESULT glance id=0 runs=6 ok=6 latencyMs(open->45 frames) min=2004 median=2083 max=2114
LED: 17:44:10.107 ON -> 17:44:12.282 OFF (2.18 s) … 17:45:00.119 ON -> 17:45:02.170 OFF (2.05 s); 0 violations
first-glance luma per frame: 81 81 106 106 105 113 … 109 (AE settled by frame ~6)
no-preview control: 3/3 ok, firstFrame 608–923 ms, close 127–164 ms, 0 violations
```

- **Latency budget:** about 0.6 s to the first usable frame, and about 0.8 s to a well-exposed
  frame. A 1.5 s look gives about 25–40 frames to run face embedding on.
- Close takes about 150 ms with real frames. Last night's 3.5 s close was the black-frame flush,
  not normal behaviour.

### Recommended approach (final)

1. **Calling:** full video via camera 0 at 1280×720 or 30 fps, through WebRTC (native or
   WebView). Always show a visible local preview while the camera is open. Use HW H.264 or VP8
   (VBR), VOICE_COMMUNICATION, and WebRTC software AEC/NS/AGC. Watch the shutter: when it's
   closed, go audio-only without opening the camera.
2. **Who's there?:** a visible look of about 1.5 s from a resumed Hearthpane screen. Trigger it
   from Meta presence (`ABSENT -> PRESENT`), and run our own face embedding on about 25 frames.
   Close right away, and expect the LED to be on for about 2 s. No hidden or background glances,
   and no capture while dreaming or with the screen off.

### Still open (not blocking)

- Echo cancellation quality on the loud speaker needs a two-device call. This wasn't tested,
  because the rule was no playback.
- Whether Meta's in-call beamformer (`AiBoommanController setInCall(true)`) improves our
  VOICE_COMMUNICATION capture.
- Memory headroom during a real call with Hearthpane in front.
- Other Portals (Gen-1 / Android 9) were not tested.
- The green LED was observed only through logs (`set green led on/off`). It wasn't checked by eye.

## Night measurements (2026-11-17, shutter closed)

### 1. Camera2 enumeration (what a third-party app sees)

```
ENUM ids=[0]
ENUM id=0 facing=0(front) hwLevel=0(LIMITED) caps=[0](BACKWARD_COMPATIBLE)
ENUM id=0 activeArray=Rect(0, 0 - 1280, 720) pixelArray=1280x720 physicalIds=[] sensorOrientation=0
ENUM id=0 fpsRanges=[[15, 30], [30, 30]] maxDigitalZoom=8.0 faceDetectModes=[0] maxFaces=0
ENUM id=0 YUV_420_888 sizes(maxfps)=1280x720@30.0 320x240@30.0 640x480@30.0
ENUM id=0 PRIVATE(SurfaceTexture) sizes(maxfps)=1280x720@30.0 320x240@30.0 640x480@30.0
ENUM id=0 JPEG sizes(maxfps)=1280x720@30.0 1280x720@30.0 320x240@30.0 640x480@30.0
```

Camera 1 (the 12 MP sensor, FULL/level 3 per the earlier `dumpsys`) is filtered out for apps
without `CAMERA_PRIV`:

```
W/ServiceManager: Permission failure: android.permission.CAMERA_PRIV from uid=10139
E/CameraService: validateClientPermissionsLocked: CameraId 1 is not valid
RESULT open id=1 FAILED CameraAccessException reason=2 CAMERA_DISCONNECTED (2): ... No camera device with ID "1" currently available
```

Camera 1 *might* only be filtered while the shutter is closed, but the `CAMERA_PRIV` check
suggests a per-client filter. The daytime rerun should repeat `enum` to confirm.

### 2. Opening camera 0 / camera 1 with Meta services running

**Camera 1:** rejected, as above. No eviction, no `CAMERA_IN_USE`/`MAX_CAMERAS_IN_USE`.

**Camera 0:** opens quickly and starts Meta's call pipeline. aiservice stays the only owner of
the physical sensor and **is not evicted**:

```
I/CameraService: ProcessPolicy: isUidForegroundActive() 10139 active
I/Camera2ClientBase: Camera 0: Opened. Client: com.immortal.camspike
I/virtualcameramanager.SmartCameraHWDevice: Constructing Virtual Camera: ID 0, facing front
I/virtualcameramanager.SmartCameraHWDevice: set green led on when opening camera
D/AlohaPrivacyShutterAnalytics: privacy_event {shutter_state=CAMERA_LENS_COVERED, led_state=GREEN_LED_ON, ...}
I/VirtualCameraManagerService: START_BROADCAST / ADD_SURFACE
I/aloha.VirtualCameraCoordinator(aiservice): Virtual camera session opened
I/aloha.AlohaCameraPipeline(aiservice): Adding video output: ..., size:1280x720
I/aloha.AiBoommanController(aiservice): setInCall() called with inCall = true
I/aloha.AiBoommanController: Noise suppression mode set to: STATISTICAL_PLUS_RESIDUAL_ECHO / Beamformer direction 15.0, width 125.0
I/PresenceManager: onCameraUnavailable not real camera [0]
CAMSPIKE: CAM 0 opened in 38ms (99 ms on the first, colder run)
```

With the shutter closed, aiservice's Scaas (AI framing) stage produced nothing, so the virtual
camera sent black/failed frames:

```
I/VirtualCameraReaderYUV: reapOldRequests: Did not get output from Scaas ... Write black frame to capture request
I/VirtualCameraReaderNative: onCaptureFailure: Success sent capture failure with frameNumber 0..3
CAMSPIKE: SEC 0 t=1..7 fps=0 total=0
```

**Meta's LED policy kills the session after exactly 7.0 s without a live preview.** This
happened in 100% of opens (open, open+preview, 18 glances, WebView, encoder):

```
I/CameraService: handleViolation: LedPolicy violation occured! LedState:1 PreviewState:0
D/PlatformStateService: setCameraDeniedAccess: LedPolicy violation occurred: reason: Led (1) Preview (0)
CAMSPIKE: CAM 0 ERROR ERROR_CAMERA_DISABLED at +7098ms   (with a SurfaceView preview: +7037ms)
dumpsys media.camera -> == Camera Led events: Led Policy Violation log == 11-17 23:22:26 : LedPolicy violation occurred ...
E/CameraService: CameraPrivacyPolicy::explicitly reject connections in shutter mode, apps failed to do so!
```

- Timeline from the glance run: LED_ON 23:26:02.330 → VIOLATION 23:26:09.335 → LED_OFF
  23:26:10.894. That's 7.0 s from LED on, and the same in every cycle.
- Adding a visible 960×540 `SurfaceView` preview (stream usage 0x900, HW_COMPOSER|TEXTURE) did
  **not** clear `PreviewState:0` while frames were black. The likely reason is that the policy
  tracks preview frames actually being delivered. Whether a visible preview with live frames
  satisfies it is **the main open question for Calling**, and it can't be answered with the shutter
  closed.
- Violations did **not** escalate. Opens kept working after more than 15 violations, and the
  launcher's own camera access was not affected.
- CameraService expects apps to refuse to connect while the shutter is closed. Apps can detect
  it from `com.facebook.aloha.intent.action.SHUTTER_STATE_CHANGED` (SystemUI registers it; whether
  third-party apps can receive it is untested) or from black frames plus `onCaptureFailed`.

**LED:** observed through logs, not by eye: `set green led on when opening camera`,
`PrivacyStateHAL updateLedListeners() ON`, `StatusViewPrivacyController onLedStateUpdated
ledState = true`. **Any third-party camera 0 open lights the green LED.** It goes off about
3.5 s after `close()`, when the virtual camera finishes closing. aiservice's own 24/7 use of
camera 1 does not light it.

**Presence:** `PresenceManager` ignores virtual-camera use (`onCameraUnavailable not real
camera [0]`). The one thing that stops Meta availability is our activity being on top: `Availability
idle since top app is not whitelisted`. That happens for any non-Meta app, Hearthpane included,
whether or not the camera is open. Presence-driven behaviour during a call is therefore paused by
the call *screen*, not by the camera.

**Reclaim:** aiservice never took camera 0 back from us. Each session ended because of the LED
policy, not because of eviction or disconnect.

**Not measurable tonight (shutter):** time to first frame, sustained 720p fps over 60 s, and frame
statistics. The probe logs all of them (`SEC … fps luma diff centroid`, `RESULT … firstFrameMs
avgFps`), so the daytime rerun only needs to run the commands again.

### 3. Glance pattern (open → 5 frames → close, every 10 s for 3 min, no preview)

```
GLANCE 0 preview=false open=25ms session=52ms firstFrame=5069ms 5frames=5055ms close=3581ms complete=false lumas=[0 0 0 0]
...
GLANCE 17 preview=false open=47ms session=89ms ... close=3578ms complete=false
RESULT glance id=0 runs=18 ok=0
```

- Open was 22–58 ms and session configure 51–107 ms in all 18 runs. Opens were 100% reliable,
  and there were no stuck or leaked sessions (the camera service events log shows 18 clean
  CONNECT/DISCONNECT pairs).
- 0/18 runs got frames, because of the shutter. Each run waited the full 5 s timeout. Every
  session then went past 7 s once the ~3.5 s close was added, so 12 of the 18 logged LED-policy
  violations.
- **Side effects:** the green LED was on about 8.6 s of every 10 s, so to a person in the room it
  looks like the camera is always on and flickering. The glance also needs our activity to be
  resumed with the screen awake. The first attempt, made while the launcher's PhotoDreamService
  covered the screen, failed:
  `cannot open camera "0" from background (calling UID 10139 proc state 13)`.
- With live frames, a glance should be about 40 ms open + about 100 ms first frame (estimate) + 5
  frames at 30 fps ≈ 0.3 s, plus about 3.5 s close. That's under the 7 s limit, but the LED still
  lights for about 4 s each time. We also haven't proven that a glance without a preview avoids
  the violation when real frames flow.

### 4. Framing on camera 0

- The capture-result crop never changed (`cropRegion=Rect(0, 0 - 1280, 720)`), and there are no
  face results (`faceDetectModes=[0]`). Any framing is applied upstream: aiservice's AI director
  (`TrackAndHoldAiDirector…`, `AiBoommanController`) feeds the virtual stream through Scaas. So
  framing would show up as content motion, not as crop metadata.
- **Not verified.** No frames came, and nobody was present at night. The probe computes a
  64×36 luma grid in memory (mean, mean-abs-diff, brightness centroid) and never saves a frame.
  Daytime check: a person walks left to right, and the centroid/diff should follow while
  `cropRegion` stays fixed.

### 5. Microphone

```
MIC effects global: AEC.isAvailable=false NS.isAvailable=false AGC.isAvailable=false
MIC unprocessedSupported=false
MIC inputDevice type=15 name=Portal+ address=bottom rates=[8000..48000] chCounts=[1, 2]
MIC inputDevice type=15 name=Portal+ address=back   rates=[8000..48000] chCounts=[1, 2]
MICX <all 60 combos> ok  silenced=false  fx[AEC=null NS=null AGC=null]
     e.g. VOICE_COMMUNICATION 16000Hz mono ok minBuf=1280 rmsDbfs=-62.0 zeroFrac=0.152 silenced=false
```

- **Sources:** VOICE_COMMUNICATION, MIC, CAMCORDER, VOICE_RECOGNITION, UNPROCESSED and DEFAULT
  all initialize at 8/16/32/44.1/48 kHz, mono and stereo. The audio is real room noise (−45 to
  −87 dBFS in a quiet room at night), not zeros.
- **Routing:** VOICE_COMMUNICATION opens the HAL "primary input" at 32 kHz 2-ch on the
  **Built-In Back Mic** (`Devices type:0x80000080,@:back`). MIC uses the **bottom** mic
  (`0x80000004,@:bottom`). The raw 4-ch far-field array is still unreachable, as before.
- **AEC/NS/AGC: not available to apps.** `isAvailable()` returns false and `create(session)`
  returns null for every source. The cause is that `/vendor/etc/audio_effects.conf` declares
  `pre_processing { voice_communication { aec ns } }` but never lists the pre-processing libraries
  (`libqcomvoiceprocessing.so`/`libaudiopreprocessing.so` exist but aren't in `libraries {}`).
  The HAL may still apply Fluence voice-comm processing inside VOICE_COMMUNICATION
  (`persist.audio.fluence.voicecomm=true`), but that can't be confirmed without playback. That
  test was not run, because it's night.
- **Lead:** opening camera 0 made aiservice switch on its "AI boom mic" in-call processing
  (`setInCall(true)`, NS `STATISTICAL_PLUS_RESIDUAL_ECHO`, beamformer FIXED 15°/125°). Whether
  that processing reaches our VOICE_COMMUNICATION AudioRecord is unknown and worth one daytime A/B
  test.
- **Jarvis concurrency:** on this Gen-2 Portal, Jarvis (`com.portal.assistant`) holds **no mic in
  standby**. Its notification says "Jarvis ready / Voice wake enabled outside Jarvis", but its
  source (`AssistantService.kt`: "Standby holds NO mic… gen2 'hey jarvis' while the app is on
  screen") and `dumpsys media.audio_policy` (empty `Inputs dump` with the spike stopped) show that.
  So there was no capture conflict to test. Our captures were never silenced
  (`MICCFG … silenced=false`), and Jarvis was the same process (PID 7872), still "ready",
  afterwards. Launching Jarvis's UI to force a conflict was skipped, because it could play sounds
  at night. The rule to expect: on Android 10 the foreground UI wins, so a Jarvis conversation
  started during a call would silence one side.

### 6. Hardware codecs

| Codec | Name | HW | 720p max fps (spec / achievable) | Max size | Instances |
|---|---|---|---|---|---|
| H.264 enc | `OMX.qcom.video.encoder.avc` | yes | 240 / 50 | 4096×2160 | 24 |
| HEVC enc | `OMX.qcom.video.encoder.hevc` | yes | 240 / 49 | 4096×2160 | 24 |
| VP8 enc | `OMX.qcom.video.encoder.vp8` | yes | 240 / 49 | 3840×2160 | 16 |
| VP9 enc | **software only** (`c2.android.vp9.encoder`) | no | — | 2048×2048 | — |
| H.264/HEVC/VP8/VP9 dec | `OMX.qcom.video.decoder.*` | yes | 240 / 262–352 | up to 4096×2160 | 6–24 |
| Opus enc/dec | `c2.android.opus.*` | sw | — | — | — |

HW encoders are **VBR only** (`cbr=false vbr=true`).

**Encode test, 30 s at 720p30, HW H.264, synthetic source** (a camera source wasn't possible
with the shutter closed):

```
ENCODE codec=OMX.qcom.video.encoder.avc
RESULT encode id=synthetic 1280x720 frames=908 keyFrames=16 avgFps=30.3 avgKbps=405 elapsedMs=30003
top: media.codec 27.5% (of one core), spike app 24.1% (includes canvas drawing); system 679%/800% idle
Mem: 73M free, Swap 1.5G total / 2.0M free
```

A camera→encoder session configures fine (`configureStreams … usage 0x10000` for the encoder
plus `usage 0x900` for the preview). It delivered 0 frames because of the shutter.
**Memory is the real constraint**: swap was full and only 73 MB was free.

### 7. WebView getUserMedia

```
WEB provider=com.facebook.portal.webview 16.0.1.0.0.1038095491   (Chrome/138.0.7204.180)
WEBJS secure true mediaDevices true RTCPeerConnection function
WEB onPermissionRequest resources=[VIDEO_CAPTURE, AUDIO_CAPTURE] -> grant
WEBJS track audio {"echoCancellation":true,"noiseSuppression":true,"autoGainControl":true,"sampleRate":48000,"channelCount":1}
WEBJS track video camera2 0, facing front {"frameRate":30,"facingMode":"user"}
WEBJS devicesAfter ["audioinput:Speakerphone","audioinput:Headset earpiece","videoinput:camera2 0, facing front"]
WEBJS offerCodecs [opus, red, G722, PCMU, PCMA, VP8, VP9, H264, H265, ...]
WEBJS WSEC 1..10 rvfcFrames 0 vw 2 vh 2   (shutter)   -> LedPolicy violation at +7.0 s, as with Camera2
```

The WebView path works and is the cheapest way to get a working call stack. You get WebRTC with
software AEC3/NS/AGC, which matters because the platform effects are missing, plus all four video
codecs. It goes through the same virtual camera 0, so it has the same LED rule and the same
preview requirement.

## Recommended capture approach (night draft; superseded by "Recommended approach (final)" above)

1. **Calling video: camera 0 only, through WebRTC, with an always-visible local preview tile**
   showing live frames for the whole call. The preview is required by Meta's LED policy as well as
   by our own privacy design. Use 1280×720 or 640×480 at 30 fps with HW H.264 (or VP8), VBR.
   Native `libwebrtc` with a Camera2 capturer and HW encoder factory, or a WebView page, both work.
   WebView is quicker to build. Native gives tighter memory control.
2. **Audio:** VOICE_COMMUNICATION at 16 or 48 kHz mono, with **WebRTC software AEC/NS/AGC
   switched on**. Don't count on `AcousticEchoCanceler`.
3. **Shutter-aware:** listen for shutter changes. When closed, don't open the camera, show "Camera
   is covered on Kitchen", and fall back to audio-only. CameraService logs an error when apps
   connect in shutter mode.
4. **Teardown:** call `close()` at least 3.5 s before anything needs the LED off. Never open the
   camera from a service, from the dream, or with the screen off: it fails, and it would be a
   privacy problem anyway.
5. **Who's there?:** don't run periodic background glances. Make it an explicit, foreground "look":
   the user taps, or a presence trigger brings a visible card up first. Show a visible preview,
   open camera 0 for 1–2 s, run our own on-device face embedding (camera 0 has no face
   detection), then close. Accept that the LED is on for about 4–5 s. The trigger can be the
   launcher's existing Meta presence reader, which needs no camera.

## Exact risks

- **LED policy (resolved in the daytime rerun, still a hard rule):** a session dies at 7.0 s unless `PreviewState` becomes 1. A visible preview getting live frames does set it (120 s, no violation). Original note: it was not yet
  proven that a third-party preview with live frames sets it. If it doesn't, video calling on
  camera 0 is NO-GO and Calling becomes audio-only (plus recorded video messages, which have the
  same problem).
- **Camera 1 inaccessible (certain):** there's no raw 12 MP stream or fallback camera. Calling
  depends entirely on Meta's virtual-camera pipeline (aiservice + VirtualCameraManager + Scaas).
  An OTA or aiservice change could break it.
- **LED behaviour (certain):** every open lights the green LED. Background glances are visible,
  which is good for privacy but kills the "silent recognition" idea.
- **Foreground only (certain):** no camera from background, the dream or a service.
- **No platform AEC (certain):** software AEC is mandatory. The Portal speaker is loud, so echo
  quality needs a daytime two-device test.
- **Memory (high):** 73 MB free with swap full during the encode test. A WebView/WebRTC call
  alongside Hearthpane may trigger LMK kills, so measure on a real call.
- **Presence pause (certain, low impact):** Meta availability goes idle while any non-Meta app is
  on top. The call screen pauses Meta presence; camera use does not.
- **Other Portals:** only the Family Portal was tested. Gen-1/Android 9 units may differ completely.

## Needs the owner (night list; items 1–3 were done in the daytime rerun, item 4 and the LED-by-eye check are still open)

1. **Open the shutter** (camera only). Then reinstall the spike (`cd
   portal-workspace/spikes/camspike && ./gradlew :app:assembleDebug`, `adb install`, `pm grant`
   CAMERA/RECORD_AUDIO) and rerun:
   `./run.sh NAME TIMEOUT_S -- <am extras>`, e.g. `./run.sh enum 30 -- --es mode enum`,
   `./run.sh open0p 110 -- --es mode open --ei id 0 --ei dur 60` (with preview), the same plus
   `--ez preview false`, `./run.sh glance 260 -- --es mode glance --ei id 0 --ei frames 5 --ei
   interval 10 --ei dur 180 --ez preview false`, `./run.sh encodecam 70 -- --es mode encode --ei id 0
   --ei dur 30`, `./run.sh webview 90 -- --es mode webview --ei dur 30`. Uninstall afterwards. Then check the Led Policy Violation log in `dumpsys
   media.camera`.
   **Deciding question:** does the session live past 7 s when a visible preview gets live frames?
2. **A person in front of the camera**, walking left to right, to confirm AI framing on camera 0
   from the probe's diff/centroid lines (no images saved), and to measure first-frame time and
   sustained fps.
3. **Look at the green LED** during a 60 s open and during glances.
4. **Daytime audio:** one AEC check with playback (two devices or a loopback), and the
   VOICE_COMMUNICATION A/B test with camera 0 open vs closed, to see whether Meta's in-call
   beamformer reaches our AudioRecord.
5. Optional: open Jarvis's UI during a VOICE_COMMUNICATION capture to record which side gets
   silenced.

## Cleanup and verification

- `adb uninstall com.immortal.camspike` → `Success`. `pm list packages | grep -c camspike` → `0`.
- `dumpsys media.camera`: `Active Camera Clients: []`. Both devices are closed, which matches the
  shutter-closed baseline from before the spike.
- Same PIDs before and after, so nothing crashed or restarted: aiservice 27457, presence 26587,
  virtualcameramanager 26658, cameraserver 25621, Jarvis 7872, launcher 15365.
- Meta presence after the spike looks like it did before: `PresenceManager onCameraAvailable not
  real camera [0]`, availability lines resumed, and no `onNotifyPresence presence updated`. That's
  expected with the shutter closed, the same as from 23:03:56 until the spike started. Presence
  can't be fully confirmed until the shutter is opened. Then the 30 s `onNotifyPresence presence
  updated` heartbeat should come back, along with aiservice's CONNECT on device 1.
- The launcher's PhotoDreamService was ended by the spike's `KEYCODE_WAKEUP`, which was needed
  because camera access fails while dreaming. The launcher process was not touched. Seven minutes
  after the spike, the Portal was still awake on the launcher's own `PhotoFramePreviewActivity`
  (screen-off timeout 300 s). It had not gone back to Dreaming, which suggests that launcher screen
  keeps the display on. The owner or orchestrator may want to confirm it settles back into the
  night dream.
- No settings, permissions or properties on other apps were changed. Nothing was played, and no
  frames were saved.
- **Daytime rerun cleanup (2026-11-18 17:48):** `adb uninstall com.immortal.camspike` → `Success`,
  0 packages. aiservice still holds camera 1 (`Camera ID: 1 … aiservice`). No new Led Policy
  Violation entries. Launcher 1.251 `PhotoFramePreviewActivity` is back in focus. Same PIDs for
  aiservice 27457, presence 26587, VCM 26658 and cameraserver 25621. Meta presence verdict
  `17:48:27 onNotifyPresence presence updated`, and the launcher reader saw `ABSENT -> PRESENT`.
