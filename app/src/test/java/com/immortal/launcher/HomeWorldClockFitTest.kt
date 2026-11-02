/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeWorldClockFitTest {
  @Test fun mediumCard_keepsTwoCompleteCitiesAndAnOverflowLine() {
    assertEquals(2, homeWorldClockVisibleCount(3, 208f, 80f, 24f))
    assertEquals(2, homeWorldClockVisibleCount(4, 208f, 80f, 24f))
  }

  @Test fun noOverflow_doesNotReserveAnUnneededFooter() {
    assertEquals(2, homeWorldClockVisibleCount(2, 168f, 80f, 24f))
    assertEquals(1, homeWorldClockVisibleCount(2, 167f, 80f, 24f))
  }

  @Test fun partialRow_isExcludedAndItsFooterSpaceIsReserved() {
    assertEquals(1, homeWorldClockVisibleCount(3, 199f, 80f, 24f))
    assertEquals(2, homeWorldClockVisibleCount(3, 200f, 80f, 24f))
  }

  @Test fun largerCard_showsAllFourCompleteCities() {
    assertEquals(4, homeWorldClockVisibleCount(4, 344f, 80f, 24f))
    assertEquals(3, homeWorldClockVisibleCount(4, 343f, 80f, 24f))
  }

  @Test fun enlargedFont_measurementsReduceCapacityWithoutShrinkingText() {
    // 52sp + 26sp at 150%, plus the fixed 2dp city gap.
    assertEquals(1, homeWorldClockVisibleCount(3, 208f, 119f, 36f))
  }

  @Test fun savedCitiesBeyondFour_remainCountedForTheOverflowBudget() {
    assertEquals(3, homeWorldClockVisibleCount(8, 344f, 80f, 24f))
    assertEquals(4, homeWorldClockVisibleCount(8, 376f, 80f, 24f))
  }

  @Test fun tinyCard_prefersAnOverviewToHalfACity() {
    assertEquals(0, homeWorldClockVisibleCount(3, 111f, 80f, 24f))
    assertEquals(1, homeWorldClockVisibleCount(1, 80f, 80f, 24f))
  }

  @Test fun emptySelection_hasNoRows() {
    assertEquals(0, homeWorldClockVisibleCount(0, 208f, 80f, 24f))
    assertEquals(0, homeWorldClockVisibleCount(-1, 208f, 80f, 24f))
  }

  @Test fun malformedConstraints_cannotAdmitRows() {
    assertEquals(0, homeWorldClockVisibleCount(3, Float.NaN, 80f, 24f))
    assertEquals(0, homeWorldClockVisibleCount(3, Float.POSITIVE_INFINITY, 80f, 24f))
    assertEquals(0, homeWorldClockVisibleCount(3, -1f, 80f, 24f))
    assertEquals(0, homeWorldClockVisibleCount(3, 208f, 0f, 24f))
    assertEquals(0, homeWorldClockVisibleCount(3, 208f, 80f, Float.NaN))
  }
}
