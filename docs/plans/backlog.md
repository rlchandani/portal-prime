# Hearthpane backlog (owner feedback → work)

Owner asked (2026-11-18): keep a running backlog of every piece of feedback; keep working through it.
Status: ⏳ in progress · 🔜 next · 🅿️ parked (waiting on owner) · ✅ shipped (version)

## In progress
- ✅ 1.272 Control Center people in rooms (anonymous rings default; names need Names + per-person consent; sealed room_seen message; Never recognise me on this Portal). Toolkit `cc-people` branch to merge after remote-security finishes in the toolkit checkout.
- ⏳ **Perf round 3 (always-on track)** — Home tab switches, Settings frame 2, CC taps, screensaver first transitions, startup, memory per screen on Portal+ (and Mini if USB). Branch `perf-3`.
- ✅ 1.268 Polish 2 (Music close worst frame 76→33 ms median; Settings tap frame 56–101→20–38 ms; phone CC sends only changed sections — Toolkit 1.8.18 at ~/portal-handoff; personal morning briefing, unseen on device). Left: Settings frame 2 (54–68 ms, system_server contention).
- ✅ 1.270 Personal reminders (store + sync, private on personal Home, Tools, briefing, Jarvis tool, phone). Device-verified on official 1.270 (Tools, due banner silent at night, Jarvis add/complete/ask-who, phone route + refusals). Fix merged locally: "tomorrow" used UTC date → ships next.
- ✅ 1.271 Household sync hardening (3 HIGH + 2 MEDIUM fixed: bounded peer reads, header-first gate, slow-sender limits, bus survives bad packets). Audit: docs/security/household-sync-audit-2026-11-19.md.
- ✅ 1.273 Remote security follow-ups (masked secrets, key share bound to pairing session, 180-day idle expiry, PIN rate limit, fetch allow-lists, /fs/write cap). Toolkit 1.8.21 (+ House people) at ~/portal-handoff.
- ✅ 1.274 /fs/read refuses app secrets; Immich fetch pinned to checked IP. Jarvis 2.7.2 (Family): 40 ms frames → tap→recording p90 282 ms (target met), first mic frame p90 365 ms.
- ⏳ **Sealed settings backup** — fleet-backup.sh can no longer read shared_prefs; add an encrypted export/import route (key held on the Mac) so backups work without exposing secrets. Branch `sealed-backup`.
- ✅ 1.267 Control Center 3D isometric view (+7 MB) + Jarvis home_control (room pulses; confirm-first never auto-runs). Jarvis 2.7.1 re-reads tools after Hearthpane updates (Family needs install). Mini compact 3D untested (no USB).
- ✅ 1.269 Calling 2b (phones in group calls, Move to phone/Portal with pick-up tap, phone video messages, opt-in no-push ringing service; 28/28 simulated-phone checks). Phone live video untested (phones off limits).
- ✅ 1.265 Calling + sync polish (messages in Recent + posters; sender keeps its own encrypted copy, newest 10; Grandma group tiles + 1:1 video fix; silent adopt; persistent kids PIN tries; floor switch worst frame 27.5→20.9 ms). JVM-only: Family↔Mini call paths (Mini off USB).
- ✅ 1.266 + Jarvis 2.7.0 (Family): "what's on my screen?" over other apps (PROJECT_MEDIA appop skips the dialog; image via functionResponse), privacy re-check after capture, Settings-block highlights, bubble window fits the card. p90 tap→recording ~324 ms (frames 100 ms in the audio lib). Owner: Jarvis 2.7.0 + PROJECT_MEDIA on Mini (USB) and Living.
- ✅ Family rebooted 2026-11-19 (system_server 1.86 GB → 106 MB, free 255 MB → 1.44 GB). 🅿️ Living reboot — owner to power-cycle (no USB/adb-wifi; app can't reboot).
- ✅ Mini synced with the Portal+ units (grants, WRITE_SETTINGS, clock/screensaver/theme prefs; household key copied → Family sees Rohit Office Available); provision.sh + docs/guides/portal-sync.md capture every manual setting (pushed). Rollback: private-captures/portal-mini/sync-rollback-2026-11-19.sh.
- 🅿️ Mini owner items: Gemini key, HA token, photos (copy or shared source), Meta sleep timeout 65 vs 20 min, touch sounds, extra apps (Breezy Weather, Material Files, Spotify, Snapcast).
- ✅ 1.261 Household settings sync (Mini + Living synced, same digest; Family joins after jarvis-wake restores 1.261). Follow-ups: adopt prompt only under About › Sync (surface it on Home for new Portals); photo-source changes picked up within 60 s; Jarvis settings not in registry yet.
- ✅ 1.262 "Hey Jarvis" on Hearthpane screens (microWakeWord, 3.8% of a core on Family, wake→listening ~326 ms); Jarvis companion 2.6.1 on Family. Owner: Mini USB dropped at 09:15 — reconnect, then install Jarvis companion + measure detector CPU on the Mini; Living Jarvis by hand; decide if "Hey Jarvis" should listen over the photo frame.
- ⏳ **Calling phase 2a (in-home) — BUILT** (groups of 4, video messages, phone↔Portal, Grandma mode; CRITICAL fix: household key only readable within 10 min of PIN pairing). Blocker: Family 2-party jank 27% vs phase-1 0.5% — checking. Owner morning: re-pair the Mini from Toolkit (key + mic), real 3–4 Portal call, Pixel phone calls.
- ⏳ **Perf follow-ups** — Settings later frames, Music open frame, Jarvis p90 <300 ms, first Flip/night warm-up. Branch `perf-followups`.
- 🅿️ Calling to other homes (grandparents) needs a relay: owner to choose/provide Cloudflare account (TURN + Worker).
- 🅿️ Who's there? daytime enrolment + threshold tuning with the family (steps in docs/plans/whos-there-v1.md).
- ⏳ **Floating Jarvis v1 — BUILT, merged locally, ships with 1.255** (Jarvis 2.6.0 on Family + Mini; listening p50 296 / p90 403 ms on Family). Follow-ups: p90 <300 ms; Hearthpane-side wake word (Jarvis can't hear in background); Settings block highlights; bubble touch strip; Jarvis 2.6 on Living (no USB) + Mini Gemini key/mic permission (owner).
- (was) **Floating Jarvis v1 — BUILDING (owner approved the mock 2026-11-18 23:00)** — talk to Jarvis without leaving the screen (floating card over Home,
  Control Center, Music…). Feasibility + live mock first (`mocks/floating-jarvis-20261118/`), then build.
  Bar: Gemini-overlay / Siri-class motion, <300 ms to listening, barge-in, follow-ups. Plus screen
  awareness ("what's on my screen?", "how do I…" with on-screen highlight) — structured context for
  Hearthpane screens, explicit-ask screenshot for other apps, photo privacy.
- 🅿️ **Calling phase 1 live test with the owner** — call each way (echo on loud speaker), drop-in Ask + Chime, Keep going, shutter closed mid-call → audio.

- ✅ (1.256) **Screensaver — BUILT (Night photo, Split glance, Digital clock redesign; existing modes unchanged; existing-mode redesign kept on `screensaver-existing-redesign`); finishing Night photo / Flip / Split-glance perf checks, then ship.** Owner approved "Night photo" + "Split glance" + the redesigned Digital clock screensaver (2026-11-18 23:15)** — building those; existing modes unchanged (redesigns held for owner review on `screensaver-existing-redesign`).
- (superseded scope) Premium screensaver phase 1 (foundation, fonts, Smart frame, default clock, captions/calendar/now playing, night clock; portrait-aware for the Mini). Branch `screensaver-premium`.
- (done) **Premium screensaver, every mode/profile** — inventory all screensaver modes, then a
  before/after functional website (`mocks/screensaver-premium-20261118/`) for owner review; build after.

## Always-on: performance (owner 2026-11-19)
When the backlog runs dry, keep a perf agent going: profile the slowest frames on Portal+ and Mini (Home tabs, Settings frame 2, Music, Control Center taps, screensaver first transitions, Jarvis tap→listen), memory per screen, startup, and fix the worst first.

## Next — perf/bugs found by the Mini work
- ✅ Family call 'slow mode' was memory pressure (0 of 5 windows slow after reboot; 0.15–0.48% janky).
- ✅ Memory health check (1.263) + Jarvis 2.6.2 unloads Vosk when idle (Family: 400 → 75 MB). Mini/Living need Jarvis 2.6.2 by USB.
- 🔜 Settings open: first frame now 45–122 ms (skeleton), later frames still 105–184 ms — make sidebar/page rows cheaper.
- 🔜 Music open: 16–29% janky on the Mini (was 37–74%); open frame 70–90 ms from Home recomposing — fix.
- 🔜 'Shared room' alert setting defaults ON — set the Mini (office) off with the owner.

## Next (in order — start as builders free up; max 2 Gradle builds)
- 🔜 Background alerts for ALL sensors (default chosen: leak/smoke/CO everywhere; door/window warnings only per banner policy — shared-room Portals after 10 min).
- 🔜 Calling phase 2 — cross-home (grandparents), Portal Toolkit phone calls, groups of 4, video
  messages, Grandma mode.
- 🔜 Who's there? — face-only v1 with the visible 1.5 s glance (camera spike GO); needs a
  commercially licensed face-embedding model + biometric-privacy note.
- 🔜 Control Center on the phone (Portal Toolkit) — matching live map/control view.
- 🔜 Control Center: draw/save the real floor plan with the owner (editor → HA user data).
- 🔜 Control Center perf: floor switch still ~1 dropped frame per tap; first open after memory pressure.
- 🔜 Control Center: kids-mode PIN try counter survives app restart.

## Parked (waiting on owner)
- 🅿️ Real live camera video needs HA streams: Ring via ring-mqtt RTSP → Generic Camera (go2rtc installed); Wyze via docker-wyze-bridge.
- 🅿️ Spotify source — owner logs in to Spotify on Family + developer.spotify.com in Chrome.
- 🅿️ Minimized music tile ✕ "pause + close" — offered, no answer yet.
- 🅿️ Google data access video (Unlisted link) / Photos-picker decision.
- 🅿️ Jarvis: confirm "Hey Jarvis" works; install Jarvis 2.5 + hide Meta bug pill on Living (needs USB).
- 🅿️ Doorbell: pick the HA doorbell entity; add the Chores widget to Home.
- 🅿️ Follow-me music daytime walk test; Pixel × clear-button recheck.

## Shipped
- ✅ 1.274 Secrets stay off fleet file reads; Immich pinning (Mini + Living; Family after perf-3).
- ✅ 1.273 Phone remote security (Mini + Living; Family after perf-3). Toolkit 1.8.21 pushed.
- ✅ 1.272 Control Center people in rooms (Mini + Living; Family after remote-security).
- ✅ 1.271 Household sync hardening + Jarvis "tomorrow" date fix (all 3 Portals).
- ✅ 1.270 Personal reminders + 1.269 Calling 2b (all 3 Portals). Portal Toolkit 1.8.19 (group calls, To Portal, phone messages, ringing service, Reminders) at ~/portal-handoff.
- ✅ 1.268 Smoother Music close + Settings open; personal morning briefing; lighter phone CC updates (all 3 Portals).
- ✅ 1.267 Control Center 3D view; Jarvis controls the house (all 3 Portals; Family after polish-2 restores).
- ✅ 1.266 Floating Jarvis phase 2 (all 3 Portals; screenshot feature needs Jarvis 2.7.0 — Family only so far).
- ✅ 1.265 Video messages in Recent, Grandma group calls, silent settings adopt, kids PIN lockout survives restart, faster floor switch (Mini + Living; Family after Jarvis agent restores).
- ✅ 1.264 Control Center on the phone (Portal routes) + 1.263 memory health check (all 3 Portals; fleetctl health: all Good). Portal Toolkit 1.8.17 (phone calls + House tab) pushed to main; APK at ~/portal-handoff/portal-toolkit-1.8.17.apk for manual install (Play Protect).
- ✅ 1.262 "Hey Jarvis" on Hearthpane screens (all 3 Portals; Mini listening confirmed via fleet logcat).
- ✅ 1.261 Household settings sync across Portals (Mini + Living; Family pending agent restore).
- ✅ 1.260 Stops Home's starting-window leak in system_server (13k leaked windows on Family); Jarvis taps never silently dropped; faster Settings/Music opens + baseline profile (all 3 Portals).
- ✅ 1.259 Calling 2a — groups of 4, video messages, phone calls, Grandma mode; household-key CRITICAL fix (Living; Family + Mini after perf agent). Family call jank is bimodal in phase 1 too (render thread slow mode) → separate investigation.
- ✅ 1.258 OTA never confirms a stale installer dialog (root cause: dream race + reused dialog task), retries with backoff — all 3 Portals on 1.258 (Living recovered by OTA).
- ✅ 1.257 Who's there? v1 (off by default) — Family (adb) + Mini; **Living stuck on 1.256** (OTA confirm bug, fix in progress).
- ✅ 1.256 Night photo, Split glance, redesigned Digital clock screensaver; feed slideshow stall fixed (all 3 Portals).
- ✅ 1.255 Floating Jarvis entry points + screen tools; alerts for every sensor (leak/smoke/CO everywhere, doors after 10 min hourly on shared rooms); Back stays on Home; Settings/Music open faster (all 3 Portals).
- ✅ 1.254 Portal Mini portrait/landscape layouts + rotation follower; Apps Calls tile opens Hearthpane Calls (all 3 Portals).
- ✅ 1.253 Hearthpane Calling phase 1 — in-home video calls + drop-in (security review clean, 6,421 tests).
- ✅ 1.252 Honest live cameras (Live only when frames change, snapshot age, Refresh); every security sensor with inline Close/Lock; Tesla screen replaces Energy; Home Control header button replaces the HA widget.
- ✅ 1.250 Header buttons aligned on Home/Apps/Tools.
- ✅ 1.251 Home Control Center (+ Home Control tile, HA widget opens it, critical alerts sound at night).

## Portal Mini (new device, owner 2026-11-18)
**Rule (owner): every feature must be as performant and optimized on the Mini as on the Portal+** — every
ship gate includes Mini perf (gfxinfo + SurfaceFlinger dropped frames + memory) in portrait AND landscape.
Handover: ~/portal-mini-handover/HANDOVER.md (tester audit of v1.251 on the Mini, serial <mini-serial>).
Owner decisions: **rotates it physically** (portrait + landscape must both work) and **full household
member** (pairing, room name, OTA, Calls/music/timers/Control Center).
- ✅ Rotation spike: rotation works (left turn → landscape in ~1 s); right turn filtered by Meta's build → needs our OrientationEventListener + REVERSE_LANDSCAPE. docs/research/portal-mini-rotation-2026-11-18.md
- ✅ Mini on 1.253 via OTA, fleet "Rohit Office", bug pill hidden, dev mode off.
- 🅿️ Owner: pair the Mini from Portal Toolkit (household key); right-turn test with the spike Follow activity; optional HA on the Mini.
- ✅ 1.254 Mini responsive layouts + rotation follower (review fixes C3/H1/M1; 6,492 tests; Portal+ pixel-identical). Owner morning: physical right-turn test; volume on the Mini back to 9. Also fixes: Apps-tab Calls tile opens Meta calling (all Portals); Settings open p90 ~350 ms on the Mini.
- 🔜 Portrait-aware screensaver (handover §8.7) — fold into the premium screensaver work.
- 🔜 Display-derived decode/cache edges (handover §8.5) — helps all Portals.
- 🔜 Separate, reversible Meta-service debloat experiment (handover §9).
