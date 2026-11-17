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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure briefing builder: sections and spoken text for the shapes a real morning takes. */
class BriefingBuilderTest {
  private val utc = TimeZone.getTimeZone("UTC")
  private val hour = 3_600_000L
  /** Tuesday 17 November 2026, 07:05 UTC. */
  private val now = 1_794_899_100_000L
  private val midnight = now - 7 * hour - 5 * 60_000L

  private fun inputs(
      weather: BriefingPart<BriefingWeather?> = BriefingPart.Ready(BriefingWeather(12, 2, 15, 6, 20)),
      events: BriefingPart<List<CalendarFeed.Event>?> = BriefingPart.Ready(emptyList()),
      list: List<String> = emptyList(),
      timers: List<BriefingTimer> = emptyList(),
      routines: List<BriefingRoutine> = emptyList(),
      speak: Boolean = true,
      quiet: Boolean = false,
  ) = BriefingInputs(nowMs = now, zone = utc, locale = Locale.US, use24Hour = true, greeting = "Good morning",
      name = "Sam", weather = weather, events = events, listItems = list, timers = timers, routines = routines,
      speakOn = speak, quiet = quiet)

  private fun event(startHour: Double, title: String, allDay: Boolean = false, lengthHours: Long = 1) =
      CalendarFeed.Event(midnight + (startHour * hour).toLong(), midnight + (startHour * hour).toLong() + lengthHours * hour,
          title, allDay)

  private fun Briefing.section(kind: BriefingSectionKind) = sections.firstOrNull { it.kind == kind }

  @Test fun greetingCarriesNameTimeAndDay() {
    val b = BriefingBuilder.build(inputs())
    assertEquals("Good morning, Sam", b.title)
    assertEquals("7:05 · Tuesday, November 17", b.subtitle)
  }

  @Test fun weatherShowsNowHighLowAndRain() {
    val w = BriefingBuilder.build(inputs()).section(BriefingSectionKind.WEATHER)!!
    assertEquals(listOf("12° now · Partly cloudy", "High 15° · Low 6°", "20% chance of rain"), w.lines)
    assertFalse(w.muted)
  }

  @Test fun noWeather_saysSoAndIsLeftOutOfTheSpeech() {
    val b = BriefingBuilder.build(inputs(weather = BriefingPart.Ready(null)))
    val w = b.section(BriefingSectionKind.WEATHER)!!
    assertTrue(w.muted)
    assertEquals(listOf("Weather isn't available right now"), w.lines)
    assertFalse(b.spoken!!.contains("degrees"))
    assertTrue(b.complete)
  }

  @Test fun weatherWithoutForecast_skipsHighLowAndRain() {
    val w = BriefingBuilder.build(inputs(weather = BriefingPart.Ready(BriefingWeather(3, 61)))).section(BriefingSectionKind.WEATHER)!!
    assertEquals(listOf("3° now · Rain"), w.lines)
  }

  @Test fun emptyCalendar_saysNothingToday() {
    val b = BriefingBuilder.build(inputs())
    assertEquals(listOf("Nothing on the calendar today"), b.section(BriefingSectionKind.CALENDAR)!!.lines)
    assertTrue(b.spoken!!.contains("Nothing on the calendar today."))
  }

  @Test fun noCalendarAdded_pointsToSettings() {
    val c = BriefingBuilder.build(inputs(events = BriefingPart.Ready(null))).section(BriefingSectionKind.CALENDAR)!!
    assertTrue(c.muted)
    assertTrue(c.lines.single().startsWith("No calendar added"))
  }

  @Test fun calendar_showsNextThreeOfTodayOnly_allDayFirst() {
    val events = listOf(
        event(6.0, "Already over"), event(9.5, "Dentist"), event(0.0, "Bin day", allDay = true, lengthHours = 24),
        event(13.0, "Lunch"), event(18.0, "Football"), event(30.0, "Tomorrow's thing"))
    val c = BriefingBuilder.build(inputs(events = BriefingPart.Ready(events))).section(BriefingSectionKind.CALENDAR)!!
    assertEquals(listOf("All day  Bin day", "9:30  Dentist", "13:00  Lunch"), c.lines)
  }

  @Test fun eventUnderWayNow_stillShows() {
    val c = BriefingBuilder.build(inputs(events = BriefingPart.Ready(listOf(event(6.5, "Run")))))
        .section(BriefingSectionKind.CALENDAR)!!
    assertEquals(listOf("6:30  Run"), c.lines)
  }

