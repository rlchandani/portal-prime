/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class HouseholdListTimeTest {
  private val zone = TimeZone.getTimeZone("America/Los_Angeles")
  private fun at(day: Int, h: Int, m: Int) =
      Calendar.getInstance(zone).apply { clear(); set(2026, Calendar.NOVEMBER, day, h, m) }.timeInMillis
  private val now = at(7, 8, 4) // Saturday

  @Test fun shortWording() {
    assertEquals("just now", HouseholdListTime.short(now - 20_000, now, zone))
    assertEquals("6:29 AM", HouseholdListTime.short(at(7, 6, 29), now, zone))
    assertEquals("yesterday", HouseholdListTime.short(at(6, 21, 0), now, zone))
    assertEquals("Monday", HouseholdListTime.short(at(2, 7, 14), now, zone))
  }

  @Test fun longWording() {
    assertEquals("today 7:02 AM", HouseholdListTime.long(at(7, 7, 2), now, zone))
    assertEquals("yesterday 9:30 PM", HouseholdListTime.long(at(6, 21, 30), now, zone))
    assertEquals("Monday 7:14 AM", HouseholdListTime.long(at(2, 7, 14), now, zone))
  }
}
