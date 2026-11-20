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

/** Who's there? personal briefing: whose it is, what is theirs, and what stays private. */
class BriefingPersonalTest {
  private val utc = TimeZone.getTimeZone("UTC")
  private val hour = 3_600_000L
  /** Tuesday 17 November 2026, 07:05 UTC. */
  private val now = 1_794_899_100_000L
  private val midnight = now - 7 * hour - 5 * 60_000L

  private val maya = WhosTherePerson("p1", "Maya", WhosThereRole.ADULT, 0, 1L)
  private val dev = WhosTherePerson("p2", "Dev", WhosThereRole.KID, 2, 1L)

  private fun ui(view: WhosThereView, hidePrivate: Boolean = false) =
      WhosThereUi(view = view, people = listOf(maya, dev), hidePrivate = hidePrivate)

  private fun event(startHour: Double, title: String, private: Boolean = false, allDay: Boolean = false) = PersonalEvent(
      midnight + (startHour * hour).toLong(), midnight + (startHour * hour).toLong() + (if (allDay) 24 else 1) * hour, title, allDay, null,
      EventOwner.MINE, private)

  private fun personal(
      hide: Boolean = false,
      events: BriefingPart<List<PersonalEvent>?> = BriefingPart.Ready(listOf(event(8.5, "Stand-up"), event(12.5, "Lunch with Priya"))),
      reminders: List<String> = listOf("Clinic call", "Gift", "Passport"),
  ) = BriefingPersonalInputs("Maya", hide, events, reminders)

  private fun inputs(p: BriefingPersonalInputs?, speak: Boolean = true) = BriefingInputs(nowMs = now, zone = utc,
      locale = Locale.US, use24Hour = true, greeting = "Good morning", name = "The Rao family",
      weather = BriefingPart.Ready(BriefingWeather(14, 0, 16, 8, 10)),
      events = BriefingPart.Ready(listOf(CalendarFeed.Event(midnight + 9 * hour, midnight + 10 * hour, "Household thing", false))),
      listItems = listOf("Milk", "Maya: passport"), speakOn = speak, personal = p)

  private fun Briefing.section(kind: BriefingSectionKind) = sections.firstOrNull { it.kind == kind }

  @Test fun onlyARecognisedAdultsOwnHomeMakesItPersonal() {
    assertEquals(maya, BriefingPersonalPolicy.owner(ui(WhosThereView.Personal("p1", othersNear = false))))
    assertEquals(maya, BriefingPersonalPolicy.owner(ui(WhosThereView.Personal("p1", othersNear = true))))
    assertNull("a child's Home", BriefingPersonalPolicy.owner(ui(WhosThereView.Personal("p2", othersNear = false))))
    assertNull("unsure: is this you?", BriefingPersonalPolicy.owner(ui(WhosThereView.Personal("p1", false, confirm = true))))
    assertNull("two people: the family view", BriefingPersonalPolicy.owner(ui(WhosThereView.Family(listOf("p1", "p2")))))
    assertNull(BriefingPersonalPolicy.owner(ui(WhosThereView.Shared)))
    assertNull("someone else's Home is showing now", BriefingPersonalPolicy.owner(ui(WhosThereView.Personal("p1", false)), "p9"))
  }

  @Test fun privateItemsHideWhenWhosThereSaysSoOrOnceTheHoldEnds() {
    val alone = ui(WhosThereView.Personal("p1", false), hidePrivate = false)
    assertFalse(BriefingPersonalPolicy.hidePrivate(alone, holdOver = false))
    assertTrue("no one re-checks who's here while the briefing is open", BriefingPersonalPolicy.hidePrivate(alone, holdOver = true))
    assertTrue(BriefingPersonalPolicy.hidePrivate(ui(WhosThereView.Personal("p1", true), hidePrivate = true), holdOver = false))
  }

