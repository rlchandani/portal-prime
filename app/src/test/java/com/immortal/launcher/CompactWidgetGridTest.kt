/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.ApprovedWidgetPacking.Placement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactWidgetGridTest {
  // The Mini's own page: Today and Quick Controls (1 slot each) beside a 2×2 Photo Memory.
  private val miniPage = listOf(
      Placement(0, 0, 0, 1, 2),
      Placement(1, 1, 0, 1, 2),
      Placement(2, 2, 0, 2, 4),
  )
  private val gap = 12f

  @Test fun largeKeepsTheExactFourByFourDivision() {
    val g = CompactWidgetGrid.geometry(LayoutClass.LARGE, editing = false, miniPage, 1872f, 765f, gap)
    assertEquals(4, g.columns)
    assertFalse(g.folded)
    assertEquals((1872f - 3 * gap) / 4, g.columnDp, 0f)
    assertEquals((765f - 3 * gap) / 4, g.halfRowDp, 0f)
    assertEquals(1f, CompactWidgetGrid.cardScale(LayoutClass.LARGE, folded = false), 0f)
  }

  @Test fun theUprightMiniFoldsThePageIntoTwoColumns() {
    val g = CompactWidgetGrid.geometry(LayoutClass.COMPACT_PORTRAIT, editing = false, miniPage, 752f, 738f, gap)
    assertTrue(g.folded)
    assertEquals(2, g.columns)
    assertEquals((752f - gap) / 2, g.columnDp, 0f) // 370 dp, not 179
    // Today/QC on top, the photo (right half) right below them: 6 half-rows fill the height.
    assertEquals(2, g.foldOffset)
    val folded = miniPage.map { CompactWidgetGrid.fold(it, g.foldOffset) }
    assertEquals(Placement(0, 0, 0, 1, 2), folded[0])
    assertEquals(Placement(1, 1, 0, 1, 2), folded[1])
    assertEquals(Placement(2, 0, 2, 2, 4), folded[2])
    assertEquals((738f - 5 * gap) / 6, g.halfRowDp, 0.01f)
  }

  @Test fun aSharedNoteInTheLeftHalfKeepsItsSlot() {
    // The note takes the free slot under Today (slot row 1 → half-rows 2–3).
    assertEquals(4, CompactWidgetGrid.foldOffset(miniPage, reservedLeftHalfRows = 4))
    val g = CompactWidgetGrid.geometry(LayoutClass.COMPACT_PORTRAIT, false, miniPage, 752f, 738f, gap, 4)
    assertEquals(4, g.foldOffset)
    assertEquals(0 to 2, CompactWidgetGrid.foldCell(0, 2, g.foldOffset))
  }

  @Test fun anEmptyLeftHalfPutsTheRightHalfOnTop() {
    assertEquals(0, CompactWidgetGrid.foldOffset(listOf(Placement(0, 2, 0, 2, 4))))
  }

  @Test fun aFullPageFloorsTheRowsAndScrolls() {
    val full = listOf(Placement(0, 0, 0, 2, 4), Placement(1, 2, 0, 2, 4))
    val g = CompactWidgetGrid.geometry(LayoutClass.COMPACT_PORTRAIT, editing = false, full, 752f, 738f, gap)
    assertTrue(g.folded)
    // 8 half-rows at (738 − 84) / 8 ≈ 82 dp would be too short: floored at 96 dp.
    assertEquals(CompactWidgetGrid.MIN_HALF_ROW_PORTRAIT_DP, g.halfRowDp, 0f)
  }

  @Test fun aWidgetAcrossTheMiddleKeepsFourColumns() {
    val wide = listOf(Placement(0, 0, 0, 4, 2), Placement(1, 1, 2, 2, 2))
    assertFalse(CompactWidgetGrid.foldable(wide))
    val g = CompactWidgetGrid.geometry(LayoutClass.COMPACT_PORTRAIT, editing = false, wide, 752f, 738f, gap)
    assertFalse(g.folded)
    assertEquals(4, g.columns)
    assertEquals(CompactWidgetGrid.COMPACT_EDIT_SCALE, CompactWidgetGrid.cardScale(LayoutClass.COMPACT_PORTRAIT, false), 0f)
  }

  @Test fun customizeAlwaysShowsTheTrueSlotGrid() {
    val g = CompactWidgetGrid.geometry(LayoutClass.COMPACT_PORTRAIT, editing = true, miniPage, 752f, 738f, gap)
    assertFalse(g.folded)
    assertEquals(4, g.columns)
  }

  @Test fun theTurnedMiniKeepsFourColumnsWithAFlooredHeight() {
    val g = CompactWidgetGrid.geometry(LayoutClass.COMPACT_LANDSCAPE, editing = false, miniPage, 1232f, 300f, gap)
    assertFalse(g.folded)
    assertEquals(4, g.columns)
    assertEquals(CompactWidgetGrid.MIN_HALF_ROW_LANDSCAPE_DP, g.halfRowDp, 0f)
    val tall = CompactWidgetGrid.geometry(LayoutClass.COMPACT_LANDSCAPE, editing = false, miniPage, 1232f, 400f, gap)
    assertEquals((400f - 3 * gap) / 4, tall.halfRowDp, 0f)
    assertEquals(CompactWidgetGrid.COMPACT_WIDGET_SCALE, CompactWidgetGrid.cardScale(LayoutClass.COMPACT_LANDSCAPE, false), 0f)
  }

  @Test fun overlayCellsFoldWithTheirWidgets() {
    assertEquals(1 to 2, CompactWidgetGrid.foldCell(1, 2))
    assertEquals(0 to 4, CompactWidgetGrid.foldCell(2, 0))
    assertEquals(1 to 6, CompactWidgetGrid.foldCell(3, 2))
    // A widget and the slot cell it occupies move together.
    val p = Placement(0, 3, 2, 1, 2)
    val f = CompactWidgetGrid.fold(p)
    assertEquals(CompactWidgetGrid.foldCell(p.column, p.row), f.column to f.row)
  }

  @Test fun foldingNeverOverlapsAFoldablePage() {
    val packed = ApprovedWidgetPacking.pack(listOf(
        ApprovedWidgetPacking.Span(1, 2), ApprovedWidgetPacking.Span(1, 2), ApprovedWidgetPacking.Span(2, 2),
        ApprovedWidgetPacking.Span(2, 2), ApprovedWidgetPacking.Span(1, 2), ApprovedWidgetPacking.Span(1, 2)))
    assertTrue(CompactWidgetGrid.foldable(packed))
    val cells = packed.map(CompactWidgetGrid::fold).flatMap { p ->
      (p.column until p.column + p.columns).flatMap { c -> (p.row until p.row + p.rows).map { r -> c to r } }
    }
    assertEquals(cells.size, cells.toSet().size)
    assertTrue(cells.all { it.first in 0..1 && it.second in 0..7 })
  }
}
