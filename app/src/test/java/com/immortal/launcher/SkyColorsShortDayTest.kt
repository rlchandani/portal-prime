/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

/** Generated solar minutes exercise the actual palette function, without provider or UI work. */
class SkyColorsShortDayTest {
  @Test fun threeHourDayHasTheExistingMiddayPaletteAtSolarNoon() {
    assertPalette(690, 600, 780, 0xFF2980B9, 0xFF6DD5FA)
  }

  @Test fun oneHourDayHasTheExistingMiddayPaletteAtSolarNoon() {
    assertPalette(630, 600, 660, 0xFF2980B9, 0xFF6DD5FA)
  }

  @Test fun threeHourDayDoesNotJumpBetweenAdjacentMinutes() {
    assertContinuousDay(600, 780)
  }

  @Test fun oneHourDayDoesNotJumpBetweenAdjacentMinutes() {
    assertContinuousDay(600, 660)
  }

  @Test fun shortDaysReachTheExistingTwilightPaletteAfterSunset() {
    assertPalette(700, 600, 660, 0xFF1A1F3D, 0xFF3A2A4D)
    assertPalette(820, 600, 780, 0xFF1A1F3D, 0xFF3A2A4D)
  }

  @Test fun fourteenHourDayRetainsItsLiteralPhasePalettes() {
    // Sunrise06:00, sunset20:00: hand-selected existing phase boundaries.
    assertPalette(320, 360, 1200, 0xFF0B1026, 0xFF05060E)
    assertPalette(400, 360, 1200, 0xFF2A3A6B, 0xFFEF9A6B)
    assertPalette(480, 360, 1200, 0xFF3A7BD5, 0xFF8FD3F4)
    assertPalette(780, 360, 1200, 0xFF2980B9, 0xFF6DD5FA)
    assertPalette(1080, 360, 1200, 0xFF3A7BD5, 0xFF8FD3F4)
    assertPalette(1160, 360, 1200, 0xFF2C3E66, 0xFFE96443)
    assertPalette(1240, 360, 1200, 0xFF1A1F3D, 0xFF3A2A4D)
    assertPalette(1280, 360, 1200, 0xFF0B1026, 0xFF05060E)
  }

  @Test fun eightHourDayRetainsItsLiteralPhasePalettes() {
    // Sunrise08:00, sunset16:00.
    assertPalette(440, 480, 960, 0xFF0B1026, 0xFF05060E)
    assertPalette(520, 480, 960, 0xFF2A3A6B, 0xFFEF9A6B)
    assertPalette(600, 480, 960, 0xFF3A7BD5, 0xFF8FD3F4)
    assertPalette(720, 480, 960, 0xFF2980B9, 0xFF6DD5FA)
    assertPalette(840, 480, 960, 0xFF3A7BD5, 0xFF8FD3F4)
    assertPalette(920, 480, 960, 0xFF2C3E66, 0xFFE96443)
    assertPalette(1000, 480, 960, 0xFF1A1F3D, 0xFF3A2A4D)
    assertPalette(1040, 480, 960, 0xFF0B1026, 0xFF05060E)
  }

  @Test fun shortDaysKeepTheExistingNightOutsideTwilight() {
    for ((sunrise, sunset) in listOf(600 to 660, 600 to 780)) {
      for (minute in listOf(0, 559, sunset + 80, 1439)) {
        assertPalette(minute, sunrise, sunset, 0xFF0B1026, 0xFF05060E)
      }
    }
  }

  @Test fun shortAndLongDaysReturnOpaqueFiniteSrgbColors() {
    for ((sunrise, sunset) in listOf(600 to 660, 600 to 780, 480 to 960, 360 to 1200)) {
      for (minute in 0 until 1440) {
        val (top, bottom) = SkyColors.gradientFor(minute, sunrise, sunset)
        for (color in listOf(top, bottom)) {
          assertEquals("Color space at $minute with $sunrise/$sunset",
              ColorSpaces.Srgb, color.colorSpace)
          assertEquals(1f, color.alpha, 0f)
          for (channel in listOf(color.red, color.green, color.blue)) {
            assertTrue("Finite bounded channel at $minute with $sunrise/$sunset: $channel",
                channel.isFinite() && channel in 0f..1f)
          }
        }
      }
    }
  }

  private fun assertPalette(
      minute: Int,
      sunrise: Int,
      sunset: Int,
      topArgb: Long,
      bottomArgb: Long,
  ) {
    val actual = SkyColors.gradientFor(minute, sunrise, sunset)
    assertEquals("Top palette at $minute with $sunrise/$sunset", Color(topArgb), actual.first)
    assertEquals("Bottom palette at $minute with $sunrise/$sunset", Color(bottomArgb), actual.second)
  }

  private fun assertContinuousDay(sunrise: Int, sunset: Int) {
    var previous = SkyColors.gradientFor(1439, sunrise, sunset)
    var largest = 0f
    var largestMinute = 0
    for (minute in 0 until 1440) {
      val next = SkyColors.gradientFor(minute, sunrise, sunset)
      val change = listOf(
          abs(next.first.red - previous.first.red),
          abs(next.first.green - previous.first.green),
          abs(next.first.blue - previous.first.blue),
          abs(next.second.red - previous.second.red),
          abs(next.second.green - previous.second.green),
          abs(next.second.blue - previous.second.blue)).maxOrNull()!!
      if (change > largest) { largest = change; largestMinute = minute }
      previous = next
    }
    // At most about26/255 per minute, including midnight. This leaves room for
    // smooth one-hour-day transitions while rejecting a sudden phase-sized jump.
    assertTrue("Largest channel jump $largest at minute $largestMinute with $sunrise/$sunset",
        largest <= 0.10f)
  }
}
