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

/** The ambient dashboard's arrangement per window class, and the shipped line gaps it now applies. */
class PhotoFrameDashboardLayoutTest {
  @Test fun portalPlusKeepsItsCentredColumnAndSizes() {
    val a = PhotoFrameDashboard.layoutFor(LayoutClass.LARGE)
    assertTrue(a.centred); assertFalse(a.columns); assertFalse(a.compact)
    assertEquals(120f, a.clockSp, 0f); assertEquals(28f, a.dateSp, 0f)
    assertEquals(0, a.sideDp); assertEquals(0, a.quoteMaxDp)
    // The gaps the card always meant to have (they never applied before the fix).
    assertEquals(listOf(18, 10, 14), PhotoFrameDashboard.LINE_GAPS_DP)
  }

  @Test fun miniUprightIsALeftColumnWithABoundedQuote() {
    val a = PhotoFrameDashboard.layoutFor(LayoutClass.COMPACT_PORTRAIT)
    assertFalse(a.centred); assertFalse(a.columns)
    assertEquals(56, a.sideDp); assertEquals(168f, a.clockSp, 0f); assertEquals(560, a.quoteMaxDp)
    assertEquals(48, a.infoTopDp)
  }

  @Test fun miniTurnedIsTwoColumns() {
    val a = PhotoFrameDashboard.layoutFor(LayoutClass.COMPACT_LANDSCAPE)
    assertTrue(a.columns); assertFalse(a.centred)
    assertEquals(72, a.sideDp); assertEquals(56, a.columnGapDp); assertEquals(150f, a.clockSp, 0f)
  }
}
