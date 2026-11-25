# Hearthpane Premium UI/UX Audit — 2026-11-24

Bar: Apple-grade. The questions asked of every screen: is the spacing on one rhythm,
the type scale deliberate, alignment exact, hierarchy obvious, contrast safe, iconography
consistent, are empty/loading/error states designed, is motion present and purposeful, are
touch targets generous, and does the surface feel like the same product as the one next to it?

Method: screens were captured from the live Family Portal+ (landscape) over adb and viewed
directly, then findings were grounded in source. Captures contained household photos/faces and
a LAN IP + one-time codes; none are committed — they stayed ephemeral in `/tmp` and are
described in words only. Verdicts are split into **Verified on device** vs **Proposed / needs
verification**. I could not test the Portal Mini (fleet-only) or portrait; Mini implications are
called out but unverified. Control Center's 3D/room-sheet could not be exercised because the
device has no floor plan built (it shows the "Build your floor plan" empty state).

---

## 0. Systemic findings (cross-screen — these matter most)

**S1. No spacing scale exists.** There is no `Spacing`/`Dimens`/`AppDesign` token object anywhere
under `app/src/main/java`. Spacing is expressed as raw `.dp` literals everywhere — the five most
common alone appear thousands of times (`24.dp` ×381, `16.dp` ×360, `12.dp` ×316, `28.dp` ×272,
`20.dp` ×257). With no shared scale, gutters and paddings drift screen-to-screen and there is no
single lever for rhythm. This also violates the project's own premium-consistency rule ("use
tokens, never hardcoded values"). *Verified in source.* High value, high effort.

**S2. Two parallel token systems have drifted.** The launcher uses `ApprovedPalette`
(`ui/theme/ApprovedPalette.kt`); Control Center uses a second, independent `CcColors`
(`ControlCenterTheme.kt`). They encode the *same roles* with *different values* — e.g. dark
surface `#2B2B2B` (launcher) vs `#24262B` (CC); divider `#484848` vs `#3A3D44`; secondary text
`#B8B8B8` (shared) but `faint` only exists in CC. Green is shared (`#66C49E`), red differs
(`#FF747D` vs CC same). The result is subtly different greys between the launcher and Control
Center on the same display. *Verified in source.* Med value, med effort (reconcile CC to derive
from the approved palette, or document the split as intentional).

**S3. Four different selected-state treatments for segmented/filter controls.** *Verified on
device.* The same interaction pattern looks different on every screen:
- Apps filter chips ("App Store / Settings folder / Edit favorites / Recent apps"): **white-filled
  pill, black text** for selected; unselected chips have a **dark pill** background.
- Tools filter chips ("All tools / Everyday / …"): selected is a **dark pill with an underline**;
  unselected chips have **no background at all** (bare text).
