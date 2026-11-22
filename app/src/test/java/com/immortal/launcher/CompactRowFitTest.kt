/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactRowFitTest {
  @Test fun aRowIsItsItemsPlusTheGapsBetweenThem() {
    assertEquals(0.0, CompactRowFit.rowWidth(emptyList(), 12.0), 0.0)
    assertEquals(100.0, CompactRowFit.rowWidth(listOf(100.0), 12.0), 0.0)
    assertEquals(224.0, CompactRowFit.rowWidth(listOf(100.0, 100.0, 0.0), 12.0), 0.0)
  }

  @Test fun specWidthScalesOnlyTheLabel() {
    val spec = CompactRowFit.Spec(charDp = 10.0, chromeDp = 40.0)
    assertEquals(90.0, spec.width("Hello"), 0.0)
    assertEquals(98.0, spec.width("Hello", fontScale = 1.15f), 0.0)
  }

  @Test fun packKeepsReadingOrderAndWrapsWhenFull() {
    assertEquals(listOf(listOf(0, 1), listOf(2)), CompactRowFit.pack(listOf(40.0, 40.0, 40.0), 10.0, 100.0))
    assertTrue(CompactRowFit.fits(listOf(45.0, 45.0), 10.0, 100.0))
    assertFalse(CompactRowFit.fits(listOf(46.0, 45.0), 10.0, 100.0))
  }

  @Test fun anItemWiderThanTheRowGetsARowOfItsOwn() {
    assertEquals(listOf(listOf(0), listOf(1), listOf(2)), CompactRowFit.pack(listOf(50.0, 150.0, 50.0), 10.0, 100.0))
    assertEquals(emptyList<List<Int>>(), CompactRowFit.pack(emptyList(), 10.0, 100.0))
  }
}
