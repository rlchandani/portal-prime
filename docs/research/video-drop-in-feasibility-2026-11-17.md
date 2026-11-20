# Room-to-room video drop-in: feasibility (feature 5)

November 17, 2026. Research gate for feature 5 in
`docs/plans/household-features-round2-2026-11-17.md`. Nothing in the app was changed and nothing
was installed. All device probes were read-only, run on the Family Portal (adb serial
`<family-serial>`). The camera was never opened, nothing was recorded, no screenshots were taken
and no permissions were granted. Command output below is trimmed. IPs are left out.

## Verdict: GO-WITH-CAVEATS

A third-party app can very likely do two-way LAN video between two Android 10 Portals. Every
check you can make without opening the camera points that way: the permissions can be granted,
the camera service has spare resource budget, hardware H.264 and VP8 codecs exist, and the
board has AEC and NS on the `VOICE_COMMUNICATION` path. Two things can't be proven without
actually opening the camera, so a short spike that the owner approves must come first:

1. **Which camera actually gives us frames.** Meta's `com.facebook.portal.aiservice` streams the
   physical camera (ID 1) all the time. Camera ID 0 is Meta's *virtual camera*. Opening ID 0
   might run alongside Meta, or it might give black or no frames. Opening ID 1 will probably
   evict aiservice while the drop-in runs, which pauses Meta's presence CV and AI framing.
2. **What the green camera LED does** when our app streams. The LED works by votes, and Meta
   runs a "LED policy". It may stay off for us, so we must draw our own indicator anyway.

If the spike shows neither camera can be opened while Meta's services run, the verdict becomes
NO-GO for video, with an **audio-only two-way drop-in** as the fallback. The mic path is already
proven on this hardware.

## 1. Can a third-party app use the camera and far-field mic?

### Device

```
ro.product.model=Portal+   ro.build.version.release=10 (SDK 29)   ro.board.platform=qcs605
ro.build.type=user   ro.build.tags=prod-keys   ro.debuggable=0   ro.secure=1
id -> uid=2000(shell) ... context=u:r:shell:s0     /system/xbin/su, /sbin/su: not present
MemTotal 2.8 GB (MemFree ~98 MB at probe time), 8 cores, arm64-v8a/armeabi-v7a
```

**Not rooted.** It's a user/prod-keys build. Everything has to work as a normal app.

### Cameras and who holds them (`dumpsys media.camera`)

```
Number of camera devices: 2   (Device 0 -> "0", Device 1 -> "1")
Active Camera Clients:
 (Camera ID: 1, Cost: 25, PID: 27457, Score: 200, State: 5, Client Package Name:
  com.facebook.portal.aiservice, Conflicting Client Devices: {})
Device 0 is closed, no client instance
Device 1 is open ... captureIntent [VIDEO_RECORD], sceneMode [FACE_PRIORITY], 2 output streams,
  Request ID counter: 22123542        <- ~30 fps continuously since boot
```

| | Camera 0 | Camera 1 |
|---|---|---|
| HAL | `device@3.3/legacy/0` | `device@3.5/legacy/1` |
| Facing | Front | Front |
| Level / caps | LIMITED, `BACKWARD_COMPATIBLE` only | FULL+ (level 3), RAW, reprocessing, manual sensor |
| Streams | 1280x720, 640x480, 320x240 only; 15–30 fps | 4032x3016 sensor, up to 4000x3000 / 3840x2160 |
| Resource cost / conflicts | 25 / None | 25 / None |
| Current holder | none | `com.facebook.portal.aiservice` (score 200) |

```
getprop: ro.camera.virtual_camera_id=[0]   camera.virtual_camera_2.enabled=[true]
pm: com.facebook.alohasdk.virtualcameramanager  /system/priv-app/VirtualCameraManagerService,
    PERSISTENT, service guarded by android.permission.CAMERA_PRIV
pm list features: android.hardware.camera.front, camera.level.full, portal.software.smart_camera,
    portal.software.smart_camera.hw_crop, portal.software.smart_camera.portrait_broadcast
```

What this means:
- **Camera 0 is Meta's virtual camera.** It is most likely the AI-framed ("smart camera" /
  hw_crop) output that Messenger and WhatsApp calls receive. A privileged service feeds it. If a
  normal app can open it *and* gets frames, it's the best option: framed video with no
  conflict, since cost 25 + 25 is within the camera service's budget of 100 and there are no
  conflicting devices. The risk is that frames only flow while VirtualCameraManager has an active
  call session, which we can't start because it needs `CAMERA_PRIV`.
