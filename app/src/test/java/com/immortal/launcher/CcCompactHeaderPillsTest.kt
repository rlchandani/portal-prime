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

/** The Mini-upright Control Center header's pills shorten to fit instead of scrolling sideways. */
class CcCompactHeaderPillsTest {
  /** The 800 dp Mini upright at [CcCompactScale.PORTRAIT]. */
  private val canvas = CcCompactScale.canvasWidth(800, 1220).toDouble()
  private val available = canvas - 2 * CcCompactHeaderPills.SIDE_PADDING
  private val calm = CcCompactHeaderPills.Input(live = "Live", people = "2 home", peopleAvatars = 2,
      alarm = "Disarmed", weatherTemp = "64°", weatherText = "sunny")

  @Test fun aCalmHomeShowsEveryPillInFull() {
    val plan = CcCompactHeaderPills.plan(calm, available)
    assertEquals("64° · sunny", plan.weather)
    assertFalse(plan.alarmShort)
    assertEquals(2, plan.avatars)
  }

  @Test fun aBusyHeaderShortensTheWeatherFirst() {
    val busy = calm.copy(live = "Reconnecting", people = "4 home", peopleAvatars = 4, alarm = "Armed · Vacation",
        weatherText = "partly cloudy")
    val plan = CcCompactHeaderPills.plan(busy, available)
    assertEquals("64°", plan.weather)
    assertTrue(CcCompactHeaderPills.width(busy, plan) <= available)
  }

  @Test fun theAlarmKeepsItsStateWhenShortened() {
    assertEquals("Armed", CcCompactHeaderPills.shortAlarm("Armed · Away"))
    assertEquals("Disarmed", CcCompactHeaderPills.shortAlarm("Disarmed"))
  }

  @Test fun everyRealisticHeaderFitsTheMiniUpright() {
    val lives = CcPhase.entries.map { ccLiveLabel(it) }
    val alarms = listOf(null, "Disarmed", "Armed · Vacation", "Entry delay", "Triggered!")
    val weathers = listOf(null, "partly cloudy", "lightning rainy", "exceptional")
    for (live in lives) for (alarm in alarms) for (weather in weathers) for (people in 0..4) for (scale in listOf(1f, 1.15f)) {
      val input = CcCompactHeaderPills.Input(live = live, people = if (people == 0) null else "$people home",
          peopleAvatars = people, alarm = alarm, weatherTemp = "-12°", weatherText = weather)
      val plan = CcCompactHeaderPills.plan(input, available, scale)
      assertTrue("$input at $scale", CcCompactHeaderPills.width(input, plan, scale) <= available)
    }
  }

  @Test fun aWideWindowNeverShortens() {
    val busy = calm.copy(live = "HA unreachable", alarm = "Armed · Vacation", weatherText = "lightning rainy")
    val plan = CcCompactHeaderPills.plan(busy, 1920.0 - 60)
    assertEquals("64° · lightning rainy", plan.weather)
    assertFalse(plan.alarmShort)
  }

  @Test fun theTurnedMiniHeaderKeepsRoomForItsTitle() {
    // Mini turned: 1280 dp at 0.75 is ~1707 units, less the 132-unit rail.
    val row = CcCompactScale.canvasWidth(1280, 740).toDouble() - 132
    val budget = CcCompactHeaderPills.rowPillBudget(row)
    val busy = CcCompactHeaderPills.Input(live = "HA unreachable", people = "Nobody home", peopleAvatars = 4,
        alarm = "Armed · Vacation", weatherTemp = "18°", weatherText = "partlycloudy")
    for (scale in listOf(1f, 1.15f)) {
      val plan = CcCompactHeaderPills.plan(busy, budget, scale, CcCompactHeaderPills.ROW_GAP)
      assertTrue("turned header at $scale", CcCompactHeaderPills.width(busy, plan, scale, CcCompactHeaderPills.ROW_GAP) <= budget)
    }
    assertEquals("a calm turned home keeps every pill in full", "64° · sunny",
        CcCompactHeaderPills.plan(calm, budget, gap = CcCompactHeaderPills.ROW_GAP).weather)
  }
}
