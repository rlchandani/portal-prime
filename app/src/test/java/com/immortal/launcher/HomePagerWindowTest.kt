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

class HomePagerWindowTest {
  @Test fun settledPageKeepsBothNeighbors() {
    assertEquals(listOf(1, 2, 3), HomePagerWindow.composed(target = 2, settled = 2, lastIndex = 4))
  }

  @Test fun slideDoesNotComposeThePageBeyondItsTarget() {
    // Sliding 2 -> 3: page 4 waits until the slide settles on 3.
    assertEquals(listOf(1, 2, 3), HomePagerWindow.composed(target = 3, settled = 2, lastIndex = 4))
    assertEquals(listOf(2, 3, 4), HomePagerWindow.composed(target = 3, settled = 3, lastIndex = 4))
  }

  @Test fun aFarJumpComposesItsTargetBesideTheSettledWindow() {
    assertEquals(listOf(0, 1, 4), HomePagerWindow.composed(target = 4, settled = 0, lastIndex = 4))
  }

  @Test fun windowIsClampedToExistingPages() {
    assertEquals(listOf(0, 1), HomePagerWindow.composed(target = 0, settled = 0, lastIndex = 1))
    assertEquals(listOf(0), HomePagerWindow.composed(target = 0, settled = 0, lastIndex = 0))
    // A removed page can leave the settled index past the end until the snap settles.
    assertEquals(listOf(0, 1), HomePagerWindow.composed(target = 1, settled = 3, lastIndex = 1))
    assertEquals(emptyList<Int>(), HomePagerWindow.composed(target = 0, settled = 0, lastIndex = -1))
  }

  @Test fun onlyTheSettledPageWorks() {
    assertTrue(HomePagerWindow.working(index = 2, settled = 2))
    assertFalse(HomePagerWindow.working(index = 3, settled = 2))
  }
}
