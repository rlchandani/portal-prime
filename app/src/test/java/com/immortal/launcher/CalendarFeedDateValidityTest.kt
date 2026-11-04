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
import org.junit.Test

class CalendarFeedDateValidityTest {
  @Test fun february30DoesNotBecomeMarch2() = rejectsStart("20230230", "2023-02-01T00:00:00Z")
  @Test fun leapYearFebruary30DoesNotBecomeMarch1() = rejectsStart("20240230", "2024-02-01T00:00:00Z")
  @Test fun nonLeapFebruary29DoesNotBecomeMarch1() = rejectsStart("20230229", "2023-02-01T00:00:00Z")
  @Test fun april31DoesNotBecomeMay1() = rejectsStart("20230431", "2023-04-01T00:00:00Z")
  @Test fun month13DoesNotBecomeNextJanuary() = rejectsStart("20231301", "2023-12-01T00:00:00Z")
  @Test fun dayZeroDoesNotBecomePreviousMonth() = rejectsStart("20230300", "2023-02-01T00:00:00Z")
  @Test fun dateSuffixDoesNotBecomeValidStart() = rejectsStart("20230301junk", "2023-02-01T00:00:00Z")

  @Test fun invalidEventDoesNotHideFollowingValidEvent() = inZone("UTC") {
    val events = CalendarFeed.parse(
        document(event("20230230", "Invalid") + event("20230303", "Valid")),
        millis("2023-02-01T00:00:00Z"))
    assertEquals(listOf("Valid"), events.map { it.title })
    assertEquals(millis("2023-03-03T00:00:00Z"), events.single().startMillis)
  }

  @Test fun invalidExdateDoesNotCancelMarch2Occurrence() = inZone("UTC") {
    val events = CalendarFeed.parse(
        document(event("20230301", extra = "RRULE:FREQ=DAILY;COUNT=3\nEXDATE;VALUE=DATE:20230230")),
        millis("2023-02-28T00:00:00Z"))
    assertEquals(
        listOf("2023-03-01T00:00:00Z", "2023-03-02T00:00:00Z", "2023-03-03T00:00:00Z").map(::millis),
        events.map { it.startMillis })
  }

  @Test fun invalidEndUsesExistingOneDayFallback() = inZone("UTC") {
    val events = CalendarFeed.parse(
        document(event("20230228", extra = "DTEND;VALUE=DATE:20230230")),
        millis("2023-02-01T00:00:00Z"))
    assertEquals(millis("2023-03-01T00:00:00Z"), events.single().endMillis)
  }

  @Test fun validLeapDayRemainsAllDay() = inZone("UTC") {
    val events = CalendarFeed.parse(document(event("20240229")), millis("2024-02-01T00:00:00Z"))
    assertEquals(
        CalendarFeed.Event(millis("2024-02-29T00:00:00Z"), millis("2024-03-01T00:00:00Z"), "Fixture", true),
        events.single())
  }

  @Test fun bareDateRemainsSupported() = inZone("UTC") {
    val events = CalendarFeed.parse(
        document("BEGIN:VEVENT\nSUMMARY:Fixture\nDTSTART:20230301\nEND:VEVENT\n"),
        millis("2023-02-01T00:00:00Z"))
    assertEquals(millis("2023-03-01T00:00:00Z"), events.single().startMillis)
  }

  @Test fun validDateWithMidnightGapKeepsLocalConversion() = inZone("America/Sao_Paulo") {
    val events = CalendarFeed.parse(document(event("20181104")), millis("2018-11-01T00:00:00Z"))
    assertEquals(millis("2018-11-04T03:00:00Z"), events.single().startMillis)
    assertEquals(millis("2018-11-05T03:00:00Z"), events.single().endMillis)
  }

  @Test fun timedDstGapKeepsExistingInterpretation() = inZone("UTC") {
    val events = CalendarFeed.parse(
        document("BEGIN:VEVENT\nSUMMARY:Fixture\nDTSTART;TZID=America/New_York:20240310T023000\nEND:VEVENT\n"),
        millis("2024-03-01T00:00:00Z"))
    assertEquals(millis("2024-03-10T07:30:00Z"), events.single().startMillis)
  }

  private fun rejectsStart(value: String, now: String) = inZone("UTC") {
    assertEquals(emptyList<CalendarFeed.Event>(), CalendarFeed.parse(document(event(value)), millis(now)))
  }

  private fun event(date: String, title: String = "Fixture", extra: String = "") =
      "BEGIN:VEVENT\nSUMMARY:$title\nDTSTART;VALUE=DATE:$date\n$extra\nEND:VEVENT\n"

  private fun document(events: String) = "BEGIN:VCALENDAR\n${events}END:VCALENDAR\n"
  private fun millis(value: String) = Instant.parse(value).toEpochMilli()

  private fun inZone(zone: String, block: () -> Unit) {
    val previousZone = TimeZone.getDefault()
    val previousLocale = Locale.getDefault()
    try {
      TimeZone.setDefault(TimeZone.getTimeZone(zone))
      Locale.setDefault(Locale.US)
      block()
    } finally {
      Locale.setDefault(previousLocale)
      TimeZone.setDefault(previousZone)
    }
  }
}
