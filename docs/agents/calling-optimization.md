# Hearthpane In-Home Calling — Audit & Optimization

Portal↔Portal video calling over the home LAN (WebRTC), in-home only — never cross-home/internet.
Board has **no hardware echo cancellation**, so audio runs WebRTC's software AEC3/NS/AGC.

Worktree: `portal-workspace/next/calling-opt` (branch `calling-opt`, off `main`).
Shipped in 1.259–1.269 (1:1, groups, video messages, phone route, Grandma mode).

---

## 1. Current flow (invite → connect → media)

### Discovery & addressing
- **mDNS/NSD**: `RemoteDiscovery.kt` advertises each Portal as `_immortal-remote._tcp.`
  (name + agent port) and browses peers into a `{name, host, port}` map, feeding
  `CallingStore.peers`. Android 10's one-resolve-at-a-time limit is serialized with retries.
- **Ring** carries the caller's own LAN address: `CallingEffects.sendRing` fetches
  `HouseholdListSync.lanAddress()` + `FleetConfig.port()` and sends a signed
  `CallWire.Ring(id,to,kind,video,selfName,host,port)` over the household bus. No LAN address/key →
  `MediaFailed(UNAVAILABLE)`. Group rings attach each member's host/port from `CallingStore.peers`.

### Signaling (offer/answer) — signed HTTP, inverted roles
- The **callee POSTs the offer**, the **caller answers** (`CallingSignal.kt`).
  `CallSignalClient.sendOffer` → `POST http://host:port/remote/callpeer` (connect 3 s, read 10 s),
  body `{i,to,sdp,fp}` with a fresh household-signed `call_offer` header. Returns
  `Answer(sdp)` / `NotYet` (HTTP 409, the vouching `call_join` hasn't arrived — retried
  `LEG_OFFER_TRIES`×700 ms for group legs) / `Failed`.
- `CallPeerRoute.handle` verifies the signed header *before* reading the body, rejects phones
  posing as Portals, calls `CallMediaHub.answerBlocking`, and returns `{payload, sig}` (HMAC over
  `call_answer|nonce|payload`).

### In-home-only invariant & security (`CallSignalAuth`) — **must be preserved**
- `lanOnly(sdp)` keeps only `a=candidate` lines that are `udp` + `typ host` + private-IPv4
  (`HouseholdListPeerAuth.isPrivateLan`) — strips STUN/TURN/IPv6/mDNS. Applied to the local
  description in `CallMediaLink.setLocal` before it ever leaves the device.
- `hasLanCandidate(sdp)` requires ≥1 surviving candidate; enforced on both offer and answer.
- `fingerprintOf` requires a single DTLS SHA-256 fingerprint; `fp` must equal it; DTLS then
  refuses any cert that doesn't hash to the signed fingerprint → no MITM without the household key.
- **No STUN/TURN/relay**: `CallSdp.lanConfig()` builds `RTCConfiguration(emptyList())`. Dormant
  cross-home relay code is absent from the calling sources.

### Media (`CallMedia` / `CallMediaLink` / `CallMediaHub`)
- One engine per call: one capture (camera 0 = Meta's AI-framed virtual camera, via
  Camera2Capturer), one `VOICE_COMMUNICATION` mic, one `PeerConnectionFactory`, and one
  `CallMediaLink` (peer connection) per other member → group = mesh of ≤3 links sharing the tracks.
  Everything WebRTC runs on one media thread. Nothing is recorded.
- **Audio**: `JavaAudioDeviceModule` with hardware AEC/NS **off** (Portal exposes none); software
  `googEchoCancellation/googNoiseSuppression/googAutoGainControl/googHighpassFilter` = true.
  Hub sets `MODE_IN_COMMUNICATION` + speakerphone, restores prior audio on teardown.
- **Codecs**: `DefaultVideoEncoderFactory(egl, enableIntelVp8=true, enableH264HighProfile=false)`,
  HW preferred, H.264 (QCOM) first then VP8 via `setCodecPreferences`.
- **PeerConnection**: UNIFIED_PLAN, MAXBUNDLE, rtcp-mux REQUIRE, TCP candidates DISABLED,
  `GATHER_ONCE`, `LOW_COST` network policy, ECDSA. Non-trickle: the full gathered SDP is exchanged
  once; `setLocal` waits for `IceGatheringState.COMPLETE` or a 2.5 s fallback, then ships `lanOnly`.
