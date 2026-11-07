/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.HomeWidgetStore.HomeWidget
import com.immortal.launcher.HomeWidgetStore.Slot

/**
 * Free placement for Home widgets (2026-11-06). Each page is 4 columns × 4 half-rows: a 2-row ×
 * 4-column grid of slots. Widgets with a saved [Slot] sit exactly there and gaps stay open; widgets
 * without one fill the earliest free slots in saved order. Layouts with no saved slots never come
 * here — they keep the original order-based paging, so updating changes nothing on screen.
 *
 * Pure: inputs are never mutated; every operation returns a new list (or null when refused).
 */
internal object HomeSlots {
  private const val COLUMNS = 4
  private const val HALF_ROWS = 4

  /** A widget and where it sits on its page. Placement rows are half-rows; index = page order. */
  data class Placed(val widget: HomeWidget, val placement: ApprovedWidgetPacking.Placement)

  fun hasSlots(widgets: List<HomeWidget>): Boolean = widgets.any { it.slot != null }

  /** Pages for slot mode. [spans] are display spans aligned with [widgets]. */
  fun resolve(widgets: List<HomeWidget>, spans: List<ApprovedWidgetPacking.Span>): List<List<Placed>> {
    val grid = Grid()
    val cells = arrayOfNulls<Cell>(widgets.size)
    widgets.forEachIndexed { index, widget ->
      val slot = widget.slot?.takeIf { it.valid } ?: return@forEachIndexed
      val cell = Cell(slot.page, slot.column, slot.row * 2, size(spans[index]))
      if (grid.free(cell)) { grid.take(cell); cells[index] = cell }
    }
    widgets.indices.filter { cells[it] == null }.forEach { index ->
      val cell = grid.firstFree(size(spans[index]))
      grid.take(cell); cells[index] = cell
    }
    val pageCount = maxOf(1, (cells.maxOfOrNull { it!!.page } ?: 0) + 1)
    return List(pageCount) { page ->
      widgets.indices.filter { cells[it]!!.page == page }
          .sortedWith(compareBy({ cells[it]!!.row }, { cells[it]!!.column }))
          .mapIndexed { order, index ->
            val cell = cells[index]!!
            Placed(widgets[index], ApprovedWidgetPacking.Placement(order, cell.column, cell.row,
                cell.columns, cell.rows))
          }
    }
  }

  /** What the original order-based layout shows today, page by page. */
  fun legacyPages(widgets: List<HomeWidget>): List<List<Placed>> {
    val count = HomePresentation.legacyPage(widgets, 0).count
    return List(count) { index ->
      val visible = HomePresentation.pageWidgets(widgets, HomePresentation.legacyPage(widgets, index))
      ApprovedWidgetPacking.pack(HomePresentation.spans(visible)).map { Placed(visible[it.index], it) }
    }
  }

  /** Saves every widget's current on-screen position as its slot, so switching modes moves nothing. */
  fun materialize(widgets: List<HomeWidget>, shown: List<List<Placed>>): List<HomeWidget> {
    val slots = buildMap {
      shown.forEachIndexed { page, list ->
        list.forEach { put(it.widget.key, Slot(page, it.placement.column, it.placement.row / 2)) }
      }
    }
    return widgets.map { widget -> slots[widget.key]?.let { widget.copy(slot = it) } ?: widget }
  }

  /**
   * Moves widget [key] to [target]. An empty footprint takes it; a widget of the same size exactly
   * at [target] swaps places with it; anything else (partial overlap, off the grid) is refused.
   */
  fun moveTo(
      widgets: List<HomeWidget>,
      spans: List<ApprovedWidgetPacking.Span>,
      key: String,
      target: Slot,
  ): List<HomeWidget>? {
    val placed = placedCells(widgets, spans)
    val source = placed[key] ?: return null
    if (!target.valid) return null
    val moving = Cell(target.page, target.column, target.row * 2, source.columns to source.rows)
    if (!moving.inBounds()) return null
    val blockers = placed.filterKeys { it != key }.filterValues { it.overlaps(moving) }
    val swap = blockers.entries.singleOrNull()?.takeIf { (_, cell) ->
      cell.page == moving.page && cell.column == moving.column && cell.row == moving.row &&
          cell.columns == moving.columns && cell.rows == moving.rows
    }
    if (blockers.isNotEmpty() && swap == null) return null
    val sourceSlot = Slot(source.page, source.column, source.row / 2)
    return widgets.map {
      when (it.key) {
        key -> it.copy(slot = target)
        swap?.key -> it.copy(slot = sourceSlot)
        else -> it
      }
    }
  }

