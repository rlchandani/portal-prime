/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarUntilValidityTest {
  private val utc = TimeZone.getTimeZone("UTC")
  private val start = instant(2033, 1, 1, 9)
  private val horizon = instant(2033, 1, 11, 9)

  @Test fun impossibleDateUntilFallsBackToSingle() {
    for (value in listOf("20330230", "20331301", "20330132")) rejects(value)
  }
  @Test fun invalidHourUntilFallsBackToSingle() = rejects("20330103T240000Z")
  @Test fun invalidMinuteUntilFallsBackToSingle() = rejects("20330103T096000Z")
  @Test fun secondsAboveLeapSecondFallBackToSingle() = rejects("20330103T090061Z")
  @Test fun trailingDateAndTimeTextFallsBackToSingle() {
    rejects("20330103junk")
    rejects("20330103T090000Zjunk")
  }
  @Test fun incompleteTimeFallsBackToSingle() = rejects("20330103T0900")
  @Test fun unreadableUntilFallsBackToSingle() = rejects("bad")

  @Test fun parsedFeedDoesNotInventFutureOccurrencesAfterInvalidUntil() {
    val events = CalendarFeed.parse(
        """
        BEGIN:VCALENDAR
        BEGIN:VEVENT
        DTSTART:20330101T090000Z
        DTEND:20330101T100000Z
        SUMMARY:Generated event
        RRULE:FREQ=DAILY;UNTIL=bad
        END:VEVENT
        END:VCALENDAR
        """.trimIndent(), instant(2033, 1, 1, 8), 10)
    assertEquals(listOf(start), events.map { it.startMillis })
  }

  @Test fun dateUntilIncludesItsFinalDay() {
    assertEquals(firstThree(), expand("20330103"))
  }

  @Test fun utcTimedUntilIncludesItsExactFinalOccurrence() {
    assertEquals(firstThree(), expand("20330103T090000Z"))
  }

  @Test fun localUntilDstGapAndLeapSecondRemainSupported() {
    val zone = TimeZone.getTimeZone("America/Los_Angeles")
    val localStart = instant(2033, 1, 1, 9, zone = zone)
    assertEquals((1..3).map { instant(2033, 1, it, 9, zone = zone) },
        CalendarFeed.expandRecurrence(localStart, "FREQ=DAILY;UNTIL=20330103T090000",
            emptyList(), localStart + 10 * DAY, zone = zone))
    val gapStart = instant(2026, 3, 7, 2, 30, zone)
    assertEquals(listOf(gapStart, instant(2026, 3, 8, 1, 30, zone)),
        CalendarFeed.expandRecurrence(gapStart, "FREQ=DAILY;UNTIL=20260308T023000",
            emptyList(), gapStart + 3 * DAY, zone = zone))
    assertEquals(listOf(start), expand("20330101T090060Z"))
  }

  private fun rejects(value: String) = assertEquals(listOf(start), expand(value))
  private fun expand(value: String) =
      CalendarFeed.expandRecurrence(start, "FREQ=DAILY;UNTIL=$value",
          emptyList(), horizon, zone = utc)
  private fun firstThree() = (1..3).map { instant(2033, 1, it, 9) }
  private fun instant(year: Int, month: Int, day: Int, hour: Int,
      minute: Int = 0, zone: TimeZone = TimeZone.getTimeZone("UTC")) =
      Calendar.getInstance(zone).apply {
        clear()
        set(year, month - 1, day, hour, minute, 0)
      }.timeInMillis
  private companion object {
    const val DAY = 24L * 60 * 60 * 1000
  }
}
