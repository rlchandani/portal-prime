/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

/** Real generated ICS parsing; every occurrence and end is an independently literal UTC instant. */
class CalendarRecurrenceZoneTest {
  @Test fun utcDailyKeepsUtcHoursAcrossPortalSpringDst() = inZone("America/New_York") {
    check(feed("utc-spring", "20260307T120000Z", "20260307T130000Z"), "2026-03-07T00:00:00Z",
        listOf("2026-03-07T12:00:00Z", "2026-03-08T12:00:00Z", "2026-03-09T12:00:00Z"),
        listOf("2026-03-07T13:00:00Z", "2026-03-08T13:00:00Z", "2026-03-09T13:00:00Z"))
  }

  @Test fun utcDailyKeepsUtcHoursAcrossPortalFallDst() = inZone("America/New_York") {
    check(feed("utc-fall", "20261031T120000Z", "20261031T130000Z"), "2026-10-31T00:00:00Z",
        listOf("2026-10-31T12:00:00Z", "2026-11-01T12:00:00Z", "2026-11-02T12:00:00Z"),
        listOf("2026-10-31T13:00:00Z", "2026-11-01T13:00:00Z", "2026-11-02T13:00:00Z"))
  }

  @Test fun berlinDailyUsesBerlinSpringDstOnUtcPortal() = inZone("UTC") {
    check(feed("berlin", "20260328T100000", "20260328T110000", zone = "Europe/Berlin"),
        "2026-03-28T00:00:00Z",
        listOf("2026-03-28T09:00:00Z", "2026-03-29T08:00:00Z", "2026-03-30T08:00:00Z"),
        listOf("2026-03-28T10:00:00Z", "2026-03-29T09:00:00Z", "2026-03-30T09:00:00Z"))
  }

  @Test fun newYorkDailyUsesItsOwnDstOnTokyoPortal() = inZone("Asia/Tokyo") {
    check(feed("ny-tokyo", "20260307T120000", "20260307T130000", zone = "America/New_York"),
        "2026-03-07T00:00:00Z",
        listOf("2026-03-07T17:00:00Z", "2026-03-08T16:00:00Z", "2026-03-09T16:00:00Z"),
        listOf("2026-03-07T18:00:00Z", "2026-03-08T17:00:00Z", "2026-03-09T17:00:00Z"))
  }

  @Test fun fixedKolkataDailyDoesNotFollowPortalDst() = inZone("America/New_York") {
    check(feed("kolkata", "20260307T120000", "20260307T130000", zone = "Asia/Kolkata"),
        "2026-03-07T00:00:00Z",
        listOf("2026-03-07T06:30:00Z", "2026-03-08T06:30:00Z", "2026-03-09T06:30:00Z"),
        listOf("2026-03-07T07:30:00Z", "2026-03-08T07:30:00Z", "2026-03-09T07:30:00Z"))
  }

  @Test fun utcWeeklyBydayKeepsUtcHoursAcrossPortalDst() = inZone("America/New_York") {
    check(feed("weekly-dst", "20260306T120000Z", "20260306T130000Z",
        rule = "FREQ=WEEKLY;BYDAY=MO,FR;COUNT=3"), "2026-03-06T00:00:00Z",
        listOf("2026-03-06T12:00:00Z", "2026-03-09T12:00:00Z", "2026-03-13T12:00:00Z"),
        listOf("2026-03-06T13:00:00Z", "2026-03-09T13:00:00Z", "2026-03-13T13:00:00Z"))
  }

  @Test fun utcWeeklyBydayUsesSourceMondayRatherThanPortalSunday() = inZone("America/New_York") {
    check(feed("weekly-boundary", "20260309T003000Z", "20260309T013000Z",
        rule = "FREQ=WEEKLY;BYDAY=MO;COUNT=2"), "2026-03-08T00:00:00Z",
        listOf("2026-03-09T00:30:00Z", "2026-03-16T00:30:00Z"),
        listOf("2026-03-09T01:30:00Z", "2026-03-16T01:30:00Z"))
  }

