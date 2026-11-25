# Premium Proposal — Unified Segmented / Tab / Chip Control

Status: **proposal for owner decision. No code changed.**
Date: 2026-11-24
Scope: addresses audit systemic finding **S3** (four selected-state treatments for the
same pattern). Companion to `premium-proposal-spacing-tokens.md`.

Separates **what the code does today (verified by reading it)** from **what I propose
(opinion)**. Ends with "Decision needed."

---

## 1. What the code does today (verified)

There is **no shared segmented component.** Four genuinely distinct implementations exist,
plus one dead one. All paths under `app/src/main/java/com/immortal/launcher/`.

| # | Control | Composable | File:line | Selected style | Unselected | Shape | Track pad | Option h | Option h-pad | Label | Wt change | Motion |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A | CC + **Alerts** tabs | `CcSeg` | `ControlCenterKit.kt:78` | `segOn` grey pill (+`cardEdge` hairline) | transparent | full pill `RoundedCornerShape(50)` | 5 | 58 | 24 | 21sp | Normal→SemiBold | none (instant alpha) |
| B | **Calls** tabs | `CallsSegmented` | `CallsActivity.kt:255` | `surface` fill pill | transparent | full pill | 6 | 76 | 34 | 25.5sp | always SemiBold | none |
| C | **Tools** chips | `ToolsCategoryChip` | `ApprovedToolsHost.kt:489` | `surface2` + 1dp divider border **+ 3dp accent underline** | transparent, no border | `RoundedCornerShape(16)` | — | 68 | 14–24 | 22sp | always Medium | none |
| D | **Settings** one-choice rows | `SettingsTileTrack` | `SettingsChoiceTiles.kt:93` | `#4A4E57` (dark) / white (light) raised tile | transparent | track r18 / tile r14 | 5 | 76 | 6 | 24sp (21 dense) | Normal→SemiBold | none |
| — | dead generic | `Segmented` | `SettingsComponents.kt:502` | — | — | — | — | — | — | — | **no callers — delete** |

**Audit corrections (verified):**
- The "**Apps** white-filled pill / dark unselected" is **not a selection filter.** Those
  are `ApprovedAppsButton` *action* buttons (App Store / Settings folder / Edit favorites /
  Recent apps), `ApprovedAppsChrome.kt:174` — a one-off `primary` flag fills with `accent`
  (white on dark). The only real selected-state chrome in Apps is the **dock tab**
  (`ApprovedAppsDock`, `ApprovedAppsChrome.kt:148`): active bg = `palette.selected`
  (`#414141` dark / `#D7E7F8` light), inactive transparent.
- The "**Settings** Hey Jarvis | Jarvis segmented control" **does not exist.** `JarvisSettings.kt`
  is boolean toggles; the wake word is fixed. The real Settings segmented control is
  `SettingsTileTrack` (row D above). `CcColors.segOn` is used by `CcSeg` **only**.
- **Nothing animates** selection. `CcSeg` toggles an instant `graphicsLayer.alpha`.

**The mocks do not agree either** (so there is no single pre-approved target — this is the
crux of the decision):

| Mock | File | Track | Selected | Radius |
|---|---|---|---|---|
| CC `.seg` | `mocks/.../quick-controls-live/home.css:20` | `#1f2228` pad 3 | `#3a3d44` grey pill, text→`--text` | track r10 / pill r8 |
| iPadOS `.segmented` | `mocks/ipados/ds.css:88` | `--fill-tertiary` pad 2 | **white + soft shadow `0 1px 3px rgba(0,0,0,.12)`** | track r9 / pill r7 |
| Tools redesign `.segmented` | `mocks/portal-plus-tools-redesign/tools.css:939` | page bg + 1px divider, pad 5 | `surface-raised` fill + **accent-colored text** | track r20 / pill r14 |
| Settings fidelity | `mocks/settings-fidelity-20261117/index.html` | — | "**Solid blue, white text**" for one-choice rows | — |

Common ground across *most* real code + *most* mocks: **a raised pill inside a track**
(A, B, D and the CC/iPadOS/Tools mocks). The two true outliers are **Tools' underline**
(row C) and the **selected-fill color** (grey vs white vs accent vs solid-blue) and
**elevation** (only the iPadOS mock asks for a shadow; no code implements one).

---

## 2. Proposed unified control (opinion)

One composable — call it **`HearthSegmented`** — with **two emphasis variants** so the
screens that approved a solid-accent selection (Settings one-choice rows, per the fidelity
mock) keep that language without re-fragmenting the component.

