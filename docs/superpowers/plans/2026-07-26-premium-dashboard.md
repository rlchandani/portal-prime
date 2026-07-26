# Premium Dashboard — Build-All Plan (2026-07-26)

Goal: build the ENTIRE premium dashboard concept (mock: /tmp/portal_shots/mockups/dashboard-premium.html)
in one continuous session. User is asleep; wants it complete on waking. Build each piece, commit,
verify build+install+screenshot. All edits are in HomeActivity.kt (+ a few helpers) — sequential,
not parallel (same file).

## Already done (phase 1)
- ✅ "Today" strip (TodayStripWidget): next event · feels-like+hi/lo · moon + sun times. Commit 8c71f73.
- ✅ Wallpaper plumbing works (HomeBackground consumed); SkyBackground exists (day-drifting gradient).

## Pieces to build (each = commit + verify)
1. **Glass material upgrade** (Task 10) — LiquidGlass: layered translucency, hairline inner highlight,
   deeper shadow. NO live blur (Portal GPU). Tune the existing LiquidGlass composable.
2. **Ambient sky default + clouds** (Task 11) — SkyBackground already drifts by real sunrise/sunset.
   Add drifting cloud blobs + sun/moon glow; make WallpaperConfig default = SKY (was g_space).
3. **Weather hero** (Task 12) — restyle left-column weather to mock hero (big temp, cond, hi/lo, sunset)
   on weather-matching gradient. ImmortalWeatherWidget already has weatherGradient() — reuse.
4. **HA quick-controls + Hey Portal tile** (Task 13) — glass tile grid: lights/scene/thermostat/lock/
   cams/night + Hey Portal (launches com.portal.assistant/.MainActivity). Wire to MqttConfig/HA where
   set; graceful placeholder when not configured. Tapping a tile publishes MQTT or opens HA.
5. **Family quick-call row** (Task 14) — avatar row → Portal calls bridge (onOpenStore-style; find the
   calls/contacts intent). Add-contact affordance. Names are placeholders until user configures.
6. **Photo 'On this day' memory strip** (Task 15) — glass strip w/ a photo from LocalMedia/screensaver
   source; tap opens viewer. Fallback text when no source.

## Constraints / gotchas
- Portal GPU: no live backdrop blur; use gradients + translucency + optional pre-blur snapshot.
- ASCII glyphs where emoji coverage is patchy (Portal Android 10) — but emoji DID render in Today strip
  (🌔 moon, ☀). Weather uses Weather.emoji(). Test each.
- Can't finger-test or supply user data (HA server, calendar URL, contacts). Build UI + real wiring +
  graceful fallback. FLAG which need user config on handoff.
- Left column is `LeftWidgetColumn` (~line 394). Cards use LiquidGlassWidget. Grid of user widgets is a
  weight(1f) reorderable LazyVerticalGrid(2) below the fixed chrome.
- Verify after each: ./gradlew :app:assembleDebug -q ; adb install -r ; screenshot.

## Handoff note — COMPLETE (all 6 pieces built, committed, verified on device)

Commits: 8c71f73 (Today strip), fdbe703 (glass + sky), 522a2b6 (weather hero + HA + family + memory).
Build + 300 unit tests green; no crash; HomeActivity resumes clean.

### What's LIVE right now (no setup needed)
- **Premium glass cards** — every dashboard card: shadow lift, sheen, specular, hairline border.
- **Ambient Sky wallpaper (default)** — gradient drifts by real sunrise/sunset; sun (day)/moon (night)
  glow arcs across the sky; 3 drifting clouds. Vivid in daylight, subtle at night (correct).
- **Today strip** — next event · feels-like + H/L · moon phase + sunrise/sunset. (Event row needs a
  calendar URL — see below.)
- **Weather hero** — city, big temp, condition · H/L · sunset. Fully live.
- **Quick controls** — "Hey Portal" voice tile works now (launches com.portal.assistant).
- **Family quick-call row** — M/D/S avatars + add; all tap → stock Portal calling surface.

### Needs YOUR data to fully light up (built + wired, just add the source)
- **Calendar event** in Today strip → Settings > Calendar > paste a public .ics URL.
- **Home Assistant tiles** (Lights/Scenes/Climate/Home) → appear automatically once the Home
  Assistant app is installed (Aurora Store). They open the HA app.
- **Photo 'On this day' strip** → hidden until Settings > Screensaver > Photo source = a local folder.
- **Family names/photos** → currently placeholders (M/D/S); all route to the Portal call screen.

### Known cosmetic follow-ups (not blocking)
- Weather widget sits in the reorderable grid (below the family row) — can be pinned higher if wanted.
- Cloud drift is a slow horizontal loop; could add weather-reactive particles (rain/snow) later.
