# Premium Proposal — Spacing Scale + Color-Token Reconciliation

Status: **proposal for owner decision. No code changed.**
Date: 2026-11-24
Scope: addresses audit systemic findings **S1** (no spacing scale) and **S2** (two
drifted token systems). Companion to `premium-proposal-segmented-control.md`.

This doc is in two halves: (A) a named spacing scale, (B) a single reconciled color
token source. Each half separates **what the code does today (verified)** from **what I
propose (opinion)**, and ends with a short "Decision needed" list.

---

## Part A — Spacing scale

### A.0 What the code does today (verified)

Grounded by ripgrep over `app/src/main/java` (1,467 Kotlin files):

- **4,702** raw `.dp` literal occurrences total. There is **no general spacing scale**.
- One correction to the audit's "no token object exists anywhere" claim — two *partial*
  tokens already exist and should be folded into the new scale, not ignored:
  - `SettingsSpacing.kt` — `internal object SettingsSpacing` with 5 constants
    (`SECTION_GAP_DP = 34`, `CARD_GAP_DP = 20`, `TEXT_INSET_DP = 28`, `HEADER_GAP_DP = 10`,
    `FOOTER_GAP_DP = 10`) + `@Composable` `SettingsSectionGap()`/`SettingsCardGap()`.
    Used in 38 sites across 20 files, but scoped to Settings only.
  - `ui/components/Glass.kt` — `object Glass { val Radius: Dp = 22.dp }`, one shared radius.
  - `ui/theme/HearthpaneTouchTargets.kt` — min touch-target sizing (accessibility), not spacing.

So the pattern to standardize already has one proven precedent in-repo (`SettingsSpacing`);
the proposal generalizes it.

**Frequency of `.dp` literals (top 25, exact counts):**

| dp | count | dp | count | dp | count |
|----|-------|----|-------|----|-------|
| 24 | 381 | 18 | 204 | 6  | 121 |
| 16 | 360 | 8  | 203 | 26 | 115 |
| 12 | 316 | 10 | 193 | 4  | 110 |
| 28 | 272 | 22 | 178 | 40 | 107 |
| 20 | 257 | 30 | 171 | 1  | 100 |
| 14 | 227 | 32 | 130 | 36 | 94  |
|    |     | 68 | 71  | 34 | 71  |
|    |     | 64 | 65  | 2  | 57  |

**The single most important fact for the scale design:** the codebase is effectively on a
**2-dp grid, not a clean 4-pt or 8-pt grid.** The 4-pt family (4/8/12/16/20/24/28/32/40)
dominates, but the *off-4* even values are individually huge: **14 (×227), 18 (×204),
22 (×178), 26 (×115), 30 (×171), 34 (×71)** — well over 900 uses combined. A purist 4-pt
scale cannot represent these without snapping them (14→16, 18→16/20, 22→24, 26→24/28),
and every snap is a visible pixel shift at hundreds of call sites. **The scale must fit
this reality or migration cannot be pixel-safe.**

Corner radii share the same 2-dp grid (no separate radius scale): 16 (×77), 24 (×44),
20 (×37), 28 (×27), 18 (×26), 14 (×23); `999.dp`/`RoundedCornerShape(50)` is the pill idiom.

Top dp-literal hotspot files (for staging the migration): `HomeWidgetAddFlow.kt` (75),
`MusicPlayerUi.kt` (71), `ApprovedToolsHost.kt` (67), `SettingsComponents.kt` (64),
`CallScreensLive.kt` (64), `StoreActivity.kt` (62), `MusicBrowseUi.kt` (61),
`CallsScreens.kt` (54), `CallScreensRing.kt` (54), `ApprovedHomeWidgetLibrary.kt` (54).

### A.1 Proposed scale (opinion)

A dual-layer scale. New code reaches for the **primary 4-pt rhythm**; the migration uses
the full enumeration (including the 2-dp "density" steps) so every existing literal maps
**1:1 with zero pixel shift**.

