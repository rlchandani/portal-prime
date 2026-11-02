/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.time.Instant
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeAlarmWidgetTimingTest {
  private val utc = TimeZone.getTimeZone("UTC")

  private fun instant(value: String): Long = Instant.parse(value).toEpochMilli()

  private fun alarm(
      hour: Int = 7,
      minute: Int = 0,
      enabled: Boolean = true,
      days: Set<Int> = emptySet(),
  ) = AlarmConfig.Config(enabled, hour, minute, "Public alarm", days)

  @Test
  fun unchangedConfigCountsDownAsNowAdvances() {
    val config = alarm(hour = 11)

    assertEquals(60L, homeAlarmRemainingMinutes(config, instant("2026-07-15T10:00:00Z"), utc))
    assertEquals(43L, homeAlarmRemainingMinutes(config, instant("2026-07-15T10:17:00Z"), utc))
    assertEquals(1L, homeAlarmRemainingMinutes(config, instant("2026-07-15T10:59:30Z"), utc))
  }

  @Test
  fun positiveRemaindersRoundUpInsteadOfDisplayingZero() {
    val config = alarm()
    val cases = listOf(
        "2026-07-15T06:58:59.999Z" to 2L,
        "2026-07-15T06:59:00Z" to 1L,
        "2026-07-15T06:59:00.001Z" to 1L,
        "2026-07-15T06:59:59.999Z" to 1L,
    )
    for ((now, expected) in cases) {
      assertEquals(now, expected, homeAlarmRemainingMinutes(config, instant(now), utc))
    }
  }

  @Test
  fun passedOneShotTimeCountsDownToTomorrow() {
    assertEquals(
        1_260L,
        homeAlarmRemainingMinutes(alarm(), instant("2026-07-15T10:00:00Z"), utc),
    )
  }

  @Test
  fun exactTriggerTimeAdvancesToNextOccurrence() {
    assertEquals(
        1_440L,
        homeAlarmRemainingMinutes(alarm(), instant("2026-07-15T07:00:00Z"), utc),
    )
  }

  @Test
  fun passedSelectedWeekdayAdvancesToNextWeek() {
    val config = alarm(days = setOf(Calendar.WEDNESDAY))

    // Wednesday 10:00 to the following Wednesday 07:00 is 6 days and 21 hours.
    assertEquals(
        9_900L,
        homeAlarmRemainingMinutes(config, instant("2026-07-15T10:00:00Z"), utc),
    )
  }

  @Test
  fun repeatScheduleSkipsUnselectedDays() {
    val config = alarm(days = setOf(Calendar.MONDAY, Calendar.FRIDAY))

    // Wednesday 10:00 to Friday 07:00 is 45 hours.
    assertEquals(
        2_700L,
        homeAlarmRemainingMinutes(config, instant("2026-07-15T10:00:00Z"), utc),
    )
  }

  @Test
  fun disabledAlarmHasNoCountdown() {
    assertNull(
        homeAlarmRemainingMinutes(
            alarm(enabled = false), instant("2026-07-15T06:00:00Z"), utc),
    )
  }

  @Test
  fun scheduleWithoutMatchingWeekdayHasNoCountdown() {
    // Empty days mean one-shot; values outside Calendar's weekday range never match.
    assertNull(
        homeAlarmRemainingMinutes(
            alarm(days = setOf(0, 8)), instant("2026-07-15T06:00:00Z"), utc),
    )
  }

  @Test
  fun suppliedTimeZoneDeterminesAlarmWallTime() {
    val zone = TimeZone.getTimeZone("Asia/Kolkata")

    // 00:30 UTC is 06:00 in Kolkata, one hour before its configured 07:00 alarm.
    assertEquals(
        60L,
        homeAlarmRemainingMinutes(alarm(), instant("2026-07-15T00:30:00Z"), zone),
    )
  }

  @Test
  fun springGapUsesSchedulerNormalizationAndElapsedMinutes() {
    val zone = TimeZone.getTimeZone("America/Los_Angeles")

    // 01:00 PST to normalized 03:30 PDT is 90 elapsed minutes, not 150.
    assertEquals(
        90L,
        homeAlarmRemainingMinutes(
            alarm(hour = 2, minute = 30), instant("2026-03-08T09:00:00Z"), zone),
    )
  }

  @Test
  fun afterSpringGapTomorrowKeepsRequestedAlarmTime() {
    val zone = TimeZone.getTimeZone("America/Los_Angeles")

    // Sunday 04:00 PDT to Monday 02:30 PDT is 22.5 hours.
    assertEquals(
        1_350L,
        homeAlarmRemainingMinutes(
            alarm(hour = 2, minute = 30), instant("2026-03-08T11:00:00Z"), zone),
    )
  }

  @Test
  fun refreshAtMinuteBoundaryWaitsAFullMinute() {
    for (now in listOf(-120_000L, -60_000L, 0L, 60_000L, 120_000L)) {
      assertEquals("now=$now", 60_000L, homeAlarmWidgetRefreshDelayMs(now))
    }
  }

  @Test
  fun refreshWithinMinuteWaitsOnlyUntilNextBoundary() {
    val cases = listOf(
        1L to 59_999L,
        30_123L to 29_877L,
        59_999L to 1L,
        120_123L to 59_877L,
        Long.MAX_VALUE to 4_193L,
    )
    for ((now, expected) in cases) {
      assertEquals("now=$now", expected, homeAlarmWidgetRefreshDelayMs(now))
    }
  }

  @Test
  fun negativeTimestampsStillWaitForTheNextMinuteBoundary() {
    val cases = listOf(
        -1L to 1L,
        -30_123L to 30_123L,
        -59_999L to 59_999L,
        -60_001L to 1L,
        Long.MIN_VALUE to 55_808L,
    )
    for ((now, expected) in cases) {
      assertEquals("now=$now", expected, homeAlarmWidgetRefreshDelayMs(now))
    }
  }
}
