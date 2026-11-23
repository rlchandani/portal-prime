/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import com.immortal.launcher.CalendarFeed
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `hearthpane.calendar`: the household calendar for a day, the week or the weekend, worked out on the Portal's clock. */
class HearthpaneCalendarTest {
  private val hour = 3_600_000L
  private val day = 24 * hour
  /** Tuesday 17 November 2026, 07:05 UTC. */
  private val now = 1_794_899_100_000L
  /** Tuesday 17 November 2026, 00:00 UTC. */
  private val midnight = now - 7 * hour - 5 * 60_000L
  private var feed: CalendarFeed.Result? = CalendarFeed.Result(emptyList())
  private var hidden = false
  private var use24 = false
  private val tool = HearthpaneCalendar(feed = { feed }, hidden = { hidden }, now = { now },
      zone = { TimeZone.getTimeZone("UTC") }, locale = { Locale.US }, use24 = { use24 })

  private fun event(start: Long, title: String, hours: Long = 1, allDay: Boolean = false, location: String? = null) =
      CalendarFeed.Event(start, start + hours * hour, title, allDay, location)

  private fun events(vararg e: CalendarFeed.Event) { feed = CalendarFeed.Result(e.toList()) }

  private fun call(vararg kv: Pair<String, Any>) = tool.invoke(JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }.toString())

  @Test fun todayIsTheDefaultAndListsWhatIsStillToComeAllDayFirst() {
    events(event(midnight + 6 * hour, "Gym"), event(midnight + 18 * hour, "Dinner with Sam"),
        event(midnight + 9 * hour, "Dentist", location = "Main St"), event(midnight, "Mum's birthday", hours = 24, allDay = true),
        event(midnight + day + 9 * hour, "Tomorrow thing"))
    val r = call()
    assertTrue(r.getBoolean("ok"))
    val titles = (0 until r.getJSONArray("events").length()).map { r.getJSONArray("events").getJSONObject(it).getString("title") }
    assertEquals(listOf("Mum's birthday", "Dentist", "Dinner with Sam"), titles)
    assertEquals("Main St", r.getJSONArray("events").getJSONObject(1).getString("location"))
    assertEquals("Today you have 3 things: Mum's birthday all day, Dentist at 9:00 AM and Dinner with Sam at 6:00 PM.",
        r.getString("message"))
  }

  @Test fun anEventUnderWayStillCounts() {
    events(event(now - hour, "Standup", hours = 2))
    assertEquals("Today you have 1 thing: Standup at 6:05 AM.", call("day" to "today").getString("message"))
  }

  @Test fun tomorrowAndAWeekdayAreResolvedOnThePortalsClock() {
    events(event(midnight + day + 9 * hour, "Dentist"), event(midnight + 3 * day + 16 * hour + 30 * 60_000L, "Soccer"))
    assertEquals("Tomorrow you have 1 thing: Dentist at 9:00 AM.", call("day" to "Tomorrow").getString("message"))
    assertEquals("On Friday you have 1 thing: Soccer at 4:30 PM.", call("day" to "friday").getString("message"))
    // Today's own weekday name is today, not next week.
    events(event(midnight + 20 * hour, "Book club"))
    assertEquals("Today you have 1 thing: Book club at 8:00 PM.", call("day" to "Tuesday").getString("message"))
  }

  @Test fun twentyFourHourClockIsHonoured() {
    use24 = true
    events(event(midnight + day + 18 * hour, "Dinner"))
    assertEquals("Tomorrow you have 1 thing: Dinner at 18:00.", call("day" to "tomorrow").getString("message"))
  }

  @Test fun anEmptyDaySaysSo() {
    events(event(midnight + 2 * day, "Later"))
    assertEquals("Nothing on the calendar tomorrow.", call("day" to "tomorrow").getString("message"))
    assertTrue(call("day" to "tomorrow").getBoolean("ok"))
  }

  @Test fun theWeekNamesEachEventsDayAndCapsTheCount() {
    events(event(midnight + 9 * hour, "Dentist"), event(midnight + day + 10 * hour, "Call"),
        event(midnight + 3 * day + 16 * hour, "Soccer"), event(midnight + 8 * day, "Next week"))
    val r = call("day" to "this week")
    assertEquals("This week you have 3 things: today, Dentist at 9:00 AM; tomorrow, Call at 10:00 AM; " +
        "Friday, Soccer at 4:00 PM.", r.getString("message"))
    assertEquals("Friday", r.getJSONArray("events").getJSONObject(2).getString("day"))

    events(*(0 until 12).map { event(midnight + 8 * hour + it * hour / 2, "Slot $it") }.toTypedArray())
    val many = call("day" to "today")
    assertEquals(HearthpaneCalendar.MAX_EVENTS, many.getJSONArray("events").length())
    assertEquals(12 - HearthpaneCalendar.MAX_EVENTS, many.getInt("more"))
    assertTrue(many.getString("message"), many.getString("message").endsWith("and 4 more."))
  }

  @Test fun theWeekendIsTheComingSaturdayAndSunday() {
    events(event(midnight + 3 * day + 9 * hour, "Friday thing"), event(midnight + 4 * day + 10 * hour, "Market"),
        event(midnight + 5 * day + 11 * hour, "Brunch"), event(midnight + 6 * day + 9 * hour, "Monday thing"))
    assertEquals("This weekend you have 2 things: Saturday, Market at 10:00 AM; Sunday, Brunch at 11:00 AM.",
        call("day" to "weekend").getString("message"))
  }

  @Test fun aDateWithinTheFeedsHorizonWorksAndOthersAreRefused() {
    events(event(midnight + 27 * day + 12 * hour, "Party"))
    assertEquals("On Monday, December 14 you have 1 thing: Party at 12:00 PM.",
        call("date" to "2026-12-14").getString("message"))
    assertTrue(call("date" to "2026-11-01").getString("message").contains("only look ahead"))
    assertTrue(call("date" to "2027-06-01").getString("message").contains("45 days"))
    assertTrue(call("date" to "next tuesday-ish").has("error"))
    assertTrue(call("day" to "someday").has("error"))
  }

  @Test fun noCalendarGuestsAndAnUnreachableFeedEachSaySo() {
    feed = null
    assertFalse(call().getBoolean("ok"))
    assertTrue(call().getString("message").contains("No calendar"))

    feed = CalendarFeed.Result(listOf(event(midnight + 9 * hour, "Secret")))
    hidden = true
    val guest = call()
    assertFalse(guest.getBoolean("ok"))
    assertFalse(guest.toString().contains("Secret"))

    hidden = false
    feed = CalendarFeed.Result(emptyList(), unavailable = true)
    assertTrue(call().getString("message").contains("couldn't reach"))
  }

  @Test fun badArgumentsAndAFailingFeedAreErrorsNotCrashes() {
    assertTrue(tool.invoke("not json").has("error"))
    assertTrue(tool.invoke(null).getBoolean("ok"))
    val failing = HearthpaneCalendar(feed = { error("boom") }, hidden = { false }, now = { now },
        zone = { TimeZone.getTimeZone("UTC") }, locale = { Locale.US }, use24 = { false })
    assertEquals("calendar unavailable", failing.invoke("{}").getString("error"))
  }

  @Test fun titlesAreTrimmedToASpokenLength() {
    events(event(midnight + 9 * hour, "x".repeat(300)))
    val title = call().getJSONArray("events").getJSONObject(0).getString("title")
    assertEquals(HearthpaneCalendar.MAX_TITLE, title.length)
  }
}
