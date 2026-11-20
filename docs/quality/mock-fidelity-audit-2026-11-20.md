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
| SS-7 | Screensaver · Split glance at night | Mock: the split layout, dimmed. Shipped, with the household's settings (dark-room clock off) and forced night: a full-screen warm clock ("Friday, November 20"). This may be the test source's forced night rather than a real dark room. | Medium | Open (verify in a real dark room) | `sbs/ss-splitGlance--night-household.png` |
| SS-8 | Screensaver · Analog | The mock has a sweeping amber second hand and no date. Shipped shows the date, and the second hand only with "show seconds". | Low | Open | `sbs/after-clocks.png` |
| SS-9 | Screensaver · dates | "Friday · November 20" vs the mock's "Friday · 20 November": the device's US locale. | Low | By design | — |
| CC-1 | Control Center · Alerts | "Open 310 h 15 min · 2:33 pm" (hours never become days, and an old time reads as today) vs the mock's "Open 17 min · since 7:25 pm". | High | **Fixed** bd6616c2 | `sbs/cc-alerts.png` |
| CC-2 | Control Center · Map scenes | Raw HA script names "[Ring]: Arm Away" / "Runs a script" vs the mock's "Good night" / "Walks the house". | Medium | **Fixed** 2b179979 | `sbs/cc-map.png` |
| CC-3 | Control Center · HA unreachable | The mock's checklist (Portal Wi-Fi ✓, server ✗ no response, retry spinner) is missing; the sidebar and Ask Jarvis aren't dimmed. | Medium | Open | `sbs/cc-hadown.png` |
| CC-4 | Control Center · Security | The mock summarises 4 groups (Doors OK, Windows 1 open, Garage, Motion). Shipped lists all 76 sensors with raw HA model names ("Tilt Shock XS Sensor Window/door is …"). The "2 things open" hint is missing. | Medium | Open | `sbs/cc-security.png` |
| CC-5 | Control Center · Edit | Title "Edit floor" vs "Edit floor plan"; a large room's 22 devices crowd an icon grid. | Low | Open | `sbs/cc-edit.png` |
| CC-6 | Control Center · header | With the debug pill and the "HA unreachable" pill both showing, the "Home" title truncates to "I…". Debug-only. | Low | Open | `sbs/cc-hadown.png` |
| CC-7 | Control Center · Alerts | The mock's "Preview a banner" card is absent. | Low | Open | `sbs/cc-alerts.png` |
| — | Control Center · Settings, map layout | Close match. | — | — | `sbs/cc-settings.png`, `sbs/cc-map.png` |
| W-1 | Who's there · People & Recognition | The header icon is a film-strip glyph (mock: people), and the toggle row's icon reads as an emoji. | Medium | Open | `sbs/whos-settings-people.png` |
| W-2 | Who's there · Settings rows | Outlined cards vs the mock's filled rows (the shared Settings style). | Low | Open | same |
| W-3 | Who's there · Enrolment | The flow differs. The mock starts on the phone (intro, consent) and the Portal opens on "Ready when you are" with an avatar; shipped opens on an Add-person form on the Portal. | Medium | Open (product flow) | `sbs/whos-e-ready.png` |
| W-4 | Who's there · Home views | Not captured (rule: never capture Home). | — | Not audited | — |
| CA-1 | Calling · Calls | Each room card carries greyed full-width "Not allowed" drop-in pills; the mock has compact round actions. They read as disabled clutter. | Medium | Open | `sbs/calling-contacts.png` |
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