```kotlin
// ui/theme/Space.kt  (proposed — not yet created)
object Space {
    // Primary 4-pt rhythm — the vocabulary for ALL new code.
    val none = 0.dp
    val xs   = 4.dp     // 110 uses
    val sm   = 8.dp     // 203
    val md   = 12.dp    // 316
    val lg   = 16.dp    // 360   <- default gutter / card padding
    val xl   = 20.dp    // 257
    val xxl  = 24.dp    // 381   <- most common; screen margin
    val h3   = 28.dp    // 272   (7×4)
    val h4   = 32.dp    // 130
    val h5   = 40.dp    // 107
    val h6   = 48.dp    // 45
    val h7   = 64.dp    // 65    (also MinTarget)
    val h8   = 72.dp    // 50

    // Hairlines / fine detail.
    val hair  = 1.dp    // 100   borders, dividers
    val micro = 2.dp    // 57

    // DENSITY steps (2-dp grid, off the 4-pt rhythm). Retained so migration is 1:1
    // and pixel-exact. FLAGGED: do not use in new code — round to the 4-pt rhythm above.
    @Deprecated("legacy density step; prefer md/lg/xl") val d14 = 14.dp  // 227
    @Deprecated("legacy density step; prefer lg/xl")    val d18 = 18.dp  // 204
    @Deprecated("legacy density step; prefer xl/xxl")   val d22 = 22.dp  // 178
    @Deprecated("legacy density step; prefer xxl/h3")   val d26 = 26.dp  // 115
    @Deprecated("legacy density step; prefer h3/h4")    val d30 = 30.dp  // 171
    @Deprecated("legacy density step")                  val d34 = 34.dp  // 71
    val d6  = 6.dp   // 121  (between xs and sm — very common; keep un-deprecated)
    val d10 = 10.dp  // 193  (ditto)
    val d36 = 36.dp  // 94
}
```

Plus a thin **semantic layer** for the roles that recur, so intent is legible (these
alias into the scale and absorb the existing `SettingsSpacing` constants):

```kotlin
object Layout {
    val screenMargin = Space.xxl   // 24
    val cardGutter   = Space.lg    // 16  (one value H & V — see audit S5/Home)
    val cardPadding  = Space.xl    // 20
    val sectionGap   = Space.d34   // 34  (== SettingsSpacing.SECTION_GAP_DP)
    val rowGap       = Space.md    // 12
    val textInset    = Space.h3    // 28  (== SettingsSpacing.TEXT_INSET_DP)
}
```

Naming rationale: t-shirt names for the 4-pt rhythm read well in Compose
(`padding(Space.lg)`); the `dNN` density steps are self-documenting (the number *is* the
dp) so a 1:1 migration is unambiguous and greppable, and the `@Deprecated` tag makes the
IDE nudge new code back onto the rhythm without breaking the build.

### A.2 Migration order + pixel-shift risk (opinion)

Staged so **every step up to Phase 3 is provably zero-pixel-shift** (pure rename):

1. **Phase 0 — introduce `Space`/`Layout` only.** Add the object. No call sites change.
   Zero risk. Fold `SettingsSpacing` constants into `Layout` with aliases so nothing breaks.
2. **Phase 1 — mechanical 1:1 replacement in the 10 hotspot files.** Replace exact literals
   (`24.dp` → `Space.xxl`, `14.dp` → `Space.d14`, …). **Zero pixel shift** — same value,
   new name. Verify with a build + a screenshot diff per file (none expected).
3. **Phase 2 — roll the same 1:1 replacement across remaining files.** Still zero shift.
4. **Phase 3 — OPTIONAL rounding pass, screen by screen, gated on design review.** Only here
   do you snap density steps onto the 4-pt rhythm (e.g. `d14`→`lg`, `d18`→`xl`). **This is
   the only phase that moves pixels** and must be approved per screen against the mocks —
   never auto-applied. Many `d30`/`d34` values are deliberate (header heights, hero cards)
   and should likely stay.

