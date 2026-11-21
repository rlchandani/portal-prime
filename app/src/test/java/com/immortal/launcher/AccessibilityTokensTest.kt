/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.immortal.launcher.ui.theme.ApprovedDarkPalette
import com.immortal.launcher.ui.theme.ApprovedLightPalette
import com.immortal.launcher.ui.theme.ApprovedPalette
import com.immortal.launcher.ui.theme.HearthpaneTouchTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Touch-target floor and WCAG AA contrast of the shared colour tokens (2026-11-20 accessibility audit). */
class AccessibilityTokensTest {
  @Test fun touchTargetFloorIs64RealDp() {
    assertEquals(64f, HearthpaneTouchTargets.minimumDp(2f, 2f), 0f)
    // Control Center's compact 75% density: 64 real dp is 85.3 of its scaled dp.
    assertEquals(64f / .75f, HearthpaneTouchTargets.minimumDp(1f, .75f), 1e-4f)
    // Mini widget cards draw denser; the floor still lands at 64 real dp.
    assertEquals(64f * 1.125f / 1.0125f, HearthpaneTouchTargets.minimumDp(1.125f, 1.0125f), 1e-4f)
    assertEquals(64f, HearthpaneTouchTargets.minimumDp(0f, 1f), 0f)
  }

  @Test fun approvedTextTokensMeetAaOnEveryBackground() {
    for (p in listOf(ApprovedLightPalette, ApprovedDarkPalette)) {
      for ((bgName, bg) in backgrounds(p)) {
        assertAtLeast(4.5, p.text, bg, "text on $bgName (dark=${p.dark})")
        assertAtLeast(4.5, p.secondary, bg, "secondary on $bgName (dark=${p.dark})")
        // Accent, error and green label larger text and icons: 3:1.
        assertAtLeast(3.0, p.accent, bg, "accent on $bgName (dark=${p.dark})")
        assertAtLeast(3.0, p.error, bg, "error on $bgName (dark=${p.dark})")
        assertAtLeast(3.0, p.green, bg, "green on $bgName (dark=${p.dark})")
      }
    }
  }

  @Test fun controlCenterFaintTextStaysReadable() {
    for (c in listOf(CcLight, CcDark)) {
      for ((name, bg) in listOf("bg" to c.bg, "surface" to c.surface, "surface2" to c.surface2, "sel" to c.sel)) {
        assertAtLeast(3.0, c.faint, bg, "faint on $name (dark=${c.dark})")
        assertAtLeast(4.5, c.sec, bg, "sec on $name (dark=${c.dark})")
      }
      // Faint is small (17–19 sp) status text on cards and the page.
      assertAtLeast(4.5, c.faint, c.bg, "faint on bg (dark=${c.dark})")
      assertAtLeast(4.5, c.faint, c.surface, "faint on surface (dark=${c.dark})")
    }
  }

  private fun backgrounds(p: ApprovedPalette) = listOf("background" to p.background, "sidebar" to p.sidebar,
      "surface" to p.surface, "surface2" to p.surface2, "selected" to p.selected)

  private fun assertAtLeast(min: Double, fg: Color, bg: Color, what: String) {
    val hi = maxOf(fg.luminance(), bg.luminance()) + .05
    val lo = minOf(fg.luminance(), bg.luminance()) + .05
    val ratio = hi / lo
    assertTrue("$what is %.2f:1, needs %.1f:1".format(ratio, min), ratio >= min)
  }
}
