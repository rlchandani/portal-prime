# Floating Jarvis: feasibility and approach

November 18, 2026. Research only. No app code was changed and no device was touched.
Mock: `mocks/floating-jarvis-20261118/index.html`
(served at <http://127.0.0.1:8891/floating-jarvis-20261118/>).

Owner ask: *"When I click on Jarvis, why does it have to go to that Jarvis app? Can we build a
floating dialogue so the interaction is faster and I don't have to leave the screen?"* Follow-up
ask: make it feel like the Gemini or Siri overlay, with an ember glow, under ~300 ms to listening,
barge-in and follow-ups. Add "what's on my screen?" and "how do I…?" with on-screen highlights.

Paths: **L** = `portal/app/src/main/java/com/immortal/launcher/`.
**J** = `portal-workspace/next/jarvis/portal-assistant/app/src/main/java/com/portal/assistant/`.

## Short answer

Yes, we can build it. The full-screen app isn't needed for the UI or for the Gemini session.
The one hard rule is the **microphone**. On these Android 10 Portals, Jarvis gets real audio only
while it owns the **top, resumed Activity**. That Activity doesn't have to be full-screen or
opaque.

**Recommendation: Option A, plus two smaller pieces.**

1. **A translucent "Float" Activity in Jarvis.** It owns the mic and draws the card. The app
   behind it stays visible.
2. **A pass-through overlay pill** for anything that outlives the conversation, such as a
   running timer. No mic is needed for that.
3. **Hearthpane registers as a Jarvis tool provider.** This gives structured screen context
   ("what's on my screen?" with no pixels) and highlights ("how do I…?"). It uses the plugin
   contract Jarvis already has.

Effort: an MVP takes about **1 week**. The full scope in the mock takes about **3 weeks**.
Voice barge-in waits on the software AEC that Calling needs anyway.

## 1. Why Jarvis needs a full-screen Activity today

It's the **mic rule**. It isn't the foreground service, the UI or the Gemini Live session.

- **The launcher gate exists only to make Jarvis the top Activity before the mic opens.**
  - `L/JarvisHandoff.kt:76-77` starts `MainActivity` (the full app).
  - `:64-71` sends the `WAKE` broadcast only once both `onLaunched` and `onHostStopped` have
    fired (`:16-32`).
  - `:88-93` cancels on `ON_PAUSE` before launch, and dispatches on `ON_STOP`.
  - The design note explains why: "Android resumes a covering Activity before stopping the
    previous one, giving Jarvis foreground microphone access" (`docs/design/portal-plus-jarvis-handoff.md:13-21`).
  - `JarvisHandoff.kt:53-55` adds: "must not start a potentially background microphone".
- **The platform behaviour is documented in three places:**
  - Jarvis README: "Gen 2, app in background: *no hands-free wake* (Android 10 silences a
    background mic)" (`README.md:66`).
  - `J/wake/ForegroundWake.kt:10-11` says the same thing.
  - `docs/guides/voice-alexa.md:12-16`: Android 10 Portals "hard-block background microphone
    capture for sideloaded apps".
- **The foreground service isn't the blocker, and it isn't enough on its own.**
  - Jarvis already runs a `microphone`-type foreground service (`J/../AndroidManifest.xml:12-13, 44-47`).
  - It is resident and pre-warmed (`J/service/AssistantService.kt:42-50, 182-211`).
  - Even so, background capture is silenced. The root cause hasn't been confirmed. It's either
    the appop being `MODE_FOREGROUND`, which `J/wake/MicrophoneState.kt:37-38` already handles,
    or the audio policy's top-UI rule.
- **The UI isn't the reason either.**
  - The conversation runs entirely in the service (`AssistantService.kt:98, 166-172`).
  - Its state lives on an in-process bus: phase `IDLE/CONNECTING/LISTENING/SPEAKING`, turns,
    audio level and notice (`J/conversation/ConversationHub.kt:37, 42, 46, 55`).
  - Any window in the Jarvis process can render that state.
- **The Gemini Live session doesn't need an Activity.**
  - The socket, the tools and playback all belong to `AssistantEngine` inside the service.
  - Text turns already exist: `realtimeInput.text` (`J/gemini/GeminiWireFormat.kt:102-107`).

