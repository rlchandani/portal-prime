/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject

/** A validated phone command for `/remote/chores` (see [ChoreRequests]). */
internal sealed interface ChoreRequest {
  data class SaveKid(val kid: ChoreKid, val baseSeq: Long) : ChoreRequest
  data class DeleteKid(val id: String) : ChoreRequest
  data class SaveChore(val chore: Chore, val baseSeq: Long) : ChoreRequest
  data class DeleteChore(val id: String) : ChoreRequest
  data class Approve(val tick: String) : ChoreRequest
  data class Decline(val tick: String) : ChoreRequest
  data class TakeBack(val tick: String) : ChoreRequest
  data class Invalid(val code: String) : ChoreRequest
}

/**
 * POST bodies from the phone (untrusted). `{action, ...}`:
 * - `kid_save` `{kid:{id?, name, avatar, color, goal, reward, seq?}}` (no id = a new kid)
 * - `chore_save` `{chore:{id?, title, pic, kid ("" = anyone), stars, repeat: daily|days|once,
 *   days:[1..7], day: epochDay, needsOk, seq?}}`
 * - `kid_delete` / `chore_delete` `{id}`; `approve` / `decline` / `take_back` `{id: tickId}`
 * `seq` is the newest version the phone saw (on any Portal), so its edit wins the merge.
 */
internal object ChoreRequests {
  fun parse(body: JSONObject, newId: () -> String): ChoreRequest = when (val action = body.optString("action")) {
    "kid_save" -> kid(body.optJSONObject("kid"), newId)
    "chore_save" -> chore(body.optJSONObject("chore"), newId)
    "kid_delete" -> id(body)?.let { ChoreRequest.DeleteKid(it) } ?: ChoreRequest.Invalid("invalid_id")
    "chore_delete" -> id(body)?.let { ChoreRequest.DeleteChore(it) } ?: ChoreRequest.Invalid("invalid_id")
    "approve" -> id(body)?.let { ChoreRequest.Approve(it) } ?: ChoreRequest.Invalid("invalid_id")
    "decline" -> id(body)?.let { ChoreRequest.Decline(it) } ?: ChoreRequest.Invalid("invalid_id")
    "take_back" -> id(body)?.let { ChoreRequest.TakeBack(it) } ?: ChoreRequest.Invalid("invalid_id")
    "" -> ChoreRequest.Invalid("missing_action")
    else -> ChoreRequest.Invalid("unknown_action")
  }

  fun apply(s: ChoreState, r: ChoreRequest, by: String): ChoreResult = when (r) {
    is ChoreRequest.SaveKid -> ChoreOps.saveKid(s, r.kid, by, r.baseSeq)
    is ChoreRequest.DeleteKid -> ChoreOps.deleteKid(s, r.id, by)
    is ChoreRequest.SaveChore -> ChoreOps.saveChore(s, r.chore, by, r.baseSeq)
    is ChoreRequest.DeleteChore -> ChoreOps.deleteChore(s, r.id, by)
    is ChoreRequest.Approve -> ChoreOps.approve(s, r.tick, by)
    is ChoreRequest.Decline -> ChoreOps.decline(s, r.tick, by)
    is ChoreRequest.TakeBack -> ChoreOps.undo(s, r.tick, by, parent = true)
    is ChoreRequest.Invalid -> ChoreResult.Refused(r.code)
  }

  private fun kid(j: JSONObject?, newId: () -> String): ChoreRequest {
    j ?: return ChoreRequest.Invalid("invalid_kid")
    val id = idOrNew(j, newId) ?: return ChoreRequest.Invalid("invalid_id")
    val name = ChoreText.clean(j.opt("name") as? String, ChoreLimits.NAME_MAX) ?: return ChoreRequest.Invalid("invalid_name")
    val avatar = (j.opt("avatar") as? String)?.takeIf(ChoreAvatars::isValid) ?: return ChoreRequest.Invalid("invalid_avatar")
    val color = ChoreCodec.int(j.opt("color"))?.takeIf { it in 0 until ChoreLimits.COLORS } ?: return ChoreRequest.Invalid("invalid_color")
    val goal = ChoreCodec.int(j.opt("goal") ?: 0)?.takeIf { it in 0..ChoreLimits.MAX_GOAL } ?: return ChoreRequest.Invalid("invalid_goal")
    val reward = when (val raw = j.opt("reward")) {
      null, JSONObject.NULL -> ""
      is String -> if (raw.isBlank()) "" else ChoreText.clean(raw, ChoreLimits.REWARD_MAX) ?: return ChoreRequest.Invalid("invalid_reward")
      else -> return ChoreRequest.Invalid("invalid_reward")
    }
    return ChoreRequest.SaveKid(ChoreKid(id, name, avatar, color, goal, reward, seq = 0L, by = ""), baseSeq(j))
  }

  private fun chore(j: JSONObject?, newId: () -> String): ChoreRequest {
    j ?: return ChoreRequest.Invalid("invalid_chore")
    val id = idOrNew(j, newId) ?: return ChoreRequest.Invalid("invalid_id")
    val title = ChoreText.clean(j.opt("title") as? String, ChoreLimits.TITLE_MAX) ?: return ChoreRequest.Invalid("invalid_title")
    val pic = (j.opt("pic") as? String)?.takeIf(RoutinePictures::isValid) ?: return ChoreRequest.Invalid("invalid_pic")
    val kid = when (val raw = j.opt("kid")) {
      null, JSONObject.NULL, "" -> null
      is String -> raw.takeIf(ChoreText::isId) ?: return ChoreRequest.Invalid("invalid_kid")
      else -> return ChoreRequest.Invalid("invalid_kid")
    }
    val stars = ChoreCodec.int(j.opt("stars"))?.takeIf { it in ChoreLimits.MIN_STARS..ChoreLimits.MAX_STARS }
        ?: return ChoreRequest.Invalid("invalid_stars")
    val schedule = schedule(j) ?: return ChoreRequest.Invalid("invalid_schedule")
    val needsOk = when (val raw = j.opt("needsOk")) { null -> false; is Boolean -> raw; else -> return ChoreRequest.Invalid("invalid_chore") }
    return ChoreRequest.SaveChore(Chore(id, title, pic, kid, stars, schedule, needsOk, seq = 0L, by = ""), baseSeq(j))
  }

  private fun schedule(j: JSONObject): ChoreSchedule? = when (j.opt("repeat")) {
    "daily" -> ChoreSchedule.Daily
    "days" -> j.optJSONArray("days")?.let { ChoreCodec.schedule(JSONObject().put("w", "w").put("wd", it)) }
    "once" -> ChoreCodec.int(j.opt("day"))?.takeIf(ChoreCodec::isDay)?.let { ChoreSchedule.Once(it) }
    else -> null
  }

  private fun idOrNew(j: JSONObject, newId: () -> String): String? = when (val raw = j.opt("id")) {
    null, JSONObject.NULL, "" -> newId()
    is String -> raw.takeIf(ChoreText::isId)
    else -> null
  }

  private fun id(body: JSONObject): String? = (body.opt("id") as? String)?.takeIf(ChoreText::isId)

  private fun baseSeq(j: JSONObject): Long = ChoreCodec.long(j.opt("seq"))?.coerceIn(0L, Long.MAX_VALUE / 4) ?: 0L
}
