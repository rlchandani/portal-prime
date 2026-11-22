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

class ToolsCategoryLayoutTest {
  private val advanced = ApprovedToolsCatalog.categories(advanced = true)

  /** The library grid's width: the window less the Tools edge (24 dp upright, 48 dp turned). */
  private val miniUpright = 800.0 - 2 * 24
  private val miniTurned = 1280.0 - 2 * 48

  @Test fun everyChipFitsTheRowOnItsOwnAtEveryMiniWidthAndScale() {
    // The flow row wraps whole chips; a chip wider than a line on its own would clip its label.
    for ((cls, width) in listOf(LayoutClass.COMPACT_PORTRAIT to miniUpright, LayoutClass.COMPACT_LANDSCAPE to miniTurned)) {
      val spec = ToolsCategoryLayout.chip(ToolsCategoryLayout.of(cls))
      for (scale in listOf(1f, 1.15f, 1.3f)) for (label in advanced) {
        assertTrue("$label fits $width dp at $scale", spec.width(label, scale) <= width - 2 * ToolsCategoryLayout.INSET_DP)
      }
    }
  }

  @Test fun theUprightChipsAreTighterThanTheTurnedAndThePortalPlusKeepsItsLook() {
    val upright = ToolsCategoryLayout.of(LayoutClass.COMPACT_PORTRAIT)
    val turned = ToolsCategoryLayout.of(LayoutClass.COMPACT_LANDSCAPE)
    assertTrue(upright.padding < turned.padding && upright.gap < turned.gap)
    assertEquals(ToolsCategoryLayout.Chips(padding = 24, gap = 12), ToolsCategoryLayout.of(LayoutClass.LARGE))
  }

  @Test fun theUprightLibraryIsOneColumnSoNeighbouringCardsNeverDifferInHeight() {
    assertEquals(1, ToolsCategoryLayout.libraryColumns(LayoutClass.COMPACT_PORTRAIT, miniUpright))
  }

  @Test fun wideWindowsKeepTheirTwoOrThreeColumns() {
    assertEquals(2, ToolsCategoryLayout.libraryColumns(LayoutClass.COMPACT_LANDSCAPE, 1100.0))
    assertEquals(3, ToolsCategoryLayout.libraryColumns(LayoutClass.COMPACT_LANDSCAPE, 1232.0))
    assertEquals(3, ToolsCategoryLayout.libraryColumns(LayoutClass.LARGE, 1728.0))
  }

  @Test fun theEstimateCoversTheLongestCategory() {
    // "Home & downtime" at 22 sp medium measured ~194 dp of text on the Portal.
    assertTrue(ToolsCategoryLayout.chip(ToolsCategoryLayout.PORTRAIT).width("Home & downtime") >= 194.0 + 28)
  }
}