- **Camera 1 is the physical 12 MP sensor.** aiservice holds it 24/7 at oom score 200, so it
  counts as "perceptible". In stock Android 10 arbitration, a foreground (top) activity outranks
  that, so our open would succeed by **evicting aiservice**. During the drop-in, Meta's presence
  CV (which drives dream vs. sleep, see `docs/design/multi-room-audio.md`) and AI framing would
  stop. aiservice reconnects after we close; the event log already shows it doing
  CONNECT/DISCONNECT/CONNECT. Meta may have changed arbitration, so this needs the spike.
- `GestureCamera.pickFrontCamera()` picks the *first* front camera, which is **ID 0**, the
  virtual camera. That experimental feature has never been checked on hardware.
- The live client already proves the HAL does dewarp, face AE/AWB and dynamic tilt
  (`portal_camera_rectilinear_dewarp`, `portal_camera_hal_face_aec_enable`, `dynamic_tilt` are
  all enabled). These look like HAL-level settings, so a camera 1 stream may come out dewarped
  but not AI-framed.

### Permissions (`dumpsys package com.immortal.launcher.debug`, `cmd appops`)

```
versionName=1.243 targetSdk=36   flags: no SYSTEM / no PRIVILEGED
android.permission.CAMERA:       granted=false, flags=[ USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED ]
android.permission.RECORD_AUDIO: granted=true,  flags=[ USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED ]
SYSTEM_ALERT_WINDOW: allow          RECORD_AUDIO appop: allow (last used 41 days ago, 1.4 s)
CAMERA appop: default (allow)
pm list packages: com.android.permissioncontroller, com.android.packageinstaller present
```

- **CAMERA can be granted.** It's already declared in the manifest (for GestureCamera) and is a
  normal runtime permission with no `POLICY_FIXED` or `SYSTEM_FIXED` flags. The AOSP permission
  dialog exists, and Intercom already uses it for the mic. Not granted here, as instructed.
- **RECORD_AUDIO is already granted**, and the appop shows the launcher has captured before.
- `SYSTEM_ALERT_WINDOW` is allowed. That exempts the launcher from Android 10's
  background-activity-start limits, so a receiving Portal can bring up the incoming drop-in card
  even when another app is in front. DinnerCall's overlay already relies on this.
- Who has used the CAMERA op: only `com.facebook.portal.aiservice` and
  `com.facebook.aloha.app.whatsapp` (`/system/app`, SYSTEM). No third-party app has opened a
  camera on this device yet, so there's no prior proof either way.

### Microphone (`dumpsys media.audio_policy`, `/vendor/etc/audio_effects.conf`, props)

```
Available input devices: Built-In Mic (@:bottom), Built-In Back Mic, Remote Submix
input "primary input":       PCM_16 32 kHz, 2ch (0x000c / 0x0030)  <- normal AudioRecord lands here
input "multichannel input":  PCM_32 32 kHz, index mask 0x8000000f (4 ch)   <- the raw array
input "virtual_loopback_input": AUDIO_INPUT_FLAG_VIRTUAL_LOOPBACK, 16 kHz
Active AudioRecord client: UID 10132 (com.portal.assistant = Jarvis)
  Source: AUDIO_SOURCE_VOICE_RECOGNITION, 16 kHz mono, State: Active
audio_effects.conf:
  pre_processing { voice_communication { aec { } ns { } } }   "supported by the board"
persist.audio.fluence.voicecomm=[true]  persist.audio.fluence.voicecall=[true]
persist.audio.fluence.speaker=[true]    use.voice.path.for.pcm.voip=[true]
pm list features: portal.software.smart_audio, smart_audio.manual_spatial_filter
```

