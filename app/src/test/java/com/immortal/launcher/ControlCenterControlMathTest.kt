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

class ControlCenterControlMathTest {
  private fun light(attrs: Map<String, Any?>, state: String = "on") = CcEntity("light.lamp", state, attrs)

  @Test fun verticalBarIsZeroAtBottomAndFullAtTop() {
    assertEquals(100, CcControlMath.pctFromVertical(0f, 600f))
    assertEquals(0, CcControlMath.pctFromVertical(600f, 600f))
    assertEquals(50, CcControlMath.pctFromVertical(300f, 600f))
    assertEquals(100, CcControlMath.pctFromVertical(-40f, 600f))
    assertEquals(0, CcControlMath.pctFromVertical(900f, 600f))
    assertEquals(0, CcControlMath.pctFromVertical(10f, 0f))
  }

  @Test fun horizontalSliderClampsToRange() {
    assertEquals(0, CcControlMath.pctFromHorizontal(-5f, 400f))
    assertEquals(25, CcControlMath.pctFromHorizontal(100f, 400f))
    assertEquals(100, CcControlMath.pctFromHorizontal(500f, 400f))
    assertEquals(0, CcControlMath.pctFromHorizontal(5f, 0f))
  }

  @Test fun dialFractionFollowsTheArcFromBottomLeft() {
    // 135° (bottom-left, y down) is the start; 270° (straight up) is the middle; 45° (bottom-right) the end.
    assertEquals(0f, CcControlMath.dialFraction(-1f, 1f), 0.001f)
    assertEquals(0.5f, CcControlMath.dialFraction(0f, -1f), 0.001f)
    assertEquals(1f, CcControlMath.dialFraction(1f, 1f), 0.001f)
    assertEquals(1f / 6f, CcControlMath.dialFraction(-1f, 0f), 0.001f)
  }

  @Test fun dialGapSnapsToTheNearerEnd() {
    assertEquals(1f, CcControlMath.dialFraction(0.5f, 1f), 0.001f)   // just past the end, right of bottom
    assertEquals(0f, CcControlMath.dialFraction(-0.5f, 1f), 0.001f)  // just before the start, left of bottom
  }

  @Test fun dialValueSnapsToStepAndRange() {
    assertEquals(68.0, CcControlMath.dialValue(0.5f, 50.0, 86.0, 1.0), 0.0)
    assertEquals(21.0, CcControlMath.dialValue(0.5f, 7.0, 35.0, 0.5), 0.0)
    assertEquals(20.5, CcControlMath.dialValue(0.48f, 7.0, 35.0, 0.5), 0.0)
    assertEquals(50.0, CcControlMath.dialValue(-1f, 50.0, 86.0, 1.0), 0.0)
    assertEquals(86.0, CcControlMath.dialValue(2f, 50.0, 86.0, 0.0), 0.0)
    assertEquals(50.0, CcControlMath.dialValue(0.5f, 50.0, 50.0, 1.0), 0.0)
  }

  @Test fun dialFractionOfAndPointRoundTrip() {
    assertEquals(0.5f, CcControlMath.dialFractionOf(68.0, 50.0, 86.0), 0.001f)
    assertEquals(0f, CcControlMath.dialFractionOf(10.0, 50.0, 86.0), 0.001f)
    assertEquals(0f, CcControlMath.dialFractionOf(10.0, 50.0, 50.0), 0.001f)
    val (x, y) = CcControlMath.dialPoint(0.5f, 100f, 0f, 0f)
    assertEquals(0f, x, 0.01f); assertEquals(-100f, y, 0.01f)
    val back = CcControlMath.dialFraction(x, y)
    assertEquals(0.5f, back, 0.001f)
  }

  @Test fun whitesKeepTheMockPresetsInsideTheBulbRange() {
    assertEquals(listOf(2200, 2700, 3000, 4000, 5500), CcControlMath.whites(2000, 6500).map { it.kelvin })
    assertEquals(listOf(2700, 3000, 4000), CcControlMath.whites(2700, 4500).map { it.kelvin })
  }

  @Test fun narrowRangeSpreadsFiveWhites() {
    val w = CcControlMath.whites(2700, 3100)
    assertEquals(5, w.size)
    assertEquals(2700, w.first().kelvin)
    assertEquals("Warmest", w.first().label)
    assertEquals("Coolest", w.last().label)
    assertTrue(CcControlMath.whites(3000, 3000).isEmpty())
  }

  @Test fun selectedWhiteNeedsColourTempModeAndCloseKelvin() {
    val whites = CcControlMath.whites(2000, 6500)
    assertEquals(1, CcControlMath.selectedWhite(light(mapOf("color_mode" to "color_temp", "color_temp_kelvin" to 2750)), whites))
    assertEquals(-1, CcControlMath.selectedWhite(light(mapOf("color_mode" to "color_temp", "color_temp_kelvin" to 4800)), whites))
    assertEquals(-1, CcControlMath.selectedWhite(light(mapOf("color_mode" to "rgb", "color_temp_kelvin" to 2700)), whites))
    assertEquals(-1, CcControlMath.selectedWhite(light(mapOf("color_mode" to "color_temp", "color_temp_kelvin" to 2700), "off"), whites))
  }

