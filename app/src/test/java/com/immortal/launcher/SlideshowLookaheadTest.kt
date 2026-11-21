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

class SlideshowLookaheadTest {
  @Test fun waitsTheWholeSettleInShortSteps() {
    val slept = mutableListOf<Long>()
    assertTrue(SlideshowLookahead.awaitSettle(250, { true }) { slept += it })
    assertEquals(listOf(100L, 100L, 50L), slept)
  }

  @Test fun settleOutlastsEveryDayDissolve() {
    assertTrue(SlideshowLookahead.SETTLE_MS > SlideshowStage.DISSOLVE_MS)
    assertTrue(SlideshowLookahead.SETTLE_MS > SlideshowStage.SPLIT_DISSOLVE_MS)
  }

  @Test fun aStaleRequestStopsWaitingAndPreparesNothing() {
    var checks = 0
    val slept = mutableListOf<Long>()
    assertFalse(SlideshowLookahead.awaitSettle(1_000, { ++checks < 3 }) { slept += it })
    assertEquals(2, slept.size)
  }

  @Test fun goingStaleDuringTheLastStepPreparesNothing() {
    var live = true
    assertFalse(SlideshowLookahead.awaitSettle(100, { live }) { live = false })
  }

  @Test fun aNewerRequestInterruptingTheWaitPreparesNothing() {
    assertFalse(SlideshowLookahead.awaitSettle(500, { true }) { throw InterruptedException() })
    // The interrupt is kept for the worker's own checks; clear it so it can't leak into other tests.
    assertTrue(Thread.interrupted())
  }

  @Test fun noSettleRunsImmediately() {
    var slept = false
    assertTrue(SlideshowLookahead.awaitSettle(0, { true }) { slept = true })
    assertFalse(slept)
  }
}
