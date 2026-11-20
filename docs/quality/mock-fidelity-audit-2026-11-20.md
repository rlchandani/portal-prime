# Mock-fidelity audit — 2026-11-20

The shipped Hearthpane screens compared with their owner-approved live mocks, on the Family Portal+ (2160×1440 px,
1920×1280 dp). The audit started on official 1.287 and the fixes were measured against main 1.288.

- **Mocks:** rendered headless at 2160×1440 (1920×1280 CSS px at 1.125 scale, fit-scale removed), dark theme,
  every state. Control Center 64, Calling 73, Who's there 67, Jarvis 108, screensaver 16 (Night photo, Split
  glance and the six Digital clock faces, day and night).
- **Device:** generated scenes only (`screensaver_test_source`, pinned modes, forced night), Control Center on
  the `cc_debug` dry run and test plan, `digital_clock_preview`. Names were blurred in every stored capture,
  and so were camera tiles and person-named rooms. Home, real photos and camera feeds were never captured.
- **Images:** stored outside git under `portal-workspace/private-captures/fidelity/`. Mock renders are in
  `mock/<feature>/`, shipped captures in `app/<feature>/`, side-by-sides (mock left, shipped right) in `sbs/`,
  and A/B captures with perf in `ab/{base,after,base2,after2}/`.

Severity: **High** = clearly not premium, or wrong. **Medium** = noticeable. **Low** = nitpick.

## Gap table

