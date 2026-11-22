/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.CallsHeaderLayout.Item.HANDOFF
import com.immortal.launcher.CallsHeaderLayout.Item.LOOPBACK_TEST
import com.immortal.launcher.CallsHeaderLayout.Item.TABS
import com.immortal.launcher.CallsHeaderLayout.Item.TEST_MESSAGE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Calls header's control rows per layout class and width: nothing scrolls sideways or clips. */
class CallsHeaderLayoutTest {
  private val plain = listOf("Rooms", "Recent", "Messages")
  private val badged = listOf("Rooms", "Recent · 12", "Messages · 99")
  private val miniUpright = LayoutClass.of(800, 1220)
  private val miniTurned = LayoutClass.of(1280, 740)

  private fun rowsFit(rows: List<List<CallsHeaderLayout.Item>>, widthDp: Int, labels: List<String>, scale: Float = 1f) =
      rows.all { row ->
        row.sumOf { CallsHeaderLayout.widthOf(it, labels, scale) } + (row.size - 1) * CallsHeaderLayout.GAP_DP <=
            widthDp - 2 * CallsHeaderLayout.SIDE_PADDING_DP
      }

  @Test fun portalPlusKeepsTheShippedSingleRowHeader() {
    assertNull(CallsHeaderLayout.rows(LayoutClass.of(1920, 1219), 1920, plain, testPills = false))
    assertNull(CallsHeaderLayout.rows(LayoutClass.of(1920, 1219), 1920, plain, testPills = true))
    assertNull("a TV stays LARGE", CallsHeaderLayout.rows(LayoutClass.of(960, 540, television = true), 960, plain, false))
  }

  @Test fun miniUprightPutsFullWidthTabsAboveAFullWidthHandoff() {
    assertEquals(LayoutClass.COMPACT_PORTRAIT, miniUpright)
    assertEquals(listOf(listOf(TABS), listOf(HANDOFF)), CallsHeaderLayout.rows(miniUpright, 800, plain, false))
    assertEquals("counts on the tabs don't change it", listOf(listOf(TABS), listOf(HANDOFF)),
        CallsHeaderLayout.rows(miniUpright, 800, badged, false))
  }

  @Test fun miniTurnedSharesOneRowWithTheHandoffAtTheEnd() {
    assertEquals(LayoutClass.COMPACT_LANDSCAPE, miniTurned)
    assertEquals(listOf(listOf(TABS, HANDOFF)), CallsHeaderLayout.rows(miniTurned, 1280, plain, false))
    assertEquals(listOf(listOf(TABS, HANDOFF)), CallsHeaderLayout.rows(miniTurned, 1280, badged, false))
  }

  @Test fun measurementPillsWrapInReadingOrderInsteadOfScrolling() {
    assertEquals(listOf(listOf(TABS), listOf(LOOPBACK_TEST, TEST_MESSAGE), listOf(HANDOFF)),
        CallsHeaderLayout.rows(miniUpright, 800, plain, true))
    assertEquals(listOf(listOf(TABS, LOOPBACK_TEST, TEST_MESSAGE), listOf(HANDOFF)),
        CallsHeaderLayout.rows(miniTurned, 1280, plain, true))
  }

  @Test fun everyCompactRowFitsItsWidthAndKeepsTheOrder() {
    val order = listOf(TABS, LOOPBACK_TEST, TEST_MESSAGE, HANDOFF)
    for (width in listOf(800, 1000, 1280)) for (labels in listOf(plain, badged)) for (pills in listOf(false, true))
      for (scale in listOf(1f, 1.15f)) {
        val rows = CallsHeaderLayout.rows(LayoutClass.of(width, 740), width, labels, pills, scale)!!
        val flat = rows.flatten()
        assertEquals("same items in the same order at $width", order.filter { pills || it == TABS || it == HANDOFF }, flat)
        assertTrue("rows fit at $width dp, scale $scale", rowsFit(rows, width, labels, scale))
      }
  }

  @Test fun theEstimatesMatchTheMeasuredMiniTabs() {
    // Measured on the emulated Mini: the three plain tabs are ~505 dp wide.
    val tabs = CallsHeaderLayout.widthOf(TABS, plain)
    assertTrue("tabs estimate $tabs", tabs in 505.0..560.0)
    assertTrue("hand-off estimate", CallsHeaderLayout.widthOf(HANDOFF, plain) >= 360.0)
  }

  @Test fun stretchedTabsAreEqualUnlessALabelNeedsMore() {
    assertEquals(listOf(1f, 1f, 1f), CallsHeaderLayout.tabWeights(plain, 736.0))
    val tight = CallsHeaderLayout.tabWeights(badged, 600.0, fontScale = 1.15f)
    assertTrue("longest label gets the most room: $tight", tight[2] > tight[1] && tight[1] > tight[0])
  }

  @Test fun theTabsTrackIsTheContentLessTheRowsOtherItems() {
    assertEquals(736.0, CallsHeaderLayout.tabsTrackDp(listOf(TABS), 800, plain), 0.001)
    val handoff = CallsHeaderLayout.widthOf(HANDOFF, plain)
    assertEquals(1216.0 - handoff - CallsHeaderLayout.GAP_DP,
        CallsHeaderLayout.tabsTrackDp(listOf(TABS, HANDOFF), 1280, plain), 0.001)
  }
}
