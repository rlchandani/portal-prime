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
import org.junit.Assert.*
import org.junit.Test

class HouseholdListRemoteActionsTest {
  private val now = 1_800_000_000_000L
  private val start = HouseholdListOps.add(HouseholdListState(), "Eggs", "Family Area", now - 60_000).state
  private val eggs = HouseholdListText.idFor("Eggs")

  private fun action(aid: String, op: String, at: Long = now - 1_000, f: JSONObject.() -> Unit = {}) =
      JSONObject().put("aid", aid).put("op", op).put("at", at).apply(f)

  private fun body(vararg actions: JSONObject, rev: String? = start.digest) = JSONObject()
      .put("device", "Rohit’s Pixel").put("actions", JSONArray(actions.toList()))
      .apply { if (rev != null) put("expectedRevision", rev) }

  private fun run(b: JSONObject, s: HouseholdListState = start) = HouseholdListRemoteActions.apply(s, b, now)

  @Test fun addsAndTicks_inOrder_withPerActionResults() {
    val r = run(body(action("a1", "add") { put("text", "Milk") }, action("a2", "tick") { put("id", eggs) })) as Applied
    assertFalse(r.stale)
    assertEquals(listOf("Milk"), r.state.open().map { it.text.value })
    assertEquals("Rohit’s Pixel", r.state.done().single().done.stamp.by)
    assertEquals("applied", r.results.getJSONObject(0).getString("status"))
    assertEquals(HouseholdListText.idFor("Milk"), r.results.getJSONObject(0).getString("id"))
    assertEquals("a2", r.results.getJSONObject(1).getString("aid"))
  }

  @Test fun staleRevision_stillMergesTheAdd_andSaysSo() {
    val r = run(body(action("a1", "add") { put("text", "Yogurt") }, rev = "000000000000")) as Applied
    assertTrue(r.stale)
    assertNotNull(r.state.liveByText("Yogurt"))
  }

  @Test fun replayingABatch_isIdempotent() {
    val b = body(action("a1", "add") { put("text", "Milk") }, action("a2", "tick") { put("id", eggs) })
    val once = (run(b) as Applied).state
    val twice = (run(b, once) as Applied).state
    assertEquals(once, twice)
  }

  @Test fun duplicatesMissingAndInvalidActions_areReportedNotFatal() {
    val r = run(body(action("a1", "add") { put("text", "eggs") }, action("a2", "tick") { put("id", "h0000000000000ff") },
        action("a3", "explode"), action("a4", "move") { put("id", eggs); put("order", "NaN") })) as Applied
    assertEquals(listOf("duplicate", "missing", "invalid", "invalid"),
        (0 until 4).map { r.results.getJSONObject(it).getString("status") })
  }

  @Test fun futureTimesAreClamped_soAFastPhoneClockCannotWinForever() {
    val r = run(body(action("a1", "tick", at = now + 3_600_000) { put("id", eggs) })) as Applied
    assertEquals(now, r.state.items.getValue(eggs).done.stamp.at)
  }

  @Test fun structuralProblems_rejectTheBatch() {
    assertEquals(Rejected(400, "bad_actions"), run(JSONObject().put("device", "x")))
    assertEquals(Rejected(400, "bad_device"), run(body(action("a1", "add") { put("text", "Milk") }).put("device", "")))
    assertEquals(Rejected(400, "bad_actions"), run(body(action("bad aid!", "add") { put("text", "Milk") })))
    assertEquals(Rejected(400, "bad_actions"), run(body(action("a1", "add", at = -5) { put("text", "Milk") })))
    val many = (1..51).map { action("a$it", "add") { put("text", "Thing $it") } }.toTypedArray()
    assertEquals(Rejected(413, "too_many_actions"), run(body(*many)))
  }

  @Test fun clearDoneAndRestore_roundTrip() {
    val ticked = (run(body(action("a1", "tick") { put("id", eggs) })) as Applied).state
    val cleared = (run(body(action("a2", "clear_done", at = now - 500) { put("ids", JSONArray(listOf(eggs))) }), ticked) as Applied).state
    assertTrue(cleared.live().isEmpty())
    val restored = (run(body(action("a3", "restore", at = now - 100) { put("id", eggs) }), cleared) as Applied).state
    assertEquals(1, restored.done().size)
  }
}
