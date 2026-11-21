/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

/** Morning-briefing wording: every weather band and its edges, clocks, durations, lists and greetings. */
class BriefingCopyCoverageTest {
  private val utc = TimeZone.getTimeZone("UTC")

  @Test fun everyWeatherBandAndItsEdges() {
    val expected = mapOf(
        0 to "Clear", 1 to "Mostly clear", 2 to "Partly cloudy", 3 to "Cloudy",
        45 to "Foggy", 48 to "Foggy", 51 to "Drizzle", 57 to "Drizzle", 61 to "Rain", 67 to "Rain",
        71 to "Snow", 77 to "Snow", 80 to "Showers", 82 to "Showers", 85 to "Snow showers", 86 to "Snow showers",
        95 to "Thunderstorms", 99 to "Thunderstorms",
    )
    expected.forEach { (code, text) -> assertEquals("code $code", text, BriefingCopy.condition(code)) }
  }

  @Test fun codesBetweenAndOutsideTheBandsAreMixedWeather() {
    listOf(-1, 4, 44, 49, 50, 58, 60, 68, 70, 78, 79, 83, 84, 87, 94, 100, Int.MAX_VALUE).forEach {
      assertEquals("code $it", "Mixed weather", BriefingCopy.condition(it))
    }
  }

  @Test fun clockFollowsTheZoneAndHourCycle() {
    val ms = 13L * 3_600_000 + 5 * 60_000 // 13:05 UTC on 1 Jan 1970
    assertEquals("13:05", BriefingCopy.clock(ms, utc, Locale.US, use24Hour = true))
    assertEquals("1:05 PM", BriefingCopy.clock(ms, utc, Locale.US, use24Hour = false))
    assertEquals("8:05", BriefingCopy.clock(ms, TimeZone.getTimeZone("GMT-05:00"), Locale.US, use24Hour = true))
  }

  @Test fun clockOfMinuteHandlesMidnightNoonAndTheLastMinute() {
    assertEquals("12:00 AM", BriefingCopy.clockOfMinute(0, use24Hour = false))
    assertEquals("0:00", BriefingCopy.clockOfMinute(0, use24Hour = true))
    assertEquals("12:00 PM", BriefingCopy.clockOfMinute(BriefingCopy.NOON_MINUTE, use24Hour = false))
    assertEquals("11:59 AM", BriefingCopy.clockOfMinute(BriefingCopy.NOON_MINUTE - 1, use24Hour = false))
    assertEquals("11:59 PM", BriefingCopy.clockOfMinute(24 * 60 - 1, use24Hour = false))
    assertEquals("23:59", BriefingCopy.clockOfMinute(24 * 60 - 1, use24Hour = true))
  }

  @Test fun dayLineNamesTheWeekdayInTheZone() {
    assertEquals("Thursday, January 1", BriefingCopy.dayLine(0, utc, Locale.US))
    assertEquals("Wednesday, December 31", BriefingCopy.dayLine(0, TimeZone.getTimeZone("GMT-01:00"), Locale.US))
  }

  @Test fun remainingRoundsUpAndSplitsHours() {
    assertEquals("Less than a minute left", BriefingCopy.remaining(-5))
    assertEquals("Less than a minute left", BriefingCopy.remaining(59_999))
    assertEquals("1 min left", BriefingCopy.remaining(60_000))
    assertEquals("2 min left", BriefingCopy.remaining(60_001))
    assertEquals("1 hr left", BriefingCopy.remaining(3_600_000))
    assertEquals("2 hr left", BriefingCopy.remaining(7_200_000))
    assertEquals("1 hr 5 min left", BriefingCopy.remaining(65 * 60_000L))
  }

  @Test fun spokenRemainingUsesWordsAndPlurals() {
    assertEquals("less than a minute", BriefingCopy.spokenRemaining(0))
    assertEquals("1 minute", BriefingCopy.spokenRemaining(60_000))
    assertEquals("4 minutes", BriefingCopy.spokenRemaining(4 * 60_000L))
    assertEquals("1 hour", BriefingCopy.spokenRemaining(3_600_000))
    assertEquals("2 hours", BriefingCopy.spokenRemaining(7_200_000))
    assertEquals("1 hour and 1 minute", BriefingCopy.spokenRemaining(61 * 60_000L))
    assertEquals("2 hours and 5 minutes", BriefingCopy.spokenRemaining(125 * 60_000L))
  }

  @Test fun pluralForIntAndLong() {
    assertEquals("0 items", BriefingCopy.plural(0, "item"))
    assertEquals("1 item", BriefingCopy.plural(1, "item"))
    assertEquals("3 items", BriefingCopy.plural(3L, "item"))
  }

  @Test fun joinNamesForNoneOneTwoAndMany() {
    assertEquals("", BriefingCopy.joinNames(emptyList()))
    assertEquals("milk", BriefingCopy.joinNames(listOf("milk")))
    assertEquals("milk and eggs", BriefingCopy.joinNames(listOf("milk", "eggs")))
    assertEquals("milk, eggs and bread", BriefingCopy.joinNames(listOf("milk", "eggs", "bread")))
  }

  @Test fun greetingTrimsPunctuationAndFallsBack() {
    assertEquals("Good morning, Sam", BriefingCopy.greeting("Good morning!", "  Sam "))
    assertEquals("Hello", BriefingCopy.greeting("  Hello. ", ""))
    assertEquals("Good morning", BriefingCopy.greeting("  !!.", "  "))
    assertEquals("Good morning, Ana", BriefingCopy.greeting("", "Ana"))
  }

  @Test fun personalNoteNamesThePerson() {
    assertEquals("Reminders are read aloud only when Ana is alone", BriefingCopy.personalNote("Ana"))
  }
}