- Control Center Alerts tabs ("Active / All / Resolved") and Calls tabs ("Rooms / Recent /
  Messages"): selected is a **rounded dark pill** inside a track.
- Settings "Hey Jarvis | Jarvis" wake-word control: selected is an **elevated grey pill inside a
  grouped track** (`CcColors.segOn` style).
There is no shared segmented-control component, so each screen invented its own. This is the most
visible consistency break in the product. Med/high value, med/high effort (needs the approved mock
to pick the single canonical style; do not guess the target).

**S4. Header title is not aligned to the content column on detail screens.** *Verified on device.*
On Control Center (Alerts), Calls, Settings-detail, Music, the back-chevron + screen title start
indented by roughly the width of the back button, while the content cards below align to the
screen margin. So titles sit optically ~a button-width to the right of everything they head.
Low/med value, low effort.

**S5. Vertical space is under-used on most screens.** *Verified on device.* Control Center Home,
CC Alerts (3 items), Calls (2 rooms), and the Home right-column cards all leave 30–45% of the
viewport empty below the content while content is top-packed. Cards like Home's "Today" and
"Weather" top-align a 2×2 grid in a tall card, leaving a large void at the bottom. Nothing is
broken, but the compositions don't feel resolved. Med value, med effort.

---

## 1. Home

*Verified on device.*
- **Gutter rhythm is uneven.** The vertical gap between the "Today" card and the "Calendar"/
  "Weather" cards below it is visibly larger than the horizontal gap between Calendar and Weather.
  A premium grid uses one gutter value both ways.
- **Tall cards have bottom voids.** "Today" (2×2 weather facts) and "Weather" (big temp) top-pack
  their content and leave ~40% empty at the bottom because their height is driven by matching the
  hero photo card. Either center the content or give the cards intrinsic height.
- **The "Photo memory" hero caption is handled well** — it already sits over a bottom-weighted
  gradient scrim (`ApprovedHomePhoto.kt`), so white text stays legible on bright photos. *(I had
  flagged this as a suspected contrast risk; verifying in source showed the scrim already exists —
  no fix needed.)* Nit: the caption's colors/sizes are hardcoded literals, not tokens.
- **Bottom app-shortcut row is ragged.** The six shortcut pills (Calls, Browser, Jarvis, …) are
  width-to-content, so inter-pill gaps are uneven and the row doesn't sit on a grid. "Calls" has a
  lot of trailing empty pill.
- **Two competing bottom elements.** The shortcut row and the floating Home/Apps/Tools segmented
  nav both occupy the bottom band, stacked — the eye isn't sure which is the primary bottom anchor.
- **Header affordance inconsistency.** "Customize" is a bare text button wedged between the
  "Connected" status and the circular icon cluster; search is an *icon* here, an *inline field* on
  Tools, and *absent* on Apps.
- **Pagination.** "1 of 2" text beside a pill-dot indicator mixes two paradigms (a progress pill
  and a numeric label).

Mini implication: the hero + 3-card right column is a wide-screen composition; in portrait / on the
Mini this must reflow to a single column — unverified.

## 2. Apps

*Verified on device.*
- **One app icon has no background tile.** Every app icon is a colored squircle except **Home
  Assistant**, which renders as a bare white house outline floating with no tile. Grounded in
  source: `ApprovedAppsIcon` (`ApprovedAppsChrome.kt`) deliberately keeps the cell transparent for
  installed apps and relies on the icon bitmap carrying its own full-bleed color (lines 253-254);
  HA's companion icon is transparent/monochrome, so there is nothing behind the glyph. The fallback
  identity map even defines a tile color for HA, but it only applies when there is *no* bitmap.
- **Chip taxonomy is mixed.** "App Store / Settings folder / Edit favorites / Recent apps" mixes a
  destination ("App Store", which is also an icon in the grid below) with actions ("Edit
  favorites"). Reads as a filter row but behaves as a toolbar.
- **Large void between the chip row and the first icon row.** Rhythm gap (see S5/S1).

## 3. Tools

*Verified on device.*
- **Chip style differs from Apps** (see S3) — this is the clearest side-by-side proof of the
  inconsistency, since Apps and Tools are peer tabs.
- **Redundant third line on cards.** Each tool card has title + description + a status line, but for
  several entries the status paraphrases the description ("Timers — A few minutes, or a few hours. —
  Ready when you are"; "Reminders — For the household, or just for one of you. — For everyone, or
  just you"). Where the third line is real status ("Off", "0 saved") it earns its place; where it's
  fluff it adds noise.
- Cards, icon tints, chevrons and section headers ("Everyday", "Home & downtime") are otherwise
  clean and consistent — this is one of the better screens.

## 4. Control Center

*Verified on device.*
- **Editorial serif display type.** CC headings ("Home", "Alerts", "Build your floor plan") render
  in a **serif** face while the entire launcher is sans-serif. Grounded in source: this is
  **intentional** — `CcStyle.Serif = FontFamily.Serif`, explicitly copied from the approved mock
  (`.serif`). So it is a deliberate voice, not a bug. But it is a real cross-surface tension: moving
  between the launcher and CC, the product changes typographic personality. Flag for a conscious
  owner decision; do **not** "fix" it blindly (that would contradict the approved mock).
- **Duplicate "Needs Attention" rows.** Three identical "Rohit's Office door is open — Open 18h37m"
  rows, identical in every field. Confirmed on the Alerts screen too (three identical rows, each
  with a heavy white "Remind 10 pm" button). Premium behavior would collapse identical alerts or
  disambiguate them. This is a data/grouping gap surfacing in the UI.
- **Three white "Remind 10 pm" buttons** stack a lot of high-contrast weight on a dark screen for
  what is a secondary snooze action.
- **Copy:** "All Light" should be "All Lights"; "Turn Off" repeats across three scene tiles.
- **Stale/cryptic camera badges:** "17 D AGO" / "20 S AGO" use terse single-letter units; one
  thumbnail is 17 days old (stale snapshot) with no "offline/stale" affordance.
- **Left/right imbalance** (S5): ~60% of CC Home is an empty void around a small centered empty-state
  card; the empty state could own more of the canvas or the right rail could reflow to fill.
- Left rail, status pills, Scenes/Stargate/Cameras cards are well-built.

Could not verify: 3D view / room sheet (needs a built floor plan).

## 5. Jarvis floating card

*Verified on device.*
- Nicely done: orange orb, "Listening… / Ask anything · tap the orb to stop", a dot waveform,
  keyboard + close buttons, suggestion chips, and a **full-screen amber listening border** — a
  premium ambient cue.
- Opportunities: the orb and dot-waveform read static in a still frame; confirm they animate while
  listening (breathing orb + live levels). The amber border could pulse subtly. The card is anchored
  slightly left of true center (pushed by the CC camera column) when launched over Control Center.

## 6. Settings

*Verified on device.* The strongest screen — a clean iOS-style master/detail. Left list uses
colored icon tiles + trailing values ("On"/"Off"/"Dark"); right pane is a well-grouped detail.
- The "Hey Jarvis | Jarvis" segmented control is the fourth distinct selected-state style (S3).
- Detail-pane title ("Jarvis") is centered while the master title ("Settings") is left-aligned —
  minor tension in a master/detail.
- Check that list dividers are inset to the text (not drawn under the icon) for the iOS look —
  unverified at this zoom.

## 7. Music

*Verified on device.* Signed-out pairing state only (couldn't reach the now-playing player without
sign-in). The QR + device-code + numbered-steps onboarding card is well composed and readable.
- Only nit: the "Waiting for your phone…" status dot is static; a subtle pulse would signal "live".

## 8. "Control from your phone" (remote-control modal)

*Verified on device — and the worst-looking single screen.* Three concrete defects:
- **The title prints twice:** the dialog header says "Control from your phone" and the card body
  repeats "Control from your phone" immediately below it.
- **The scan hint prints twice:** the dialog subtitle ("Scan with a phone on the same Wi-Fi…") and
  the body subtitle ("Scan with your phone camera…") say the same thing back to back.
- **The 6-digit pairing code wraps onto two lines** ("5199" / "62") because the large code `Text`
  had no line/width guard and overflowed its half-width column.
- Minor: both a top-right "Close" and a centered "Done" dismiss the modal (redundant).

→ **Built the fix on `premium-ui-1`** (see below).

## 9. Calls

*Verified on device.* (Observed only — calling code is owned by other branches and was not
modified.) Room cards (gradient monogram avatars, "Available", green "Video call" + audio button,
favorite star) are well designed and consistent. Tab style is the S3 pill. Large bottom void (S5).
A stray brightness/sun icon sits in the Calls header (odd placement). Header title indented vs cards
(S4).

## 10. Screensaver / Photo frame

*Verified on device.* Two states seen: an initial **pure-black** state with only clock + date/
weather, then the **polaroid scrapbook** mode (white-framed family photos, small rotations, faint
doodle background, clock + date/weather bottom-left).
- **Abrupt entry.** The transition from the launcher (and from the black state to the full photo
  wall) looked hard — no visible crossfade or staggered fade-in; the black intermediate frame reads
  as an unstyled load rather than a designed dim state.
- **The "scatter" is a rotated grid.** The six polaroids sit on a detectable 2×3 grid with similar
  small rotation magnitudes; a true scrapbook varies position, scale and angle more organically
  (and may overlap). Right now it reads as "grid, slightly tilted".
- **A lone orange dot** sits top-right over the ambient scene — unexplained in a full-bleed view
  (likely a Jarvis availability/status indicator); it reads as an artifact here.
- **Bottom row clips the edge.** The bottom polaroids nearly touch the screen bottom; on the Mini /
  in portrait this clipping would be worse (unverified).
- The clock/date/weather overlay typography is clean and consistent with the launcher.

---

## Prioritized fixes

Effort: S ≈ hours, M ≈ a day, L ≈ multi-day. Risk notes collision/verification exposure.

| # | Fix | Screen(s) | Value | Effort | Risk | Status |
|---|-----|-----------|-------|--------|------|--------|
| 1 | Remove the duplicated title + duplicated scan hint in the remote modal | §8 | High | S | Low (isolated, shared file) | **Built** |
| 2 | Keep the 6-digit pairing code on one line (no wrap) | §8 | High | S | Low | **Built** |
| 3 | Fall back to a colored tile behind transparent/monochrome app icons (Home Assistant) | §2 | High | M | Med — needs transparency heuristic in `IconRenderer` + cross-icon & Mini verification | Proposed |
| 4 | Align detail-screen header titles to the content column (S4) | §4,§6,§7,§9 | Med | S | Low–med (touches several headers) | Proposed |
| 5 | Introduce one shared segmented/filter-chip component + token; adopt everywhere (S3) | all | High | M/L | Med — needs approved mock to choose canonical style; collides with contested tabs | Proposed |
| 6 | Equalize Home grid gutters (one value H & V); resolve tall-card bottom voids | §1 | Med | S/M | Med — Home is contested by many branches | Proposed |
| 7 | Collapse/deduplicate identical "Needs Attention" / Alerts rows | §4 | High | M | Med — CC alerts owned by cc-alerts\* branches | Proposed |
| 8 | Introduce a central `Spacing` scale and migrate hot paths (S1) | all | High | L | Med — broad, staged | Proposed |
| 9 | Reconcile `CcColors` to derive from `ApprovedPalette` (or document the split) (S2) | all | Med | M | Low–med | Proposed |
| 10 | Screensaver: staggered crossfade entry + more organic scatter; remove/justify the stray dot | §10 | Med | M | Med — hard to verify motion; screensaver owned by ss-\* branches | Proposed |
| 11 | Pulse the Music "Waiting…" indicator; confirm Jarvis orb/waveform animate | §5,§7 | Low | S | Low | Proposed |
| 12 | Copy: "All Light" → "All Lights"; de-fluff Tools status lines | §3,§4 | Low | S | Low | Proposed |
| 13 | Owner decision: keep CC serif voice vs unify to sans (deliberate, mock-sourced) | §4 | Med | — | — | Owner decision |

---

## What was built on `premium-ui-1`

A single safe, isolated, device-verifiable win addressing the worst-looking screen (§8), fixing
three distinct premium defects at once, all within the shared `RemotePairCard` and its one modal
call site:

1. **De-duplicated the modal title and scan hint.** Added `showHeader: Boolean = true` to
   `RemotePairCard` (`RemotePairUi.kt`) and passed `showHeader = false` from the Home quick-connect
   modal (`HomeLauncherScreen.kt`), which already shows "Control from your phone" as the dialog
   title and its own scan hint. The full-screen `RemotePairActivity` (titled "Phone Remote") keeps
   the card heading via the default — so no regression there.
2. **Stopped the 6-digit code from wrapping.** Reduced the code `Text` from `90.sp`/`letterSpacing
   8.sp` to `64.sp`/`4.sp` and added `maxLines = 1, softWrap = false`. The PIN is always exactly 6
   digits (`RemotePairing.randomDigits(6)`); 64.sp fits one line in the code column on both Portal+
   (~344dp) and the narrower Mini dialog column (~280–316dp by the dialog's own compact-frame math),
   with margin.

Scope was kept deliberately conservative: the change lives in one shared file (+ one call site) that
is **not** owned by the calling / polish-3 / overlay-resilience branches, avoids the structurally
contested surfaces (Home, Control Center, Music, Calls, screensaver) where parallel agents are
active and the Mini cannot be verified, and makes no token/structural change that would need the
approved mocks to adjudicate.

No unit test was added: the change is purely presentational (a boolean guard + text sizing) with no
JVM-testable logic seam, and the repo has no Compose-render test harness (pure-JVM JUnit/Mockito
only — Robolectric is unavailable on compileSdk 36 / AGP 9). Verification is via the build gate and
on-device check.

### Still needs owner review / device or Mini verification
- Items 3–13 above are proposed, not built.
- The built fix should be eyeballed on the Mini (portrait + landscape) to confirm the 64.sp code
  never wraps or clips there — computed to fit, not measured on hardware.
- S3 (segmented control) and the CC serif decision (item 13) need the approved mocks and an owner
  call before any code change.
