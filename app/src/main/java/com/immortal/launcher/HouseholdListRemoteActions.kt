/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject

/**
 * Applies a phone's `POST /remote/list` batch (live edits or its offline queue). Every action is
 * merged under the normal rules even when `expectedRevision` is stale — an add is never lost — and
 * the response says `stale` so the phone re-renders from the returned state. Re-sending a batch is
 * harmless: each action writes registers stamped with its own time and device.
 */
internal object HouseholdListRemoteActions {
  const val MAX_ACTIONS = 50
  /** Oldest queued change accepted (a phone left in a drawer for a month is not a merge source). */
  const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
  private val AID = Regex("^[A-Za-z0-9_-]{1,64}$")
  private val OPS = setOf("add", "tick", "untick", "remove", "restore", "rename", "move", "clear_done")

  sealed class Result
  data class Applied(val state: HouseholdListState, val results: JSONArray, val stale: Boolean, val device: String) : Result()
  data class Rejected(val status: Int, val code: String) : Result()

  fun apply(state: HouseholdListState, body: JSONObject, nowMs: Long): Result {
    val actions = body.optJSONArray("actions") ?: return Rejected(400, "bad_actions")
    if (actions.length() == 0) return Rejected(400, "bad_actions")
    if (actions.length() > MAX_ACTIONS) return Rejected(413, "too_many_actions")
    val device = HouseholdListText.cleanName(body.opt("device") as? String) ?: return Rejected(400, "bad_device")
    val parsed = (0 until actions.length()).map { i ->
      val a = actions.optJSONObject(i) ?: return Rejected(400, "bad_actions")
      val aid = (a.opt("aid") as? String)?.takeIf(AID::matches) ?: return Rejected(400, "bad_actions")
      val at = (a.opt("at") as? Number)?.toLong()?.takeIf { it > 0 } ?: return Rejected(400, "bad_actions")
      Triple(aid, a, at.coerceIn(nowMs - MAX_AGE_MS, nowMs))
    }
    val expected = body.opt("expectedRevision") as? String
    val stale = expected != null && expected != state.digest
    val results = JSONArray()
    val next = parsed.fold(state) { s, (aid, a, at) ->
      val r = one(s, a, device, at)
      results.put(JSONObject().put("aid", aid).put("status", status(r.outcome)).put("id", idOf(r.outcome) ?: JSONObject.NULL))
      r.state
    }
    return Applied(next, results, stale, device)
  }

  private fun one(s: HouseholdListState, a: JSONObject, by: String, at: Long): HouseholdListOps.Result {
    val op = (a.opt("op") as? String)?.takeIf { it in OPS } ?: return invalid(s)
    if (op == "add") return HouseholdListOps.add(s, a.opt("text") as? String ?: return invalid(s), by, at)
    if (op == "clear_done") {
      val ids = a.optJSONArray("ids") ?: return invalid(s)
      if (ids.length() > HouseholdListOps.MAX_ITEMS) return invalid(s)
      val list = (0 until ids.length()).map { (ids.opt(it) as? String)?.takeIf(HouseholdListText::isValidId) ?: return invalid(s) }
      return HouseholdListOps.clearDone(s, list, by, at)
    }
    val id = (a.opt("id") as? String)?.takeIf(HouseholdListText::isValidId) ?: return invalid(s)
    return when (op) {
      "tick" -> HouseholdListOps.setDone(s, id, true, by, at)
      "untick" -> HouseholdListOps.setDone(s, id, false, by, at)
      "remove" -> HouseholdListOps.remove(s, id, by, at)
      "restore" -> HouseholdListOps.restore(s, id, by, at)
      "rename" -> HouseholdListOps.rename(s, id, a.opt("text") as? String ?: return invalid(s), by, at)
      "move" -> {
        val order = when (val v = a.opt("order")) { is Number -> v.toDouble(); else -> return invalid(s) }
        HouseholdListOps.move(s, id, order, by, at)
      }
      else -> invalid(s)
    }
  }

  private fun invalid(s: HouseholdListState) = HouseholdListOps.Result(s, HouseholdListOps.Outcome.Invalid)

  private fun status(o: HouseholdListOps.Outcome) = when (o) {
    is HouseholdListOps.Outcome.Applied, is HouseholdListOps.Outcome.MovedBack -> "applied"
    is HouseholdListOps.Outcome.Duplicate -> "duplicate"
    HouseholdListOps.Outcome.Missing -> "missing"
    HouseholdListOps.Outcome.Invalid -> "invalid"
  }

  private fun idOf(o: HouseholdListOps.Outcome) = when (o) {
    is HouseholdListOps.Outcome.Applied -> o.id
    is HouseholdListOps.Outcome.MovedBack -> o.id
    is HouseholdListOps.Outcome.Duplicate -> o.id
    else -> null
  }
}
