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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real parser inputs: elapsed series age must not consume the current display window. */
class CalendarRecurringWindowTest {
  @Test fun dailySeriesOlderThanOneYearStillShowsCurrentDates() = inUtc {
    val rows = CalendarFeed.parse(
        feed("old-daily", "20250101T090000Z", "20250101T100000Z", "FREQ=DAILY"),
        utc("2026-01-05T00:00:00Z"), 2)
    assertStarts(rows, "2026-01-05T09:00:00Z", "2026-01-06T09:00:00Z")
  }

  @Test fun dailySeriesStartedDecadesAgoStillShowsCurrentDates() = inUtc {
    val rows = CalendarFeed.parse(
        feed("decades-daily", "19700101T090000Z", "19700101T100000Z", "FREQ=DAILY"),
        utc("2026-01-05T00:00:00Z"), 2)
    assertStarts(rows, "2026-01-05T09:00:00Z", "2026-01-06T09:00:00Z")
  }

  @Test fun weeklySeriesOlderThanTwentyYearsStillShowsCurrentMondays() = inUtc {
    val rows = CalendarFeed.parse(
        feed("decades-weekly", "20030106T090000Z", "20030106T100000Z", "FREQ=WEEKLY"),
        utc("2026-01-05T00:00:00Z"), 8)
    assertStarts(rows, "2026-01-05T09:00:00Z", "2026-01-12T09:00:00Z")
  }

  @Test fun oldWeeklyByDaySeriesKeepsSortedCurrentWeekdays() = inUtc {
    val rows = CalendarFeed.parse(
        feed("old-byday", "20030106T090000Z", "20030106T100000Z",
            "FREQ=WEEKLY;BYDAY=FR,MO,WE"),
        utc("2026-01-05T00:00:00Z"), 7)
    assertStarts(rows, "2026-01-05T09:00:00Z", "2026-01-07T09:00:00Z",
        "2026-01-09T09:00:00Z")
  }

  @Test fun oldDailyFiniteCountIncludesExpiredAndExcludedOccurrences() = inUtc {
    // Jan 5 and Jan 6 are occurrences 370 and 371; excluding Jan 5 cannot add Jan 7.
    val rows = CalendarFeed.parse(
        feed("old-count-daily", "20250101T090000Z", "20250101T100000Z",
            "FREQ=DAILY;COUNT=371", "20260105T090000Z"),
        utc("2026-01-05T00:00:00Z"), 7)
    assertStarts(rows, "2026-01-06T09:00:00Z")
  }

  @Test fun oldWeeklyByDayCountPreservesHistoricalGenerationOrdinal() = inUtc {
    // Exactly 1200 weeks precede Jan 5: 3600 generated MO/WE/FR occurrences.
    // COUNT=3602 includes Monday and Wednesday only; excluding Wednesday does not add Friday.
    val rows = CalendarFeed.parse(
        feed("old-count-byday", "20030106T090000Z", "20030106T100000Z",
            "FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=3602", "20260107T090000Z"),
        utc("2026-01-05T00:00:00Z"), 8)
    assertStarts(rows, "2026-01-05T09:00:00Z")
  }

  @Test fun expiredFiniteCountsNeverRestartAtCurrentWindow() = inUtc {
    assertTrue(CalendarFeed.parse(
        feed("expired-daily-count", "20250101T090000Z", "20250101T100000Z",
            "FREQ=DAILY;COUNT=369"),
        utc("2026-01-05T00:00:00Z"), 7).isEmpty())
    assertTrue(CalendarFeed.parse(
        feed("expired-weekly-count", "20030106T090000Z", "20030106T100000Z",
            "FREQ=WEEKLY;COUNT=1200"),
        utc("2026-01-05T00:00:00Z"), 8).isEmpty())
  }

  @Test fun oldSeriesUntilIncludesItsExactLastStartWithoutExtendingIt() = inUtc {
    val rows = CalendarFeed.parse(
        feed("old-until", "20250101T090000Z", "20250101T100000Z",
            "FREQ=DAILY;UNTIL=20260106T090000Z"),
        utc("2026-01-05T00:00:00Z"), 7)
    assertStarts(rows, "2026-01-05T09:00:00Z", "2026-01-06T09:00:00Z")
  }

  @Test fun untilBeforeCurrentWindowDoesNotReviveExpiredSeries() = inUtc {
    assertTrue(CalendarFeed.parse(
        feed("expired-until", "20250101T090000Z", "20250101T100000Z",
            "FREQ=DAILY;UNTIL=20260104T090000Z"),
        utc("2026-01-05T00:00:00Z"), 7).isEmpty())
  }

  @Test fun oldOverlappingMultiDayEventsRetainStartsBeforeNow() = inUtc {
    val rows = CalendarFeed.parse(
        feed("old-overlap", "20250101T090000Z", "20250104T100000Z", "FREQ=DAILY"),
        utc("2026-01-05T12:00:00Z"), 1)
    assertStarts(rows, "2026-01-03T09:00:00Z", "2026-01-04T09:00:00Z",
        "2026-01-05T09:00:00Z", "2026-01-06T09:00:00Z")
    assertEquals(listOf("2026-01-06T10:00:00Z", "2026-01-07T10:00:00Z",
        "2026-01-08T10:00:00Z", "2026-01-09T10:00:00Z").map(::utc),
        rows.map { it.endMillis })
  }

