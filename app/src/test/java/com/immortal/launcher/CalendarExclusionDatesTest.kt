/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.time.Instant
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Parse real recurring iCalendar events; exclusion lists must preserve date parameters. */
class CalendarExclusionDatesTest {
  private lateinit var previousZone: TimeZone
  private val now = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()

  @Before
  fun pinDefaultZone() {
    previousZone = TimeZone.getDefault()
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
  }

  @After
  fun restoreDefaultZone() {
    TimeZone.setDefault(previousZone)
  }

  private fun utc(day: Int, time: String = "09:00:00"): Long =
      Instant.parse("2026-01-${day.toString().padStart(2, '0')}T${time}Z").toEpochMilli()

  private fun feed(vararg properties: String): String =
      (listOf(
          "BEGIN:VCALENDAR",
          "VERSION:2.0",
          "BEGIN:VEVENT",
          "UID:public-exdate-regression",
          "SUMMARY:Public exclusion dates") +
          properties.toList() +
          listOf("END:VEVENT", "END:VCALENDAR"))
          .joinToString("\r\n")

  private fun utcEvents(vararg exclusions: String): List<CalendarFeed.Event> =
      CalendarFeed.parse(
          feed(
              "DTSTART:20260105T090000Z",
              "DTEND:20260105T093000Z",
              "RRULE:FREQ=DAILY;COUNT=5",
              *exclusions),
          now,
          horizonDays = 45)

  private fun assertStarts(events: List<CalendarFeed.Event>, days: List<Int>, time: String = "09:00:00") {
    assertEquals(days.map { utc(it, time) }, events.map { it.startMillis })
  }

  @Test
  fun utcListExcludesDtstartAndLaterDateWithoutExtendingCount() {
    val events = utcEvents("EXDATE:20260105T090000Z,20260107T090000Z")
    // COUNT generates Jan 5-9 before exclusions; Jan 10-11 must not replace removed dates.
    assertStarts(events, listOf(6, 8, 9))
  }

  @Test
  fun dateOnlyListAppliesValueDateToEveryExcludedDate() {
    val events =
        CalendarFeed.parse(
            feed(
                "DTSTART;VALUE=DATE:20260105",
                "RRULE:FREQ=DAILY;COUNT=5",
                "EXDATE;VALUE=DATE:20260106,20260108"),
            now,
            horizonDays = 45)
    assertStarts(events, listOf(5, 7, 9), time = "00:00:00")
    assertTrue(events.all { it.allDay })
  }

  @Test
  fun tzidListAppliesFixedEventZoneToEveryExcludedValue() {
    val events =
        CalendarFeed.parse(
            feed(
                "DTSTART;TZID=Asia/Kolkata:20260105T090000",
                "DTEND;TZID=Asia/Kolkata:20260105T093000",
                "RRULE:FREQ=DAILY;COUNT=5",
                "EXDATE;TZID=Asia/Kolkata:20260106T090000,20260108T090000"),
            now,
            horizonDays = 45)
    assertStarts(events, listOf(5, 7, 9), time = "03:30:00")
  }

  @Test
  fun floatingListUsesPinnedDefaultZoneForEveryValue() {
    TimeZone.setDefault(TimeZone.getTimeZone("GMT+02:00"))
    val events =
        CalendarFeed.parse(
            feed(
                "DTSTART:20260105T090000",
                "DTEND:20260105T093000",
                "RRULE:FREQ=DAILY;COUNT=5",
                "EXDATE:20260106T090000,20260108T090000"),
            now,
            horizonDays = 45)
    assertStarts(events, listOf(5, 7, 9), time = "07:00:00")
  }

  @Test
  fun repeatedExdatePropertiesCombineTheirExclusions() {
    val events = utcEvents("EXDATE:20260106T090000Z", "EXDATE:20260108T090000Z")
    assertStarts(events, listOf(5, 7, 9))
  }

  @Test
  fun foldedListIsUnfoldedBeforeParsingEveryValue() {
    val events = utcEvents("EXDATE:20260106T090000Z,\r\n 20260108T090000Z")
    assertStarts(events, listOf(5, 7, 9))
  }

  @Test
  fun malformedTokenDoesNotDiscardLaterValidExclusions() {
    val events = utcEvents("EXDATE:20260106T090000Z,not-a-date,20260108T090000Z")
    assertStarts(events, listOf(5, 7, 9))
  }

  @Test
  fun noExdateRetainsAllCountedOccurrences() {
    assertStarts(utcEvents(), listOf(5, 6, 7, 8, 9))
  }

  @Test
  fun singleExdateRetainsExistingExclusionBehavior() {
    assertStarts(utcEvents("EXDATE:20260107T090000Z"), listOf(5, 6, 8, 9))
  }
}
