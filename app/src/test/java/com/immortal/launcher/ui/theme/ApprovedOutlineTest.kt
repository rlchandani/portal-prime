/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Cards and round chrome buttons get a slim outline so they don't melt into the page in day
 * theme. It must be visible against both the page and white cards, yet stay a light trim — never
 * close to text contrast.
 */
class ApprovedOutlineTest {
  private fun lum(c: Color): Double {
    fun ch(v: Float) = if (v <= 0.03928f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
  }
  private fun contrast(a: Color, b: Color): Double {
    val (hi, lo) = listOf(lum(a), lum(b)).sortedDescending()
    return (hi + 0.05) / (lo + 0.05)
  }

  @Test fun dayOutlineIsVisibleAgainstThePageAndCards() = with(ApprovedLightPalette) {
    assertTrue("page ${contrast(outline, background)}", contrast(outline, background) >= 1.3)
    assertTrue("card ${contrast(outline, surface)}", contrast(outline, surface) >= 1.4)
    assertTrue("button ${contrast(outline, surface2)}", contrast(outline, surface2) >= 1.25)
  }

  @Test fun dayOutlineStaysALightTrimNotADarkLine() = with(ApprovedLightPalette) {
    assertTrue(contrast(outline, background) < contrast(secondary, background) / 2.5)
  }

  @Test fun nightOutlineIsASubtleLighterEdge() = with(ApprovedDarkPalette) {
    assertTrue(lum(outline) > lum(surface2))
    assertTrue("night ${contrast(outline, surface)}", contrast(outline, surface) in 1.25..2.2)
  }
}
