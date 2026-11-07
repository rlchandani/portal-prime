/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject

/**
 * Phone edits to the routine document, applied purely. Every edit carries the revision the phone
 * last saw (`expectedRev`); a mismatch is [Result.Stale] so two phones can't silently overwrite each
 * other. Actions: upsert, delete, skip, kids.
 */
internal object RoutineMutations {
  private const val SKIP_WINDOW_DAYS = 14

  sealed interface Result {
    data class Ok(val doc: RoutineDoc) : Result
    data class Stale(val doc: RoutineDoc) : Result
    data class Invalid(val code: String) : Result
  }

  val actions = setOf("upsert", "delete", "skip", "kids")

  fun apply(doc: RoutineDoc, body: JSONObject, today: Int): Result {
    val action = body.optString("action")
    if (action !in actions) return Result.Invalid("unknown_action")
    val expected = body.opt("expectedRev") as? Number ?: return Result.Invalid("missing_revision")
    if (expected.toLong() != doc.rev) return Result.Stale(doc)
    return when (action) {
      "upsert" -> upsert(doc, body, today)
      "delete" -> delete(doc, body.optString("id"))
      "skip" -> skip(doc, body, today)
      else -> kids(doc, body)
    }
  }

  /** Parses and checks a routine against [doc] without saving it (also used by preview). */
  fun candidate(doc: RoutineDoc, json: JSONObject?, today: Int): Parsed<Routine> {
    val parsed = RoutineCodec.parseRoutine(json)
    if (parsed !is Parsed.Ok) return parsed
    val r = parsed.value.copy(skips = parsed.value.skips.filter { it >= today })
    return RoutineRules.check(r, doc)?.let { Parsed.Bad(it) } ?: Parsed.Ok(r)
  }

  private fun next(doc: RoutineDoc, routines: List<Routine>, kids: List<RoutineKid> = doc.kids) =
      Result.Ok(RoutineDoc(doc.rev + 1, routines, kids))

  private fun upsert(doc: RoutineDoc, body: JSONObject, today: Int): Result {
    val r = when (val c = candidate(doc, body.optJSONObject("routine"), today)) {
      is Parsed.Bad -> return Result.Invalid(c.code)
      is Parsed.Ok -> c.value
    }
    val exists = doc.routines.any { it.id == r.id }
    return next(doc, if (exists) doc.routines.map { if (it.id == r.id) r else it } else doc.routines + r)
  }

  private fun delete(doc: RoutineDoc, id: String): Result {
    if (doc.routines.none { it.id == id }) return Result.Invalid("not_found")
    return next(doc, doc.routines.filter { it.id != id })
  }

  private fun skip(doc: RoutineDoc, body: JSONObject, today: Int): Result {
    val r = doc.routines.firstOrNull { it.id == body.optString("id") } ?: return Result.Invalid("not_found")
    val day = RoutineDates.epochDayOf(body.optString("date")) ?: return Result.Invalid("invalid_skip")
    val on = body.opt("skip") as? Boolean ?: return Result.Invalid("invalid_skip")
    if (day < today || day >= today + SKIP_WINDOW_DAYS) return Result.Invalid("invalid_skip")
    val skips = (r.skips.filter { it >= today && it != day } + if (on) listOf(day) else emptyList()).sorted()
    return next(doc, doc.routines.map { if (it.id == r.id) it.copy(skips = skips) else it })
  }

  private fun kids(doc: RoutineDoc, body: JSONObject): Result {
    val kids = when (val p = RoutineCodec.parseKids(body.optJSONArray("kids"))) {
      is Parsed.Bad -> return Result.Invalid(p.code)
      is Parsed.Ok -> p.value
    }
    val ids = kids.map { it.id }.toSet()
    return next(doc, doc.routines.map { r -> r.copy(kids = r.kids.filter { it in ids }) }, kids)
  }
}