  @Test fun longList_countsAllNamesFirstThree() {
    val items = (1..12).map { "Item $it" }
    val b = BriefingBuilder.build(inputs(list = items))
    assertEquals(listOf("12 items to get", "Item 1", "Item 2", "Item 3", "and 9 more"),
        b.section(BriefingSectionKind.LIST)!!.lines)
    assertTrue(b.spoken!!.contains("The shared list has 12 items: Item 1, Item 2 and Item 3, and more."))
  }

  @Test fun shortList_hasNoMoreLine() {
    val l = BriefingBuilder.build(inputs(list = listOf("Milk"))).section(BriefingSectionKind.LIST)!!
    assertEquals(listOf("1 item to get", "Milk"), l.lines)
  }

  @Test fun emptyList_isMuted() {
    assertTrue(BriefingBuilder.build(inputs()).section(BriefingSectionKind.LIST)!!.muted)
  }

  @Test fun timers_onlyWhenRunning_soonestFirst() {
    assertNull(BriefingBuilder.build(inputs()).section(BriefingSectionKind.TIMERS))
    val t = BriefingBuilder.build(inputs(timers = listOf(
        BriefingTimer("Bread", "Kitchen", 65 * 60_000L, paused = false),
        BriefingTimer("Eggs", "Kitchen", 4 * 60_000L - 1, paused = false),
        BriefingTimer("Tea", "", 30_000L, paused = true))))
    assertEquals(listOf("Tea · Paused, less than a minute left", "Eggs · 4 min left · Kitchen",
        "Bread · 1 hr 5 min left · Kitchen"), t.section(BriefingSectionKind.TIMERS)!!.lines)
    assertTrue(t.spoken!!.contains("3 timers are set"))
  }

  @Test fun routine_firstOfTheMorningOnly() {
    val routines = listOf(BriefingRoutine("Bedtime", 19 * 60), BriefingRoutine("School run", 8 * 60),
        BriefingRoutine("Breakfast", 7 * 60 + 30))
    val b = BriefingBuilder.build(inputs(routines = routines))
    assertEquals(listOf("Breakfast at 7:30"), b.section(BriefingSectionKind.ROUTINE)!!.lines)
    assertNull(BriefingBuilder.build(inputs(routines = listOf(routines[0]))).section(BriefingSectionKind.ROUTINE))
  }

  @Test fun quietHours_neverSpeak() {
    val b = BriefingBuilder.build(inputs(quiet = true, list = listOf("Milk")))
    assertNull(b.spoken)
    assertEquals(3, b.sections.size) // weather, calendar, list: the card itself is unchanged
  }

  @Test fun readAloudOff_neverSpeaks() {
    assertNull(BriefingBuilder.build(inputs(speak = false)).spoken)
  }

  @Test fun spokenSummary_readsNaturally() {
    val b = BriefingBuilder.build(inputs(events = BriefingPart.Ready(listOf(event(9.5, "Dentist")))))
    assertEquals("Good morning, Sam. It's 7:05. It's 12 degrees and partly cloudy, with a high of 15 and a low of 6." +
        " Chance of rain: 20 percent. One thing on the calendar today, starting with Dentist at 9:30.", b.spoken)
  }

  @Test fun stillLoading_showsCheckingAndIsIncomplete() {
    val b = BriefingBuilder.build(inputs(weather = BriefingPart.Loading, events = BriefingPart.Loading))
    assertFalse(b.complete)
    assertEquals(listOf("Checking the weather…"), b.section(BriefingSectionKind.WEATHER)!!.lines)
    assertEquals(listOf("Checking your calendar…"), b.section(BriefingSectionKind.CALENDAR)!!.lines)
    assertNotNull(b.spoken) // the host waits for complete (or a timeout) before reading it
  }

  @Test fun blankGreeting_fallsBackAndNoNameDropsComma() {
    assertEquals("Good morning", BriefingCopy.greeting("  ", ""))
    assertEquals("Morning, Ana", BriefingCopy.greeting("Morning!", " Ana "))
  }

  @Test fun twelveHourClock() {
    assertEquals("7:30 AM", BriefingCopy.clockOfMinute(7 * 60 + 30, use24Hour = false))
    assertEquals("12:00 PM", BriefingCopy.clockOfMinute(12 * 60, use24Hour = false))
    assertEquals("12:15 AM", BriefingCopy.clockOfMinute(15, use24Hour = false))
  }
}
