/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.ReminderFixtures.NOW
import com.immortal.launcher.ReminderFixtures.bill
import com.immortal.launcher.ReminderFixtures.clinic
import com.immortal.launcher.ReminderFixtures.leela
import com.immortal.launcher.ReminderFixtures.maya
import com.immortal.launcher.ReminderFixtures.milk
import com.immortal.launcher.ReminderFixtures.people
import com.immortal.launcher.ReminderFixtures.state
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's `/remote/reminders`: a shared surface that can never read or change private reminders. */
class ReminderRequestsTest {
  private fun parse(vararg kv: Pair<String, Any?>) = ReminderRequests.parse(JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } })
  private fun run(req: ReminderRequest) = ReminderRequests.apply(state, req, people, "rphone001", "phoneportal", NOW)

  @Test fun parsingRejectsJunk() {
    assertEquals(ReminderRequest.Invalid("bad_action"), parse("action" to "steal"))
    assertEquals(ReminderRequest.Invalid("bad_title"), parse("action" to "add", "title" to " "))
    assertEquals(ReminderRequest.Invalid("bad_person"), parse("action" to "add", "title" to "x", "person" to 5))
    assertEquals(ReminderRequest.Invalid("bad_person"), parse("action" to "add", "title" to "x", "person" to "no spaces!"))
    assertEquals(ReminderRequest.Invalid("bad_due"), parse("action" to "add", "title" to "x", "due" to "6pm"))
    assertEquals(ReminderRequest.Invalid("bad_private"), parse("action" to "add", "title" to "x", "private" to "yes"))
    assertEquals(ReminderRequest.Invalid("bad_id"), parse("action" to "done", "id" to "../x"))
    assertEquals(ReminderRequest.Invalid("bad_base"), parse("action" to "done", "id" to "rmilk0001", "base" to -1))
    assertEquals(ReminderRequest.Add("x", maya.id, null, null), parse("action" to "add", "title" to "x", "person" to maya.id, "due" to 0))
    assertEquals(ReminderRequest.Done("rmilk0001", false, 3), parse("action" to "undo", "id" to "rmilk0001", "base" to 3))
  }

  @Test fun thePhoneCantTouchAPrivateReminderAndCantTellItExists() {
    val missing = ReminderResult.Refused("unknown_reminder")
    for (id in listOf(clinic.id, bill.id)) {
      assertEquals(missing, run(ReminderRequest.Done(id, true, 0)))
      assertEquals(missing, run(ReminderRequest.Delete(id, 0)))
      assertEquals(missing, run(ReminderRequest.Edit(id, "x", null, null, false, 99)))
    }
    assertEquals(missing, run(ReminderRequest.Done("rnothere1", true, 0)))
  }

  @Test fun thePhoneMayChangeSharedRemindersAndMoveOneIntoPrivate() {
    val done = run(ReminderRequest.Done(milk.id, true, 4)) as ReminderResult.Ok
    assertEquals(5L, done.reminder.seq)
    val hidden = run(ReminderRequest.Edit(milk.id, "Milk", maya.id, null, true, 0)) as ReminderResult.Ok
    assertTrue(hidden.reminder.private)
    assertTrue(run(ReminderRequest.Delete(milk.id, 0)) is ReminderResult.Ok)
  }

  @Test fun addingDefaultsToPrivateForAdultsNeverForKidsOrTheHousehold() {
    val adult = run(ReminderRequest.Add("Dentist", maya.id, NOW + 3_600_000, null)) as ReminderResult.Ok
    assertTrue(adult.reminder.private)
    assertEquals("Maya", adult.reminder.personName)
    assertEquals(ReminderLimits.PHONE, adult.reminder.createdBy)
    val kid = run(ReminderRequest.Add("Swim", leela.id, null, true)) as ReminderResult.Ok
    assertFalse(kid.reminder.private)
    val home = run(ReminderRequest.Add("Bins", null, null, true)) as ReminderResult.Ok
    assertFalse(home.reminder.private)
    val open = run(ReminderRequest.Add("Shared", maya.id, null, false)) as ReminderResult.Ok
    assertFalse(open.reminder.private)
    assertEquals(ReminderResult.Refused("unknown_person"), run(ReminderRequest.Add("x", "strangerid01", null, null)))
  }

  @Test fun editingKeepsAnOwnerFromAnotherPortal() {
    val remote = milk.copy(person = "otherportal1", personName = "Asha")
    val s = ReminderState(mapOf(remote.id to remote))
    val r = ReminderRequests.apply(s, ReminderRequest.Edit(remote.id, "Milk!", "otherportal1", null, null, 0), people, "x", "p", NOW)
        as ReminderResult.Ok
    assertEquals("Asha", r.reminder.personName)
    assertEquals("Milk!", r.reminder.title)
  }

  @Test fun theViewHasNoPrivateTitlesOrIdsOnlyCounts() {
    val view = ReminderRequests.view(state, people, "Kitchen", ready = true).toString()
    for (secret in listOf(clinic.title, clinic.id, bill.title, bill.id, ReminderFixtures.gift.title)) {
      assertFalse(secret, view.contains(secret))
    }
    val o = JSONObject(view)
    assertEquals(3, o.getJSONArray("reminders").length())
    assertEquals(2, o.getJSONObject("privateCounts").getInt("Maya"))
    assertEquals(3, o.getJSONArray("people").length())
    assertTrue(o.getBoolean("ready"))
  }

  @Test fun routeStatusCodes() {
    assertEquals(404, RemindersRoute.status("unknown_reminder"))
    assertEquals(404, RemindersRoute.status("unknown_person"))
    assertEquals(409, RemindersRoute.status("too_many_reminders"))
    assertEquals(400, RemindersRoute.status("invalid_reminder"))
  }
}
