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

class CalendarFeedTimedValidityTest {
  @Test fun february30DoesNotBecomeMarch2() =
      rejectsStart("20260230T090000Z", "2026-02-01T00:00:00Z")
  @Test fun hour24DoesNotBecomeNextDay() =
      rejectsStart("20260301T240000Z", "2026-03-01T00:00:00Z")
  @Test fun minute60DoesNotBecomeNextHour() =
      rejectsStart("20260301T096000Z", "2026-03-01T00:00:00Z")
  @Test fun second61DoesNotBecomeNextMinute() =
      rejectsStart("20260301T090061Z", "2026-03-01T00:00:00Z")
  @Test fun trailingJunkDoesNotBecomeValidStart() =
      rejectsStart("20260301T090000junkZ", "2026-03-01T00:00:00Z")

  @Test fun invalidExdateDoesNotCancelMarch2Occurrence() = inUtc {
    val events = CalendarFeed.parse(
        document("DTSTART:20260301T090000Z",
            "RRULE:FREQ=DAILY;COUNT=3\nEXDATE:20260230T090000Z"),
        millis("2026-03-01T00:00:00Z"))
    assertEquals(
        listOf("2026-03-01T09:00:00Z", "2026-03-02T09:00:00Z", "2026-03-03T09:00:00Z").map(::millis),
        events.map { it.startMillis })
  }

  @Test fun invalidEndUsesExistingZeroDurationFallback() = inUtc {
    val event = CalendarFeed.parse(
        document("DTSTART:20260228T090000Z", "DTEND:20260230T090000Z"),
        millis("2026-02-01T00:00:00Z")).single()
    assertEquals(event.startMillis, event.endMillis)
  }

  @Test fun validUtcRemainsSupported() =
      acceptsStart("DTSTART:20260301T090000Z", "2026-03-01T00:00:00Z", "2026-03-01T09:00:00Z")

  @Test fun validTimezoneRemainsSupported() =
      acceptsStart("DTSTART;TZID=Asia/Kolkata:20260301T090000",
          "2026-03-01T00:00:00Z", "2026-03-01T03:30:00Z")

  @Test fun timedDstGapKeepsExistingInterpretation() =
      acceptsStart("DTSTART;TZID=America/New_York:20260308T023000",
          "2026-03-01T00:00:00Z", "2026-03-08T07:30:00Z")

  @Test fun leapSecondKeepsExistingInterpretation() =
      acceptsStart("DTSTART:20161231T235960Z", "2016-12-31T00:00:00Z", "2017-01-01T00:00:00Z")

  private fun rejectsStart(value: String, now: String) = inUtc {
    assertEquals(emptyList<CalendarFeed.Event>(),
        CalendarFeed.parse(document("DTSTART:$value"), millis(now)))
  }

  private fun acceptsStart(start: String, now: String, expected: String) = inUtc {
    val events = CalendarFeed.parse(document(start), millis(now))
    assertEquals(millis(expected), events.single().startMillis)
  }

  private fun document(start: String, extra: String = "") =
      "BEGIN:VCALENDAR\nBEGIN:VEVENT\nSUMMARY:Fixture\n$start\n$extra\nEND:VEVENT\nEND:VCALENDAR\n"

  private fun millis(value: String) = Instant.parse(value).toEpochMilli()

  private fun inUtc(block: () -> Unit) {
    val previousZone = TimeZone.getDefault()
    val previousLocale = Locale.getDefault()
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
      Locale.setDefault(Locale.US)
      block()
    } finally {
      Locale.setDefault(previousLocale)
      TimeZone.setDefault(previousZone)
    }
  }
}
