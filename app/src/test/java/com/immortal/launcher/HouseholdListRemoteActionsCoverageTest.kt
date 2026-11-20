/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.HouseholdListRemoteActions.Applied
import com.immortal.launcher.HouseholdListRemoteActions.Rejected
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phone action batches are untrusted: envelope limits, per-action validation and clock clamping. */
class HouseholdListRemoteActionsCoverageTest {
  private val now = 1_800_000_000_000L
  private val start = HouseholdListOps.add(HouseholdListState(), "Milk", "Kitchen", now - 10_000).state
  private val milk = start.liveByText("Milk")!!.id

  private fun action(op: Any?, aid: Any? = "a1", at: Any? = now - 1_000, f: JSONObject.() -> Unit = {}) =
      JSONObject().apply { if (op != null) put("op", op); if (aid != null) put("aid", aid); if (at != null) put("at", at); f() }

  private fun body(vararg actions: Any, device: Any? = "Phone") = JSONObject().put("actions", JSONArray(actions.toList()))
      .apply { if (device != null) put("device", device) }

  private fun run(b: JSONObject, s: HouseholdListState = start) = HouseholdListRemoteActions.apply(s, b, now)

  private fun applied(b: JSONObject, s: HouseholdListState = start) = run(b, s) as Applied

  private fun statusOf(a: JSONObject, s: HouseholdListState = start) =
      applied(body(a), s).results.getJSONObject(0).getString("status")

  @Test fun envelopeRejections() {
    assertEquals(Rejected(400, "bad_actions"), run(JSONObject().put("device", "Phone")))
    assertEquals(Rejected(400, "bad_actions"), run(body()))
    val many = (0..HouseholdListRemoteActions.MAX_ACTIONS).map { action("add", aid = "a$it") { put("text", "t$it") } }
    assertEquals(Rejected(413, "too_many_actions"), run(body(*many.toTypedArray())))
    assertEquals(Rejected(400, "bad_device"), run(body(action("add"), device = null)))
    assertEquals(Rejected(400, "bad_device"), run(body(action("add"), device = 7)))
    assertEquals(Rejected(400, "bad_device"), run(body(action("add"), device = "   ")))
  }

  @Test fun everyActionNeedsAnObjectAidAndPositiveTime() {
    assertEquals(Rejected(400, "bad_actions"), run(body("not an object")))
    assertEquals(Rejected(400, "bad_actions"), run(body(action("add", aid = null))))
    assertEquals(Rejected(400, "bad_actions"), run(body(action("add", aid = "has space"))))
    assertEquals(Rejected(400, "bad_actions"), run(body(action("add", aid = 5))))
    assertEquals(Rejected(400, "bad_actions"), run(body(action("add", at = null))))
    assertEquals(Rejected(400, "bad_actions"), run(body(action("add", at = 0))))
    assertEquals(Rejected(400, "bad_actions"), run(body(action("add", at = "123"))))
  }

  @Test fun futureAndAncientTimesAreClamped() {
    val future = applied(body(action("add", at = now + 999_999) { put("text", "Eggs") }))
    assertEquals(now, future.state.liveByText("Eggs")!!.added.at)
    val ancient = applied(body(action("add", at = 1) { put("text", "Eggs") }))
    assertEquals(now - HouseholdListRemoteActions.MAX_AGE_MS, ancient.state.liveByText("Eggs")!!.added.at)
  }

  @Test fun staleOnlyWhenARevisionWasGivenAndDiffers() {
    val a = action("add") { put("text", "Eggs") }
    assertFalse(applied(body(a)).stale)
    assertFalse(applied(body(a).put("expectedRevision", start.digest)).stale)
    assertTrue(applied(body(a).put("expectedRevision", "000000000000")).stale)
    assertFalse("non-string revision is ignored", applied(body(a).put("expectedRevision", 5)).stale)
    assertEquals("Phone", applied(body(a)).device)
  }

  @Test fun unknownOrMalformedOpsAreInvalidWithNullId() {
    for (a in listOf(action("explode"), action(null), action(3), action("add"), action("add") { put("text", 5) },
        action("tick"), action("tick") { put("id", "milk") }, action("rename") { put("id", milk) },
        action("move") { put("id", milk) }, action("move") { put("id", milk).put("order", "1") },
        action("clear_done"), action("clear_done") { put("ids", JSONArray(listOf("bad"))) },
        action("clear_done") { put("ids", JSONArray(listOf(5))) })) {
      val r = applied(body(a)).results.getJSONObject(0)
      assertEquals("$a", "invalid", r.getString("status"))
      assertEquals(JSONObject.NULL, r.get("id"))
    }
  }

  @Test fun clearDoneRefusesAnOversizedIdList() {
    val ids = JSONArray((0..HouseholdListOps.MAX_ITEMS).map { milk })
    assertEquals("invalid", statusOf(action("clear_done") { put("ids", ids) }))
  }

  @Test fun eachOpMapsToItsEdit() {
    assertEquals("applied", statusOf(action("tick") { put("id", milk) }))
    val ticked = HouseholdListOps.setDone(start, milk, true, "Kitchen", now - 5_000).state
    assertEquals("applied", statusOf(action("untick") { put("id", milk) }, ticked))
    assertEquals("applied", statusOf(action("clear_done") { put("ids", JSONArray(listOf(milk))) }, ticked))
    assertEquals("missing", statusOf(action("clear_done") { put("ids", JSONArray()) }, ticked))
    assertEquals("applied", statusOf(action("remove") { put("id", milk) }))
    val removed = HouseholdListOps.remove(start, milk, "Kitchen", now - 5_000).state
    assertEquals("applied", statusOf(action("restore") { put("id", milk) }, removed))
    assertEquals("applied", statusOf(action("rename") { put("id", milk).put("text", "Oat milk") }))
    assertEquals("applied", statusOf(action("move") { put("id", milk).put("order", 3) }))
    assertEquals("missing", statusOf(action("tick") { put("id", "h000000000000000") }))
    assertEquals("duplicate", statusOf(action("add") { put("text", "milk") }))
    val moved = applied(body(action("add") { put("text", "Milk") }), ticked).results.getJSONObject(0)
    assertEquals("applied", moved.getString("status"))
    assertEquals(milk, moved.getString("id"))
  }

  @Test fun actionsApplyInOrderAndEachGetsAResult() {
    val r = applied(body(action("add", aid = "x1") { put("text", "Eggs") }, action("add", aid = "x2") { put("text", "eggs") }))
    assertEquals(listOf("applied", "duplicate"), (0 until 2).map { r.results.getJSONObject(it).getString("status") })
    assertEquals(listOf("x1", "x2"), (0 until 2).map { r.results.getJSONObject(it).getString("aid") })
  }
}
