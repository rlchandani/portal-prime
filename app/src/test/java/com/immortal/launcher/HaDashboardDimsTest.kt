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

class HaDashboardDimsTest {
  private val all = listOf(HaDashboardDims.LARGE, HaDashboardDims.COMPACT_LANDSCAPE, HaDashboardDims.COMPACT_PORTRAIT)

  @Test fun `4, 3 and 2 columns by device class`() {
    assertEquals(4, HaDashboardDims.of(LayoutClass.LARGE).columns)
    assertEquals(3, HaDashboardDims.of(LayoutClass.COMPACT_LANDSCAPE).columns)
    assertEquals(2, HaDashboardDims.of(LayoutClass.COMPACT_PORTRAIT).columns)
    assertTrue(HaDashboardDims.of(LayoutClass.COMPACT_PORTRAIT).portrait)
  }

  @Test fun `every control is at least 64 dp`() {
    all.forEach { d ->
      assertTrue(d.target >= 64)
      assertTrue(d.roomRow >= 64)
      assertTrue(d.sceneHeight >= 64)
    }
  }

  @Test fun `Mini keeps its type floor (names 20, status 17), Portal+ uses CC sizes`() {
    listOf(HaDashboardDims.COMPACT_LANDSCAPE, HaDashboardDims.COMPACT_PORTRAIT).forEach {
      assertEquals(20, it.name)
      assertEquals(17, it.status)
    }
    assertEquals(22, HaDashboardDims.LARGE.name)
    assertEquals(19, HaDashboardDims.LARGE.status)
    assertEquals(54, HaDashboardDims.LARGE.title)
    all.forEach { assertTrue(it.small >= 16) }
  }
}