**Conflicting evidence on background wake.** `docs/research/video-drop-in-feasibility-2026-11-17.md:136`
says Jarvis "holds the mic all the time (~43% CPU)". The camera spike found the opposite: no mic
was held in standby, even though the notification said "Voice wake enabled outside Jarvis"
(`camera-spike-results-2026-11-17.md:335-343`). The likely cause is that the companion patch's
background wake (`J/wake/WakePolicy.kt:6-9, 30-31`) arms, gets a `Silenced` capture loss and then
waits. **Spike S1 below settles this.** Every design decision here assumes background capture is
silenced, which is the safe assumption.

## 2. How "Hey Jarvis" voice wake shows its UI today

It doesn't open the app. The service starts the conversation headlessly:
`onWake = { conversationHost.start(Trigger.WakeWord(it)) }` (`AssistantService.kt:98`). The only
UI is the orange recording bar.

- The bar is a `TYPE_APPLICATION_OVERLAY` window that is `NOT_FOCUSABLE | NOT_TOUCHABLE`, 56 px
  high, along the bottom edge (`J/ui/RecordingOverlay.kt:18-24, 48-56`).
- It is shown only while `UiVisibility.inForeground` is false (`J/conversation/AndroidEnginePorts.kt:33, 40`,
  `J/ui/UiVisibility.kt:3-8`).
- `MainActivity.kt:36-41` says so directly: "a wake-triggered turn never launches this — that's
  the orange bar".
- `SYSTEM_ALERT_WINDOW` is already declared (`AndroidManifest.xml:18-19`) and granted at install
  (`provisioning/install.sh:180`).

So the overlay plumbing that Option B needs is already in the app. What's missing is the mic,
not the window.

## 3. Options compared

| | **A. Translucent Float Activity (Jarvis)** | **B. Overlay window from the FGS (Jarvis)** | **C. Hearthpane draws, Jarvis streams** |
|---|---|---|---|
| Mic on gen2 | **Works.** Jarvis is the top resumed Activity, which is the documented rule. | **Probably silenced.** An overlay raises process importance (perceptible), not TOP. Unproven (S2). | **Silenced.** Jarvis is in the background while Hearthpane is on top. It only works if the launcher captures PCM and pipes it to Jarvis. |
| Time to listening (estimate) | ~150–250 ms: warm process plus a translucent Activity's first frame. A reused task (`REORDER_TO_FRONT`) skips `onCreate`. | ~50–100 ms for the window, if the mic worked. | ~1 frame for the card. Mic as in the row above. |
| Over other apps | Yes. `SYSTEM_ALERT_WINDOW` holders may start Activities from the background on Android 10 (same exemption as `provisioning/provision.sh:315-319`). Covers Control Center, Music, web apps and third-party apps. | Yes, over everything, including Meta screens. | Hearthpane screens only. Not third-party apps, and not the stock calling surface. |
| Photo frame (dream) | Starting an Activity ends the dream. Keep the `DreamPolicy` bridge that `JarvisHandoff` already uses (`:62, :109`) so it doesn't re-dream mid-answer. | Draws above the dream (no wake-up), but the mic still needs A. | Same as A, via the launcher. |
| Focus and app behind | The app behind is **paused, not stopped**. `JarvisForegroundWake` waits for `ON_STOP` (`JarvisHandoff.kt:93`), so it would never fire. **The gate must change** (see §4). | Nothing behind changes. | Nothing changes. |
| Camera and calls | The Float window being on top would win the mic and silence a call. **Refuse when `MicrophoneState.free()` is false** (`J/wake/MicrophoneState.kt:44-48`). Today the only refusal is a missing credential (`J/conversation/ConversationOpening.kt:148-150`). Jarvis never opens the camera, and camera access is foreground-only anyway (`camera-spike…:422`). | Same refusal is needed. | Same. |
| Touch pass-through | `FLAG_NOT_TOUCH_MODAL` plus `FLAG_WATCH_OUTSIDE_TOUCH`. A tap outside either dismisses or passes through (owner decision D3). | Native: size the window to the card, or `NOT_TOUCHABLE` for the pill. | Native. |
| Keyboard input | Normal Activity IME with `adjustResize`. Easy. | Overlay IME needs `NOT_FOCUSABLE` toggling and is fiddly. | Normal. Typed text goes to Jarvis over IPC. |
| Contract and IPC change | Launcher sends one intent with a trigger extra. Jarvis renders `ConversationHub` in-process. | None for the UI. | **Large.** Today's bridge is a finite `status`/`configure` (`J/control/ControlPolicy.kt:16, 22`; `AssistantControlProvider.kt:30-46, 67`). Streaming transcripts means household speech crossing IPC. |
| Effort | **Medium (~1 week MVP)** | Low for the UI, but blocked on the mic | **High (2–3 weeks)**, and still needs a mic workaround |