  /** Resizes widget [key] in place if the new footprint stays on the grid and clear of neighbours. */
  fun resize(
      widgets: List<HomeWidget>,
      spans: List<ApprovedWidgetPacking.Span>,
      key: String,
      span: Pair<Int, Int>,
  ): List<HomeWidget>? {
    val placed = placedCells(widgets, spans)
    val source = placed[key] ?: return null
    val snapped = HomePresentation.slotSpan(span.first, span.second)
    val grown = source.copy(columns = snapped.columns, rows = snapped.rows)
    if (!grown.inBounds() || placed.any { (other, cell) -> other != key && cell.overlaps(grown) }) return null
    val slot = Slot(source.page, source.column, source.row / 2)
    return widgets.map {
      if (it.key == key) it.copy(spanX = snapped.columns, spanY = snapped.rows, explicitSize = true, slot = slot)
      else it
    }
  }

  /** Every empty slot widget [key] fits, on existing pages and one new page after them. */
  fun freeSpots(widgets: List<HomeWidget>, spans: List<ApprovedWidgetPacking.Span>, key: String): List<Slot> {
    val placed = placedCells(widgets, spans)
    val source = placed[key] ?: return emptyList()
    val pages = (placed.values.maxOfOrNull { it.page } ?: 0) + 2
    return (0 until pages).flatMap { page ->
      (0..1).flatMap { row -> (0 until COLUMNS).map { column -> Slot(page, column, row) } }
    }.filter { slot ->
      val cell = Cell(slot.page, slot.column, slot.row * 2, source.columns to source.rows)
      cell.inBounds() && placed.none { (other, it) -> other != key && it.overlaps(cell) } &&
          slot != Slot(source.page, source.column, source.row / 2)
    }
  }

  /** The layout Customize edits: saved slots, or today's on-screen positions saved as slots. */
  fun forEditing(widgets: List<HomeWidget>): List<HomeWidget> =
      if (hasSlots(widgets)) widgets else materialize(widgets, legacyPages(widgets))

  fun moveForEditing(widgets: List<HomeWidget>, key: String, target: Slot): List<HomeWidget>? =
      forEditing(widgets).let { moveTo(it, HomePresentation.spans(it), key, target) }

  fun resizeForEditing(widgets: List<HomeWidget>, key: String, span: Pair<Int, Int>): List<HomeWidget>? =
      forEditing(widgets).let { resize(it, HomePresentation.spans(it), key, span) }

  fun freeSpotsForEditing(widgets: List<HomeWidget>, key: String): List<Slot> =
      forEditing(widgets).let { freeSpots(it, HomePresentation.spans(it), key) }

  private fun placedCells(widgets: List<HomeWidget>, spans: List<ApprovedWidgetPacking.Span>): Map<String, Cell> =
      resolve(widgets, spans).flatMapIndexed { page, list ->
        list.map { it.widget.key to Cell(page, it.placement.column, it.placement.row,
            it.placement.columns to it.placement.rows) }
      }.toMap()

  private fun size(span: ApprovedWidgetPacking.Span) =
      span.columns.coerceIn(1, COLUMNS) to span.rows.coerceIn(1, HALF_ROWS)

  private data class Cell(val page: Int, val column: Int, val row: Int, val columns: Int, val rows: Int) {
    constructor(page: Int, column: Int, row: Int, size: Pair<Int, Int>) :
        this(page, column, row, size.first, size.second)
    fun inBounds() = page >= 0 && column >= 0 && row >= 0 && column + columns <= COLUMNS && row + rows <= HALF_ROWS
    fun overlaps(other: Cell) = page == other.page &&
        column < other.column + other.columns && other.column < column + columns &&
        row < other.row + other.rows && other.row < row + rows
  }

  /** Occupancy across pages; grows as pages are used. */
  private class Grid {
    private val taken = mutableListOf<Cell>()
    fun free(cell: Cell) = cell.inBounds() && taken.none { it.overlaps(cell) }
    fun take(cell: Cell) { taken += cell }
    /** Earliest page, then slot row, then column (slot-aligned rows). */
    fun firstFree(size: Pair<Int, Int>): Cell {
      var page = 0
      while (true) {
        for (row in 0 until HALF_ROWS step 2) for (column in 0 until COLUMNS) {
          val cell = Cell(page, column, row, size)
          if (free(cell)) return cell
        }
        page++
      }
    }
  }
}
