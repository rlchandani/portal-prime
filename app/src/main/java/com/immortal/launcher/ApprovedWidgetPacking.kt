/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Pure, input-ordered rectangle packing into a grid with no fixed row limit. */
internal object ApprovedWidgetPacking {
  data class Span(val columns: Int, val rows: Int)

  data class Placement(
      val index: Int,
      val column: Int,
      val row: Int,
      val columns: Int,
      val rows: Int,
  )

  /**
   * Places each normalized span at the earliest fitting row, then the leftmost column.
   * Coordinates are zero-based; results retain input order and identify inputs by [Placement.index].
   * Widths clamp to 1..[columns] and heights to 1..4. Grid widths must be in 1..64.
   *
   * One bit mask per occupied row preserves vertical holes beneath shorter rectangles.
   * Storage grows geometrically and stays local to this call.
   */
  fun pack(spans: List<Span>, columns: Int = 4): List<Placement> {
    require(columns in 1..64) { "columns must be between 1 and 64" }
    if (spans.isEmpty()) return emptyList()

    val placements = ArrayList<Placement>(spans.size)
    var occupied = LongArray(4)
    for (index in spans.indices) {
      val span = spans[index]
      val width = span.columns.coerceIn(1, columns)
      val height = span.rows.coerceIn(1, 4)
      // JVM shifts wrap at 64, so the full-width case needs an explicit all-bits mask.
      val widthMask = if (width == 64) -1L else (1L shl width) - 1L
      var row = 0
      search@ while (true) {
        var blockedColumns = 0L
        for (y in row until minOf(row + height, occupied.size)) {
          blockedColumns = blockedColumns or occupied[y]
        }
        for (column in 0..columns - width) {
          val mask = widthMask shl column
          if (blockedColumns and mask != 0L) continue

          val endRow = row + height
          if (endRow > occupied.size) {
            occupied = occupied.copyOf(maxOf(endRow, occupied.size * 2))
          }
          for (y in row until endRow) {
            occupied[y] = occupied[y] or mask
          }
          placements.add(Placement(index, column, row, width, height))
          break@search
        }
        row++
      }
    }
    return placements
  }
}
