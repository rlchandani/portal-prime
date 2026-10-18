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

class ApprovedHomeCalendarOngoingTest {
  private val utc = TimeZone.getTimeZone("UTC")
  private fun ms(time: String) = ZonedDateTime.parse(time).toInstant().toEpochMilli()
  private fun event(start: String, end: String, title: String = "Event", allDay: Boolean = false) =
      CalendarFeed.Event(ms(start), ms(end), title, allDay)
  private fun rows(events: List<CalendarFeed.Event>, now: String, use24: Boolean = true,
      zone: TimeZone = utc) = HomePresentationCalendar.prepare(events, ms(now), use24, zone, Locale.US)

  @Test fun overnightTimedEventStaysVisibleWithoutPresentingYesterdaysTimeAsToday() {
    val overnight = event("2026-10-17T23:00:00Z", "2026-10-18T09:00:00Z", "Overnight")
    for (use24 in listOf(false, true)) {
      val actual = rows(listOf(overnight), "2026-10-18T08:00:00Z", use24)
      assertEquals(listOf("Overnight"), actual.map { it.title })
      assertEquals("Today · In progress", actual.single().kicker)
    }
  }

  @Test fun multiDayAllDayEventRemainsOnItsIntermediateDay() {
    val trip = event("2026-10-16T00:00:00Z", "2026-10-20T00:00:00Z", "Public fixture", true)
    assertEquals("Today · All-day", rows(listOf(trip), "2026-10-18T12:00:00Z").single().kicker)
  }

  @Test fun exclusiveEndBoundaryDoesNotAddAnExtraDay() {
    val finished = event("2026-10-16T00:00:00Z", "2026-10-18T00:00:00Z", allDay = true)
    assertTrue(rows(listOf(finished), "2026-10-18T00:00:00Z").isEmpty())
    assertTrue(rows(listOf(finished), "2026-10-18T12:00:00Z").isEmpty())
    val timed = event("2026-10-17T23:00:00Z", "2026-10-18T09:00:00Z")
    assertTrue(rows(listOf(timed), "2026-10-18T09:00:00Z").isEmpty())
    assertTrue(rows(listOf(timed), "2026-10-18T09:00:00.001Z").isEmpty())
  }

  @Test fun midnightKeepsTheOccurrenceKeyWhileItsContextChanges() {
    val night = event("2026-10-17T23:00:00Z", "2026-10-18T09:00:00Z")
    val before = rows(listOf(night), "2026-10-17T23:59:59Z").single()
    val after = rows(listOf(night), "2026-10-18T00:00:00Z").single()
    assertEquals(before.key, after.key)
    assertEquals("Today · 23:00", before.kicker)
    assertEquals("Today · In progress", after.kicker)
  }

  @Test fun ongoingOccurrencesKeepTimeOrderAndDistinctStableDuplicateKeys() {
    val old = event("2026-10-16T20:00:00Z", "2026-10-20T09:00:00Z", "Ongoing")
    val today = event("2026-10-18T14:00:00Z", "2026-10-18T15:00:00Z", "Today")
    val input = listOf(today, old, old)
    val prepared = rows(input, "2026-10-18T08:00:00Z")
    assertEquals(listOf("Ongoing", "Ongoing", "Today"), prepared.map { it.title })
    assertEquals(3, prepared.map { it.key }.toSet().size)
    assertEquals(prepared, rows(input, "2026-10-18T08:00:00Z"))
  }

  @Test fun futureWindowAndClockFormattingStayUnchanged() {
    val today = event("2026-10-18T14:00:00Z", "2026-10-18T15:00:00Z", "Today")
    val next = event("2026-10-19T09:00:00Z", "2026-10-19T10:00:00Z", "Tomorrow")
    val third = event("2026-10-20T23:59:00Z", "2026-10-21T00:59:00Z", "Third")
    val outside = event("2026-10-21T00:00:00Z", "2026-10-21T01:00:00Z", "Outside")
    val input = listOf(outside, third, next, today)
    assertEquals(listOf("Today · 14:00", "Tomorrow · 09:00", "Tuesday · 23:59"),
        rows(input, "2026-10-18T08:00:00Z").map { it.kicker })
    assertEquals(listOf("Today · 2:00 PM", "Tomorrow · 9:00 AM", "Tuesday · 11:59 PM"),
        rows(input, "2026-10-18T08:00:00Z", false).map { it.kicker })
  }

  @Test fun springAndAutumnTimeChangesRetainAnActiveEarlierDate() {
    val zone = TimeZone.getTimeZone("America/Los_Angeles")
    val dates = listOf(
        Triple("2026-03-07T23:00:00-08:00", "2026-03-08T10:00:00-07:00", "2026-03-08T08:00:00-07:00"),
        Triple("2026-10-31T23:00:00-07:00", "2026-11-01T10:00:00-08:00", "2026-11-01T08:00:00-08:00"))
    for ((start, end, now) in dates) {
      assertEquals("Today · In progress", rows(listOf(event(start, end)), now, zone = zone).single().kicker)
    }
  }

  @Test fun ongoingAllDayEventIsIncludedOnceRatherThanExpandedIntoSeveralDays() {
    val spanning = event("2026-10-16T00:00:00Z", "2026-10-20T00:00:00Z", allDay = true)
    assertEquals(1, rows(listOf(spanning), "2026-10-18T08:00:00Z").size)
  }
}