### 2.1 Canonical spec

```
Track
  fill        = palette.surface2          (token; dark #31343A / light #E5EBF2 after B2 reconcile)
  shape       = RoundedCornerShape(16.dp)  (Space.lg — continuous rounded-rect, iOS-style, NOT full pill)
  padding     = 4.dp (Space.xs) all sides
  layout      = equal-width segments (each weight 1f) — premium alignment, no ragged widths
  gap         = 2.dp (Space.micro) between segments

Segment (option)
  minHeight   = 64.dp (Space.h7 == HearthpaneTouchTargets floor) — unifies 58/68/76
  h-padding   = 20.dp (Space.xl)
  label       = 22.sp, maxLines 1
  icon        = optional, 26.dp, tinted to match label

Selected pill
  shape       = RoundedCornerShape(12.dp)  (Space.md — slightly tighter than track)
  NEUTRAL variant (default):
      fill    = palette.segOn   (NEW reconciled token; dark #4A4D54 / light #FFFFFF)
      label   = palette.text, FontWeight.SemiBold
      depth   = light: soft shadow 0×1dp blur 3dp @12% (honors the iPadOS mock);
                dark:  1dp palette.cardEdge hairline (shadows read as nothing on dark)
  ACCENT variant (opt-in, high-emphasis — e.g. Settings one-choice rows):
      fill    = palette.accent
      label   = palette.accentInk, FontWeight.SemiBold

Unselected segment
      fill    = transparent
      label   = palette.secondary, FontWeight.Normal

States
  pressed     = segment scales to 0.97 + label dims to 85% (spring)
  disabled    = whole control alpha 0.45 (matches SettingsChoiceTiles.DISABLED_ALPHA)
  hover/focus = TV focus ring = palette.accent (keep existing tvFocusable ring)

Motion (the signature premium upgrade — nothing animates today)
  The selected pill is ONE element that SLIDES between segments:
      offset  = animateDpAsState, spring(dampingRatio≈0.9, stiffness≈Medium) (~250ms)
  Labels crossfade color + weight over the same duration.
  Pressed scale = spring(stiffness High).
```

### 2.2 ASCII — each current style vs the proposal

```
A  CcSeg (CC / Alerts)                    PROPOSED (neutral)
   ╭───────────────────────────╮          ╭───────────────────────────╮
   │ (Active·3) │  All  │ Resolv│          │ ┌─────────┐               │
   │ ███████████│       │       │  full    │ │Active·3 │  All  Resolved│  r16 track,
   │ grey pill, instant alpha   │  pill    │ └─────────┘               │  r12 pill
   ╰───────────────────────────╯  r=50     ╰───────────────────────────╯  SLIDES, +depth

B  CallsSegmented                         same proposed component
   ╭───────────────────────────╮            height 76→64, pad 6→4,
   │ ███████ │ Recent │ Messages│            font 25.5→22, surface→segOn,
   │ Rooms   │        │         │  76dp      full-pill→r16/r12, + motion
   ╰───────────────────────────╯

C  ToolsCategoryChip  (OUTLIER)           PROPOSED (neutral)
    All tools   Everyday   Discover         ╭───────────────────────────╮
   ┌─────────┐                              │ ┌─────────┐               │
   │All tools│  Everyday   Discover         │ │All tools│ Everyday  …   │  gains a track,
   └━━━━━━━━━┘  (bare text, no bg)          │ └─────────┘               │  loses underline,
    ▔▔▔▔▔▔▔ 3dp accent underline            ╰───────────────────────────╯  raised pill + motion

D  SettingsTileTrack  (near-reference)    PROPOSED (neutral OR accent)
   ╭───────────────────────────╮          ╭───────────────────────────╮
   │ ┌───────┐                 │          │ ┌───────┐                 │  r18→16, tile r14→12,
   │ │  On   │   Off    Auto   │          │ │  On   │   Off    Auto   │  pad5→4, +slide+depth,
   │ └───────┘ raised, static  │          │ └───────┘                 │  reconcile #4A4E57→segOn
   ╰───────────────────────────╯          ╰───────────────────────────╯
```

### 2.3 Why these specific choices

- **Pill-in-track, not underline, not full-pill.** Three of four controls and three of four
  mocks are already pill-in-track; it is the premium/iOS idiom. A **continuous rounded-rect**
  (r16/r12) rather than a full pill (`RoundedCornerShape(50)`) matches every mock radius
  (r7–r14) and the iOS segmented look; full-pill reads more "toggle chips" than "segmented."