  @Test fun utcExdateRemovesItsExactOccurrenceWithoutExtendingCount() = inZone("America/New_York") {
    check(feed("utc-exdate", "20260307T120000Z", "20260307T130000Z",
        rule = "FREQ=DAILY;COUNT=4", extra = "EXDATE:20260308T120000Z"),
        "2026-03-07T00:00:00Z",
        listOf("2026-03-07T12:00:00Z", "2026-03-09T12:00:00Z", "2026-03-10T12:00:00Z"),
        listOf("2026-03-07T13:00:00Z", "2026-03-09T13:00:00Z", "2026-03-10T13:00:00Z"))
  }

  @Test fun utcUntilIncludesTheExactLastUtcOccurrenceAcrossDst() = inZone("America/New_York") {
    check(feed("utc-until", "20260307T120000Z", "20260307T130000Z",
        rule = "FREQ=DAILY;UNTIL=20260309T120000Z"), "2026-03-07T00:00:00Z",
        listOf("2026-03-07T12:00:00Z", "2026-03-08T12:00:00Z", "2026-03-09T12:00:00Z"),
        listOf("2026-03-07T13:00:00Z", "2026-03-08T13:00:00Z", "2026-03-09T13:00:00Z"))
  }

  @Test fun floatingUntilForTzidEventUsesThatSourceZone() = inZone("UTC") {
    check(feed("source-until", "20260307T120000", "20260307T130000",
        zone = "America/New_York", rule = "FREQ=DAILY;UNTIL=20260309T120000"),
        "2026-03-07T00:00:00Z",
        listOf("2026-03-07T17:00:00Z", "2026-03-08T16:00:00Z", "2026-03-09T16:00:00Z"),
        listOf("2026-03-07T18:00:00Z", "2026-03-08T17:00:00Z", "2026-03-09T17:00:00Z"))
  }

  @Test fun dateUntilUsesTheUtcSeriesDateRatherThanTokyoDate() = inZone("Asia/Tokyo") {
    check(feed("source-date-until", "20260307T200000Z", "20260307T210000Z",
        rule = "FREQ=DAILY;UNTIL=20260309"), "2026-03-07T00:00:00Z",
        listOf("2026-03-07T20:00:00Z", "2026-03-08T20:00:00Z", "2026-03-09T20:00:00Z"),
        listOf("2026-03-07T21:00:00Z", "2026-03-08T21:00:00Z", "2026-03-09T21:00:00Z"))
  }

  @Test fun seekingAnOldUtcSeriesPreservesItsHoursAcrossPortalDst() = inZone("America/New_York") {
    check(feed("old-utc", "20200101T120000Z", "20200101T130000Z", rule = "FREQ=DAILY"),
        "2026-03-08T00:00:00Z",
        listOf("2026-03-08T12:00:00Z", "2026-03-09T12:00:00Z"),
        listOf("2026-03-08T13:00:00Z", "2026-03-09T13:00:00Z"), horizonDays = 2)
  }

  @Test fun mixedEventsRetainTheirOwnZonesWithinOnePreparedDocument() = inZone("America/New_York") {
    val source = calendar(
        event("mixed-utc", "20260328T120000Z", "20260328T130000Z"),
        event("mixed-berlin", "20260328T100000", "20260328T110000", zone = "Europe/Berlin"),
        event("mixed-kolkata", "20260328T120000", "20260328T130000", zone = "Asia/Kolkata"))
    check(source, "2026-03-28T00:00:00Z",
        listOf("2026-03-28T06:30:00Z", "2026-03-28T09:00:00Z", "2026-03-28T12:00:00Z",
            "2026-03-29T06:30:00Z", "2026-03-29T08:00:00Z", "2026-03-29T12:00:00Z",
            "2026-03-30T06:30:00Z", "2026-03-30T08:00:00Z", "2026-03-30T12:00:00Z"),
        listOf("2026-03-28T07:30:00Z", "2026-03-28T10:00:00Z", "2026-03-28T13:00:00Z",
            "2026-03-29T07:30:00Z", "2026-03-29T09:00:00Z", "2026-03-29T13:00:00Z",
            "2026-03-30T07:30:00Z", "2026-03-30T09:00:00Z", "2026-03-30T13:00:00Z"))
  }