  @Test fun recentSeriesAndNonRecurringEventsKeepExistingWindowFiltering() = inUtc {
    val now = utc("2026-01-05T00:00:00Z")
    assertStarts(CalendarFeed.parse(
        feed("recent-control", "20260105T090000Z", "20260105T100000Z",
            "FREQ=DAILY;COUNT=3"), now, 2),
        "2026-01-05T09:00:00Z", "2026-01-06T09:00:00Z")
    assertStarts(CalendarFeed.parse(
        feed("single-future-control", "20260106T090000Z", "20260106T100000Z"), now, 2),
        "2026-01-06T09:00:00Z")
    assertTrue(CalendarFeed.parse(
        feed("single-expired-control", "20250101T090000Z", "20250101T100000Z"), now, 2)
        .isEmpty())
  }

  @Test fun oldIntervalSeriesIncludesExactHorizonAndNoLaterStart() = inUtc {
    // Jan 5 is exactly 1200 weeks after the original Monday, on a two-week interval.
    val rows = CalendarFeed.parse(
        feed("old-interval-boundary", "20030106T090000Z", "20030106T100000Z",
            "FREQ=WEEKLY;INTERVAL=2"),
        utc("2026-01-05T09:00:00Z"), 0)
    assertStarts(rows, "2026-01-05T09:00:00Z")
  }

  @Test fun monthlySeriesOlderThanThirtyYearsStillShowsCurrentDates() = inUtc {
    val rows = CalendarFeed.parse(
        feed("decades-monthly", "19900115T090000Z", "19900115T100000Z", "FREQ=MONTHLY"),
        utc("2026-01-01T00:00:00Z"), 60)
    assertStarts(rows, "2026-01-15T09:00:00Z", "2026-02-15T09:00:00Z")
  }

  @Test fun yearlySeriesOlderThanFourCenturiesStillShowsCurrentDates() = inUtc {
    val rows = CalendarFeed.parse(
        feed("centuries-yearly", "16000115T090000Z", "16000115T100000Z", "FREQ=YEARLY"),
        utc("2026-01-15T09:00:00Z"), 365)
    assertStarts(rows, "2026-01-15T09:00:00Z", "2027-01-15T09:00:00Z")
  }

  @Test fun recentMonthlyAndYearlySeriesKeepExistingDayClamping() = inUtc {
    assertStarts(CalendarFeed.parse(
        feed("recent-monthly-clamp", "20240131T090000Z", "20240131T100000Z",
            "FREQ=MONTHLY;COUNT=3"), utc("2024-01-01T00:00:00Z"), 90),
        "2024-01-31T09:00:00Z", "2024-02-29T09:00:00Z", "2024-03-29T09:00:00Z")
    assertStarts(CalendarFeed.parse(
        feed("recent-yearly-clamp", "20200229T090000Z", "20200229T100000Z",
            "FREQ=YEARLY;COUNT=5"), utc("2024-01-01T00:00:00Z"), 60),
        "2024-02-28T09:00:00Z")
  }

  @Test fun oldMonthlyAndYearlySeriesRetainEarlierDayClamping() = inUtc {
    // Once a generated date clamps, later leap months/years do not restore the original day.
    assertStarts(CalendarFeed.parse(
        feed("old-monthly-clamp", "19900131T090000Z", "19900131T100000Z",
            "FREQ=MONTHLY"), utc("2026-01-01T00:00:00Z"), 60),
        "2026-01-28T09:00:00Z", "2026-02-28T09:00:00Z")
    assertStarts(CalendarFeed.parse(
        feed("old-yearly-clamp", "16000229T090000Z", "16000229T100000Z",
            "FREQ=YEARLY"), utc("2026-01-01T00:00:00Z"), 60),
        "2026-02-28T09:00:00Z")
  }

  private fun assertStarts(rows: List<CalendarFeed.Event>, vararg expected: String) {
    assertEquals(expected.map(::utc), rows.map { it.startMillis })
  }

  private fun feed(title: String, start: String, end: String, rule: String? = null,
      exclusion: String? = null): String =
      (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "BEGIN:VEVENT", "UID:generated-$title",
          "SUMMARY:$title", "DTSTART:$start", "DTEND:$end") +
          listOfNotNull(rule?.let { "RRULE:$it" }, exclusion?.let { "EXDATE:$it" }) +
          listOf("END:VEVENT", "END:VCALENDAR")).joinToString("\r\n")

  private fun utc(value: String): Long = Instant.parse(value).toEpochMilli()

  private fun inUtc(block: () -> Unit) {
    val zone = TimeZone.getDefault()
    val locale = Locale.getDefault()
    val format = Locale.getDefault(Locale.Category.FORMAT)
    val display = Locale.getDefault(Locale.Category.DISPLAY)
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
      Locale.setDefault(Locale.US)
      block()
    } finally {
      TimeZone.setDefault(zone)
      Locale.setDefault(locale)
      Locale.setDefault(Locale.Category.FORMAT, format)
      Locale.setDefault(Locale.Category.DISPLAY, display)
    }
  }
}
