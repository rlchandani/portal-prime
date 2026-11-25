/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class HomePagerGestureTest {
  private val fling = 400f // px/s threshold used across cases

  @Test fun dragTracksFinger_leftDragAdvancesPositionProportionally() {
    // Drag left by a quarter viewport from page 0 -> scroll 0.25 toward page 1.
    assertEquals(0.25f, HomePagerGesture.dragPosition(0f, -250f, 1000f, 2), 1e-4f)
    // Drag right by half a viewport from page 1 -> scroll 0.5 back toward page 0.
    assertEquals(0.5f, HomePagerGesture.dragPosition(1f, 500f, 1000f, 2), 1e-4f)
  }

  @Test fun dragClampsToRealPages_noOverscroll() {
    assertEquals(0f, HomePagerGesture.dragPosition(0f, 5000f, 1000f, 1), 0f)
    assertEquals(1f, HomePagerGesture.dragPosition(1f, -5000f, 1000f, 1), 0f)
  }

  @Test fun dragWithoutMeasuredViewport_staysPut() {
    assertEquals(1f, HomePagerGesture.dragPosition(1f, -800f, 0f, 3), 0f)
  }

  @Test fun slowReleasePastHalfway_settlesToNearestPage() {
    // No fling: nearest page wins.
    assertEquals(1, HomePagerGesture.targetPage(0.6f, 0, 0f, fling, 3))
    assertEquals(0, HomePagerGesture.targetPage(0.4f, 0, 0f, fling, 3))
  }

  @Test fun fastFlickBeforeHalfway_stillAdvancesOnePage() {
    // Barely moved (0.2) but flicked left fast -> advance to next page.
    assertEquals(1, HomePagerGesture.targetPage(0.2f, 0, -900f, fling, 3))
    // Flicked right fast from page 2 -> return one page even if barely moved.
    assertEquals(1, HomePagerGesture.targetPage(1.8f, 2, 900f, fling, 3))
  }

  @Test fun flingNeverLeavesTheRealRange() {
    assertEquals(0, HomePagerGesture.targetPage(0.1f, 0, 900f, fling, 2)) // right fling at first page
    assertEquals(2, HomePagerGesture.targetPage(1.9f, 2, -900f, fling, 2)) // left fling at last page
  }

  @Test fun emptyPager_targetIsZero() {
    assertEquals(0, HomePagerGesture.targetPage(0f, 0, -900f, fling, -1))
  }

  @Test fun settleDurationShrinksAsTheFingerGetsCloser() {
    // Finger left it almost on the target: minimum settle.
    assertEquals(HomePagerGesture.MIN_SETTLE_MS, HomePagerGesture.settleDurationMillis(0.98f, 1))
    // A full-page programmatic jump: maximum settle.
    assertEquals(HomePagerGesture.MAX_SETTLE_MS, HomePagerGesture.settleDurationMillis(0f, 1))
    // Halfway remaining lands between the bounds.
    val mid = HomePagerGesture.settleDurationMillis(0.5f, 1)
    assertEquals(true, mid in HomePagerGesture.MIN_SETTLE_MS..HomePagerGesture.MAX_SETTLE_MS)
  }
}