- The **raw 4-channel far-field array** ("multichannel input") is not something a normal source
  will reach. That matches the existing code comments ("the far-field array needs a Meta-signed
  permission"). We don't need it.
- What we can use is **`AudioSource.VOICE_COMMUNICATION`**. On this board it goes through
  Qualcomm **Fluence voice-comm** (multi-mic beamforming and noise suppression), and the
  platform attaches **AEC + NS** pre-processing, with VoIP routed over the voice path. For a
  speakerphone video call that's the right processed far-field signal, and it's the most
  important factor for call quality on the Portal's loud speaker.
- **Mic contention:** Jarvis holds the mic all the time (`VOICE_RECOGNITION`, ~43% CPU). Under
  Android 10's shared-capture rules, the foreground app with the top UI gets real audio and a
  background non-privileged app gets silence. So the drop-in Activity, which must be in the
  foreground, wins and Jarvis goes quiet for the call. That's also what we want, because
  otherwise the remote voice from our speaker could trigger the wake word. The spike should
  confirm this with `AudioManager.getActiveRecordingConfigurations()` and
  `AudioRecordingConfiguration.isClientSilenced()` (API 29).

### Load already on the device (`top`, idle Home)

```
com.facebook.portal.aiservice               93% CPU
android.hardware.camera.provider@2.4-service 77% CPU
com.portal.assistant (Jarvis)               43% CPU
com.immortal.launcher.debug                 23% CPU   system_server 23%
MemFree ~98 MB of 2.8 GB
codecs: OMX.qcom.video.encoder.avc / .vp8 / .hevc, OMX.qcom.video.decoder.avc / .vp8 / .vp9 / .hevc
```

About 3.5 of 8 cores are already busy at idle and free RAM is low. The drop-in **must use
hardware H.264 (or HW VP8) encode and decode at 720p30 or lower**. Software VP8/VP9/AV1 is out.
If camera 1 evicts aiservice, that frees about 1.7 cores during the call.

## 2. What the code already does

- **`GestureCamera.kt`**: plain Camera2 (`CameraManager.openCamera`, YUV `ImageReader`, front
  camera = first `LENS_FACING_FRONT`, so ID 0). Opt-in, every step guarded, off by default, never
  checked on hardware. Its open/close/teardown is a good base for a capturer that releases
  cleanly.
- **`IntercomActivity.kt` + `LanAudio.kt`**: one-way audio. The broadcaster opens `ServerSocket`
  **TCP 8724** and streams raw **16 kHz mono PCM** from `AudioSource.MIC` to *every* client that
  connects. The listener connects by **typed IP** and plays through `AudioTrack`
  (USAGE_MEDIA/SPEECH). There's no codec, no AEC, no signalling, and **no authentication or
  encryption**: anyone on the LAN who connects to :8724 hears the room. Video drop-in must **not**
  copy this transport. It should reuse only the session-ownership pattern (`LanAudioSessions`).
- **`AudioNote.kt`**: `MediaRecorder` with `AudioSource.MIC`, foreground only.
- **HouseholdBus / HouseholdMessage**: signed HMAC-SHA256 UDP datagrams on 50122, ±120 s skew,
  nonce replay guard, **`MAX_WIRE_CHARS = 1400`**. A WebRTC SDP offer (about 3–6 KB) **does not
  fit**. There's already a pattern for this: `HouseholdListPeerAuth` sends a tiny signed bus
  signal (digest + private host:port), then a signed HTTP POST to `/remote/listpeer` with an
  `X-Immortal-Household` header whose HMAC pins the body's SHA-256. The drop-in signalling
  should copy it exactly.
- **DinnerCall** (`DinnerCall.kt`, `DinnerCallModel.kt`): signed CALL/ACK/REPLY/HELLO messages,
  a peer roster with room names, an incoming card overlay, a 3 s answer window, and
  `DinnerQuiet.isQuiet()` using the shared chime quiet hours. That's the ready-made
  "Living Area wants to drop in — Accept / Decline" ring path, room naming and quiet-hours
  policy.
- **Jarvis handoff** (`docs/design/portal-plus-jarvis-handoff.md`): Jarvis gets foreground mic
  access by being the resumed Activity, and a non-Activity caller never starts a background mic.
  `docs/guides/voice-alexa.md`: Android 10 Portals block *background* mic capture for sideloaded
  apps, and the wake word is designed to give up the mic during calls. Rule for drop-in: capture
  **only while a drop-in Activity is resumed**, never from a service. Don't run JarvisHandoff
  while a drop-in is active. Stop capture on `ON_STOP`, as Intercom does.
- **WebView**: `YouTubePlayerHost` already uses `loadDataWithBaseURL(https base, ...)`. Nothing
  implements `onPermissionRequest` today. The WebView provider is
  `com.facebook.portal.webview` 16.0.1 (`/system/app/portal-webview`), and earlier probes found
  Chromium 138 (`docs/music-standalone-readiness-2026-11-02.md`).

## 3. Transport options

| | A. Native WebRTC (`io.getstream:stream-webrtc-android`) | B. WebView `getUserMedia` + RTCPeerConnection | C. Custom MediaCodec H.264 + RTP/UDP |
|---|---|---|---|
| APK size | About +15–20 MB across arm64 + armv7 `.so` (estimate, measure; current performance APK is 25 MB). Could ship arm64 only | 0 | Under 100 KB |
| Camera choice | Full: `Camera2Enumerator`/`Camera2Capturer` by ID (pick 0 or 1) | Through Chromium's own capture; `deviceId` only. We don't control which ID or eviction | Full (our own Camera2) |
| Codec / CPU | HW H.264 via `DefaultVideoEncoderFactory` (QCOM avc/vp8 present); adaptive bitrate and resolution | HW where Chromium allows it on this WebView build; renderer process adds RAM (MemFree is ~98 MB) | HW H.264; no congestion control |
| Echo cancellation | `JavaAudioDeviceModule` with `VOICE_COMMUNICATION` + `setUseHardwareAcousticEchoCanceler/NoiseSuppressor(true)`, falls back to WebRTC AEC3 | Chromium AEC3 or platform; less control | Platform AEC through `VOICE_COMMUNICATION` only; we'd write our own jitter buffer and A/V sync |
| Encryption | DTLS-SRTP built in | DTLS-SRTP built in | We'd build it (SRTP or AES-GCM keyed from the household key) |
| Unknowns | None structural | Whether Meta's custom WebView enables media capture at all; Permissions-Policy in a data-URL page; camera selection | Loss recovery, lip sync, keyframe requests: weeks of work for worse quality |
| Effort | Medium | Low–medium if it works | High |

**Recommendation: A, native WebRTC.** The Stream build is the maintained Maven repackage of
Google's libwebrtc, with prebuilt `.so` files and no GMS. Choosing it gives us:
- direct control over **which camera ID** we open, and the ability to fall back between 0 and 1,
  which the spike outcome requires;
- **hardware AEC/NS on the Fluence `VOICE_COMMUNICATION` path**, with AEC3 as a fallback;
- HW H.264 at 720p/30 with congestion control, so the CPU stays manageable on an already busy
  QCS605;
- DTLS-SRTP for free. We bind each side's DTLS fingerprint into the household-HMAC-signed
  offer/answer, so a LAN attacker without the household key can neither impersonate a Portal nor
  sit in the middle;
- **host candidates only**: no STUN, no TURN, no internet.

To control APK size, ship arm64-v8a only for the WebRTC `.so` (every Portal reports arm64), or
put it behind an on-demand module. That's an owner decision.

Keep **B as a cheap probe** inside the spike: in a test page, check whether `navigator.mediaDevices`
and `RTCPeerConnection` exist in the Portal WebView. If A's APK cost gets rejected and B works,
B is the fallback. Reject C, except as the audio-only fallback where `LanAudio` already shows
the pattern (that fallback would still need auth and encryption added).

### Signalling over the household key

1. Caller sends bus message `dropin_ring {id, room, host, port}`. It's tiny and fits easily.
2. The callee shows the accept card (DinnerCall overlay pattern). Decline or a 30 s timeout
   sends `dropin_end {id, reason}`. **No media resources are opened before Accept.**
3. On Accept, the callee POSTs `/remote/dropin` with `X-Immortal-Household`. The HMAC body pins
   SHA-256 of the SDP offer, and the reply carries the answer bound to the request nonce, exactly
   as `HouseholdListPeerAuth` does it. ICE candidates are bundled into the SDP (non-trickle; it's
   a LAN, so gathering is fast).
4. Either side ends with `dropin_end`. ICE disconnection or `ON_STOP` also tears the call down.
   A hard cap (for example 15 min) applies unless someone extends it.

## 4. Privacy and UX

- **Always ask by default.** "Living Area wants to drop in — Accept / Decline", with full-screen
  wake. Camera and mic open only after Accept, on both ends. Any "auto-accept from these rooms"
  setting must be an explicit owner opt-in per sender. Recommend not offering it in v1.
- **Never silent, never hidden.** The call only runs while a resumed full-screen Activity shows
  it. Capture lives in that Activity's lifecycle, never in a service. A persistent red
  "● Camera on · Mic on · with Kitchen" bar, plus a self-preview thumbnail, stays on both
  screens for the whole call. Stop and mute buttons are 64 dp or larger.
- **Android 10 has no system camera/mic indicator** (that arrived in Android 12), so ours is the
  only on-screen signal.
- **Meta's camera LED.** The green LED is driven by votes
  (`== Green LED events log ==`, "LED is turned OFF ... All LED events for pid … are cleaned up"),
  and `portal_camera_fw_ledpolicy_sessionless:enabled : 1` along with an
  `== Camera Led events: Led Policy Violation log ==` section show Meta enforces an LED policy.
  aiservice streams 24/7 with the LED **off**, so the LED is not tied to the sensor being on.
  Whether a third-party client turns it on, gets flagged as a "violation", or gets blocked is
  unknown. The spike must record this by observing the LED, not with screenshots. `/sys/class/leds`
  isn't accessible (`Permission denied`), so we can't drive it ourselves.
- **Privacy switch/shutter.** The `privacystate-1-0` HAL is running. The log shows
  `Privacy State: PRIVACY_OFF`. When the hardware privacy switch is engaged, expect the camera to
  disconnect or give black frames, and the mic to go muted. The drop-in must show "Camera is
  covered/off on Kitchen" and allow audio-only, and must never try to get around the switch.
- **Quiet hours** (shared `ChimeConfig`, via `DinnerQuiet`): recommend that **incoming drop-ins
  are blocked** during quiet hours, with "Kitchen is in quiet hours" shown to the caller, unless
  the owner sets "allow visual ring". Never auto-accept and never play a ring sound in quiet
  hours.
- Also recommended: per-Portal "Allow drop-ins" switch (default **off** until the owner turns it
  on); an end-of-call log entry ("Drop-in from Kitchen, 3 min"); no recording feature; the phone
  can't start a video drop-in in v1, because a phone viewing a room is a surveillance risk.

## Required spike (owner consent needed, about 1–2 days)

Run it on the Family Portal plus one second Android 10 Portal, from a debug-only test Activity.
Nothing gets saved, and no frames or audio leave the device:
1. Grant CAMERA through the normal dialog. Open **camera 0** at 1280x720 and record only whether
   frames arrive and their mean luma (no images), plus `dumpsys media.camera` active clients.
2. Same for **camera 1**: does aiservice get evicted, does it reconnect after close, does the
   dream/presence behaviour recover, and does anything appear in Led Policy Violation.
3. Watch the **green LED** in both cases.
4. Run `VOICE_COMMUNICATION` AudioRecord with Jarvis running: check `isClientSilenced` for both
   clients, and whether `AcousticEchoCanceler.isAvailable()` is true for our session.
5. WebView probe: `navigator.mediaDevices` and `RTCPeerConnection` present?
6. With 720p30 HW H.264 loopback (encode + decode, no network), take a CPU and thermal reading
   over 10 min.

## Effort estimate (after a positive spike)

| Piece | Days |
|---|---|
| Spike (above) | 1–2 |
| WebRTC dependency, capturer (with camera-ID fallback), audio module, HW codec factory | 2–3 |
| Signalling (`dropin_*` bus types, `/remote/dropin` signed SDP exchange, state machine, JVM tests) | 2 |
| Ring / accept card, in-call screen, indicators, quiet hours, settings, Hearthpane copy, light/dark | 2–3 |
| On-device validation on two Portals, teardown/regression (Jarvis, presence, dream), OTA | 1–2 |
| **Total** | **about 8–12 days** (audio-only fallback: about 4–5) |

## Owner decisions

1. **Approve the spike.** It opens the camera on the Family Portal and grants it CAMERA. No
   recording.
2. If camera 1 is the only one that works: accept that **Meta's presence/AI framing pauses during
   a drop-in**?
3. APK budget: about +15–20 MB, or arm64-only (about +10 MB, estimate), or a separately installed
   module?
4. Default for "Allow drop-ins" (recommend off) and whether auto-accept between named rooms is
   ever allowed (recommend not in v1).
5. Quiet hours: block (recommended) or visual-only ring?
6. Maximum call length (recommend 15 min with "Keep going").
7. Should the existing **unauthenticated Intercom (TCP 8724)** be moved onto the same signed
   transport? Today anyone on the LAN can listen while it broadcasts.

## Top risks

- Camera 0 (virtual) may give no frames to a non-`CAMERA_PRIV` client, and camera 1 may evict
  Meta's always-on aiservice, which affects presence-driven dream/sleep during calls.
- Meta's LED policy may light, block, or flag our camera use. That can only be confirmed on
  hardware.
- CPU and RAM headroom on QCS605 is tight at idle (about 3.5 cores busy, about 98 MB free), so
  HW codecs and 720p or lower are mandatory, and thermal throttling is possible on long calls.
- Signalling payloads don't fit the 1400-char bus, so the signed-POST pattern is required.
- Only the Family Portal was probed. The other Portal(s) (model, Android version, camera layout)
  must be checked before building. Android 9 Gen-1 units have a different camera and HAL stack.
