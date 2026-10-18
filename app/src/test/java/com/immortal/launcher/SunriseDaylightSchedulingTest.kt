/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SunriseDaylightSchedulingTest {
  private fun date(zone: String, year: Int, month: Int, day: Int, hour: Int, minute: Int) =
      Calendar.getInstance(TimeZone.getTimeZone(zone), Locale.ROOT).apply {
        clear()
        set(year, month - 1, day, hour, minute, 0)
      }

  private fun alarm(hour: Int, minute: Int, days: Set<Int> = emptySet(), enabled: Boolean = true) =
      SunriseConfig.Config(enabled, hour, minute, 20, false, days)

  private fun expectTrigger(from: Calendar, config: SunriseConfig.Config,
      year: Int, month: Int, day: Int, hour: Int, minute: Int) {
    val expected = date(from.timeZone.id, year, month, day, hour, minute).timeInMillis
    assertEquals(expected, SunriseConfig.nextTrigger(config, from))
  }

  @Test fun oneShotAfterSpringGapKeepsChosenTimeTomorrow() {
    expectTrigger(date("America/Los_Angeles", 2026, 3, 8, 4, 0), alarm(2, 30),
        2026, 3, 9, 2, 30)
  }

  @Test fun repeatOnMondayDoesNotInheritSundayGapAdjustment() {
    expectTrigger(date("America/Los_Angeles", 2026, 3, 8, 1, 0),
        alarm(2, 30, setOf(Calendar.MONDAY)), 2026, 3, 9, 2, 30)
  }

  @Test fun nextWeekRepeatKeepsChosenTime() {
    expectTrigger(date("America/Los_Angeles", 2026, 3, 8, 4, 0),
        alarm(2, 30, setOf(Calendar.SUNDAY)), 2026, 3, 15, 2, 30)
  }

  @Test fun europeanSpringGapDoesNotShiftTomorrow() {
    expectTrigger(date("Europe/Berlin", 2026, 3, 29, 4, 0), alarm(2, 15),
        2026, 3, 30, 2, 15)
  }

  @Test fun halfHourSpringGapDoesNotShiftTomorrow() {
    expectTrigger(date("Australia/Lord_Howe", 2026, 10, 4, 3, 0), alarm(2, 15),
        2026, 10, 5, 2, 15)
  }

  @Test fun futureGapTodayRetainsPlatformNormalization() {
    expectTrigger(date("America/Los_Angeles", 2026, 3, 8, 1, 0), alarm(2, 30),
        2026, 3, 8, 3, 30)
  }

  @Test fun dateBeforeTransitionKeepsLaterWeekdayTime() {
    expectTrigger(date("America/Los_Angeles", 2026, 3, 7, 4, 0),
        alarm(2, 30, setOf(Calendar.MONDAY)), 2026, 3, 9, 2, 30)
  }

  @Test fun fallOverlapRetainsPlatformResolution() {
    expectTrigger(date("America/Los_Angeles", 2026, 11, 1, 0, 30), alarm(1, 30),
        2026, 11, 1, 1, 30)
  }

  @Test fun dateAfterFallOverlapKeepsChosenTime() {
    expectTrigger(date("America/Los_Angeles", 2026, 11, 1, 3, 0), alarm(1, 30),
        2026, 11, 2, 1, 30)
  }

  @Test fun disabledAndEnabledChecksPreserveInputCalendar() {
    val from = date("America/Los_Angeles", 2026, 3, 8, 4, 0)
    from.timeInMillis
    val original = from.clone() as Calendar
    assertNull(SunriseConfig.nextTrigger(alarm(2, 30, enabled = false), from))
    assertEquals(original, from)
    expectTrigger(from, alarm(2, 30), 2026, 3, 9, 2, 30)
    assertEquals(original, from)
  }
}
