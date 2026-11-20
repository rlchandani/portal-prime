/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.ChoreFixtures.MONDAY
import com.immortal.launcher.ChoreFixtures.state
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every validation rejection of the phone's untrusted chore POST bodies, and the apply() dispatch. */
class ChoreRequestsCoverageTest {
  private val newId = { "newid001" }
  private fun parse(body: JSONObject) = ChoreRequests.parse(body, newId)
  private fun invalid(body: JSONObject) = (parse(body) as ChoreRequest.Invalid).code

  private fun kid() = JSONObject().put("name", "Zoe").put("avatar", "🐼").put("color", 1)
  private fun chore() = JSONObject().put("title", "Bed").put("pic", "bed").put("stars", 1).put("repeat", "daily")
  private fun kidSave(k: JSONObject) = JSONObject().put("action", "kid_save").put("kid", k)
  private fun choreSave(c: JSONObject) = JSONObject().put("action", "chore_save").put("chore", c)

  @Test fun idActionsNeedAValidId() {
    for (action in listOf("kid_delete", "chore_delete", "approve", "decline", "take_back")) {
      assertEquals(action, "invalid_id", invalid(JSONObject().put("action", action)))
      assertEquals(action, "invalid_id", invalid(JSONObject().put("action", action).put("id", "UPPER")))
      assertEquals(action, "invalid_id", invalid(JSONObject().put("action", action).put("id", 12345)))
    }
    assertEquals(ChoreRequest.DeleteKid("kidmia"), parse(JSONObject().put("action", "kid_delete").put("id", "kidmia")))
    assertEquals(ChoreRequest.DeleteChore("chbed"), parse(JSONObject().put("action", "chore_delete").put("id", "chbed")))
    assertEquals(ChoreRequest.Approve("tk0001"), parse(JSONObject().put("action", "approve").put("id", "tk0001")))
    assertEquals(ChoreRequest.Decline("tk0001"), parse(JSONObject().put("action", "decline").put("id", "tk0001")))
    assertEquals(ChoreRequest.TakeBack("tk0001"), parse(JSONObject().put("action", "take_back").put("id", "tk0001")))
  }

  @Test fun actionMustBeAKnownString() {
    assertEquals("unknown_action", invalid(JSONObject().put("action", "KID_SAVE")))
    assertEquals("unknown_action", invalid(JSONObject().put("action", 3)))
    assertEquals("missing_action", invalid(JSONObject().put("action", "")))
  }

  @Test fun missingOrNonObjectPayloads() {
    assertEquals("invalid_kid", invalid(JSONObject().put("action", "kid_save")))
    assertEquals("invalid_kid", invalid(JSONObject().put("action", "kid_save").put("kid", "Zoe")))
    assertEquals("invalid_chore", invalid(JSONObject().put("action", "chore_save")))
    assertEquals("invalid_chore", invalid(JSONObject().put("action", "chore_save").put("chore", JSONArray())))
  }

  @Test fun kidIdIsNewWhenAbsentNullOrEmptyAndRejectedWhenNotAString() {
    for (id in listOf<Any>(JSONObject.NULL, "")) {
      assertEquals("newid001", ((parse(kidSave(kid().put("id", id))) as ChoreRequest.SaveKid).kid.id))
    }
    assertEquals("kidmia", (parse(kidSave(kid().put("id", "kidmia"))) as ChoreRequest.SaveKid).kid.id)
    assertEquals("invalid_id", invalid(kidSave(kid().put("id", 7))))
  }

  @Test fun kidFieldRejections() {
    assertEquals("invalid_name", invalid(kidSave(kid().put("name", 5))))
    assertEquals("invalid_name", invalid(kidSave(kid().apply { remove("name") })))
    assertEquals("invalid_avatar", invalid(kidSave(kid().apply { remove("avatar") })))
    assertEquals("invalid_color", invalid(kidSave(kid().put("color", -1))))
    assertEquals("invalid_color", invalid(kidSave(kid().put("color", "1"))))
    assertEquals("invalid_color", invalid(kidSave(kid().apply { remove("color") })))
    assertEquals("invalid_goal", invalid(kidSave(kid().put("goal", -1))))
    assertEquals("invalid_goal", invalid(kidSave(kid().put("goal", 2.5))))
    assertEquals("invalid_reward", invalid(kidSave(kid().put("reward", 5))))
    assertEquals("invalid_reward", invalid(kidSave(kid().put("reward", "\u0000\u0001"))))
  }

  @Test fun kidOptionalFieldsDefault() {
    val save = parse(kidSave(kid())) as ChoreRequest.SaveKid
    assertEquals(0, save.kid.goal)
    assertEquals("", save.kid.reward)
    assertEquals(0L, save.baseSeq)
    assertEquals("", (parse(kidSave(kid().put("reward", JSONObject.NULL))) as ChoreRequest.SaveKid).kid.reward)
    assertEquals("", (parse(kidSave(kid().put("reward", "   "))) as ChoreRequest.SaveKid).kid.reward)
    assertEquals(ChoreLimits.MAX_GOAL, (parse(kidSave(kid().put("goal", ChoreLimits.MAX_GOAL))) as ChoreRequest.SaveKid).kid.goal)
  }