| ID | Feature · screen | Gap (mock → shipped) | Sev | Status | Side-by-side |
|---|---|---|---|---|---|
| SS-1 | Screensaver · Split glance | Everything at 75% of the mock. The mock sizes in a 1440-wide space (1 unit = 1.33 dp on Portal+), but shipped used the numbers as dp: clock 176 dp vs 234.7, date 34 vs 45, weather/next row, margins and card corner all smaller. | High | **Fixed** 0d2e691c | `sbs/ss-splitGlance--day.png`, `sbs/ss-split-day-left-{mock,app}.png` → `sbs/after-split.png` |
| SS-2 | Screensaver · Split glance, Night photo, clocks | Typefaces: the mock uses SF / Iowan, shipped bundles Plus Jakarta Sans (tabular "1" has a foot) and Instrument Serif (condensed), so the date reads narrower. | Medium | Open (needs a bundled, licensed serif; owner call) | same |
| SS-3 | Screensaver · Night photo | Clock and date at 75% (92 dp vs 122.7). | High | **Fixed** 0d2e691c | `sbs/ss-nightPhoto--night.png` → `sbs/after-nightphoto.png` |
| SS-4 | Screensaver · Digital clock (Classic / Bold / Neon / Retro LED) | Numerals 250 dp vs 333. The serif date was a tiny line (LED: a fixed 30 dp). LED digits weren't the mock's 230/250 ratio. | High | **Fixed** 536d156c | `sbs/ss-clocks.png` → `sbs/after-clocks.png` (mock · 1.288 · fixed) |
| SS-5 | Screensaver · Flip face | No flip cards: the card halves were drawn with a transparent paint, so only bare digits and the seam showed. | High | **Fixed** b7a46546 | `sbs/after-clocks.png` row 5 |
| SS-6 | Screensaver · Analog face | Dial 1.36× the mock (it was fit to the panel). | Medium | **Fixed** 536d156c | `sbs/after-clocks.png` row 6 |
| SS-7 | Screensaver · Split glance at night | Mock: the split layout, dimmed. Shipped, with the household's settings (dark-room clock off) and forced night: a full-screen warm clock ("Friday, November 20"). This may be the test source's forced night rather than a real dark room. | Medium | **Fixed** 051b8bb1 (round 2) | `sbs/ss-splitGlance--night-household.png` |
| SS-8 | Screensaver · Analog | The mock has a sweeping amber second hand and no date. Shipped shows the date, and the second hand only with "show seconds". | Low | By design (follows Show seconds / Show date) | `sbs/after-clocks.png` |
| SS-9 | Screensaver · dates | "Friday · November 20" vs the mock's "Friday · 20 November": the device's US locale. | Low | By design | — |
| CC-1 | Control Center · Alerts | "Open 310 h 15 min · 2:33 pm" (hours never become days, and an old time reads as today) vs the mock's "Open 17 min · since 7:25 pm". | High | **Fixed** bd6616c2 | `sbs/cc-alerts.png` |
| CC-2 | Control Center · Map scenes | Raw HA script names "[Ring]: Arm Away" / "Runs a script" vs the mock's "Good night" / "Walks the house". | Medium | **Fixed** 2b179979 | `sbs/cc-map.png` |
| CC-3 | Control Center · HA unreachable | The mock's checklist (Portal Wi-Fi ✓, server ✗ no response, retry spinner) is missing; the sidebar and Ask Jarvis aren't dimmed. | Medium | **Fixed** c96f04a2 (round 2) | `sbs/cc-hadown.png` |
| CC-4 | Control Center · Security | The mock summarises 4 groups (Doors OK, Windows 1 open, Garage, Motion). Shipped lists all 76 sensors with raw HA model names ("Tilt Shock XS Sensor Window/door is …"). The "2 things open" hint is missing. | Medium | **Fixed** 0a2508a8 (round 2) | `sbs/cc-security.png` |
| CC-5 | Control Center · Edit | Title "Edit floor" vs "Edit floor plan"; a large room's 22 devices crowd an icon grid. | Low | Title: debug pill, as CC-6. Crowding: open | `sbs/cc-edit.png` |
| CC-6 | Control Center · header | With the debug pill and the "HA unreachable" pill both showing, the "Home" title truncates to "I…". Debug-only. | Low | Open | `sbs/cc-hadown.png` |
| CC-7 | Control Center · Alerts | The mock's "Preview a banner" card is absent. | Low | **Fixed** 0fc6b551 (round 2) | `sbs/cc-alerts.png` |
| — | Control Center · Settings, map layout | Close match. | — | — | `sbs/cc-settings.png`, `sbs/cc-map.png` |
| W-1 | Who's there · People & Recognition | The header icon is a film-strip glyph (mock: people), and the toggle row's icon reads as an emoji. | Medium | **Fixed** 42cf5f6d (round 2) | `sbs/whos-settings-people.png` |
| W-2 | Who's there · Settings rows | Outlined cards vs the mock's filled rows (the shared Settings style). | Low | Open | same |
| W-3 | Who's there · Enrolment | The flow differs. The mock starts on the phone (intro, consent) and the Portal opens on "Ready when you are" with an avatar; shipped opens on an Add-person form on the Portal. | Medium | Open (product flow) | `sbs/whos-e-ready.png` |
| W-4 | Who's there · Home views | Not captured (rule: never capture Home). | — | Not audited | — |
| CA-1 | Calling · Calls | Each room card carries greyed full-width "Not allowed" drop-in pills; the mock has compact round actions. They read as disabled clutter. | Medium | **Fixed** 474aa049 (round 2) | `sbs/calling-contacts.png` |
| CA-2 | Calling · Calls | The tabs and contact model differ (Rooms / Recent / Messages vs Favourites / Households / Rooms). This is phase scope, not styling. | Low | By scope | same |
| CA-3 | Calling · ring / in-call / ended | Not captured: a call needs a second Portal, and only Family was in scope. | — | Not audited | — |
| J-1 | Floating Jarvis · typed | The mock has the keyboard inside the card and "Replies will also be spoken". Shipped uses the system IME. Size can't be compared: the mock's 1920 frame is a 1.4× upscale. The card belongs to the Jarvis app (com.portal.assistant), not this repo. | Medium | Open (Jarvis repo) | `sbs/jarvis-typed.png` |

## Fixes

| Commit | Fix | Gaps |
|---|---|---|
| 0d2e691c | `HearthMockUnit` (mock unit = frame width / 1440 dp, only on Portal+-class surfaces, so the Mini and previews stay unchanged). Split glance type, margins and corner, and Night photo's clock, date and drift use it. | SS-1, SS-3 |
| 536d156c | Digital clock: numerals, date and gap in mock units. LED is 230/250 of the numerals with the same date rule. The analog dial is 560 units, never bigger than the old fit. | SS-4, SS-6 |
| b7a46546 | Flip card halves are drawn with an opaque paint. | SS-5 |
| bd6616c2 | Alert durations roll into days ("12 days"). An alert's time carries its day when it isn't today. | CC-1 |
| 2b179979 | Scene tiles split a leading "[Group]:" tag off: the action becomes the title and the tag replaces the generic subtitle. | CC-2 |

## Performance (Family, main 1.288 vs fidelity-audit, same session, `dumpsys gfxinfo`)

