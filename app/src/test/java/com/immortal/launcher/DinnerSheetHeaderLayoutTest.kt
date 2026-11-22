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

/** Call everyone's "who it reaches" line wraps to two lines on the Mini upright instead of "…". */
class DinnerSheetHeaderLayoutTest {
  @Test fun onlyTheUprightLineGetsASecondLine() {
    assertEquals(2, DinnerSheetHeaderLayout.lineMaxLines(LayoutClass.of(800, 1220)))
    assertEquals(1, DinnerSheetHeaderLayout.lineMaxLines(LayoutClass.of(1280, 740)))
    assertEquals(1, DinnerSheetHeaderLayout.lineMaxLines(LayoutClass.of(1920, 1219)))
  }

  @Test fun theQuietHoursLineNeedsTheSecondLineAndFitsInTwo() {
    val room = DinnerSheetHeaderLayout.uprightLineRoom(800)
    val one = DinnerCopy.toLine(listOf("Living Area"), listOf("Living Area"), offline = false)
    assertTrue("one line was too short: it ended in …", DinnerSheetHeaderLayout.LINE.width(one) > room)
    // Two quiet rooms still fit the two lines (word wrap wastes at most a word a line).
    val two = DinnerCopy.toLine(listOf("Living Area", "Family Room"), listOf("Living Area", "Family Room"), offline = false)
    val longestWord = two.split(' ').maxOf { DinnerSheetHeaderLayout.LINE.width(it) }
    assertTrue("$two", DinnerSheetHeaderLayout.LINE.width(two) <= 2 * (room - longestWord))
  }
}