- **Lifecycle (`CallMediaHub`)**: camera+mic open only while the call screen is foreground; the
  callee opens nothing before Accept; everything releases the moment the call/screen ends.
  `answerBlocking` re-checks on the main thread (foreground, same call, member still setting up).

### Quality settings in use
| Preset (`CallQuality`) | Resolution | fps | per-link maxKbps |
|---|---|---|---|
| AUTO (default) | 1280×720 | 30 | 2500 |
| SAVE / low-memory | 640×360 | 30 | 700 |
| BEST | 1280×720 | 30 | 4000 |

- **Group sizing** (`CallGroupQuality`): ≥3 people → 640×360@30/800 (`GROUP_3`); 4 people →
  640×360@24/600 (`GROUP_4`); low-memory/CPU always wins.
- **Start-at-360p** when `ActivityManager` reports low memory or <220 MB free.
- **Per-call degrade** (`CallingQualityPolicy.shouldDegrade`): permanent step to 360p when
  CPU-limited and send fps in 1..20.
- **Per-link adaptive bitrate** (`CallLinkBitrate`): step the ceiling down 30% on `bandwidth`
  limitation (floor 250), climb back after staying clean.
- **Degradation preference**: `DegradationPreference.BALANCED`, `maxFramerate` pinned per profile.
- Stats polled every 2 s (`CallStatsParser` → fps/kbps/rtt/jitter-buffer/limitedBy, e2e estimate).

### State machine (`CallingMachine` / `CallingGroupMachine`)
`DIALING → RINGING/COUNTDOWN → CONNECTING → LIVE`, plus `RECONNECTING`. Timings:
`DIAL_ACK 4 s`, `RING 45 s`, `DROP_IN_COUNTDOWN 3 s`, `CONNECT 15 s`, `RECONNECT 10 s`,
`AUTO_ANSWER 10 s`. `MediaUp` (CONNECTING|RECONNECTING → LIVE); `MediaFailed` → immediate end.

### UI (`CallScreensLive.kt`)
- Remote `SurfaceView` stays composed the whole call ("no surface churn"); camera-off avatar
  covers it. Perceived join time is gated on the first decoded frame, polled every
  `FRAME_POLL_MS = 150 ms` (`sink.frames > 0`). Chips: CONNECTING "Connecting securely…",
  RECONNECTING "Reconnecting… the Wi-Fi dropped for a moment".
- `CallTitle` shows the peer/room name + elapsed timer (ties into the CALL_WITH room label).

### Test coverage (before this work)
~300 calling unit tests, but the **media-quality/reliability layer was only lightly covered**:
`CallGroupQuality.profile` and `CallLinkBitrate.next` had one test each in
`CallGroupMachineTest`; `CallingQualityPolicy`/`CallShutter` partial in `CallPolicyTest`;
`CallStatsParser` partial in `CallHistoryCopyTest`. No test exercised the fast-start estimate or
the recovery/oscillation behaviour. No ICE-restart path exists to test.

---

## 2. Findings — highest-value improvements

| # | Area | Finding | Ship now? |
|---|---|---|---|
| F1 | **Reliability** | **No ICE restart / re-offer.** `MediaDown` only flips LIVE→RECONNECTING; with `GATHER_ONCE` and no re-offer, a real route change (DHCP renew, AP roam) can't recover — it waits out `RECONNECT_MS` (10 s) and ends `NETWORK_LOST`. Only self-healing `DISCONNECTED→CONNECTED` blips recover. **Top gap.** | No — needs live 2-Portal test |
| F2 | **Quality after a blip** | Recovery was slow for a dedicated home LAN: 10 clean samples (20 s) before the first climb, then +15% steps — a 1 s hiccup could hold the picture soft for ~a minute. | **Yes (F2 shipped)** |
| F3 | **Connection quality / open-sharpness** | Opening bitrate estimate was capped at 1500 kbps even for the AUTO (2500) default, so HD ramped up instead of opening sharp, despite the code comment intending the opposite. | **Yes (F3 shipped)** |
| F4 | **Audio (no HW AEC)** | AEC3/NS/AGC are on via legacy `goog*` constraints. Levels/variants (e.g. experimental AEC/NS, typing-noise) are untuned. Same-room speakerphone echo is the #1 audio risk. | No — needs live echo test |
| F5 | **Video degradation** | Single hardcoded `BALANCED` preference. Talking-head calls may prefer MAINTAIN_FRAMERATE (group/low) vs MAINTAIN_RESOLUTION (HD solo). | No — needs live A/B |
| F6 | **Connection speed** | Callee pre-warming: factory/ADM/EGL (~hundreds of ms) build only after Accept inside the answer window. Pre-warming on ring (no camera/mic) would cut first-media latency without touching privacy. | No — needs live timing |

