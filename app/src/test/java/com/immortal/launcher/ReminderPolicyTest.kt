/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.ReminderFixtures.NOW
import com.immortal.launcher.ReminderFixtures.arjun
import com.immortal.launcher.ReminderFixtures.bins
import com.immortal.launcher.ReminderFixtures.clinic
import com.immortal.launcher.ReminderFixtures.leela
import com.immortal.launcher.ReminderFixtures.maya
import com.immortal.launcher.ReminderFixtures.people
import com.immortal.launcher.ReminderFixtures.reminder
import com.immortal.launcher.ReminderFixtures.state
import com.immortal.launcher.ReminderFixtures.zone
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who sees which reminder, when one is due, and how it announces itself. */
class ReminderPolicyTest {
  private val hour = 3_600_000L

  // ---- visibility ----

  @Test fun aReminderBelongsByIdOrByNameWhenThisPortalDoesntKnowTheId() {
    assertTrue(ReminderVisibility.belongsTo(clinic, maya, people))
    assertFalse(ReminderVisibility.belongsTo(clinic, arjun, people))
    val fromOtherPortal = clinic.copy(person = "otherportal1", personName = "maya")
    assertTrue(ReminderVisibility.belongsTo(fromOtherPortal, maya, people))
    // A known id never falls back to the name.
    val arjunsIdMayasName = clinic.copy(person = arjun.id, personName = "Maya")
    assertFalse(ReminderVisibility.belongsTo(arjunsIdMayasName, maya, people))
    assertFalse(ReminderVisibility.belongsTo(bins, maya, people))
    assertNull(ReminderVisibility.ownerOf(bins, people))
    assertEquals("Home", ReminderVisibility.ownerLabel(bins, people))
    assertEquals("Maya", ReminderVisibility.ownerLabel(fromOtherPortal, people))
  }

  @Test fun mayasHomeShowsHerRemindersThenTheHouseholdsAndBlursOnlyPrivateOnesWhenOthersNear() {
    val alone = ReminderVisibility.homeCard(state, maya, people, hidePrivate = false)
    assertEquals(listOf("rclinic01", "rgift0001", "rmilk0001", "rbins0001"), alone.rows.map { it.reminder.id })
    assertTrue(alone.rows.none { it.redacted })
    assertTrue(alone.hasPrivate)
    assertEquals(4, alone.total)
    val near = ReminderVisibility.homeCard(state, maya, people, hidePrivate = true)
    assertEquals(listOf(true, true, false, false), near.rows.map { it.redacted })
    assertEquals(2, near.privateHidden)
    assertTrue(near.rows.none { it.reminder.id == "rbill0001" }) // Arjun's never shows on Maya's Home
  }

  @Test fun doneRemindersLeaveTheHomeAndTheCardIsCapped() {
    val s = ReminderState(state.reminders + (clinic.id to clinic.copy(done = true)))
    assertTrue(ReminderVisibility.homeCard(s, maya, people, false).rows.none { it.reminder.id == clinic.id })
    val many = ReminderState((1..9).associate { "rmany$it" to reminder("rmany$it", "x$it", maya) })
    assertEquals(ReminderVisibility.HOME_ROWS, ReminderVisibility.homeCard(many, maya, people, false).rows.size)
  }

  @Test fun aKidsReminderIsNeverPrivateEvenIfAPeerSaysSo() {
    val s = ReminderState(mapOf("rkid00001" to reminder("rkid00001", "Swim", leela, private = true)))
    val card = ReminderVisibility.homeCard(s, leela, people, hidePrivate = true)
    assertFalse(card.rows.single().redacted)
    assertFalse(card.hasPrivate)
    assertFalse(ReminderVisibility.defaultPrivate(leela))
    assertTrue(ReminderVisibility.defaultPrivate(maya))
    assertFalse(ReminderVisibility.defaultPrivate(null))
  }

  @Test fun sharedSurfacesNeverCarryAPrivateReminderOnlyCounts() {
    val shared = ReminderVisibility.shared(state, people)
    assertTrue(shared.reminders.none { it.private })
    assertEquals(setOf("rmilk0001", "rbins0001", "rswim0001"), shared.reminders.map { it.id }.toSet())
    assertEquals(mapOf("Maya" to 2, "Arjun" to 1), shared.privateCounts)
  }

  @Test fun theBriefingGetsThePersonsOwnOpenRemindersSoonestFirst() {
    assertEquals(listOf("Call Dr. Rao's clinic", "Order Arjun's gift", "Pick up milk"),
        ReminderVisibility.briefingTitles(state, maya, people))
  }

  // ---- due ----

  @Test fun quietHoursAreAlwaysSilentAndAPersonsReminderChimesOnlyWhereTheyAre() {
    val d = ReminderDue::decide
    assertEquals(ReminderDue.Decision(sound = true, showTitle = true), d(bins, false, false, true))
    assertEquals(ReminderDue.Decision(sound = false, showTitle = true), d(bins, true, false, true))
    assertEquals(ReminderDue.Decision(sound = false, showTitle = false), d(clinic, false, false, true))
    assertEquals(ReminderDue.Decision(sound = true, showTitle = false), d(clinic, false, true, true))
    assertEquals(ReminderDue.Decision(sound = true, showTitle = true), d(clinic, false, true, false))
    assertEquals(ReminderDue.Decision(sound = false, showTitle = true), d(clinic, true, true, false))
    val milk = ReminderFixtures.milk
    assertEquals(ReminderDue.Decision(sound = false, showTitle = true), d(milk, false, false, true))
  }

