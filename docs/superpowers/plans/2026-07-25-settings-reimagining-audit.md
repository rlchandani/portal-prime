# Immortal Settings — Reimagining Audit (2026-07-25)

## DECISIONS (approved by user 2026-07-25)
- **Dead settings:** Rewire the useful ones, remove the rest.
  - REWIRE: `tileSize` (App tile size), `weatherWidget` (Off/Hourly/Daily).
  - REMOVE: `showGradient` (Legibility gradient), `feed` (Photo feed), `constrainPageWidth`.
  - CONFIRM-OR-CUT: Countdown events (verify a home/frame renderer exists; if not, remove the setting or add a renderer).
- **Raw settings:** Bury under an "Advanced" disclosure (keep functional). Auto-default
  `validateCert` on and hide it; hide `cacheBudgetGb` + `albumRefreshMin` (auto-manage);
  `antiBurnIn` on-by-default + hidden; debug mode stays but de-emphasized.
- **New settings THIS pass:** Build in-app **Display brightness + auto-brightness** and a
  **Night Shift / warm-tint** mode tied to the overnight window. Defer unified DND/Focus +
  Accessibility text-size to a follow-up.

## EXECUTION PHASES
1. **Remove dead controls** (showGradient, feed, constrainPageWidth) — spec + UI + setter cleanup. Low risk.
2. **Rewire tileSize** — provide `LocalTileDp` from `tileDpFor(ImmortalSettings.tileSize)`, use `gridColumnsFor` for grid columns.
3. **Rewire weatherWidget** — make `ImmortalWeatherWidget` honor Off/Hourly/Daily (hide when Off).
4. **Confirm/cut Countdown** — trace for a renderer; add one or remove the setting.
5. **Advanced disclosure** — group MQTT + Multi-room + cache + debug under a collapsible "Advanced" section; auto-default + hide cert/budget/refresh/antiBurnIn.
6. **Build Display section** — in-app brightness + auto-brightness (Settings.System.SCREEN_BRIGHTNESS / WRITE_SETTINGS).
7. **Build Night Shift** — warm-tint overlay tied to overnight window.
8. **Consolidate** — 4 chime volumes → 1; digital-clock enums → preset faces; welcome floats → S/M/L.
(Phases 1-4 are correctness — do first. 5-7 are the reimagining. 8 is polish.)

---


Full functional + consumer-grade audit of every Immortal-owned setting, verified against
runtime consumers (not just setters). Main-session spot-checked the 5 DEAD claims — all
confirmed. This is the input to the settings reimagining.

## VERIFIED DEAD settings (write to prefs, nothing reads them → user changes them and sees nothing)
1. **Legibility gradient** (`showGradient`, screensaver) — FaceRenderer always draws a scrim "regardless of the showGradient setting" (FaceRenderer.kt:168). No consumer.
2. **Photo feed** (`feed`, screensaver: Picsum/Met/Artic/Wikimedia/APOD) — PhotoFrameController.kt:1485-1492 hardcodes unsplash→picsum→wikimedia→bundled, never reads `feed`.
3. **App tile size** (`tileSize`, appearance) — HomeActivity hardcodes `LocalTileDp = 88.dp`; `tileDpFor`/`gridColumnsFor` (HomeActivity.kt:1256/1263) are DEFINED but never CALLED. Regressed in the iOS26 rewrite.
4. **Weather widget** (`weatherWidget`, appearance: Off/Hourly/Daily) — live `ImmortalWeatherWidget` (HomeActivity.kt:2273) ignores the mode; the honoring `WeatherWidget(mode)` composable is orphaned (only a mock references it). Regressed in iOS26 rewrite.
5. **Constrain page width** (`constrainPageWidth`, appearance) — no consumer anywhere.
6. **Countdown events** (`CountdownConfig`) — PARTIAL: editable, but no confirmed home/frame renderer. NEEDS confirmation (the "countdown" hits in HomeActivity/FaceRenderer/SleepScheduler are the sleep-timer countdown, likely not CountdownConfig events).

Decision needed per dead setting: REWIRE (make it work) or REMOVE (drop the control).

## TOO RAW / not consumer-grade (Apple would hide/auto/bury under "Advanced")
- **MQTT**: host, port, username, password, useTls, **validateCert** — pure integrator surface.
- **Multi-room**: snapcastHost, maPort, maUsername, maPassword — hostnames/ports/creds. Apple → auto-discovery.
- **Cache**: `cacheEnabled` + `cacheBudgetGb` (GB budget) — auto-manage; at most one "Offline" toggle.
- **albumRefreshMin** (15min–24hr) — auto.
- **antiBurnIn** — on-by-default, invisible.
- **Welcome Float sliders** (greetingSize/clockSize/dateSize/backgroundOpacity) — sub-pixel typography; Apple → Small/Default/Large.
- **Film grain** — photographer's term; fold into wallpaper style.
- **Debug mode** — hide behind 7-tap developer affordance.
- **presenceMode** label "Follow presence" leaks internals → "Keep display on: Always / When someone's nearby".
- **7 digital-clock enums** (style/color/font/size/layout/background/glow) → a few named faces w/ live preview.

## CONSOLIDATE
- 4 chime volume steppers (chime/spoken/golden/ping) → 1 "Sound volume" or defer to system.
- Calendar size + side → fewer knobs.
- Welcome show clock/date/duration → grouped.
- Sleep: pauseAudioOnSleep + closeAppOnSleep → buried under sleep timer.

## MISSING but expected (sensible for Portal+ / Android 10, no Google services)
- **Display brightness + auto-brightness / schedule** — biggest gap for a wall display (only a stock link-out today).
- **Night mode / warm tint (Night Shift)** — pairs with the overnight window.
- **Auto-Lock** framing for `idleSleepMin`.
- **Unified Do Not Disturb / Focus** — today chime quiet-hours + screensaver overnight-off + MQTT notifications are 3 separate nightly windows.
- **Accessibility**: text size / bold / reduce motion.
- **Per-source notification control**.
- **In-app Wi-Fi/network status** (only a link-out today).
- **Volume / audio-output selection**.
- **Unified "Sleep Schedule"** merging overnight-off + sleep timer + idle-off.

## KEEP (consumer-grade, functional) — the core
Screensaver on/off, clock face, photo source, tap-to-exit target, fit, interval, shuffle,
play videos, now-playing, power(presence), idle/overnight/night-clock, sleep timer, ambient
sound + volume, ambient dashboard, gesture-wave, welcome; Calendar (feed/show/range); temp
unit; clock format; mini-player; hide status bar; world clock; chime hourly/spoken/golden/
sunrise-sound/quiet-hours + voice; sunrise alarm (time/ramp/days/chime); digital clock
enable/style/color/size/show-date/seconds; device name; touch sounds; start-on-boot; back
gesture; remote pairing; device health; wallpaper mode.
