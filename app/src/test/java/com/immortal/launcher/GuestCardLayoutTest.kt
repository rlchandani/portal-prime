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

class GuestCardLayoutTest {
  @Test fun thePrimaryChoiceSpansAndTheTimedChoicesShareTheNextRow() {
    assertEquals(listOf(listOf(0), listOf(1, 2)), GuestCardLayout.choiceRows(3))
  }

  @Test fun everyChoiceAppearsOnceInOrder() {
    for (n in 0..5) assertEquals((0 until n).toList(), GuestCardLayout.choiceRows(n).flatten())
    assertEquals(listOf(listOf(0)), GuestCardLayout.choiceRows(1))
  }

  @Test fun theMiniTurnedKeepsTheDetailsClearOfTheCloseButtonAndUsesCompactType() {
    val turned = GuestCardLayout.of(LayoutClass.COMPACT_LANDSCAPE)
    assertTrue(turned.detailsEndPadding >= GuestCardLayout.CLOSE_CLEARANCE_DP)
    assertTrue(turned.compactDetails)
    assertTrue("the code still fits the 740 dp height", turned.qr + 2 * turned.verticalPadding + 60 <= 740)
  }

  @Test fun thePortalPlusKeepsTheShippedCard() {
    val large = GuestCardLayout.of(LayoutClass.LARGE)
    assertEquals(400, large.qr)
    assertEquals(0, large.detailsEndPadding)
    assertFalse(large.compactDetails)
  }
}
