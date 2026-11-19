/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.ApprovedWidgetPacking.Placement

/**
 * Home's widget grid on compact windows. Pure (JVM tested); the slot model itself (4 columns ×
 * 2 slot rows per page, [HomeSlots]) never changes, so a layout made on the Portal+ shows the same
 * widgets on the Mini and edits stay in the same coordinates.
 *
 * - **Upright (COMPACT_PORTRAIT):** the 4-column page is shown 2 columns wide: its left half on
 *   top, its right half directly below the left half's last used row ([fold], [foldOffset]). Two
 *   370 dp columns instead of four 179 dp ones. A page with a widget across the middle keeps the
 *   4-column grid.
 * - **Turned (COMPACT_LANDSCAPE):** 4 columns (299 dp each at 1280 dp), the height floored so a card
 *   never gets shorter than [MIN_HALF_ROW_LANDSCAPE_DP] per half-row; a shorter viewport scrolls.
 * - **LARGE:** [geometry] returns today's 4 × 4 half-row division exactly.
 *
 * Customize always shows the true 4-column grid (drag targets are slot columns).
 */
internal object CompactWidgetGrid {
  const val LARGE_COLUMNS = 4
  const val FOLDED_COLUMNS = 2
  /** Half-rows a page spans in the slot model (2 slot rows × 2). */
  const val PAGE_HALF_ROWS = 4
  /** A folded page has both halves stacked: up to 8 half-rows. */
  const val FOLDED_HALF_ROWS = 8
  /** A folded page with fewer rows still divides the height as if it had this many (no giant cards). */
  const val FOLDED_MIN_DIVISIONS = 6
  const val MIN_HALF_ROW_PORTRAIT_DP = 96f
  const val MIN_HALF_ROW_LANDSCAPE_DP = 80f
  /**
   * The density factor widget cards get on compact windows (not hosted app widgets, which size
   * themselves). Cards were designed for the Portal+'s 459 × 376 dp slot; the Mini's slots are
   * 299–370 dp wide, so content is laid out 15% smaller rather than truncated mid-word.
   */
  const val COMPACT_WIDGET_SCALE = 0.85f

  /** Compact Customize keeps four 179 dp columns upright, so its cards (and edit legend) are denser. */
  const val COMPACT_EDIT_SCALE = 0.75f

  /** The density factor for widget cards on a compact window (LARGE never scales). */
  fun cardScale(layout: LayoutClass, folded: Boolean): Float = when {
    !layout.compact -> 1f
    layout.compactPortrait && !folded -> COMPACT_EDIT_SCALE
    else -> COMPACT_WIDGET_SCALE
  }

  /** True when every placement lies within one half (columns 0–1 or 2–3) of the 4-column page. */
  fun foldable(placements: List<Placement>): Boolean = placements.all { p ->
    p.column + p.columns <= FOLDED_COLUMNS || p.column >= FOLDED_COLUMNS
  }

  /**
   * How far down the right half moves: below the left half's last used half-row (whole slot rows),
   * or below [reservedLeftHalfRows] when an overlay (a shared note) sits in the left half lower down.
   */
  fun foldOffset(placements: List<Placement>, reservedLeftHalfRows: Int = 0): Int {
    val leftUsed = placements.filter { it.column < FOLDED_COLUMNS }.maxOfOrNull { it.row + it.rows } ?: 0
    val used = maxOf(leftUsed, reservedLeftHalfRows).coerceIn(0, PAGE_HALF_ROWS)
    return (used + 1) / 2 * 2
  }

  /** The right half (columns 2–3) moves [offset] half-rows down under the left half. */
  fun fold(p: Placement, offset: Int = PAGE_HALF_ROWS): Placement =
      if (p.column < FOLDED_COLUMNS) p
      else p.copy(column = p.column - FOLDED_COLUMNS, row = p.row + offset)

  /** A logical 4-column cell (column, half-row) on the folded grid, for overlays (slots, notes). */
  fun foldCell(column: Int, halfRow: Int, offset: Int = PAGE_HALF_ROWS): Pair<Int, Int> =
      if (column < FOLDED_COLUMNS) column to halfRow else (column - FOLDED_COLUMNS) to (halfRow + offset)

  /** How a page is drawn. [folded]: [fold] with [foldOffset] applies to placements and overlay cells. */
  data class Geometry(val columns: Int, val folded: Boolean, val halfRowDp: Float, val columnDp: Float,
                      val foldOffset: Int = 0)

  /**
   * Cell sizes for a viewport [widthDp] × [heightDp] with [gapDp] gaps. [usedHalfRows] is how far
   * down the (possibly folded) placements reach.
   */
  fun geometry(layout: LayoutClass, editing: Boolean, placements: List<Placement>, widthDp: Float, heightDp: Float,
               gapDp: Float, reservedLeftHalfRows: Int = 0): Geometry {
    val fold = layout.compactPortrait && !editing && foldable(placements)
    val offset = if (fold) foldOffset(placements, reservedLeftHalfRows) else 0
    val columns = if (fold) FOLDED_COLUMNS else LARGE_COLUMNS
    val columnDp = ((widthDp - gapDp * (columns - 1)) / columns).coerceAtLeast(1f)
    val halfRow = when {
      fold -> {
        val used = maxOf(reservedLeftHalfRows, placements.maxOfOrNull { fold(it, offset).let { f -> f.row + f.rows } } ?: 0)
        val divisions = maxOf(used, FOLDED_MIN_DIVISIONS).coerceAtMost(FOLDED_HALF_ROWS)
        maxOf((heightDp - gapDp * (divisions - 1)) / divisions, MIN_HALF_ROW_PORTRAIT_DP)
      }
      layout.compactLandscape -> maxOf((heightDp - gapDp * 3) / 4, MIN_HALF_ROW_LANDSCAPE_DP)
      // LARGE (and compact Customize): today's exact division.
      else -> ((heightDp - gapDp * 3) / 4).coerceAtLeast(1f)
    }
    return Geometry(columns, fold, halfRow, columnDp, offset)
  }


}
