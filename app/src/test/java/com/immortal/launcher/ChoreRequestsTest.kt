/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.ChoreFixtures.MONDAY
import com.immortal.launcher.ChoreFixtures.ok
import com.immortal.launcher.ChoreFixtures.refused
import com.immortal.launcher.ChoreFixtures.state
import com.immortal.launcher.ChoreFixtures.tick
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `/remote/chores` POST bodies from the phone: validated, then applied as pure edits. */
class ChoreRequestsTest {
  private val newId = { "newid001" }
  private fun parse(body: JSONObject) = ChoreRequests.parse(body, newId)
  private fun invalid(body: JSONObject) = (parse(body) as ChoreRequest.Invalid).code

  @Test fun addsAKidWithAvatarColourGoalAndReward() {
    val body = JSONObject().put("action", "kid_save").put("kid", JSONObject().put("name", " Zoe ").put("avatar", "🐼")
        .put("color", 4).put("goal", 20).put("reward", "Movie night"))
    val s = ok(ChoreRequests.apply(state, parse(body), "living-room"))
    val zoe = s.kids.getValue("newid001")
    assertEquals(listOf("Zoe", "🐼", 4, 20, "Movie night", 1L), listOf(zoe.name, zoe.avatar, zoe.color, zoe.goal, zoe.reward, zoe.seq))
  }

  @Test fun savesAChoreForAnyoneOnChosenWeekdays() {
    val body = JSONObject().put("action", "chore_save").put("chore", JSONObject().put("title", "Feed the cat").put("pic", "bowl")
        .put("kid", "").put("stars", 2).put("repeat", "days").put("days", JSONArray(listOf(6, 7))).put("needsOk", true))
    val chore = ok(ChoreRequests.apply(state, parse(body), "living-room")).chores.getValue("newid001")
    assertEquals(null, chore.kid)
    assertEquals(ChoreSchedule.Weekdays(setOf(6, 7)), chore.schedule)
    assertTrue(chore.needsOk)
  }

  @Test fun editsKeepTheirIdAndBeatTheSeqThePhoneSaw() {
    val body = JSONObject().put("action", "chore_save").put("chore", JSONObject().put("id", "chbed").put("title", "Bed")
        .put("pic", "bed").put("kid", "kidmia").put("stars", 3).put("repeat", "once").put("day", MONDAY + 2).put("seq", 4))
    val bed = ok(ChoreRequests.apply(state, parse(body), "living-room")).chores.getValue("chbed")
    assertEquals(5L, bed.seq)
    assertEquals(ChoreSchedule.Once(MONDAY + 2), bed.schedule)
  }

  @Test fun rejectsBadInput() {
    assertEquals("missing_action", invalid(JSONObject()))
    assertEquals("unknown_action", invalid(JSONObject().put("action", "explode")))
    val kid = JSONObject().put("name", "Zoe").put("avatar", "🐼").put("color", 1)
    assertEquals("invalid_avatar", invalid(JSONObject().put("action", "kid_save").put("kid", JSONObject(kid.toString()).put("avatar", "x"))))
    assertEquals("invalid_color", invalid(JSONObject().put("action", "kid_save").put("kid", JSONObject(kid.toString()).put("color", 8))))
    assertEquals("invalid_goal", invalid(JSONObject().put("action", "kid_save").put("kid", JSONObject(kid.toString()).put("goal", 501))))
    assertEquals("invalid_name", invalid(JSONObject().put("action", "kid_save").put("kid", JSONObject(kid.toString()).put("name", "  "))))
    assertEquals("invalid_id", invalid(JSONObject().put("action", "kid_save").put("kid", JSONObject(kid.toString()).put("id", "../x"))))
    val chore = JSONObject().put("title", "Bed").put("pic", "bed").put("stars", 1).put("repeat", "daily")
    assertEquals("invalid_stars", invalid(JSONObject().put("action", "chore_save").put("chore", JSONObject(chore.toString()).put("stars", 6))))
    assertEquals("invalid_pic", invalid(JSONObject().put("action", "chore_save").put("chore", JSONObject(chore.toString()).put("pic", "rocket"))))
    assertEquals("invalid_schedule", invalid(JSONObject().put("action", "chore_save").put("chore", JSONObject(chore.toString()).put("repeat", "days"))))
    assertEquals("invalid_id", invalid(JSONObject().put("action", "approve").put("id", 5)))
  }

  @Test fun approvesDeclinesAndTakesBack() {
    val waiting = tick(state, "chbath", "kidleo", MONDAY + 1, "tick0001")
    val approve = JSONObject().put("action", "approve").put("id", "tick0001")
    val approved = ok(ChoreRequests.apply(waiting, parse(approve), "living-room"))
    assertEquals(TickStatus.APPROVED, approved.ticks.getValue("tick0001").status)
    val back = ok(ChoreRequests.apply(approved, parse(JSONObject().put("action", "take_back").put("id", "tick0001")), "living-room"))
    assertEquals(TickStatus.UNDONE, back.ticks.getValue("tick0001").status)
    assertEquals("unknown_tick", refused(ChoreRequests.apply(state, parse(approve), "living-room")))
    assertEquals(404, ChoresRoute.status("unknown_tick"))
    assertEquals(409, ChoresRoute.status("too_many_kids"))
    assertEquals(400, ChoresRoute.status("invalid_name"))
  }

  @Test fun deletesKidsAndChores() {
    val s = ok(ChoreRequests.apply(state, parse(JSONObject().put("action", "chore_delete").put("id", "chtoys")), "living-room"))
    assertTrue(s.chores.getValue("chtoys").deleted)
    assertEquals("unknown_kid", refused(ChoreRequests.apply(state, parse(JSONObject().put("action", "kid_delete").put("id", "nokid1")), "x")))
  }
}