| Screen | Before | After |
|---|---|---|
| Split glance, 40 s at a 10 s beat | 2126 frames, 2 janky, p90 9 / p99 12 ms | 2126 frames, 0 janky, p90 9 / p99 11 ms |
| Night photo, 40 s at a 10 s beat | 2145 frames, 672 janky, p90 18 / p99 21 ms | 2145 frames, 681 janky, p90 18 / p99 21 ms |
| Flip minute turn (3 turns, after the first) | 47–49 frames, 0–4 janky, p99 12–65 ms | 49 frames, 0 janky, p99 10–16 ms |
| Classic / Bold / Neon / LED / Analog, 8 s static | 0 frames (Classic 0) | 0 frames (Classic 1 frame, 13 ms) |
| Control Center Alerts open (3) | janky 9 / 10 / 6, p90 250 / 20 / 29 ms | janky 10 / 10 / 7, p90 200 / 40 / 25 ms |
| Control Center map open (3) | 301–315 frames, janky 6 / 32 / 17, p99 21 / 57 / 133 ms | 303–315 frames, janky 3 / 18 / 32, p99 15 / 53 / 48 ms |

- Night photo's janky count is the same on both builds. It is the existing warm-filtered full-bleed motion
  (p50 6–7 ms, p90 18 ms), not this change.
- The first Flip turn after opening the face costs more on the fixed build (34 janky of 46 in one sample), because
  the cards now actually draw. The next three turns had 0 janky frames.
- Control Center opens vary as much within a build as between builds.

**Untouched screens:**
- Calls and People & Recognition: 100.0000 % identical.
- Settings Display, Sounds and Wallpaper: identical outside the OTA status line (reinstall) and the wallpaper
  preview's live clock.
- Control Center Security and Settings: identical outside the header's live time.

Gates: all 8437 unit tests pass (922 classes, 1 skipped); `:app:assemblePerformance` passes on the merged branch (1.288 + fixes).

## Remaining (by priority)

1. **SS-7:** check Split glance at night in a real dark room before changing anything.
2. **CC-4:** a Security sensor summary (group counts first, raw list behind a tap), with friendly sensor names.
3. **CC-3:** the HA-unreachable checklist and dimmed chrome.
4. **CA-1:** compact drop-in actions on the room cards.
5. **W-1 and W-3:** the People & Recognition icon, and the enrolment flow (an owner decision).
6. **SS-2:** typeface fidelity (an owner decision on bundling a wider book serif).
7. **J-1:** the in-card keyboard, in the Jarvis repo.
8. **Not audited:** the Mini (not on USB), calls with a second Portal, and the Who's there Home views.

## Round 2 (fidelity-2, 2026-11-20)

Branch `fidelity-2` from main 1.289, merged with main 1.290 (the main-thread sweep) before measuring. Mocks were
re-rendered with the round-1 renderer and matched round 1's renders (Security, HA unreachable, Alerts and map at
100 % / max 1/255). Images are under `portal-workspace/private-captures/fidelity-2/`: `mock/`, `ab/base` (official
1.290), `ab/after` and `ab/after2` (fidelity-2 performance builds), and `sbs/` (mock · 1.290 · fidelity-2 strips).
Person-named rooms, people, the HA address and cameras are blurred.

### Fixes

| Commit | Fix | Gap |
|---|---|---|
| 0a2508a8, 7b6d5bf1 | Security SENSORS card: the mock's four rows (Doors with locks, Windows, Garage, Motion), each with a count, a line and a tag, all starting closed. A tap still opens the exact rows with the inline Close / Lock. Alarm, smoke & CO and tamper go behind "More sensors" while calm, and lead the card already open when they need attention. Friendly names: a "[Ring]: " tag and a trailing "is open" are dropped, hardware names become "<room> door", "Pantry Door Sensor" becomes "Pantry door", and equal names are numbered. Several open rows in one room read "Office · 3 open". The "3 things open" note is visible again. The phone remote's list is unchanged. | CC-4 |
| ea6a5f9b | Door alerts use the same friendly name ("Office door is open", not "Tilt Shock XS Sensor Window/door is open is open"). | CC-1 / CC-4 |
| c96f04a2 | HA unreachable: the mock's checklist (Portal Wi-Fi ✓, the server address ✗ no response, a "Retrying in N s" spinner) and "Retry now" with its icon. While a state card covers the map, the rail and the Mini's bar keep only Home and Settings, and Ask Jarvis is dimmed, as in the mock. A refused token stays its own card. | CC-3 |
| 474aa049 | Calls room cards show only the actions the room takes: drop-ins off leaves Video call and audio, with a quiet "Drop-ins off" line. Calls off shows no buttons. | CA-1 |
| 42cf5f6d | People & Recognition uses the mock's two-person glyph (page header and sidebar), and the "Recognise people" switch uses the face-scan glyph instead of an emoji. | W-1 |
| 051b8bb1 | Split glance in a dark room keeps the split layout, warm and dimmed (the mock's night veil as a colour matrix on the card and backdrop, and the mock's night ink on the panel), for every night colour. | SS-7 |
| 0fc6b551 | Alerts: the mock's "Preview a banner on Portal home" card (Washer done, Garage open, Leak (critical)). Silent, no entity, gone after 8 s. | CC-7 |