**Pixel-shift risk, stated honestly:** Phases 0–2 are rename-only and safe to land
incrementally even across the many parallel branches (a token file + mechanical edits
rarely conflict semantically). Phase 3 is where premium rhythm is actually won *and* where
regressions hide — it is a design decision, not a refactor, and should be the last thing
done, one contested screen at a time (Home, Control Center, Music are owned by active
branches — coordinate). Do **not** bundle Phase 3 into the token rollout.

### A.3 Decision needed (Part A)

1. **Approve the dual-layer shape** (4-pt rhythm + retained 2-dp density steps), or mandate
   a stricter pure-4-pt scale (accepting pixel shifts at ~900 sites)?
2. **Approve the names** (`Space.xs…h8` + `dNN` density + `Layout.*` semantic), or prefer a
   different convention?
3. **Is Phase 3 (rounding to rhythm) wanted at all**, or is zero-shift tokenization the goal
   and current spacing values are accepted as-is?

---

## Part B — Color-token reconciliation

### B.0 What the code does today (verified)

Two independent token objects encode the same roles:

- `ui/theme/ApprovedPalette.kt` — `ApprovedLightPalette` / `ApprovedDarkPalette`
  (app-wide base; governs the launcher).
- `ControlCenterTheme.kt` — `CcLight` / `CcDark` (`internal data class CcColors`), used
  only by Control Center. Its file comment states the values are **"copied from the
  approved mock's CSS"** (lines 22–27).

**Key finding the audit did not surface: the drift is confined to DARK mode.** In **light**
mode the two palettes already agree on every shared role (bg `#F2F3F7`, rail/sidebar
`#E9EDF3`, surface `#FFFFFF`, surface2 `#E5EBF2`, text `#182C39`, secondary `#536674`,
divider `#DCE3EB`, accent `#176ABB`, selected `#D7E7F8`, green `#288B61`, red `#D33D4C`).
So **light mode needs no change at all** — only dark-mode greys diverged.

**Dark-mode collision table (verified, exact hex):**

| Role | `ApprovedDarkPalette` | `CcDark` | Same? | Note |
|------|----------------------|----------|-------|------|
| background / bg | `#14161B` | `#14161B` | ✅ | agree |
| sidebar / rail | `#14161B` | `#101216` | ❌ | CC rail is a distinct, darker element — arguably an *intentional* separate role, not pure drift |
| surface | `#2B2B2B` | `#24262B` | ❌ | **collision** — flat-neutral vs cool-tinted, slightly darker |
| surface2 | `#363636` | `#31343A` | ❌ | **collision** — same character |
| divider / div | `#484848` | `#3A3D44` | ❌ | **collision** — same character |
| text | `#FFFFFF` | `#FFFFFF` | ✅ | agree |
| secondary / sec | `#B8B8B8` | `#B8B8B8` | ✅ | agree |
| selected / sel | `#414141` | `#414141` | ✅ | agree |
| accent | `#FFFFFF` | `#FFFFFF` | ✅ | agree |
| green | `#66C49E` | `#66C49E` | ✅ | agree |
| error / red | `#FF747D` | `#FF747D` | ✅ | agree |

**CC-only dark roles with no launcher equivalent (not collisions — genuine extensions):**
`faint #979AA1`, `amber #F0A65A` (+ `amberBg #3B2A15`, `redBg #43191E`, `greenBg #173328`),
`segOn #4A4D54` (segmented pill), `cardEdge #0AFFFFFF`, `glass #D1202227`, `scrim #80000000`,
`accentInk #14161B`, `dotOff #4A4F57`, and the 3D floor-plan set (`floor #22252B`,
`floor2 #1E2126`, `wall #5A606A`, `wallFace #2E3238`, `slab #191B1F`, `slab2 #111316`,
`planBg #16181C`, `ink #FFFFFF`).

Character of the collision: `ApprovedDarkPalette` greys are **pure neutral** (equal R=G=B:
`2B2B2B`, `363636`, `484848`); `CcDark` greys are **subtly cool-tinted and a touch darker**
(`24262B`, `31343A`, `3A3D44`). On the same panel, the launcher reads a hair warmer/flatter
than Control Center. The magnitude is small (a handful of 8-bit levels per channel) but
visible side by side.

