/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The header's theme toggle never wears a moon beside the Sleep button's moon. */
class ApprovedThemeIconTest {
  private val moons = listOf("sleep", "moon", "theme_night").map { approvedIconResource(it) }

  @Test fun lightThemeShowsTheAppearanceGlyphNotAMoon() {
    val light = approvedIconResource(approvedThemeIcon(dark = false))
    assertEquals(R.drawable.approved_icon_theme_appearance, light)
    moons.forEach { assertNotEquals(it, light) }
  }

  @Test fun darkThemeKeepsTheSun() {
    assertEquals(R.drawable.approved_icon_theme_day, approvedIconResource(approvedThemeIcon(dark = true)))
  }

  @Test fun bothStatesDifferFromTheSleepButton() {
    for (dark in listOf(true, false)) assertNotEquals(approvedIconResource("sleep"), approvedIconResource(approvedThemeIcon(dark)))
  }
}
