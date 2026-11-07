/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.RoutineFixtures.MON
import org.junit.Assert.*
import org.junit.Test

class RoutineDatesTest {
  @Test fun isoRoundTrips_andWeekdayStartsMonday() {
    assertEquals("2026-11-09", RoutineDates.isoOf(MON))
    assertEquals(MON, RoutineDates.epochDayOf("2026-11-09"))
    assertEquals(0, RoutineDates.weekday(MON))
    assertEquals(5, RoutineDates.weekday(MON + 5))
    assertEquals("2024-02-29", RoutineDates.isoOf(RoutineDates.epochDayOf("2024-02-29")!!))
    assertEquals(-1, RoutineDates.epochDayOf("1969-12-31"))
  }

  @Test fun rejectsMalformedDates() {
    listOf("2026-13-01", "2026-02-30", "2026-1-01", "x", "", "2026-11-09T00", "2023-02-29")
        .forEach { assertNull(it, RoutineDates.epochDayOf(it)) }
  }

  @Test fun clockFormats_matchThePrototype() {
    assertEquals("7:15", RoutineDates.clock12(435))
    assertEquals("12:05", RoutineDates.clock12(5))
    assertEquals("7:15 AM", RoutineDates.time12(435))
    assertEquals("7:30 PM", RoutineDates.time12(1170))
    assertEquals("12:00 PM", RoutineDates.time12(720))
  }

  @Test fun localMsSplitsIntoDayAndMinute() {
    val t = RoutineDates.localMs(MON, 435) + 30_000
    assertEquals(MON, RoutineDates.day(t))
    assertEquals(435, RoutineDates.minuteOfDay(t))
  }
}