  @Test fun aloneTheBriefingIsTheirsCalendarAndReminders() {
    val b = BriefingBuilder.build(inputs(personal()))
    assertEquals("Good morning, Maya", b.title)
    assertTrue(b.subtitle.startsWith("Your briefing · 7:05 · "))
    assertEquals(listOf("8:30  Stand-up", "12:30  Lunch with Priya"), b.section(BriefingSectionKind.CALENDAR)!!.lines)
    assertEquals(listOf("3 reminders", "Clinic call", "Gift", "Passport"), b.section(BriefingSectionKind.REMINDERS)!!.lines)
    assertNull("the household list isn't the personal briefing", b.section(BriefingSectionKind.LIST))
    assertEquals("Reminders are read aloud only when Maya is alone", b.note)
    assertTrue(b.complete)
    val spoken = b.spoken!!
    assertTrue(spoken, spoken.startsWith("Good morning, Maya."))
    assertTrue(spoken, "You have 3 reminders: Clinic call, Gift and Passport." in spoken)
    assertFalse(spoken, "Household thing" in spoken)
  }

  @Test fun withSomeoneElseNearNoPrivateItemShowsOrIsSpoken() {
    val events = BriefingPart.Ready(listOf(event(8.5, "Therapy", private = true), event(12.5, "Lunch with Priya")))
    val b = BriefingBuilder.build(inputs(personal(hide = true, events = events)))
    assertEquals(listOf("8:30  Private", "12:30  Lunch with Priya"), b.section(BriefingSectionKind.CALENDAR)!!.lines)
    val reminders = b.section(BriefingSectionKind.REMINDERS)!!
    assertTrue(reminders.muted)
    assertEquals(listOf("3 reminders, private", "Hidden while others may be near"), reminders.lines)
    val spoken = b.spoken!!
    listOf("Therapy", "Clinic", "Gift", "Passport", "reminder").forEach { assertFalse("$it in: $spoken", it in spoken) }
    assertTrue(spoken, "starting with something private at 8:30" in spoken)
  }

  @Test fun loadingAndEmptyStatesAreTheirs() {
    val loading = BriefingBuilder.build(inputs(personal(events = BriefingPart.Loading)))
    assertEquals(listOf("Checking your calendar…"), loading.section(BriefingSectionKind.CALENDAR)!!.lines)
    assertFalse("speech waits for their calendar", loading.complete)
    val none = BriefingBuilder.build(inputs(personal(events = BriefingPart.Ready(emptyList()), reminders = emptyList())))
    assertEquals(listOf("Nothing on your calendar today"), none.section(BriefingSectionKind.CALENDAR)!!.lines)
    assertEquals(listOf("Nothing on your list"), none.section(BriefingSectionKind.REMINDERS)!!.lines)
    val noCalendar = BriefingBuilder.build(inputs(personal(events = BriefingPart.Ready(null))))
    assertTrue(noCalendar.section(BriefingSectionKind.CALENDAR)!!.muted)
  }

  @Test fun pastEventsDropAndAllDayLeads() {
    val events = BriefingPart.Ready(listOf(event(6.0, "Gym"), event(9.0, "Review"), event(0.0, "Holiday", allDay = true)))
    val lines = BriefingBuilder.build(inputs(personal(events = events))).section(BriefingSectionKind.CALENDAR)!!.lines
    assertEquals(listOf("All day  Holiday", "9:00  Review"), lines)
  }

  @Test fun withoutWhosThereTheHouseholdBriefingIsUnchanged() {
    val b = BriefingBuilder.build(inputs(null))
    assertEquals("Good morning, The Rao family", b.title)
    assertNotNull(b.section(BriefingSectionKind.LIST))
    assertNull(b.section(BriefingSectionKind.REMINDERS))
    assertNull(b.note)
  }

  @Test fun readAloudOffStaysSilent() {
    assertNull(BriefingBuilder.build(inputs(personal(), speak = false)).spoken)
  }
}