  @Test fun baseSeqIsClampedAndJunkIsZero() {
    fun seq(v: Any) = (parse(kidSave(kid().put("seq", v))) as ChoreRequest.SaveKid).baseSeq
    assertEquals(9L, seq(9))
    assertEquals(0L, seq(-4))
    assertEquals(Long.MAX_VALUE / 4, seq(Long.MAX_VALUE))
    assertEquals(0L, seq("9"))
    assertEquals(0L, seq(9.5))
  }

  @Test fun choreIdAndTitleAndPic() {
    assertEquals("invalid_id", invalid(choreSave(chore().put("id", "NO"))))
    assertEquals("invalid_id", invalid(choreSave(chore().put("id", true))))
    assertEquals("newid001", (parse(choreSave(chore().put("id", JSONObject.NULL))) as ChoreRequest.SaveChore).chore.id)
    assertEquals("invalid_title", invalid(choreSave(chore().put("title", 3))))
    assertEquals("invalid_title", invalid(choreSave(chore().apply { remove("title") })))
    assertEquals("invalid_pic", invalid(choreSave(chore().put("pic", 3))))
  }

  @Test fun choreKidMayBeAbsentNullEmptyOrAnIdOnly() {
    for (k in listOf<Any?>(null, JSONObject.NULL, "")) {
      val body = if (k == null) chore() else chore().put("kid", k)
      assertNull((parse(choreSave(body)) as ChoreRequest.SaveChore).chore.kid)
    }
    assertEquals("kidmia", (parse(choreSave(chore().put("kid", "kidmia"))) as ChoreRequest.SaveChore).chore.kid)
    assertEquals("invalid_kid", invalid(choreSave(chore().put("kid", "Mia!"))))
    assertEquals("invalid_kid", invalid(choreSave(chore().put("kid", 5))))
  }

  @Test fun choreStarsAndNeedsOk() {
    assertEquals("invalid_stars", invalid(choreSave(chore().put("stars", 0))))
    assertEquals("invalid_stars", invalid(choreSave(chore().put("stars", "2"))))
    assertEquals("invalid_stars", invalid(choreSave(chore().apply { remove("stars") })))
    assertFalse((parse(choreSave(chore())) as ChoreRequest.SaveChore).chore.needsOk)
    assertTrue((parse(choreSave(chore().put("needsOk", true))) as ChoreRequest.SaveChore).chore.needsOk)
    assertEquals("invalid_chore", invalid(choreSave(chore().put("needsOk", "yes"))))
  }

  @Test fun choreScheduleRejections() {
    assertEquals("invalid_schedule", invalid(choreSave(chore().put("repeat", "weekly"))))
    assertEquals("invalid_schedule", invalid(choreSave(chore().apply { remove("repeat") })))
    assertEquals("invalid_schedule", invalid(choreSave(chore().put("repeat", "days").put("days", JSONArray()))))
    assertEquals("invalid_schedule", invalid(choreSave(chore().put("repeat", "days").put("days", JSONArray(listOf(0, 1))))))
    assertEquals("invalid_schedule", invalid(choreSave(chore().put("repeat", "days").put("days", "1,2"))))
    assertEquals("invalid_schedule", invalid(choreSave(chore().put("repeat", "once"))))
    assertEquals("invalid_schedule", invalid(choreSave(chore().put("repeat", "once").put("day", -1))))
    assertEquals("invalid_schedule", invalid(choreSave(chore().put("repeat", "once").put("day", "$MONDAY"))))
    assertEquals(ChoreSchedule.Once(MONDAY),
        (parse(choreSave(chore().put("repeat", "once").put("day", MONDAY))) as ChoreRequest.SaveChore).chore.schedule)
  }

  @Test fun applyDispatchesEveryRequestKind() {
    val by = ChoreFixtures.LIVING
    assertEquals("bad", ChoreFixtures.refused(ChoreRequests.apply(state, ChoreRequest.Invalid("bad"), by)))
    val withoutMia = ChoreFixtures.ok(ChoreRequests.apply(state, ChoreRequest.DeleteKid("kidmia"), by))
    assertTrue(withoutMia.kids.getValue("kidmia").deleted)
    val withoutBed = ChoreFixtures.ok(ChoreRequests.apply(state, ChoreRequest.DeleteChore("chbed"), by))
    assertTrue(withoutBed.chores.getValue("chbed").deleted)
    val ticked = ChoreFixtures.tick(state, "chbath", "kidleo", MONDAY + 1, "tk0001")
    val approved = ChoreFixtures.ok(ChoreRequests.apply(ticked, ChoreRequest.Approve("tk0001"), by))
    assertEquals(TickStatus.APPROVED, approved.ticks.getValue("tk0001").status)
    val declined = ChoreFixtures.ok(ChoreRequests.apply(ticked, ChoreRequest.Decline("tk0001"), by))
    assertEquals(TickStatus.DECLINED, declined.ticks.getValue("tk0001").status)
    val undone = ChoreFixtures.ok(ChoreRequests.apply(ticked, ChoreRequest.TakeBack("tk0001"), by))
    assertEquals(TickStatus.UNDONE, undone.ticks.getValue("tk0001").status)
  }
}
