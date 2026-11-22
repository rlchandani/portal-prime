/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

/**
 * Dense grid packing for the room's tiles, the same rule as CSS `grid-auto-flow: row dense`:
 * every tile takes the first free cell (row by row, left to right) its span fits, so a 1×1 tile
 * fills the hole beside a 2×2 thermostat instead of leaving a half-empty row. Pure, unit-tested.
 */
object HaDashboardGrid {

  /** A tile's cell: top-left [row]/[col] and its size in cells. */
  data class Placement(val row: Int, val col: Int, val cols: Int, val rows: Int)

  /** The packed layout: one [Placement] per input span, in input order, and the rows used. */
  data class Packing(val placements: List<Placement>, val rowCount: Int)

  fun pack(spans: List<HaDashboardModel.Span>, columns: Int): Packing {
    require(columns > 0) { "columns must be positive" }
    var taken = emptySet<Pair<Int, Int>>()
    val placements = spans.map { span ->
      val w = span.cols.coerceIn(1, columns)
      val h = span.rows.coerceAtLeast(1)
      val spot = firstFit(taken, columns, w, h)
      taken = taken + cells(spot.first, spot.second, w, h)
      Placement(spot.first, spot.second, w, h)
    }
    val rows = placements.maxOfOrNull { it.row + it.rows } ?: 0
    return Packing(placements, rows)
  }

  private fun firstFit(taken: Set<Pair<Int, Int>>, columns: Int, w: Int, h: Int): Pair<Int, Int> =
      generateSequence(0) { it + 1 }
          .flatMap { r -> (0..columns - w).asSequence().map { c -> r to c } }
          .first { (r, c) -> cells(r, c, w, h).none { it in taken } }

  private fun cells(row: Int, col: Int, w: Int, h: Int): List<Pair<Int, Int>> =
      (row until row + h).flatMap { r -> (col until col + w).map { c -> r to c } }

  /** Total height in dp for [rows] rows of [rowHeight] separated by [gap]. */
  fun height(rows: Int, rowHeight: Int, gap: Int): Int = if (rows <= 0) 0 else rows * rowHeight + (rows - 1) * gap
}

/** Sparkline geometry (the mock's `sparkPath`): points across [w]×[h], centred with a minimum span. */
object HaSpark {
  private const val MIN_SPAN_SHARE = 0.06f
  private const val HEIGHT_SHARE = 0.7f

  fun points(values: List<Float>, w: Float, h: Float): List<Pair<Float, Float>> {
    if (values.size < 2) return emptyList()
    val min = values.min()
    val max = values.max()
    val span = maxOf(max - min, kotlin.math.abs(max) * MIN_SPAN_SHARE, 1f)
    val mid = (min + max) / 2f
    return values.mapIndexed { i, v -> (i / (values.size - 1f) * w) to (h / 2f - ((v - mid) / span) * (h * HEIGHT_SHARE)) }
  }
}
