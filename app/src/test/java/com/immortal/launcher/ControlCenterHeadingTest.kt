/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

/** The header heading ([ccHeading]) is built from minute-resolution inputs the shell can pass unchanged across HA updates. */
class ControlCenterHeadingTest {
  private lateinit var locale: Locale
  private lateinit var zone: TimeZone
  /** A Saturday morning in Los Angeles, 37.5 s into the minute. */
  private val now = 1_795_000_000_000L + 37_500L
  private val plan = CcPlan(listOf(CcFloor("g", "Ground floor"), CcFloor("u", "Upstairs")))

  @Before fun save() {
    locale = Locale.getDefault(); zone = TimeZone.getDefault()
    Locale.setDefault(Locale.US); TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
  }
  @After fun restore() { Locale.setDefault(locale); TimeZone.setDefault(zone) }

  private fun inputs(kids: Boolean = false, at: Long = now) = CcHeadingInputs.of(CcUiState(now = at, plan = plan, settings = ControlCenterConfig.Settings(portalMode = if (kids) ControlCenterConfig.MODE_KIDS else ControlCenterConfig.MODE_FULL)))

  @Test fun mapShowsDayPartTimeAndFloor() {
    val h = ccHeading(inputs(), CcScreen.MAP, "u")
    assertEquals("Home", h.title)
    assertEquals(ccSubtitle(now, "Upstairs"), h.subtitle)
  }

  @Test fun mapWithoutAFloorIdShowsTheFirstFloorAndKidsMode() {
    assertEquals(ccSubtitle(now, "Ground floor · Kids mode"), ccHeading(inputs(kids = true), CcScreen.MAP, null).subtitle)
  }

  @Test fun otherScreensDropTheDayPart() {
    val h = ccHeading(inputs(), CcScreen.ALERTS, "u")
    assertEquals("Alerts", h.title)
    assertEquals(ccSubtitle(now).replace(Regex(" (morning|afternoon|evening|night)"), ""), h.subtitle)
  }

  @Test fun teslaIsCalledByTheCarName() {
    assertEquals(CcTeslaIds.DEFAULT_LABEL, ccHeading(inputs(), CcScreen.TESLA, null).title)
  }

  /** Minute resolution formats exactly like the exact time did. */
  @Test fun minuteResolutionMatchesTheExactTime() {
    for (screen in CcScreen.entries) {
      val exact = if (screen == CcScreen.MAP) ccSubtitle(now, "Ground floor") else ccSubtitle(now).replace(Regex(" (morning|afternoon|evening|night)"), "")
      assertEquals(exact, ccHeading(inputs(), screen, "g").subtitle)
    }
  }

  /** HA updates within the minute hand the header the same inputs, so it skips. */
  @Test fun inputsAreEqualWithinAMinuteAndChangeWithIt() {
    assertEquals(inputs(at = now), inputs(at = now + 15_000L))
    assertNotEquals(inputs(at = now), inputs(at = now + 60_000L))
  }
}
