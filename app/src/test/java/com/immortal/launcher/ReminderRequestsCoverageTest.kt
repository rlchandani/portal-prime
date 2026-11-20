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
import com.immortal.launcher.ReminderFixtures.leela
import com.immortal.launcher.ReminderFixtures.maya
import com.immortal.launcher.ReminderFixtures.milk
import com.immortal.launcher.ReminderFixtures.people
import com.immortal.launcher.ReminderFixtures.reminder
import com.immortal.launcher.ReminderFixtures.state
import com.immortal.launcher.ReminderFixtures.swim
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Phone reminder requests: parser edges for every action and the owner/privacy rules on edit. */
class ReminderRequestsCoverageTest {
  private fun parse(vararg kv: Pair<String, Any?>) = ReminderRequests.parse(JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } })
  private fun run(req: ReminderRequest, s: ReminderState = state) = ReminderRequests.apply(s, req, people, "rphone001", "phoneportal", NOW)

  @Test fun actionMustBeAKnownString() {
    assertEquals(ReminderRequest.Invalid("bad_action"), parse())
    assertEquals(ReminderRequest.Invalid("bad_action"), parse("action" to 1))
    assertEquals(ReminderRequest.Invalid("bad_action"), parse("action" to "ADD"))
  }

  @Test fun nonNumericBaseCountsAsZero() {
    assertEquals(ReminderRequest.Delete(milk.id, 0), parse("action" to "delete", "id" to milk.id, "base" to "7"))
    assertEquals(ReminderRequest.Delete(milk.id, 9), parse("action" to "delete", "id" to milk.id, "base" to 9L))
    assertEquals(ReminderRequest.Done(milk.id, true, 0), parse("action" to "done", "id" to milk.id))
  }

  @Test fun idActionsNeedAValidStringId() {
    for (action in listOf("edit", "done", "undo", "delete")) {
      assertEquals(action, ReminderRequest.Invalid("bad_id"), parse("action" to action, "title" to "x"))
      assertEquals(action, ReminderRequest.Invalid("bad_id"), parse("action" to action, "id" to 12345, "title" to "x"))
      assertEquals(action, ReminderRequest.Invalid("bad_id"), parse("action" to action, "id" to "a/b", "title" to "x"))
    }
  }

  @Test fun editParsesTheSameFieldsAsAdd() {
    assertEquals(ReminderRequest.Edit(milk.id, "Milk", maya.id, NOW, false, 4),
        parse("action" to "edit", "id" to milk.id, "title" to "  Milk ", "person" to maya.id, "due" to NOW, "private" to false, "base" to 4))
    assertEquals(ReminderRequest.Invalid("bad_title"), parse("action" to "edit", "id" to milk.id, "title" to 5))
    assertEquals(ReminderRequest.Invalid("bad_title"), parse("action" to "edit", "id" to milk.id))
  }

  @Test fun nullOrEmptyPersonAndNullDueMeanNone() {
    assertEquals(ReminderRequest.Add("x", null, null, null), parse("action" to "add", "title" to "x", "person" to JSONObject.NULL, "due" to JSONObject.NULL))
    assertEquals(ReminderRequest.Add("x", null, null, true), parse("action" to "add", "title" to "x", "person" to "", "private" to true))
    assertEquals(ReminderRequest.Add("x", null, 1234L, null), parse("action" to "add", "title" to "x", "due" to 1234))
    assertEquals(ReminderRequest.Invalid("bad_due"), parse("action" to "add", "title" to "x", "due" to 12.5))
    assertEquals(ReminderRequest.Invalid("bad_private"), parse("action" to "add", "title" to "x", "private" to 1))
  }

  @Test fun applyRefusesAnInvalidRequestWithItsCode() {
    assertEquals(ReminderResult.Refused("bad_title"), run(ReminderRequest.Invalid("bad_title")))
  }

  @Test fun deletedRemindersAreUnknownToThePhone() {
    val gone = milk.copy(deleted = true)
    val s = ReminderState(mapOf(gone.id to gone))
    val missing = ReminderResult.Refused("unknown_reminder")
    assertEquals(missing, run(ReminderRequest.Edit(gone.id, "x", null, null, null, 0), s))
    assertEquals(missing, run(ReminderRequest.Done(gone.id, true, 0), s))
    assertEquals(missing, run(ReminderRequest.Delete(gone.id, 0), s))
  }

  @Test fun editingToAStrangerIsRefusedButMovingToAnEnrolledPersonWorks() {
    assertEquals(ReminderResult.Refused("unknown_person"), run(ReminderRequest.Edit(milk.id, "Milk", "strangerid01", null, null, 0)))
    val moved = run(ReminderRequest.Edit(milk.id, "Milk", arjun.id, null, false, 0)) as ReminderResult.Ok
    assertEquals(arjun.id, moved.reminder.person)
    assertEquals("Arjun", moved.reminder.personName)
    assertFalse(moved.reminder.private)
  }

  @Test fun editingAKidsReminderNeverMakesItPrivate() {
    val r = run(ReminderRequest.Edit(swim.id, "Swim bag", leela.id, null, true, 0)) as ReminderResult.Ok
    assertFalse(r.reminder.private)
  }

  @Test fun editingToTheHouseholdClearsTheOwner() {
    val r = run(ReminderRequest.Edit(milk.id, "Milk", null, null, true, 0)) as ReminderResult.Ok
    assertNull(r.reminder.person)
    assertEquals("", r.reminder.personName)
    assertFalse(r.reminder.private)
  }

  @Test fun anOtherPortalOwnerKeepsOrChangesPrivacyOnEdit() {
    val remote = milk.copy(person = "otherportal1", personName = "Asha", private = false)
    val s = ReminderState(mapOf(remote.id to remote))
    val kept = run(ReminderRequest.Edit(remote.id, "Milk", "otherportal1", NOW + 5, null, 0), s) as ReminderResult.Ok
    assertFalse(kept.reminder.private)
    assertEquals(NOW + 5, kept.reminder.dueMs)
    val hidden = run(ReminderRequest.Edit(remote.id, "Milk", "otherportal1", null, true, 0), s) as ReminderResult.Ok
    assertTrue(hidden.reminder.private)
    assertEquals("Asha", hidden.reminder.personName)
    // A different unknown owner is not grandfathered in.
    assertEquals(ReminderResult.Refused("unknown_person"), run(ReminderRequest.Edit(remote.id, "Milk", "otherportal2", null, null, 0), s))
  }

  @Test fun viewFillsEmptyPersonAndDueAndLabelsTheHousehold() {
    val o = ReminderRequests.view(ReminderState(mapOf(bins.id to bins.copy(dueMs = null))), people, "Den", ready = false)
    assertTrue(o.getBoolean("ok"))
    assertFalse(o.getBoolean("ready"))
    assertEquals("Den", o.getString("portal"))
    val row = o.getJSONArray("reminders").getJSONObject(0)
    assertEquals("", row.getString("person"))
    assertEquals("Home", row.getString("for"))
    assertEquals(0L, row.getLong("due"))
    assertEquals(0, o.getJSONObject("privateCounts").length())
    val who = o.getJSONArray("people")
    assertTrue(who.getJSONObject(2).getBoolean("kid"))
    assertFalse(who.getJSONObject(0).getBoolean("kid"))
  }

  @Test fun visibilityMatchesUnknownIdsByNameOnly() {
    val asha = reminder("rasha0001", "Call", null).copy(person = "otherportal1", personName = "maya")
    assertTrue(ReminderVisibility.belongsTo(asha, maya, people))
    assertFalse(ReminderVisibility.belongsTo(asha.copy(personName = " "), maya, people))
    // An id that belongs to someone else here never matches by name.
    assertFalse(ReminderVisibility.belongsTo(asha.copy(person = arjun.id), maya, people))
    assertFalse(ReminderVisibility.belongsTo(bins, maya, people))
    assertEquals("Someone", ReminderVisibility.ownerLabel(asha.copy(personName = ""), people))
    assertEquals("Asha", ReminderVisibility.ownerLabel(asha.copy(personName = "Asha"), people))
    assertEquals("Maya", ReminderVisibility.ownerLabel(asha, people))
  }

  @Test fun homeCardRedactsPrivateRowsCapsTheListAndNeverHidesAKidsItem() {
    val card = ReminderVisibility.homeCard(state, maya, people, hidePrivate = true, limit = 2)
    assertEquals(2, card.rows.size)
    assertTrue(card.rows.all { it.mine && it.redacted })
    assertEquals(2, card.privateHidden)
    assertTrue(card.hasPrivate)
    assertEquals(4, card.total) // clinic, gift, milk + household bins
    val shown = ReminderVisibility.homeCard(state, maya, people, hidePrivate = false)
    assertEquals(0, shown.privateHidden)
    assertEquals(listOf(false, false, false, true), shown.rows.map { !it.mine })
    val kidPrivate = swim.copy(private = true)
    val kidCard = ReminderVisibility.homeCard(ReminderState(mapOf(kidPrivate.id to kidPrivate)), leela, people, hidePrivate = true)
    assertFalse(kidCard.rows.single().redacted)
    assertFalse(kidCard.hasPrivate)
  }

  @Test fun orderPutsOpenFirstThenDueThenOldest() {
    val a = reminder("ra", "A", due = null, created = 1)
    val b = reminder("rb", "B", due = 5, created = 2)
    val c = reminder("rc", "C", due = 5, created = 1)
    val d = reminder("rd", "D", done = true, due = 1)
    assertEquals(listOf("rc", "rb", "ra", "rd"), listOf(a, b, c, d).sortedWith(ReminderVisibility.order).map { it.id })
  }

  @Test fun privateCountsFallBackToTheStoredNameAndSkipDoneOnes() {
    val remote = reminder("rrem0001", "Secret", null, private = true).copy(person = "otherportal1", personName = "Asha")
    val done = reminder("rdone001", "Old secret", maya, private = true, done = true)
    val shared = ReminderVisibility.shared(ReminderState(listOf(remote, done).associateBy { it.id }), people)
    assertEquals(mapOf("Asha" to 1), shared.privateCounts)
    assertTrue(shared.reminders.isEmpty())
    assertFalse(ReminderVisibility.defaultPrivate(null))
    assertFalse(ReminderVisibility.canBePrivate(leela))
  }
}
