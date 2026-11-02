# World Clock fit: independent review

Reviewed the working changes against base `13760826`, after reading `AGENTS.md`
and `docs/superpowers/plans/2026-11-02-world-clock-fit.md`.
App and test files were read only. The prior routing/alarm review was not repeated.

Follow-up reviewed the final explicit-font-scale budgeting correction. The
current source hashes below supersede the earlier HomeActivity snapshot.

**Findings: no concrete source-level regressions identified in the final
budgeting correction. Runtime acceptance remains unverified by this reviewer.**

## Source findings

- `HomeWorldClockFit.kt` examines at most four candidates, admits whole budgeted
  rows, includes an 8dp gap between rows, and reserves the footer plus another
  8dp whenever saved cities remain. It rejects nonfinite/nonpositive dimensions.
- UI requests a 52sp time line, 26sp city/day line, fixed 2dp label padding,
  8dp between children, and 24sp footer line. The final policy inputs budget
  each line as `max(SP-to-DP conversion, SP value * LocalConfiguration.fontScale)`.
  Row height sums the two independently bounded lines plus 2dp; it does not
  multiply the converted value again or scale the fixed DP gaps.
- At 150% configured scale the row budget is at least 119dp and the footer
  budget at least 36dp. Taking the maximum cannot reduce either budget relative
  to the previous conversion-only implementation, so it cannot admit extra rows
  into the same body. Fewer previews under enlarged text are the intended tradeoff.
- The capacity remember keys now contain saved count, available height, row
  height and footer height: all inputs consumed by the helper. Configuration or
  density changes that alter either budget invalidate capacity. The footer-only
  admission check uses the same `footerHeight` as the helper, covering the
  zero-row case without reverting to the smaller nominal SP conversion.
- The approved grid measures each cell with `Constraints.fixed`. Its enclosing
  page fills its bounded viewport. The other `ImmortalWidgetContent` host,
  `WidgetTile`, supplies an explicit height. The shell's weighted body therefore
  receives finite remaining height; removing its scrolling modifier for World
  Clock lets `BoxWithConstraints` observe that bounded body.
- `zones.take(4)` preserves persisted ordering. The policy receives the full
  saved count, and the footer subtracts only the visible count, so cities beyond
  four remain counted. The zero-row case says “N cities,” rather than implying
  that clocks are already visible.
- No selected cities uses the existing add-cities message. If a row cannot fit,
  the footer alone is allowed when its budgeted line fits. Below that line's
  height, no row/footer is rendered; the existing heading remains the entry
  point to the picker.
- The existing active-page minute producer, resume refresh, timezone/day
  formatting and 12/24-hour selection remain unchanged. The fit logic adds no
  coroutine, listener, preference mutation or scheduling work.
- `scrollContent` defaults to true, and only World Clock passes false. Other
  shell callers retain their prior weighted scrolling body and header behavior.
- Picker wording now describes complete clocks up to four and resizing.
  Selection still removes/reappends a re-picked city. The first-four rank badges
  express order; the “hidden” badge beyond four remains accurate under the cap.
- The new helper uses Kotlin arithmetic and finite-value checks; no new Android
  platform API or minSdk-24 incompatibility was found.

## Native evidence and superseded review

The coordinating task/user reports that native 150% font clipped the previous
footer both after a hot change and on cold launch. This establishes a failure
of the previous native fit assumption; the prior source-only “no concrete
defects” finding was not device acceptance. This reviewer did not independently
observe those runs or inspect their captures.

The final source explicitly addresses the reported under-budgeting and applies
one footer budget consistently. Final compilation and native acceptance were
not independently verified in this follow-up. The main task owns those checks;
this report does not claim the clipping is fixed.

## Unverified assumptions and limits

The policy now uses conservative line budgets, not measured `Text` layouts.
Actual font metrics, line-height trimming, font fallback and pixel rounding can
differ from those budgeted DP totals. Complete row/footer rendering at exact
capacity boundaries, especially at large font scales or fractional density,
requires native measurement; the pure tests cannot establish it.

All clock text/footer rows now use one line with ellipsis. Vertical capacity
does not establish horizontal readability: narrow cards or large text may
truncate time, AM/PM, city/day or overflow wording. The source expresses this
behavior deliberately, but whether the tested widths preserve meaningful
clock labels remains a device acceptance question.

Empty-state text can wrap and does not use the capacity helper. Its readability
in very short cards, and the shared fixed-height heading at large font scales,
also need device inspection. No general shell redesign is proposed here.

Read all nine new policy tests: ordinary capacity, exact boundaries, footer
reservation, four-city cap, larger font measurements, additional saved cities,
tiny/empty selection and malformed constraints. They were not executed.
The existing enlarged-font test supplies 119dp/36dp directly; it does not exercise
the Compose configuration/conversion correction or native text measurement.
`git diff --check 13760826` passed for the two tracked Kotlin files under review.
No Gradle, build, unit-test execution, device command or performance measurement
was performed. No claim is made about measured clipping, lifecycle cancellation
timing, actual scroll gestures, runtime compatibility or frame performance.

Final reviewed source SHA-256 values:

| File | SHA-256 |
| --- | --- |
| `HomeActivity.kt` | `edf4b2598d94f67db91bbfb0f45ae04dcb8fcd7252f008250b38b5f224163cbf` |
| `HomeWorldClockFit.kt` | `2d42d2b062ecc5b67cddc71f5a35f0340b5bb5659065d87a1901888da76ef749` |
| `HomeWorldClockFitTest.kt` | `fbf8180b6642f73ab5a50c758124264c34a54217d74c1aff34b05d828111e6f8` |
| `ImmortalSettingsActivity.kt` | `5728f60db74716c210635771b6e93465df2f0c81b5bccc63db7dffe91d05ec8b` |

Superseded HomeActivity SHA-256 from the initial review:
`cbd424d2be3485ea2b99f5be7f795026bd2e3b00aee701648e5dcdd18f9acb51`.
The helper, tests and picker hashes remain unchanged.

Only this document was written. Git commands used
`-c core.hooksPath=/dev/null --no-optional-locks`. No authentication, GitHub,
push, new agents or commit was used.
