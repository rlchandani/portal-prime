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

/**
 * Column counts and dialog sizing for the Portal Mini (800×1220 upright, 1280×740 turned). The
 * Portal+ ([LayoutClass.LARGE]) must keep today's values exactly.
 */
class CompactScreenSizingTest {
  @Test fun widgetPickerKeepsFourColumnsOnThePortalPlus() {
    assertEquals(4, WidgetPickerColumns.picker(LayoutClass.LARGE))
    assertEquals(2, WidgetPickerColumns.picker(LayoutClass.COMPACT_PORTRAIT))
    assertEquals(3, WidgetPickerColumns.picker(LayoutClass.COMPACT_LANDSCAPE))
  }

  @Test fun widgetLibraryKeepsThreeColumnsAndItsPaddingOnThePortalPlus() {
    assertEquals(3, WidgetPickerColumns.library(LayoutClass.LARGE))
    assertEquals(56, WidgetPickerColumns.librarySidePaddingDp(LayoutClass.LARGE))
    assertEquals(2, WidgetPickerColumns.library(LayoutClass.COMPACT_PORTRAIT))
    assertEquals(3, WidgetPickerColumns.library(LayoutClass.COMPACT_LANDSCAPE))
    assertEquals(24, WidgetPickerColumns.librarySidePaddingDp(LayoutClass.COMPACT_PORTRAIT))
    assertEquals(24, WidgetPickerColumns.librarySidePaddingDp(LayoutClass.COMPACT_LANDSCAPE))
  }

  @Test fun compactPickerTilesFitTheShortLandscapeWindow() {
    assertTrue(WidgetPickerColumns.COMPACT_TILE_MIN_DP < WidgetPickerColumns.COMPACT_TILE_MAX_DP)
    // 740 dp less the sheet's top inset, header and grid padding still shows a whole tile.
    assertTrue(WidgetPickerColumns.COMPACT_TILE_MAX_DP <= 740 - 72 - 24 - 24 - 68 - 64)
  }

  @Test fun widgetLibraryCardsStayWideEnoughForWholeWords() {
    // Two cards across 800 dp, less padding and the 24 dp gap, keep about 340 dp each.
    val side = WidgetPickerColumns.librarySidePaddingDp(LayoutClass.COMPACT_PORTRAIT)
    val cols = WidgetPickerColumns.library(LayoutClass.COMPACT_PORTRAIT)
    val card = (800 - 2 * side - (cols - 1) * 24) / cols
    assertTrue("card $card", card >= 320)
  }

  @Test fun storeGridKeepsTwoColumnsAndItsPaddingOnThePortalPlus() {
    assertEquals(2, storeGridColumns(LayoutClass.LARGE))
    assertEquals(56, storeSidePaddingDp(LayoutClass.LARGE))
    assertEquals(1, storeGridColumns(LayoutClass.COMPACT_PORTRAIT))
    assertEquals(2, storeGridColumns(LayoutClass.COMPACT_LANDSCAPE))
    assertEquals(24, storeSidePaddingDp(LayoutClass.COMPACT_PORTRAIT))
    assertEquals(32, storeSidePaddingDp(LayoutClass.COMPACT_LANDSCAPE))
  }

  @Test fun compactDialogsAreCappedBelowTheWindowHeight() {
    // Mini turned: a 980 dp dialog becomes 660 dp tall, leaving 40 dp above and below.
    assertEquals(660, CompactDialogLayout.maxHeightDp(740, 980))
    // Mini upright: the dialog's own cap still applies.
    assertEquals(980, CompactDialogLayout.maxHeightDp(1220, 980))
    // A tiny window never collapses the card.
    assertEquals(CompactDialogLayout.MIN_HEIGHT_DP, CompactDialogLayout.maxHeightDp(200, 980))
  }
}
