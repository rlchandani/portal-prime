# Immortal OS — Evaluation Roadmap (2026-07-26)

Synthesized from a 6-dimension parallel audit (architecture, UX, reliability,
performance, security, testing). Headline: **no critical security hole, no
crash-loop/ANR/leak defects.** Codebase is defensively solid (global CrashGuard,
authenticated LAN server, careful bitmap decode). Real issues = shipped-half-done
UX + always-on efficiency.

## Tier 1 — high impact, low effort (IN PROGRESS this session)
1. **Tools hub unreachable** — `ToolsActivity` exists, `ToolsTile()` defined but never
   invoked; grid injects only Calls/Settings/Store. Strands timers, notes, converter,
   cameras, and Countdown add/edit. Fix: inject a Tools grid tile. (UX)
2. **Greeting hardcodes "Rohit"** — `HomeActivity.kt:431` ignores `WelcomeConfig.userName`
   (screensaver already honors it). Also greeting words hardcoded `:781-786`. (UX)
3. **Dead "Now Playing" stub card** — `HomeActivity.kt:449-476` static, not wired to
   NowPlayingHub, duplicates header MiniPlayer. Fix: wire + self-hide, or delete. (UX)
4. **Home clock recomposes whole tree every 1s + 3 SimpleDateFormat allocs/sec** —
   `HomeActivity.kt:699,780`. Fix: isolate clock in leaf composable, remember formatters. (Perf)
5. **Mock/preview activities ship exported in release** — `AndroidManifest.xml:578-640`
   (~2500 LOC). Triple-flagged (arch+security+ux). Fix: move to src/debug manifest. 
6. **Chime-volume migration has zero tests** — `ChimeConfig.loadVolume` (:76). (Testing)
7. **Pairing PIN no brute-force lockout** — `RemotePairing.redeem` (:66); cracked session
   leaks fleet tokens via `/remote/roster`. Fix: per-PIN attempt counter + burn. (Security H1)
8. **Dead settings in search index** — "Constrain page width"/"Legibility gradient"/"Photo
   feed" jump to nonexistent controls. `ImmortalSettingsActivity.kt:244-254`. (UX/Arch)

## Tier 2 — high impact, medium effort (BACKLOG)
- Missing table-stakes: alarm clock, volume control, text-size/accessibility (UX #13-14)
- Silent network failures — remote album/WebDAV/calendar/weather-location fail with no
  log + no "source unreachable" UI (Reliability #1,#11)
- Weather location cached permanently, no re-detect/TTL (Reliability #2)
- Bespoke settings editors unreachable (day-of-week, TTS voice, colors) (UX #4)

## Tier 3 — structural / lower urgency (BACKLOG)
- God files: HomeActivity 3588 / ImmortalSettingsActivity 2063 / PhotoFrameController
  1924 / SettingsDomains 1149 — all > 800-line rule. Extract widgets/domains. (Arch)
- 31 copy-pasted config stores; flat 183-file package (Arch)
- Crash edges: `handler!!` MediaSessionReader:147, `out!!` MaWebSocket:45 (Reliability)
- No lint/detekt/coverage tooling; zero instrumented/E2E tests (Testing)
- Fleet token in world-readable external storage, legacy Android 9/10 (Security H2)
- MQTT connect swallows real cause (H/S diagnosability); TTS instantiated per-utterance
