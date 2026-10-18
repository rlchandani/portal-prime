/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class ApprovedHomeCalendarPreparationTest {
  private val utc = TimeZone.getTimeZone("UTC")
  private fun millis(time: String) = ZonedDateTime.parse(time).toInstant().toEpochMilli()
  private fun event(start: Long, title: String = "Event", allDay: Boolean = false) =
      CalendarFeed.Event(start, start + 60 * 60 * 1000, title, allDay)

  @Test fun busyFeed_keepsEveryThreeDayEventInTimeOrderAndExcludesOtherDates() {
    val start = millis("2026-10-04T00:00:00Z")
    val visible = (0..499).map { event(start + it * 60_000L, "Event $it") }
    val outside = (1..10000).map { event(start + 3 * 86_400_000L + it, "Later") }
    val prepared = HomePresentationCalendar.prepare(
        (outside + visible + event(start - 3_600_000L)).reversed(), start, true, utc, Locale.US)
    assertEquals(visible.map { it.title }, prepared.map { it.title })
    assertEquals(500, prepared.map { it.key }.toSet().size)
    assertEquals("Today · 00:00", prepared.first().kicker)
  }

  @Test fun unchangedEventIdentity_survivesInsertionAndClockFormatChanges() {
    val start = millis("2026-10-04T09:00:00Z")
    val original = listOf(event(start, "Meeting: 1"), event(start + 3600000L, allDay = true))
    val before = HomePresentationCalendar.prepare(original, start, true, utc, Locale.US)
    val after = HomePresentationCalendar.prepare(
        listOf(event(start - 60000L, "New")) + original, start, false, utc, Locale.US)
    assertEquals(before.map { it.key }, after.drop(1).map { it.key })
    assertEquals("Today · 9:00 AM", after[1].kicker)
    assertEquals("Today · All-day", after[2].kicker)
  }

  @Test fun exactDuplicates_haveDistinctStableLazyKeys() {
    val start = millis("2026-10-04T09:00:00Z")
    val input = List(3) { event(start, "Duplicate") }
    val rows = HomePresentationCalendar.prepare(input, start, true, utc, Locale.US)
    assertEquals(3, rows.map { it.key }.toSet().size)
    assertEquals(rows, HomePresentationCalendar.prepare(input, start, true, utc, Locale.US))
  }

  @Test fun daylightSavingBoundary_usesCalendarDaysInsteadOfFixed24HourDivision() {
    val zone = TimeZone.getTimeZone("America/Los_Angeles")
    val now = millis("2026-10-31T12:00:00-07:00[America/Los_Angeles]")
    val next = event(millis("2026-11-01T23:30:00-08:00[America/Los_Angeles]"), "Tomorrow")
    val third = event(millis("2026-11-02T23:59:00-08:00[America/Los_Angeles]"), "Third")
    val outside = event(millis("2026-11-03T00:00:00-08:00[America/Los_Angeles]"))
    val rows = HomePresentationCalendar.prepare(listOf(outside, third, next), now, true, zone, Locale.US)
    assertEquals(listOf("Tomorrow", "Third"), rows.map { it.title })
    assertEquals(listOf("Tomorrow · 23:30", "Monday · 23:59"), rows.map { it.kicker })
  }
}
