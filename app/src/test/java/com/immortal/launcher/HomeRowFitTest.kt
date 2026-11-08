/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRowFitTest {
  private fun fit(items: Int, height: Float, cap: Int = 5) =
      homeRowsVisibleCount(items, height, rowHeightDp = 75f, gapDp = 16f, footerHeightDp = 24f, cap = cap)

  @Test fun allRowsShowWhenTheyFit() = assertEquals(3, fit(3, 3 * 75f + 2 * 16f))

  @Test fun aShortCardDropsRowsAndReservesTheFooter() {
    // Three rows need 257dp; at 220dp two rows (166) plus the footer (24 + 16) fit.
    assertEquals(2, fit(3, 220f))
  }

  @Test fun theFooterIsOnlyReservedWhenSomethingIsHidden() = assertEquals(1, fit(1, 80f))

  @Test fun theCapLimitsRowsEvenWithRoom() = assertEquals(5, fit(9, 10_000f))

  @Test fun nothingFitsOrBadInputShowsNoRows() {
    assertEquals(0, fit(3, 50f))
    assertEquals(0, fit(0, 500f))
    assertEquals(0, fit(3, Float.NaN))
  }
}