**Why A.** It's the only option whose mic path matches what's already been proven on these
devices. It works over every app. It reuses the in-process `ConversationHub` and the existing
text turn. It also removes the full-screen jump the owner dislikes. B is the right tool for the
**mini pill**, which needs no mic. C's real benefit is native-looking launcher context and
highlights, and §6 gets that through Jarvis's existing tool-provider contract with no streaming
bridge.

## 4. The recommended design

**Launcher (Hearthpane)**

- `JarvisHandoff.launch` starts `com.portal.assistant/.FloatActivity` with
  `trigger=tap|text`, `quiet=<ChimeConfig.isQuietNow>` (`L/ChimeConfig.kt:116-119`) and a small
  `screen=<id>` hint.
- There's no WAKE broadcast and no `ON_STOP` wait. The host is only paused.
- Jarvis starts the turn itself on its first `onWindowFocusChanged(true)`, which is the moment
  it is truly top UI.
- Keep the old full-screen path as a fallback when `FloatActivity` is missing, i.e. an older
  Jarvis. `RemoteCompanionJarvis` `status` can report support.
- All call sites go through `JarvisHandoff`, so this is a change in one place:
  - `HomeActivity.kt:391-392, 2663-2664` (the Home tile and the header mic)
  - `ControlCenterShell.kt:45`
  - `AppSwitcherActivity.kt:533-534`
  - The Dock default in `DockStore.kt:28`
- `RemoteInput.kt:272-277` (the remote allowlist) and `ApprovedAppsChrome.kt:194` (the tile
  colour) need no change.

**Jarvis (our patched build)**

- **`FloatActivity`:**
  - Theme: `Theme.Translucent.NoTitleBar` with no animation. Window flags:
    `FLAG_NOT_TOUCH_MODAL | FLAG_WATCH_OUTSIDE_TOUCH`.
  - Task: `singleTask`, in its own `taskAffinity`, `excludeFromRecents`.
  - Draws a Compose card bound to `ConversationHub`.
  - Sets `UiVisibility.inForeground = true` so the orange bar stays hidden, as in
    `MainActivity.kt:122-149`.
- **Voice wake while another app is in front:**
  - If S1 shows background capture works, Jarvis starts `FloatActivity` *first* on a wake, then
    opens the conversation mic, so the UI matches the tap path. The `SYSTEM_ALERT_WINDOW` grant
    lets it start an Activity from the background.
  - If S1 shows background capture is silenced (expected), "Hey Jarvis" only works while a
    Jarvis window is on top. The real fix would be for Hearthpane, which *is* on top, to run
    the wake detector and hand off (decision D6).
- **Call guard:** refuse with "Mic is busy during a call" when `MicrophoneState.free()` is false.
- **Quiet hours:**
  - Mute `PcmPlayer` and show the output transcription, which is already requested
    (`GeminiWireFormat.kt:72-73`). Native-audio Live models don't reliably support a TEXT-only
    modality, so keep receiving audio and don't play it.
  - Show a "Quiet hours · text replies" badge.
- **Mini pill:** a `TYPE_APPLICATION_OVERLAY` window with `NOT_FOCUSABLE`, shown after the card
  closes while a timer from `TimerStore` is running. Tapping it reopens the Float card.

### Premium bar (owner input)

