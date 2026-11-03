/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.time.Instant
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

/** Actual parser and display window; UTC expectations independently identify local date boundaries. */
class CalendarAllDayDurationTest {
  @Test fun missingDateEndUsesNextMidnightOnSpringTransition() = inZone {
    val row = CalendarFeed.parse(feed("spring-default", "20260308"), utc("2026-03-08T05:00:00Z")).single()
    assertTrue(row.allDay)
    assertEquals(utc("2026-03-09T04:00:00Z"), row.endMillis)
    assertEquals(23 * HOUR, row.endMillis - row.startMillis)
  }

  @Test fun missingDateEndUsesNextMidnightOnFallTransition() = inZone {
    val row = CalendarFeed.parse(feed("fall-default", "20261101"), utc("2026-11-01T04:00:00Z")).single()
    assertTrue(row.allDay)
    assertEquals(utc("2026-11-02T05:00:00Z"), row.endMillis)
    assertEquals(25 * HOUR, row.endMillis - row.startMillis)
  }

  @Test fun springDateEventCannotRemainVisibleAfterItsExclusiveDateEnd() = inZone {
    // 00:30 on the following local date is after the event's exclusive midnight end.
    val now = utc("2026-03-09T04:30:00Z")
    val rows = CalendarFeed.parse(feed("spring-window", "20260308"), now)
    assertTrue(CalendarFeed.window(rows, CalendarFeed.RANGE_DAY, now).isEmpty())
  }

  @Test fun fallDateEventRemainsVisibleThroughItsLastLocalHour() = inZone {
    // 23:30 on the event's local date precedes its exclusive midnight end.
    val now = utc("2026-11-02T04:30:00Z")
    val rows = CalendarFeed.parse(feed("fall-window", "20261101"), now)
    assertEquals(listOf("fall-window"), CalendarFeed.window(rows, CalendarFeed.RANGE_DAY, now).map { it.title })
    val midnight = utc("2026-11-02T05:00:00Z")
    assertTrue(CalendarFeed.window(rows, CalendarFeed.RANGE_DAY, midnight).isEmpty())
  }

  @Test fun explicitMultiDayDateEndsKeepTheirExclusiveMidnights() = inZone {
    val spring = CalendarFeed.parse(feed("spring-explicit", "20260307", "20260309"),
        utc("2026-03-07T05:00:00Z")).single()
    assertEquals(utc("2026-03-09T04:00:00Z"), spring.endMillis)
    assertEquals(47 * HOUR, spring.endMillis - spring.startMillis)
    val fall = CalendarFeed.parse(feed("fall-explicit", "20261031", "20261102"),
        utc("2026-10-31T04:00:00Z")).single()
    assertEquals(utc("2026-11-02T05:00:00Z"), fall.endMillis)
    assertEquals(49 * HOUR, fall.endMillis - fall.startMillis)
  }

  @Test fun recurringImplicitDateEndsUseEachSpringOccurrenceCalendarDay() = inZone {
    val rows = CalendarFeed.parse(feed("spring-recurring-default", "20260307", rule = "FREQ=DAILY;COUNT=3"),
        utc("2026-03-07T05:00:00Z"))
    assertEquals(listOf("2026-03-07T05:00:00Z", "2026-03-08T05:00:00Z", "2026-03-09T04:00:00Z").map(::utc),
        rows.map { it.startMillis })
    assertEquals(listOf("2026-03-08T05:00:00Z", "2026-03-09T04:00:00Z", "2026-03-10T04:00:00Z").map(::utc),
        rows.map { it.endMillis })
  }

  @Test fun recurringImplicitDateEndsUseEachFallOccurrenceCalendarDay() = inZone {
    val rows = CalendarFeed.parse(feed("fall-recurring-default", "20261031", rule = "FREQ=DAILY;COUNT=3"),
        utc("2026-10-31T04:00:00Z"))
    assertEquals(listOf("2026-10-31T04:00:00Z", "2026-11-01T04:00:00Z", "2026-11-02T05:00:00Z").map(::utc),
        rows.map { it.startMillis })
    assertEquals(listOf("2026-11-01T04:00:00Z", "2026-11-02T05:00:00Z", "2026-11-03T05:00:00Z").map(::utc),
        rows.map { it.endMillis })
  }