- **Neutral default + accent opt-in** resolves the mock disagreement without a second
  component: CC/Calls/Tools get the calm neutral grey/white raised pill; Settings one-choice
  rows that the fidelity mock approved as "solid blue, white text" use the `ACCENT` variant.
- **Add the elevation the iPadOS mock asks for** (light-mode soft shadow; dark-mode hairline).
  No current control implements it — it is the cheapest single "premium" tell.
- **Add sliding motion.** This is the largest perceived-quality gain and the clearest Apple
  signature; today every control snaps. One shared component means it is written once.
- **64.dp min height / equal-width segments** satisfy the touch-target floor and kill the
  ragged content-widths the audit flagged elsewhere.

### 2.4 Token additions (ties to the tokens proposal)

- New role **`segOn`** in the reconciled palette: dark `#4A4D54`, light `#FFFFFF`. This also
  **resolves a near-duplicate**: `CcColors.segOn = #4A4D54` and
  `SettingsChoiceTiles.DarkSelectedTile = #4A4E57` are the same intent, 1 level apart —
  canonicalize to `#4A4D54`.
- **`segTrack`** = alias of `surface2`.
- Shape/sizing from the `Space` scale (track `Space.lg`=16, pill `Space.md`=12, pad `Space.xs`=4).

---

## 3. Which screens change, and how much (opinion)

| Control | Composable | Change size | Notes |
|---|---|---|---|
| CC + Alerts (+ ~12 other CC callers of `CcSeg`) | `CcSeg` | **Low–Med** | shared, so one edit propagates everywhere; full-pill→r16/r12, +slide, +depth, h 58→64. Keeps `segOn`. |
| Calls | `CallsSegmented` | **Med** | h 76→64, pad 6→4, font 25.5→22, `surface`→`segOn`, full-pill→rounded-rect, +motion. Collapse into `HearthSegmented`. |
| Tools | `ToolsCategoryChip` | **Med–High** (most visible) | removes the underline, adds a track behind the unselected items, equal-width, raised pill, +motion. Biggest visual shift; verify against `tools-redesign` mock. |
| Settings one-choice rows | `SettingsTileTrack` | **Low** (near-reference) | r18→16, r14→12, pad5→4, +slide+depth, grey reconcile. Can expose the `ACCENT` variant for "solid blue" rows. |
| Apps dock | `ApprovedAppsDock` | **Low / optional** | adopt the unified pill for the active tab (`palette.selected`→`segOn`). `ApprovedAppsButton` action buttons are NOT segmented — leave them. |
| dead generic | `Segmented` (`SettingsComponents.kt:502`) | **Delete** | no callers. |

**Effort:** one new shared composable + migration of 4 call-family sites ≈ **M/L** (audit
item #5 agreed). **Risk:** Med — Tools and Control Center are owned by active branches
(`cc-*`, tools); the shared `CcSeg` edit touches ~15 CC call sites at once. Landing order:
build `HearthSegmented` + tokens on a quiet base → migrate `SettingsTileTrack` (lowest risk,
proves the component) → Tools → CC/Alerts → Calls → delete dead `Segmented`.

No Compose-render test harness exists in-repo (pure-JVM JUnit only), so verification is the
build gate + a device/Mini eyeball of the slide motion and dark-mode depth.

---

## 4. Open questions / Decision needed

1. **Selected-fill language:** approve **neutral grey/white raised pill as default** with an
   **accent (solid-blue) opt-in variant** for Settings one-choice rows — or mandate ONE fill
   everywhere (which contradicts either the CC `.seg` mock or the Settings-fidelity mock)?
2. **Shape:** continuous **rounded-rect (r16/r12, recommended)** or keep the current **full
   pill** (`RoundedCornerShape(50)`) that CC and Calls use?
3. **Motion:** approve the **sliding selected pill + label crossfade** (new behavior, the main
   premium win) — any objection to the added animation on the Portal GPU?
4. **Elevation:** add the iPadOS mock's **soft shadow** on light / hairline on dark?
5. **Tools:** confirm it is acceptable to **drop the 3dp accent underline** in favor of the
   unified raised pill (biggest single visual change; must be checked against the approved
   `tools-redesign` mock).
6. **Scope of the shared `CcSeg` edit:** OK to change all ~15 Control Center call sites at once
   via the shared component, coordinating with the active `cc-*` branches?
</content>