  @Test fun dueNowSkipsAnnouncedFutureDoneAndLongMissedOnes() {
    val due = clinic.copy(dueMs = NOW - 1_000)
    val late = ReminderFixtures.gift.copy(dueMs = NOW - ReminderDue.LATE_LIMIT_MS - 1)
    val done = ReminderFixtures.milk.copy(dueMs = NOW - 1_000, done = true)
    val future = bins
    val s = ReminderState(listOf(due, late, done, future).associateBy { it.id })
    assertEquals(listOf(due.id), ReminderDue.dueNow(s, NOW, emptySet()).map { it.id })
    assertTrue(ReminderDue.dueNow(s, NOW, setOf(ReminderDue.key(due))).isEmpty())
    // Rescheduling makes a new due moment.
    assertTrue(ReminderDue.key(due) != ReminderDue.key(due.copy(dueMs = NOW + 5)))
    assertEquals(bins.dueMs, ReminderDue.nextDue(s, NOW))
    assertNull(ReminderDue.nextDue(ReminderState(), NOW))
  }

  @Test fun tagsReadLikeTheMock() {
    fun tag(due: Long?, use24: Boolean = false) = ReminderDue.tag(due, NOW, zone, use24, Locale.UK)
    assertNull(tag(null))
    assertEquals("6 pm", tag(NOW + 8 * hour))
    assertEquals("18:00", tag(NOW + 8 * hour, use24 = true))
    assertEquals("6:30 pm", tag(NOW + 8 * hour + 30 * 60_000))
    assertEquals("Tomorrow", tag(NOW + 24 * hour))
    assertEquals("3 days", tag(NOW + 72 * hour))
    assertEquals("22 Jan", tag(NOW + 7 * 24 * hour))
    assertEquals("Overdue", tag(NOW - 24 * hour))
    assertEquals("12 am", ReminderDue.clock(NOW + 14 * hour, zone, false, Locale.UK))
    assertEquals("12 pm", ReminderDue.clock(NOW + 2 * hour, zone, false, Locale.UK))
  }

  @Test fun timesResolveToTheNextOccurrenceOrAPickedDay() {
    assertEquals(NOW + 8 * hour, ReminderDue.nextAt(18, 0, NOW, zone))
    assertEquals(NOW + 23 * hour, ReminderDue.nextAt(9, 0, NOW, zone)) // 9:00 already passed → tomorrow
    assertEquals(NOW + 8 * hour, ReminderDue.dueFrom(0, ReminderTimeText.Clock(18, 0), NOW, zone))
    assertNull(ReminderDue.dueFrom(0, ReminderTimeText.Clock(8, 0), NOW, zone)) // past today
    assertEquals(NOW + 23 * hour, ReminderDue.dueFrom(1, null, NOW, zone)) // tomorrow 9:00
    assertNull(ReminderDue.dueFrom(null, ReminderTimeText.Clock(8, 0), NOW, zone))
    assertEquals(1, ReminderDue.dayOffsetOf(NOW + 23 * hour, NOW, zone))
    assertNull(ReminderDue.dayOffsetOf(NOW + 30 * 24 * hour, NOW, zone))
    assertNull(ReminderDue.dayOffsetOf(null, NOW, zone))
    assertEquals(NOW + 8 * hour, ReminderDue.at(2027, 1, 15, 18, 0, zone))
    assertNull(ReminderDue.at(2027, 2, 30, 18, 0, zone))
    assertNull(ReminderDue.at(2027, 13, 1, 18, 0, zone))
    val days = ReminderDue.dayOptions(NOW, zone, Locale.UK)
    assertEquals(listOf("No time", "Today", "Tomorrow", "Sunday"), days.take(4).map { it.first })
    assertEquals(listOf(null, 0, 1, 2, 3, 4, 5, 6), days.map { it.second })
  }

  @Test fun typedAndSpokenTimesParse() {
    fun p(t: String) = ReminderTimeText.parse(t)?.let { it.hour to it.minute }
    assertEquals(18 to 30, p("18:30"))
    assertEquals(18 to 30, p("6:30 pm"))
    assertEquals(18 to 0, p("6pm"))
    assertEquals(18 to 0, p(" 6 PM "))
    assertEquals(6 to 30, p("0630"))
    assertEquals(6 to 30, p("6.30"))
    assertEquals(0 to 15, p("12:15 am"))
    assertEquals(12 to 0, p("12 pm"))
    assertEquals(12 to 0, p("noon"))
    assertEquals(0 to 0, p("midnight"))
    assertEquals(6 to 0, p("6"))
    assertNull(p("25:00"))
    assertNull(p("13 pm"))
    assertNull(p("6:75"))
    assertNull(p("soon"))
    assertNull(ReminderTimeText.parse(null))
  }
}