  @Test fun localFloatingDailyRetainsPortalWallTimeAcrossDst() = inZone("America/New_York") {
    check(feed("floating-control", "20260307T120000", "20260307T130000"),
        "2026-03-07T00:00:00Z",
        listOf("2026-03-07T17:00:00Z", "2026-03-08T16:00:00Z", "2026-03-09T16:00:00Z"),
        listOf("2026-03-07T18:00:00Z", "2026-03-08T17:00:00Z", "2026-03-09T17:00:00Z"))
  }

  @Test fun allDayDailyRetainsLocalExclusiveMidnightsAcrossDst() = inZone("America/New_York") {
    val source = calendar(listOf("BEGIN:VEVENT", "SUMMARY:all-day-control",
        "DTSTART;VALUE=DATE:20260307", "RRULE:FREQ=DAILY;COUNT=3", "END:VEVENT").joinToString("\r\n"))
    check(source, "2026-03-07T00:00:00Z",
        listOf("2026-03-07T05:00:00Z", "2026-03-08T05:00:00Z", "2026-03-09T04:00:00Z"),
        listOf("2026-03-08T05:00:00Z", "2026-03-09T04:00:00Z", "2026-03-10T04:00:00Z"),
        allDay = true)
  }

  private fun check(source: String, now: String, starts: List<String>, ends: List<String>,
      horizonDays: Int = 45, allDay: Boolean = false) {
    val rows = CalendarFeed.parse(source, utc(now), horizonDays)
    assertEquals(starts.map(::utc), rows.map { it.startMillis })
    assertEquals(ends.map(::utc), rows.map { it.endMillis })
    assertTrue(rows.all { it.allDay == allDay })
  }

  private fun feed(title: String, start: String, end: String, zone: String? = null,
      rule: String = "FREQ=DAILY;COUNT=3", extra: String? = null) =
      calendar(event(title, start, end, zone, rule, extra))

  private fun event(title: String, start: String, end: String, zone: String? = null,
      rule: String = "FREQ=DAILY;COUNT=3", extra: String? = null): String {
    val parameter = zone?.let { ";TZID=$it" }.orEmpty()
    return (listOf("BEGIN:VEVENT", "SUMMARY:$title", "DTSTART$parameter:$start",
        "DTEND$parameter:$end", "RRULE:$rule") + listOfNotNull(extra) +
        listOf("END:VEVENT")).joinToString("\r\n")
  }

  private fun calendar(vararg events: String) =
      (listOf("BEGIN:VCALENDAR", "VERSION:2.0") + events + listOf("END:VCALENDAR")).joinToString("\r\n")

  private fun utc(value: String): Long = Instant.parse(value).toEpochMilli()

  private fun inZone(zone: String, test: () -> Unit) {
    val savedZone = TimeZone.getDefault()
    val savedLocale = Locale.getDefault()
    val savedFormat = Locale.getDefault(Locale.Category.FORMAT)
    val savedDisplay = Locale.getDefault(Locale.Category.DISPLAY)
    try {
      TimeZone.setDefault(TimeZone.getTimeZone(zone))
      Locale.setDefault(Locale.US)
      test()
    } finally {
      TimeZone.setDefault(savedZone)
      Locale.setDefault(savedLocale)
      Locale.setDefault(Locale.Category.FORMAT, savedFormat)
      Locale.setDefault(Locale.Category.DISPLAY, savedDisplay)
    }
  }
}
