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

class LayoutClassTest {
  @Test fun thePortalPlusIsLarge() {
    assertEquals(LayoutClass.LARGE, LayoutClass.of(1920, 1219))
    assertEquals(LayoutClass.LARGE, LayoutClass.of(1920, 1280))
    assertFalse(LayoutClass.LARGE.compact)
  }

  @Test fun thePortalPlusTurnedUprightStaysLarge() {
    // Its short side (1280/1219 dp) is above the compact bound whichever way it faces.
    assertEquals(LayoutClass.LARGE, LayoutClass.of(1280, 1859))
    assertEquals(LayoutClass.LARGE, LayoutClass.of(1219, 1920))
  }

  @Test fun theMiniUprightIsCompactPortrait() {
    val c = LayoutClass.of(800, 1220)
    assertEquals(LayoutClass.COMPACT_PORTRAIT, c)
    assertTrue(c.compact)
    assertTrue(c.compactPortrait)
    assertFalse(c.compactLandscape)
  }

  @Test fun theMiniTurnedIsCompactLandscape() {
    val c = LayoutClass.of(1280, 740)
    assertEquals(LayoutClass.COMPACT_LANDSCAPE, c)
    assertTrue(c.compact)
    assertTrue(c.compactLandscape)
    assertFalse(c.compactPortrait)
  }

  @Test fun theTenInchPortalClassIsCompactLandscape() {
    assertEquals(LayoutClass.COMPACT_LANDSCAPE, LayoutClass.of(1280, 800))
  }

  @Test fun theBoundIsTheShortSide() {
    assertEquals(LayoutClass.LARGE, LayoutClass.of(1000, 1000))
    assertEquals(LayoutClass.COMPACT_LANDSCAPE, LayoutClass.of(1600, 999))
    assertEquals(LayoutClass.COMPACT_PORTRAIT, LayoutClass.of(999, 1600))
    // A square compact window is treated as landscape (wider-or-equal).
    assertEquals(LayoutClass.COMPACT_LANDSCAPE, LayoutClass.of(900, 900))
  }

  @Test fun aTelevisionStaysLarge() {
    assertEquals(LayoutClass.LARGE, LayoutClass.of(960, 540, television = true))
    assertEquals(LayoutClass.COMPACT_LANDSCAPE, LayoutClass.of(960, 540, television = false))
  }

  @Test fun anUndefinedSizeKeepsTheOriginalLayout() {
    assertEquals(LayoutClass.LARGE, LayoutClass.of(0, 0))
    assertEquals(LayoutClass.LARGE, LayoutClass.of(800, 0))
    assertEquals(LayoutClass.LARGE, LayoutClass.of(-1, 1220))
  }
}