  @Test fun recurringExplicitSpringSpanDoesNotReuseFirstOccurrenceElapsedHours() = inZone {
    val spring = CalendarFeed.parse(feed("spring-recurring-explicit", "20260307", "20260309", "FREQ=DAILY;COUNT=3"),
        utc("2026-03-07T05:00:00Z"))
    assertEquals(listOf("2026-03-09T04:00:00Z", "2026-03-10T04:00:00Z", "2026-03-11T04:00:00Z").map(::utc),
        spring.map { it.endMillis })
  }

  @Test fun recurringExplicitFallSpanDoesNotReuseFirstOccurrenceElapsedHours() = inZone {
    val fall = CalendarFeed.parse(feed("fall-recurring-explicit", "20261031", "20261102", "FREQ=DAILY;COUNT=3"),
        utc("2026-10-31T04:00:00Z"))
    assertEquals(listOf("2026-11-02T05:00:00Z", "2026-11-03T05:00:00Z", "2026-11-04T05:00:00Z").map(::utc),
        fall.map { it.endMillis })
  }

  @Test fun ordinaryAndUtcDateEventsKeepOneDayDuration() {
    for (zone in listOf("America/New_York", "UTC")) inZone(zone) {
      val row = CalendarFeed.parse(feed("ordinary-$zone", "20260210"), utc("2026-02-09T00:00:00Z")).single()
      assertTrue(row.allDay)
      assertEquals(24 * HOUR, row.endMillis - row.startMillis)
    }
  }

  @Test fun timedRecurrenceRetainsItsExistingElapsedDuration() = inZone {
    val spring = CalendarFeed.parse(timedFeed("spring-timed", "20260307T120000", "20260308T120000"),
        utc("2026-03-07T05:00:00Z"))
    assertEquals(3, spring.size)
    assertTrue(spring.all { !it.allDay && it.endMillis - it.startMillis == 23 * HOUR })
    val fall = CalendarFeed.parse(timedFeed("fall-timed", "20261031T120000", "20261101T120000"),
        utc("2026-10-31T04:00:00Z"))
    assertEquals(3, fall.size)
    assertTrue(fall.all { !it.allDay && it.endMillis - it.startMillis == 25 * HOUR })
  }

  @Test fun warmPreparedDateEventStillExpiresAtTheCorrectMidnight() = inZone {
    val source = feed("spring-warm", "20260308")
    assertEquals(1, CalendarFeed.parse(source, utc("2026-03-08T05:00:00Z")).size)
    assertTrue(CalendarFeed.parse(source, utc("2026-03-09T04:30:00Z")).isEmpty())
  }

  private fun feed(title: String, start: String, end: String? = null, rule: String? = null): String =
      (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "BEGIN:VEVENT", "SUMMARY:$title",
          "DTSTART;VALUE=DATE:$start") +
          listOfNotNull(end?.let { "DTEND;VALUE=DATE:$it" }, rule?.let { "RRULE:$it" }) +
          listOf("END:VEVENT", "END:VCALENDAR")).joinToString("\r\n")

  private fun timedFeed(title: String, start: String, end: String): String =
      listOf("BEGIN:VCALENDAR", "BEGIN:VEVENT", "SUMMARY:$title",
          "DTSTART;TZID=America/New_York:$start", "DTEND;TZID=America/New_York:$end",
          "RRULE:FREQ=DAILY;COUNT=3", "END:VEVENT", "END:VCALENDAR").joinToString("\r\n")

  private fun utc(value: String): Long = Instant.parse(value).toEpochMilli()

  private fun inZone(zone: String = "America/New_York", block: () -> Unit) {
    val saved = TimeZone.getDefault()
    try {
      TimeZone.setDefault(TimeZone.getTimeZone(zone))
      block()
    } finally {
      TimeZone.setDefault(saved)
    }
  }

  private companion object {
    const val HOUR = 3_600_000L
  }
}
