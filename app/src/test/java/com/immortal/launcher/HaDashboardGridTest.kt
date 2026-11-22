/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import com.immortal.launcher.HaDashboardGrid.Placement
import com.immortal.launcher.HaDashboardModel.Span
import org.junit.Assert.assertEquals
import org.junit.Test

class HaDashboardGridTest {

  // The mock's living room: dimmer, climate, media, then four small tiles.
  private val living = listOf(Span(2, 1), Span(2, 2), Span(2, 1), Span(1, 1), Span(1, 1), Span(1, 1), Span(1, 1))

  @Test fun `Portal+ packs the living room like the mock, no holes`() {
    val p = HaDashboardGrid.pack(living, columns = 4)
    assertEquals(listOf(Placement(0, 0, 2, 1), Placement(0, 2, 2, 2), Placement(1, 0, 2, 1),
        Placement(2, 0, 1, 1), Placement(2, 1, 1, 1), Placement(2, 2, 1, 1), Placement(2, 3, 1, 1)), p.placements)
    assertEquals(3, p.rowCount)
  }

  @Test fun `Mini landscape (3 columns) fills the third column with small tiles`() {
    val p = HaDashboardGrid.pack(living, columns = 3)
    assertEquals(listOf(Placement(0, 0, 2, 1), Placement(1, 0, 2, 2), Placement(3, 0, 2, 1),
        Placement(0, 2, 1, 1), Placement(1, 2, 1, 1), Placement(2, 2, 1, 1), Placement(3, 2, 1, 1)), p.placements)
    assertEquals(4, p.rowCount)
  }

  @Test fun `Mini portrait (2 columns) stacks wide tiles and pairs small ones`() {
    val p = HaDashboardGrid.pack(living, columns = 2)
    assertEquals(listOf(Placement(0, 0, 2, 1), Placement(1, 0, 2, 2), Placement(3, 0, 2, 1),
        Placement(4, 0, 1, 1), Placement(4, 1, 1, 1), Placement(5, 0, 1, 1), Placement(5, 1, 1, 1)), p.placements)
    assertEquals(6, p.rowCount)
  }

  @Test fun `a small tile fills the hole left before a wide one`() {
    val p = HaDashboardGrid.pack(listOf(Span(1, 1), Span(2, 1), Span(1, 1)), columns = 2)
    assertEquals(listOf(Placement(0, 0, 1, 1), Placement(1, 0, 2, 1), Placement(0, 1, 1, 1)), p.placements)
  }

  @Test fun `spans wider than the grid are clamped, empty input is zero rows`() {
    assertEquals(Placement(0, 0, 1, 2), HaDashboardGrid.pack(listOf(Span(2, 2)), columns = 1).placements.single())
    assertEquals(0, HaDashboardGrid.pack(emptyList(), columns = 4).rowCount)
  }

  @Test(expected = IllegalArgumentException::class) fun `zero columns is refused`() {
    HaDashboardGrid.pack(living, columns = 0)
  }

  @Test fun `height uses one gap between rows`() {
    assertEquals(176 * 3 + 20 * 2, HaDashboardGrid.height(3, 176, 20))
    assertEquals(0, HaDashboardGrid.height(0, 176, 20))
  }

  @Test fun `sparkline points span the box and stay inside it`() {
    val pts = HaSpark.points(listOf(19.6f, 20f, 20.6f), 140f, 60f)
    assertEquals(3, pts.size)
    assertEquals(0f, pts.first().first, 0.01f)
    assertEquals(140f, pts.last().first, 0.01f)
    pts.forEach { assert(it.second in 0f..60f) }
    assertEquals(emptyList<Pair<Float, Float>>(), HaSpark.points(listOf(1f), 10f, 10f))
  }
}
