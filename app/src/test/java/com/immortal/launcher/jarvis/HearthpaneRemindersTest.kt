/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import com.immortal.launcher.ReminderDraft
import com.immortal.launcher.ReminderFixtures
import com.immortal.launcher.ReminderFixtures.NOW
import com.immortal.launcher.ReminderFixtures.arjun
import com.immortal.launcher.ReminderFixtures.clinic
import com.immortal.launcher.ReminderFixtures.leela
import com.immortal.launcher.ReminderFixtures.maya
import com.immortal.launcher.ReminderFixtures.people
import com.immortal.launcher.ReminderFixtures.state
import com.immortal.launcher.ReminderOps
import com.immortal.launcher.ReminderResult
import com.immortal.launcher.ReminderState
import com.immortal.launcher.WhosThereUi
import com.immortal.launcher.WhosThereView
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `hearthpane.reminders`: "me" resolution, and no private title or id ever reaching Jarvis's model. */
class HearthpaneRemindersTest {
  private var ui = WhosThereUi(people = people)
  private var s: ReminderState = state
  private val added = mutableListOf<ReminderDraft>()
  private val completed = mutableListOf<String>()
  private val tool = HearthpaneReminders(
      who = { ui }, state = { s },
      add = { d -> added += d; ReminderOps.add(s, "rjarvis${added.size}", d, "Jarvis", "kitchen", NOW).also { if (it is ReminderResult.Ok) s = it.state } },
      setDone = { id -> completed += id; ReminderOps.setDone(s, id, true, "kitchen", NOW) },
      now = { NOW }, zone = { ReminderFixtures.zone })

  private fun call(vararg kv: Pair<String, Any>) = tool.invoke(JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }.toString())
  private fun mayaAlone() { ui = WhosThereUi(view = WhosThereView.Personal(maya.id, othersNear = false), people = people, hidePrivate = false) }
  private fun mayaWithOthers() { ui = WhosThereUi(view = WhosThereView.Personal(maya.id, othersNear = true), people = people, hidePrivate = true) }

  @Test fun remindMeIsTheRecognisedPersonPrivateByDefault() {
    mayaAlone()
    val r = call("action" to "add", "title" to "call mom", "for" to "me", "time" to "18:00")
    assertTrue(r.toString(), r.getBoolean("ok"))
    val d = added.single()
    assertEquals(maya.id, d.person)
    assertTrue(d.private)
    assertEquals(NOW + 8 * 3_600_000, d.dueMs)
    assertTrue(r.getString("message"), r.getString("message").contains("for Maya at 6 pm today"))
  }

  @Test fun remindMeWithNobodyRecognisedAsksWhoItsFor() {
    val r = call("action" to "add", "title" to "call mom", "for" to "me")
    assertTrue(r.getBoolean("needs_person"))
    assertTrue(r.getString("message").startsWith("Who's this for?"))
    assertTrue(added.isEmpty())
    // An unconfirmed ("Is this you?") Home is not "me" either.
    ui = WhosThereUi(view = WhosThereView.Personal(maya.id, othersNear = false, confirm = true), people = people)
    assertTrue(call("action" to "add", "title" to "x", "for" to "me").getBoolean("needs_person"))
  }

  @Test fun noOneEnrolledOrNoForMeansTheHousehold() {
    ui = WhosThereUi()
    assertTrue(call("action" to "add", "title" to "bins", "for" to "me").getBoolean("ok"))
    assertNull(added.last().person)
    ui = WhosThereUi(people = people)
    assertTrue(call("action" to "add", "title" to "bins").getBoolean("ok"))
    assertNull(added.last().person)
    assertFalse(added.last().private)
  }

  @Test fun namedPeopleKidsAndUnknownNames() {
    assertTrue(call("action" to "add", "title" to "bill", "for" to "arjun", "private" to false).getBoolean("ok"))
    assertEquals(arjun.id, added.last().person)
    assertFalse(added.last().private)
    assertTrue(call("action" to "add", "title" to "swim bag", "for" to "Leela", "private" to true).getBoolean("ok"))
    assertEquals(leela.id, added.last().person)
    assertFalse("kids never private", added.last().private)
    val unknown = call("action" to "add", "title" to "x", "for" to "Zed")
    assertTrue(unknown.getBoolean("needs_person"))
  }

  @Test fun badArgumentsAreRefused() {
    assertTrue(tool.invoke("not json").has("error"))
    assertTrue(call("action" to "fly").has("error"))
    assertTrue(call("action" to "add").has("error"))
    assertTrue(call("action" to "add", "title" to "x", "time" to "teatime").has("error"))
    assertTrue(call("action" to "add", "title" to "x", "date" to "tomorrow").has("error"))
    assertTrue(call("action" to "add", "title" to "x", "date" to "2020-01-01", "time" to "10:00").has("error"))
    assertTrue(call("action" to "add", "title" to "x", "date" to "2027-02-30").has("error"))
    assertTrue(added.isEmpty())
    assertTrue(call("action" to "add", "title" to "x", "date" to "2027-01-16").getBoolean("ok"))
    assertEquals(NOW + 23 * 3_600_000, added.single().dueMs)
  }

  @Test fun listingNeverReturnsAPrivateTitleOrIdEvenToTheOwnerAlone() {
    mayaAlone()
    val r = call("action" to "list", "for" to "me")
    val text = r.toString()
    assertFalse(text.contains(clinic.title))
    assertFalse(text.contains(clinic.id))
    assertFalse(text.contains(ReminderFixtures.gift.title))
    assertTrue(text.contains("Pick up milk"))
    assertEquals(2, r.getInt("private_count"))
    val arjuns = call("action" to "list", "for" to "Arjun").toString()
    assertFalse(arjuns.contains(ReminderFixtures.bill.title))
    val household = call("action" to "list", "for" to "household")
    assertEquals("Bins out", household.getJSONArray("reminders").getJSONObject(0).getString("title"))
  }

  @Test fun completingAPrivateOneNeedsTheOwnerAloneAndNeverEchoesIt() {
    mayaWithOthers()
    assertFalse(call("action" to "complete", "title" to "clinic").getBoolean("ok"))
    assertTrue(completed.isEmpty())
    ui = WhosThereUi(view = WhosThereView.Personal(arjun.id, othersNear = false), people = people, hidePrivate = false)
    assertFalse("not Arjun's", call("action" to "complete", "title" to "Rao clinic").getBoolean("ok"))
    mayaAlone()
    val r = call("action" to "complete", "title" to "call the clinic")
    assertTrue(r.getBoolean("ok"))
    assertEquals(listOf(clinic.id), completed)
    assertFalse(r.toString().contains("Rao"))
  }

  @Test fun completingSharedOnesByWordsOrIdButNeverAPrivateId() {
    assertTrue(call("action" to "complete", "title" to "milk").getString("message").contains("Pick up milk"))
    assertTrue(call("action" to "complete", "id" to "rbins0001").getBoolean("ok"))
    mayaAlone()
    assertFalse(call("action" to "complete", "id" to clinic.id).getBoolean("ok"))
    assertTrue(call("action" to "complete").has("error"))
  }

  @Test fun toolsDispatchReachesTheRemindersSeam() {
    val tools = HearthpaneTools({ null }, { _, _ -> }, reminders = { JSONObject().put("seen", it) })
    assertEquals("{}", tools.invoke(HearthpaneTools.REMINDERS, "{}").getString("seen"))
    val failing = HearthpaneTools({ null }, { _, _ -> }, reminders = { error("boom") })
    assertEquals("reminders failed", failing.invoke(HearthpaneTools.REMINDERS, "{}").getString("error"))
  }
}
