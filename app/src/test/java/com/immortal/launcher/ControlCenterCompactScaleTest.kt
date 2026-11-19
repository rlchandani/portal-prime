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

class ControlCenterCompactScaleTest {
  @Test fun thePortalPlusIsUnscaled() {
    assertEquals(1f, CcCompactScale.of(1920, 1219), 0f)
    assertEquals(CcCompactScale.legacy(1920), CcCompactScale.of(1920, 1219), 0f)
  }

  @Test fun largeKeepsTheWidthOnlyRuleEverywhere() {
    for ((w, h) in listOf(1920 to 1219, 1280 to 1219, 1699 to 1200, 1700 to 1100, 2560 to 1600, 0 to 0)) {
      assertEquals("$w×$h", LayoutClass.LARGE, LayoutClass.of(w, h))
      assertEquals("$w×$h", CcCompactScale.legacy(w), CcCompactScale.of(w, h), 0f)
    }
    // A TV is LARGE whatever its size and keeps the old rule.
    assertEquals(CcCompactScale.legacy(960), CcCompactScale.of(960, 540, television = true), 0f)
  }

  @Test fun theLegacyRuleMatchesTheThemeConstants() {
    assertEquals(CcStyle.COMPACT_BREAK_DP, CcCompactScale.BREAK_DP)
    assertEquals(CcStyle.COMPACT_SCALE, CcCompactScale.LEGACY, 0f)
    assertEquals(0.75f, CcCompactScale.legacy(1280), 0f)
    assertEquals(1f, CcCompactScale.legacy(1700), 0f)
    assertEquals(1f, CcCompactScale.legacy(0), 0f)
  }

  @Test fun theTurnedMiniAndTenInchPortalsKeepSeventyFivePercent() {
    assertEquals(0.75f, CcCompactScale.of(1280, 740), 0f)
    assertEquals(0.75f, CcCompactScale.of(1280, 800), 0f)
  }

  @Test fun compactNeverShrinksBelowTheTouchFloorScale() {
    for ((w, h) in listOf(800 to 1220, 1280 to 740, 1280 to 800, 600 to 900, 900 to 600)) {
      assertTrue("$w×$h", CcCompactScale.of(w, h) >= 0.75f)
    }
  }

  @Test fun theUprightMiniIsReArrangedNotShrunk() {
    val scale = CcCompactScale.of(800, 1220)
    assertTrue(scale in 0.85f..1f)
    // The canvas stays narrower than any side-by-side pane layout (sheet 860 + insets just fit).
    val canvas = CcCompactScale.canvasWidth(800, 1220)
    assertTrue("canvas $canvas", canvas in 896f..1000f)
  }
}
