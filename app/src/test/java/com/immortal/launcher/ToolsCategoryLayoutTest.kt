/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Tools library's category chips wrap instead of scrolling sideways on the Mini. */
class ToolsCategoryLayoutTest {
  private val basic = ApprovedToolsCatalog.categories(advanced = false)
  private val advanced = ApprovedToolsCatalog.categories(advanced = true)

  /** The library grid's width: the window less the Tools edge (24 dp upright, 48 dp turned). */
  private val miniUpright = 800.0 - 2 * 24
  private val miniTurned = 1280.0 - 2 * 48

  private fun fits(rows: List<List<Int>>, labels: List<String>, width: Double, scale: Float) = rows.all { row ->
    CompactRowFit.rowWidth(row.map { ToolsCategoryLayout.CHIP.width(labels[it], scale) },
        ToolsCategoryLayout.GAP_DP.toDouble()) <= width - 2 * ToolsCategoryLayout.INSET_DP
  }

  @Test fun theBasicSetStaysOnOneRowOnTheMiniUpright() {
    assertEquals(listOf(basic.indices.toList()), ToolsCategoryLayout.rows(basic, miniUpright))
  }

  @Test fun theAdvancedSetWrapsItsLastChipInsteadOfScrolling() {
    val rows = ToolsCategoryLayout.rows(advanced, miniUpright)
    assertEquals(2, rows.size)
    assertEquals(listOf(advanced.lastIndex), rows.last())
  }

  @Test fun theMiniTurnedFitsEverySetOnOneRow() {
    assertEquals(1, ToolsCategoryLayout.rows(advanced, miniTurned).size)
  }

  @Test fun everyRowFitsAndKeepsReadingOrderAtEveryMiniWidthAndScale() {
    for (labels in listOf(basic, advanced)) for (width in listOf(miniUpright, miniTurned)) for (scale in listOf(1f, 1.15f, 1.3f)) {
      val rows = ToolsCategoryLayout.rows(labels, width, scale)
      assertEquals(labels.indices.toList(), rows.flatten())
      assertTrue("rows fit $width dp at $scale", fits(rows, labels, width, scale))
    }
  }

  @Test fun theEstimateIsGenerousForTheLongestCategory() {
    // "Home & downtime" at 22 sp medium is ~170 dp of text plus 40 dp padding.
    assertTrue(ToolsCategoryLayout.CHIP.width("Home & downtime") >= 210.0)
  }
}