---

## 3. Changes shipped in this branch (safe, in-home-appropriate, TDD'd)

### F2 — LAN-aware adaptive-bitrate recovery (`CallLinkBitrate`)
- `CLEAN_SAMPLES_TO_RAISE` 10 → **4** (first climb after ~8 s clean, not ~20 s).
- `STEP_UP` 1.15 → **1.3** (brisker climb; a home LAN has the headroom).
- Step-down (0.7) and floor (250) unchanged; fresh congestion still resets the clean run, so a
  genuinely saturated link settles low and **cannot oscillate**.
- Before/after: a transient blip that stepped a link to the floor now climbs back to an AUTO
  budget in ~9 clean steps instead of the old slow 15% crawl; worst case is a brief overshoot that
  the unchanged aggressive step-down corrects within one 2 s sample.

### F3 — LAN fast-start opening estimate (`CallLinkBitrate.startEstimateKbps`, wired in `CallMediaLink.applyEncoding`)
- Opening estimate = `min(ceiling, 2500)` (≥ floor), replacing the hardcoded 1500 cap (removed
  dead `START_KBPS`). AUTO now opens at its full 2500 budget (sharp immediately); BEST opens at
  2500 and climbs; SAVE/group open at their full small budgets.
- Before/after: the default AUTO call comes up sharp instead of ramping 1500→2500; "Best" is still
  capped at the open so the estimator isn't blasted to 4000 before it settles.

### Tests
- New `CallLinkBitrateTest.kt` (12 tests): fast-start estimate across AUTO/BEST/SAVE/group/floor;
  step-down; floor settling with no oscillation; clean-run gating; congestion resetting recovery;
  no-overshoot clamp; brisk floor→AUTO recovery.
- Existing `CallGroupMachineTest` bitrate test references the constants symbolically, so it stays
  green with the new values.

**Invariants preserved:** no change to `lanOnly`/fingerprint/signaling/discovery; purely the
per-link bitrate numbers the LAN can comfortably carry. Grandma mode, groups and video messages
untouched.

---

## 4. Needs a 2-Portal live test (Family `2221B01C7T01C0` + another Portal, same room)

> Same-room speakerphone: watch for echo. Keep volume low (household etiquette); use the
> `/perf/calling` fleet probe (loopback build) for state/stats without touching the screens.

1. **F1 — ICE-restart reconnection (recommended next, highest reliability value).** Design: on an
   offerer link going `DISCONNECTED`, after a short grace (before `RECONNECT_MS`) issue
   `pc.restartIce()` + a fresh offer with `IceRestart=true`, re-run the **existing signed
   `sendOffer`/`exchange`** path (so `lanOnly` + fingerprint + household signing all still apply —
   invariant-safe by construction), and apply the answer. Requires widening
   `answerBlocking`'s guard to accept a re-offer during RECONNECTING/LIVE and glare handling
   (fixed offerer role per link). Not shipped blind: role/glare correctness and real route-change
   recovery must be verified on two Portals (pull Wi-Fi briefly, roam APs).
2. **F3 confirm** — the fast-start open and F2 recovery pace on real home Wi-Fi (no visible
   overshoot/pumping at AUTO and BEST).
3. **F4 — audio/echo** — confirm AEC3 holds with both Portals on speakerphone in one room; only
   then consider experimental AEC/NS constraints or AGC level tuning.
4. **F5 — degradation preference** — A/B BALANCED vs MAINTAIN_FRAMERATE for group/low and
   MAINTAIN_RESOLUTION for HD solo on the Portal's fixed talking-head framing.
5. **F6 — callee pre-warming** — measure first-media latency with factory/EGL pre-warmed on ring.
6. **Camera capability** — confirm whether camera 0 can deliver 1080p for a future BEST bump.