**SS-7, from the code.** The light sensor runs only when "Dark room clock" is on. The test source's forced night
skips that check. With the household's settings (dark room clock off), a real dark room changed nothing, so round 1's
full-screen warm clock came from the forced night. With dark room clock on, a real dark room showed the quiet clock
(Warm / Red) or the full-bleed night photo (Photo). It now shows the dimmed split. A household with dark room clock off
still sees the day split in the dark, because nothing senses the room.

**Lows not changed.**
- CC-5 title: the code already says "Edit floor plan". The serif title wraps at a word because the debug "Dry run ·
  Test plan" pill takes its room, the same cause as CC-6 (skipped, debug only). The 22-device crowding was left: each
  dot is a draggable device.
- SS-8: the analog face already sweeps (50 ms) with Show seconds on and draws the date only with Show date on. The
  mock's look is those two settings.

### Performance (Family, official 1.290 vs fidelity-2 on 1.290, same session, `dumpsys gfxinfo`)

| Screen | 1.290 | fidelity-2 |
|---|---|---|
| CC Security open (3) | janky 10 / 8 / 9, p90 250 / 38 / 32 ms | janky 9 / 10 / 6, p90 250 / 26 / 40 ms |
| CC Alerts open (3) | janky 5 / 8 / 14, p90 26 / 26 / 30 ms | janky 8 / 7 / 17, p90 34 / 24 / 24 ms |
| CC HA unreachable, 10 s | 603 frames, 11 janky, p90 12 / p99 17 ms | 603 frames, 27 janky, p90 14 / p99 20 ms |
| Calls open (3) | janky 5 / 5 / 6, p90 150 / 105 / 97 ms | janky 6 / 6 / 6, p90 150 / 105 / 89 ms |
| People & Recognition open (3) | janky 16 / 13 / 11, p90 77 / 44 / 61 ms | janky 16 / 11 / 14, p90 61 / 57 / 61 ms |
| Split glance day, 40 s | 2130 frames, 2 janky, p99 13 ms | 2127 frames, 5 janky, p99 14 ms |
| Split glance forced night, 40 s | 2 frames (static quiet clock) | 2126 frames, 4 janky, p90 12 / p99 14 ms |

- The HA-unreachable state already drew at 60 fps on 1.290 (603 frames). The spinner adds a few janky frames
  (p99 17 → 20 ms).
- At night Split glance keeps its day drift, so it draws like the day split rather than a still clock.
- Opens vary as much within a build as between builds. A later Security re-check (`ab/after2`) ran while another
  agent was using Family, so its numbers are not comparable.

**Untouched screens** (1.290 vs fidelity-2): Settings Display and Sounds are 100.0000 % identical. CC Settings is
identical outside the header clock. The CC map and Settings › Clock Faces differ only at live values (header time,
preview clock). The day Split glance panel matches outside its live time.

**Mini compact layouts:** covered by unit tests only (the Mini is off USB). The Security card, room cards, rail and
bar changes share one code path with the compact layouts. Nothing was measured on a Mini.

Gates (merged 1.290 + fixes, final HEAD): 8488 unit tests pass (931 classes, 1 skipped); `:app:assemblePerformance` passes.

### Remaining after round 2

1. **CC-7 on device:** in the capture session, neither the washer nor the leak preview showed in the screencap.
   The card and chips render. The cause (overlay host after a reinstall, or the tap) is not confirmed. Check it with
   logcat (`HearthpaneBanner`) before relying on it.
2. **SS-7 with dark room clock off:** Split glance can't dim in a dark room the Portal doesn't sense. Owner call:
   use the light sensor for Split glance anyway, or the sunset-based evening warm dim the mock recommends
   (`HearthNight.level` exists but isn't wired).
3. **W-3** enrolment flow, **SS-2** typefaces, **J-1** the Jarvis in-card keyboard: owner or other-repo decisions.
4. **CC-5** device crowding in a large room; **CC-6** the debug-pill title truncation.
5. **CC-3 extra:** the mock's checklist doesn't check the token or the network. A refused token is a separate card
   today.
6. **Not audited:** the Mini on device, calls with a second Portal, the Who's there Home views.
