/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shared segmented control's sliding pill is positioned purely from the measured option widths
 * and the inter-option gap (see [HearthSegmentedGeometry]). These tests pin that math — the same
 * helper the composable animates towards — so the pill can't drift or land on the wrong option.
 */
class HearthSegmentedGeometryTest {

  @Test fun pillSitsAtFirstOption_offsetZero() {
    val widths = listOf(100, 120, 140)
    assertEquals(0, HearthSegmentedGeometry.pillOffsetPx(widths, gapPx = 8, index = 0))
    assertEquals(100, HearthSegmentedGeometry.pillWidthPx(widths, index = 0))
  }

  @Test fun pillOffsetAccumulatesWidthsAndOneGapPerEarlierOption() {
    val widths = listOf(100, 120, 140)
    // index 1: skip option 0 (100) + one gap (8)
    assertEquals(108, HearthSegmentedGeometry.pillOffsetPx(widths, gapPx = 8, index = 1))
    assertEquals(120, HearthSegmentedGeometry.pillWidthPx(widths, index = 1))
    // index 2: skip options 0+1 (220) + two gaps (16)
    assertEquals(236, HearthSegmentedGeometry.pillOffsetPx(widths, gapPx = 8, index = 2))
    assertEquals(140, HearthSegmentedGeometry.pillWidthPx(widths, index = 2))
  }

  @Test fun equalWidthOptions_landOnGridMultiples() {
    val widths = listOf(200, 200, 200, 200)
    (0 until 4).forEach { i ->
      assertEquals(i * (200 + 4), HearthSegmentedGeometry.pillOffsetPx(widths, gapPx = 4, index = i))
      assertEquals(200, HearthSegmentedGeometry.pillWidthPx(widths, index = i))
    }
  }

  @Test fun zeroGap_offsetIsPlainWidthSum() {
    val widths = listOf(50, 75, 90)
    assertEquals(125, HearthSegmentedGeometry.pillOffsetPx(widths, gapPx = 0, index = 2))
  }

  @Test fun indexOutOfRange_isClampedIntoBounds() {
    val widths = listOf(100, 120, 140)
    // Negative clamps to the first option.
    assertEquals(0, HearthSegmentedGeometry.pillOffsetPx(widths, gapPx = 8, index = -3))
    assertEquals(100, HearthSegmentedGeometry.pillWidthPx(widths, index = -3))
    // Past the end clamps to the last option.
    assertEquals(236, HearthSegmentedGeometry.pillOffsetPx(widths, gapPx = 8, index = 9))
    assertEquals(140, HearthSegmentedGeometry.pillWidthPx(widths, index = 9))
  }

  @Test fun emptyWidths_returnZeroInsteadOfCrashing() {
    assertEquals(0, HearthSegmentedGeometry.pillOffsetPx(emptyList(), gapPx = 8, index = 0))
    assertEquals(0, HearthSegmentedGeometry.pillWidthPx(emptyList(), index = 0))
  }
}