- **Motion:** an ember orb plus a glowing edge on the card, driven by `ConversationHub.audioLevel`
  (it's already a `StateFlow`, `ConversationHub.kt:46`).
  - Listening: the orb breathes with the user's voice level.
  - Thinking: a slow conic sweep.
  - Speaking: the orb pulses with playback level and the text reveals in step. `RevealProgress`
    already exists for the reveal.
- **Card size:** starts as a compact one-line strip and grows to fit the answer, using a spring
  animation. That's the same motion language as the mock.
- **Under 300 ms to listening.** Budget, estimated (S3 measures it):

  | Step | Estimate |
  |---|---|
  | Tap dispatch | ~16 ms |
  | Warm translucent Activity to first frame (process already resident) | 120–220 ms |
  | `AudioRecord` start | 30–60 ms |
  | Card and orb shown on the first frame | — |

  - The ~440 ms socket connect is hidden by the existing **connect buffer**
    (`J/conversation/MicGate.kt:20-27`).
  - **Watch out:** the listening tone (240 ms) plus its 400 ms echo tail drops the first
    **~640 ms** of speech (`J/conversation/ListeningTone.kt:56, 70`; `MicGate.kt:12-17`). In
    floating mode the orb is the cue, so turn the tone off by default (decision D5).
- **Barge-in:**
  - Today Jarvis is half-duplex. Mic frames are dropped while it speaks (`MicGate.kt:19, 142`),
    a trigger during a live turn is ignored (`J/service/ConversationHost.kt:41-46`), and the
    platform has no AEC (`camera-spike…:310`).
  - Gemini Live already supports server-side interruption, and Jarvis handles it
    (`GeminiWireFormat.kt:152`, `J/conversation/AssistantEngine.kt:294-296`). It needs mic
    audio during playback without our own voice in it.
  - **v1:** tap the orb or "Stop" to interrupt. Jarvis cuts playback and goes straight back to
    listening.
  - **v2:** true voice barge-in on the **WebRTC software AEC** that Calling needs anyway
    (`camera-spike…:40`).
  - A cheap middle step: a high-threshold "Jarvis, stop" keyword detector during playback.
- **Follow-ups:** these already work. After each answer, Jarvis keeps listening
  (multi-turn, `README.md:73-74`) and the conversation ends on silence. The card shows a
  "Still listening…" row with a fading timer bar. Typed follow-ups go through `userText`.

## 5. Rich answers

Tool results are the source for the cards:

| Card | Source |
|---|---|
| Weather | `lookup/Weather` |
| Timer | `SetTimerTool` / `TimerStore` |
| Music | `PlayMusic` / `NowPlaying` |
| Calendar and Home Assistant | Hearthpane's tools (§6) |

- `FloatActivity` maps the last tool call and result in a turn to one card type. Anything else
  is shown as text.
- The "Kitchen lights off" card has **Undo**. The undo calls the inverse Home Assistant service
  through the same tool, with no model round trip.

## 6. Screen awareness: "what's on my screen?" and "how do I…?"

### (a) Hearthpane screens: structured context, no pixels (recommended default)

Jarvis already discovers **tool providers** through PackageManager meta-data and calls them with
`ContentResolver.call()`:

- Discovery and the per-session snapshot: `J/conversation/tools/ExternalToolProvider.kt:4-16`
- Contract keys: `J/conversation/tools/ToolContract.kt:47, 54, 57, 60, 63`
- 5 s timeout: `ExternalToolProvider.kt:97`

Hearthpane doesn't implement it yet. Plan:

- **Add a launcher-owned provider** with three tools:
  - `hearthpane.screen_context()` returns the visible screen as JSON. Examples:
    - Control Center: rooms with light, climate and lock states
    - Music: what's playing, the queue and the output room
    - Settings: the page and its rows with their values
    - Home: widgets, with **no photo content**
  - `hearthpane.highlight(target_id, hint)` pulses a control. Target ids come back in
    `screen_context`.
  - `hearthpane.ha_action(...)` for Home Assistant, optionally replacing the separate Home
    Assistant tool app.
- **Cost:** about 300–1,500 tokens of JSON per ask, and only when the model calls the tool.
- **Latency:** one tool round trip. The local `call()` takes under 50 ms; the model turn adds
  about 300–600 ms.
- **Privacy:** no pixels and no photos. The `screen=<id>` launch hint helps the model decide
  whether to call the tool at all.
- **Effort:** about 3–4 days, including the highlight ring on Control Center, Settings and Music.

### (b) Other apps: a screenshot, only on an explicit ask

- **MediaProjection on Android 10.**
  - In AOSP 10, SystemUI's `MediaProjectionPermissionActivity` skips the dialog when
    `MediaProjectionManagerService.hasProjectionPermission()` is true. That method returns true
    when the caller holds `CAPTURE_VIDEO_OUTPUT` or `OP_PROJECT_MEDIA` is `MODE_ALLOWED`.
  - So `adb shell appops set com.portal.assistant PROJECT_MEDIA allow` at provisioning
    *should* remove the consent dialog. **Not verified on Meta's SystemUI (S4).**
  - Jarvis targets SDK 29 (`portal-assistant/app/build.gradle.kts:18`), so Android 10 also
    requires the service to declare `foregroundServiceType="microphone|mediaProjection"`.
  - Capture flow: create the projection, take **one** frame through a `VirtualDisplay` and
    `ImageReader`, then stop. Don't keep a projection open.
  - Added latency is about 0.4–0.7 s in total (estimates): ~150–300 ms to start, ~16–50 ms for
    the frame, ~50–100 ms to scale and encode a JPEG, and ~100 ms to upload.
  - With the dialog gone, Jarvis's own explicit-ask rule is the only consent check.
- **Alternative: an AccessibilityService text tree.**
  - It can be enabled at provisioning through `settings put secure enabled_accessibility_services`.
  - It sends no pixels, takes ~50 ms and costs very few tokens. It can also find the bounds of
    controls in third-party apps.
  - Downsides: it's always-on and sees every window event, which is a larger privacy surface
    and some CPU. WebView and canvas apps (Chromium, YouTube) give weak trees.
  - Use it as the "how do I" highlighter for third-party apps later, not as v1.
- **The Gemini Live image path in Jarvis today: none.**
  - The wire format sends only `realtimeInput.audio` (`GeminiWireFormat.kt:89-98`) and
    `realtimeInput.text` (`:102-107`).
  - Add one frame: `{"realtimeInput":{"video":{"mimeType":"image/jpeg","data":<b64>}}}`, sent
    right before the question.
  - This needs a new `image()` method on the backend wire interface
    (`J/conversation/backend/WireFormat.kt`) and a refusal in `LocalBackend`.
  - Send it for that one question only, and never re-send it on follow-ups.
- **Cost.**
  - Gemini bills images at 258 tokens per 768×768 tile. A 2160×1440 screenshot scaled to
    1024×683 is about **2–4 tiles, roughly 0.5–1k tokens**.
  - That's about the same as 15–30 s of audio input, which is billed at 32 tokens per second.
  - These rates are from Gemini's published media-token rules. Check them for `gemini-3.8-live`.
- **Privacy rules (built into the mock):**
  1. Capture only on an explicit ask ("what's on my screen?", "look at this"). Never in the
     background, never periodically.
  2. Show a visible "Jarvis is looking at your screen" indicator: an ember scan sweep plus a
     chip on the card. Show a thumbnail of exactly what was sent, and offer Undo, which means
     "forget it" and drops it from the session.
  3. Hide the Jarvis card for one frame during capture, so the card itself isn't in the image.
  4. **Home and photo frame:** these are Hearthpane screens, so use (a) and send no pixels. If
     pixels are ever requested there, **ask first**, or blur the photo regions. The default is
     decision D7.
  5. **Never capture camera or call video.**
     - Refuse while a call is active (audio mode isn't normal) or the camera is in use
       (`CameraManager.AvailabilityCallback`).
     - Calling's Activity should set `FLAG_SECURE`, which also blacks out any projection.

### (c) "How do I…?": highlight on screen

- The model calls `hearthpane.highlight("settings.display.brightness", "Drag to adjust")`.
- The launcher scrolls to the control, draws an ember ring and a pulse with a hint chip, and
  shrinks the Float card into a corner so the control stays visible.
- On Hearthpane screens this needs no pixels and no accessibility service.
- For third-party apps, it's the accessibility tree later (above).

## 7. Risks

- **Meta's overlays.**
  - The bug pill is hidden by appops (`provisioning/provision.sh:538-546`). Keep that, or it
    floats over the card's bottom-right corner.
  - Meta's incoming-call and system UI sit above `TYPE_APPLICATION_OVERLAY` and above our
    Activity. That's correct behaviour.
  - The stock calling surface is a Meta app: the call guard covers it.
- **Quiet hours:** text-only replies plus a badge. The wake word could also be suppressed at
  night (D4).
- **Voice wake while another app is in front:** expected *not to work* on gen2 (S1). Don't
  advertise "Hey Jarvis anywhere" until S1 passes or D6 (wake in Hearthpane) ships.
- **The app behind pauses.** Some third-party video apps pause playback in `onPause`. Hearthpane
  Music and web video are ours, so keep them playing and duck them through Jarvis's existing
  `AudioFocusHush`.
- **Licensing:** upstream `rudysev/portal-assistant` has no license. The Float patch, like the
  companion patch, stays personal-use only and is never pushed.
- **No consent dialog for screenshots:** once the appop removes the system dialog, a bug in
  our explicit-ask rule becomes a privacy bug. Gate it in one pure, tested function.

## 8. Effort (one developer, estimates)

| Piece | Days |
|---|---|
| Jarvis `FloatActivity`: card, states, dismiss (tap outside, swipe down, auto-hide), typed input | 3–4 |
| Launcher handoff switch, fallback and tests (`JarvisHandoff`, `JarvisPreparedLaunchTest`) | 1 |
| Call guard, quiet-hours text mode, tone-off-in-float | 1 |
| Mini pill overlay (timers) | 1 |
| Rich answer cards and HA Undo | 2–3 |
| Hearthpane tool provider: screen context, highlight ring, HA | 3–4 |
| Screenshot ask (appop, FGS type, one-frame capture, image frame, indicator, guards) | 3–4 |
| Tap barge-in (v1) | 0.5 |
| Voice barge-in (v2, after Calling's AEC lands) | 3–5 |
| Device spikes and latency measurement | 1–2 |

- **MVP** (Float, handoff, call guard, quiet hours, dismiss, tap barge-in): **about 1 week.**
- **Everything in the mock except voice barge-in:** **about 3 weeks.**

## 9. Device spikes still needed (not run: no devices touched)

- **S1. Background capture.** With background wake on and Hearthpane on top, check
  `dumpsys media.audio_policy` and `AudioRecordingConfiguration.isClientSilenced()` for Jarvis.
  This settles the 43%-CPU versus no-mic conflict.
- **S2. Overlay and the mic.** Does a visible `TYPE_APPLICATION_OVERLAY` let Jarvis's service
  capture unsilenced? If yes, B becomes viable for voice wake.
- **S3. Time to listening.** Measure the Float Activity's first frame and `AudioRecord` start,
  both cold and with a reused task.
- **S4. Screenshot appop.** Does `appops set com.portal.assistant PROJECT_MEDIA allow` skip
  Meta SystemUI's projection dialog?

## 10. Owner decisions

| | Decision | Recommended |
|---|---|---|
| D1 | Placement | **Bottom-centre sheet** by default. A corner bubble when a highlight is showing, or as an option. |
| D2 | Auto-hide after the answer | **8 s** after speech ends, unless touched or a card needs action (Undo). |
| D3 | Tap outside the card | **Dismiss** for the sheet, **pass-through** for the bubble and pill. |
| D4 | Quiet hours | **Text replies, no wake word.** Tap still works. |
| D5 | Listening tone in float mode | **Off.** The orb is the cue, and it saves ~640 ms of clipped speech. |
| D6 | "Hey Jarvis" over Hearthpane screens | **Hearthpane-hosted wake detector**, but only if S1 confirms background capture is silenced. It costs CPU (Vosk was ~43% on one core). |
| D7 | Photo privacy default | **Never send pixels from Home or the photo frame.** Use structured context only. For other apps: explicit ask, indicator, thumbnail and Undo. |
| D8 | Screenshot consent | **Provisioned appop, no system dialog**, plus our own indicator. The alternative is a system dialog every time. |
| D9 | Voice barge-in | **Ship tap barge-in now.** Voice barge-in follows the Calling AEC. |