### B.1 Proposed reconciliation (opinion)

**Collapse to one source of truth and treat CC-only roles as a typed extension of it.**

Concretely:
- Keep a single `ApprovedPalette` as the base (shared roles). Add the CC-only roles
  (`faint`, `amber*`, `segOn`, `glass`, `scrim`, floor-plan set, …) as a small companion
  object `ControlCenterPalette` that *derives its shared roles from* `ApprovedPalette` and
  only adds what is genuinely CC-specific. `CcColors` stops holding its own copies of
  surface/surface2/divider/text/etc.
- **Canonical value for each dark collision — recommendation:** adopt the **cool-tinted
  `CcDark` values** (`surface #24262B`, `surface2 #31343A`, `divider #3A3D44`) as canonical,
  and shift `ApprovedDarkPalette` to match. Rationale: (a) they are **mock-sourced** (the CC
  file is "copied from the approved mock's CSS"), which is the owner's stated bar — match the
  approved mock; (b) a cool, slightly-darker neutral reads more premium than flat `#2B2B2B`.
  Trade-off: this moves the **launcher's** dark surfaces (the larger surface area), so it needs
  one dark-mode eyeball pass. Light mode is untouched.
  - *Alternative* (lower blast radius, less "premium"): keep `ApprovedDarkPalette`'s neutral
    greys as canonical and re-point CC to them — then only Control Center's dark surfaces move.
- **`rail`/`sidebar`:** treat as a **deliberate distinct role**, not a collision. CC's darker
  `#101216` rail against the `#14161B` body is an intentional depth cue; document it as a named
  role (`railDark`) rather than forcing it equal to `sidebar`.

Proposed canonical dark greys (recommended path):

| Role | Proposed canonical | From |
|------|-------------------|------|
| surface | `#24262B` | CcDark (mock-sourced) |
| surface2 | `#31343A` | CcDark (mock-sourced) |
| divider | `#3A3D44` | CcDark (mock-sourced) |
| sidebar (body rail) | `#14161B` | unchanged |
| railDark (CC deep rail) | `#101216` | documented as its own role |

### B.2 Migration order + risk (opinion)

1. **Phase 0 — reconcile light first (zero visual change).** Point `CcLight`'s shared roles at
   `ApprovedLightPalette`. Values are already identical, so this is a pure dedupe with **no
   pixel change** — safe, uncontested, proves the derivation pattern.
2. **Phase 1 — add CC-only roles to the unified palette** (`faint`, `amber*`, `segOn`, glass,
   scrim, floor-plan). No shared value changes. Zero risk.
3. **Phase 2 — flip the dark canonical.** Apply the chosen canonical greys. If the recommended
   (CC) path: launcher dark surfaces shift subtly cooler/darker — **one dark-mode eyeball across
   launcher screens required.** If the alternative path: only CC dark shifts. Either way light is
   untouched.
4. **Phase 3 — delete the duplicate `CcColors` copies**, leaving `ControlCenterPalette` as a
   thin extension. Mechanical.

**Pixel-shift risk:** Phases 0–1 are zero-shift. Phase 2 is the only color move and is a *subtle*
dark-only shift (small per-channel deltas) on exactly the three greys — low risk but genuinely
visible, so gate it on a dark eyeball. Control Center is owned by many `cc-*` branches; land the
palette dedupe on a quiet base and coordinate the dark flip.

### B.3 Decision needed (Part B)

1. **Canonical dark greys:** adopt the mock-sourced **CcDark** values (recommended — shifts the
   launcher) or keep **ApprovedDark** neutral greys (shifts only CC)?
2. **Confirm `rail`/`sidebar` is an intentional two-value role** (keep both) rather than a drift
   to collapse.
3. **One object or two?** Approve "one base `ApprovedPalette` + thin `ControlCenterPalette`
   extension," or keep two objects but make CC derive its shared roles from the base?
</content>
</invoke>