  @Test fun selectedColourMatchesNearbyRgb() {
    assertEquals(3, CcControlMath.selectedColour(light(mapOf("color_mode" to "rgb", "rgb_color" to listOf(80, 140, 250)))))
    assertEquals(-1, CcControlMath.selectedColour(light(mapOf("color_mode" to "rgb", "rgb_color" to listOf(10, 10, 10)))))
    assertEquals(-1, CcControlMath.selectedColour(light(mapOf("color_mode" to "color_temp", "rgb_color" to listOf(255, 90, 106)))))
  }

  @Test fun fanSpeedsFromPercentageStep() {
    assertEquals(listOf(0, 33, 67, 100), CcControlMath.fanSpeeds(CcEntity("fan.f", "on")))
    assertEquals(listOf(0, 25, 50, 75, 100), CcControlMath.fanSpeeds(CcEntity("fan.f", "on", mapOf("percentage_step" to 25))))
    assertTrue(CcControlMath.fanSpeeds(CcEntity("fan.f", "on", mapOf("percentage_step" to 1))).isEmpty())
  }

  @Test fun fanSpeedIndexPicksNearestSegment() {
    val speeds = listOf(0, 33, 67, 100)
    assertEquals(2, CcControlMath.fanSpeedIndex(CcEntity("fan.f", "on", mapOf("percentage" to 60)), speeds))
    assertEquals(0, CcControlMath.fanSpeedIndex(CcEntity("fan.f", "off", mapOf("percentage" to 60)), speeds))
    assertEquals(3, CcControlMath.fanSpeedIndex(CcEntity("fan.f", "on"), speeds))
    assertEquals(listOf("Off", "Low", "Medium", "High"), CcControlMath.fanLabels(4))
    assertEquals(listOf("Off", "1", "2", "3", "4"), CcControlMath.fanLabels(5))
    assertEquals(listOf("Off", "On"), CcControlMath.fanLabels(2))
    assertEquals(listOf("Off", "Low", "High"), CcControlMath.fanLabels(3))
  }

  @Test fun fanSpinsFasterWithSpeed() {
    assertTrue(fanPeriodMs(100) < fanPeriodMs(20))
    assertEquals(450, fanPeriodMs(100))
  }

  @Test fun throttleSendsAtMostEvery250msAndAlwaysTheFinalValue() {
    var t = CcThrottle()
    assertTrue(t.shouldSend(1_000, 40))
    t = t.sent(1_000, 40)
    assertFalse(t.shouldSend(1_100, 45))
    assertTrue(t.shouldSend(1_250, 45))
    assertTrue(t.shouldSend(1_100, 45, final = true))
    assertFalse(t.shouldSend(5_000, 40))
    assertFalse(t.shouldSend(5_000, 40, final = true))
  }

  @Test fun tempRangeUsesHaThenTheRightUnit() {
    val withAttrs = CcEntity("climate.c", "heat", mapOf("min_temp" to 50, "max_temp" to 86, "temperature" to 70))
    assertEquals(50.0 to 86.0, CcControlMath.tempRange(withAttrs, fahrenheit = true))
    // The Family Portal case: °F setpoint, no min/max from HA → 45–95, never the 7–35 °C default.
    val bare = CcEntity("climate.c", "cool", mapOf("temperature" to 86, "current_temperature" to 71))
    assertEquals(45.0 to 95.0, CcControlMath.tempRange(bare, fahrenheit = false))
    val celsius = CcEntity("climate.c", "heat", mapOf("temperature" to 21))
    assertEquals(7.0 to 35.0, CcControlMath.tempRange(celsius, fahrenheit = false))
    val outside = CcEntity("climate.c", "heat", mapOf("min_temp" to 60, "max_temp" to 80, "temperature" to 85))
    assertEquals(60.0 to 85.0, CcControlMath.tempRange(outside, fahrenheit = true))
    val broken = CcEntity("climate.c", "heat", mapOf("min_temp" to 90, "max_temp" to 50, "temperature" to 70))
    assertEquals(45.0 to 95.0, CcControlMath.tempRange(broken, fahrenheit = true))
    assertEquals(0.82f, CcControlMath.dialFractionOf(86.0, 45.0, 95.0), 0.001f)
  }

  @Test fun dialHeadlineFallsBackToTheMode() {
    assertEquals("Cooling to", dialHeadline(CcEntity("climate.c", "cool")))
    assertEquals("Heating to", dialHeadline(CcEntity("climate.c", "heat_cool", mapOf("hvac_action" to "heating"))))
    assertEquals("Set to", dialHeadline(CcEntity("climate.c", "cool", mapOf("hvac_action" to "idle"))))
    assertEquals("Off", dialHeadline(CcEntity("climate.c", "off")))
  }
}
