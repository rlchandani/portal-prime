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

/** The Store's banner leaves room for at least two rows of app cards on the Mini turned. */
class StoreBrowseLayoutTest {
  /** Window height − the 81 dp compact header − the dock's 24 + 92 + 16. */
  private fun content(windowHeight: Int) = windowHeight - 81 - 132

  @Test fun theMiniTurnedShowsASecondRowOfCards() {
    val top = StoreBrowseLayout.of(LayoutClass.of(1280, 800))
    assertTrue(top.strip)
    assertTrue(top.searchBesideActions)
    // 1280 × 800 @160 (the Family check): the first row and over half of the second.
    val emulated = StoreBrowseLayout.rowsVisible(top, content(800))
    assertTrue("1280×800 shows $emulated rows", emulated >= 1.5)
    // The Mini itself (1280 × 740 dp): the second row still starts on screen, a quarter in.
    val mini = StoreBrowseLayout.rowsVisible(top, content(740))
    assertTrue("1280×740 shows $mini rows", mini >= 1.25)
    assertTrue("short cards show both rows", StoreBrowseLayout.rowsVisible(top, content(800), StoreBrowseLayout.CARD_ROW) >= 1.75)
  }

  @Test fun theShippedBannerLeftOnlyOneRow() {
    // Why the strip exists: the Portal+ banner on the Mini turned put the second row off screen.
    assertTrue(StoreBrowseLayout.rowsVisible(StoreBrowseLayout.LARGE, content(800)) < 1.0)
  }

  @Test fun thePortalPlusAndUprightKeepTheShippedBanner() {
    val large = StoreBrowseLayout.of(LayoutClass.of(1920, 1219))
    assertEquals(StoreBrowseLayout.LARGE, large)
    assertFalse(large.strip)
    assertFalse(large.searchBesideActions)
    assertEquals(36, large.topPadding)
    assertEquals(28, StoreBrowseLayout.headingTopPadding(large))
    assertFalse(StoreBrowseLayout.of(LayoutClass.of(800, 1220)).strip)
  }

  @Test fun theStripKeepsItsLineAndTouchTargets() {
    val strip = StoreBrowseLayout.LANDSCAPE
    assertTrue("title + line fit the strip", strip.bannerHeight >=
        2 * StoreBrowseLayout.STRIP_PADDING + StoreBrowseLayout.STRIP_TITLE_LINE + StoreBrowseLayout.STRIP_LINE_LINE)
    assertTrue("search stays a 64 dp target", StoreBrowseLayout.SEARCH_HEIGHT >= 64)
    assertEquals(16, StoreBrowseLayout.headingTopPadding(strip))
  }
}
