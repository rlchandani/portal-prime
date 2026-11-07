/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject

/** What the phone route asks of the running engine; implemented on the main thread. */
internal interface RoutineActions {
  /** The document changed: re-arm alarms and refresh Home. */
  fun changed()
  /** Closes today's running run of [rid] ("Close for today" in Toolkit). False if not running. */
  fun closeFromPhone(rid: String): Boolean
  /** Shows [routine] for 90 s without saving ticks. False when a routine is running. */
  fun preview(routine: Routine): Boolean
}

/**
 * `/remote/routines` behavior (auth, body limits and JSON parsing are done by the companion
 * server). GET: the document plus today's progress. POST `action`: upsert, delete, skip, kids
 * (each with `expectedRev`), close, preview. A save counts only once this returns 200.
 */
internal class RoutinesApi(private val repo: RoutineRepository, private val actions: RoutineActions) {
  fun handle(method: String, body: JSONObject): Pair<Int, JSONObject> = when (method) {
    "GET" -> try { 200 to snapshot(repo.docOrThrow()) } catch (_: RoutineRepository.Unreadable) { 503 to err("storage_unreadable") }
    "POST" -> post(body)
    else -> 405 to err("method_not_allowed")
  }

  private fun post(body: JSONObject): Pair<Int, JSONObject> = when (val action = body.optString("action")) {
    "close" -> if (actions.closeFromPhone(body.optString("id"))) 200 to snapshot(repo.doc()) else 409 to err("not_running")
    "preview" -> preview(body)
    in RoutineMutations.actions -> when (val o = repo.mutate(body)) {
      is RoutineRepository.Outcome.Saved -> { actions.changed(); 200 to snapshot(o.doc) }
      is RoutineRepository.Outcome.Stale -> 409 to snapshot(o.doc).put("ok", false).put("error", "stale_revision")
      is RoutineRepository.Outcome.Invalid -> 400 to err(o.code)
      RoutineRepository.Outcome.StorageFull -> 507 to err("storage_full")
      RoutineRepository.Outcome.Unavailable -> 503 to err("storage_unreadable")
    }
    else -> 400 to err(if (action.isEmpty()) "missing_action" else "unknown_action")
  }

  private fun preview(body: JSONObject): Pair<Int, JSONObject> {
    val today = RoutineDates.day(repo.clock.nowLocalMs())
    // A preview may overlap the routine it edits, so only the routine's own fields are checked.
    val routine = when (val p = RoutineCodec.parseRoutine(body.optJSONObject("routine"))) {
      is Parsed.Bad -> return 400 to err(p.code)
      is Parsed.Ok -> p.value.copy(skips = p.value.skips.filter { it >= today })
    }
    return if (actions.preview(routine)) 200 to RoutineFeatureJson.ok() else 409 to err("busy")
  }

  private fun err(code: String) = RoutineFeatureJson.error(code)

  private fun snapshot(doc: RoutineDoc): JSONObject {
    val now = repo.clock.nowLocalMs()
    val runs = repo.runs()
    val today = JSONArray()
    runs.values.forEach { run ->
      val r = doc.routines.firstOrNull { it.id == run.rid } ?: return@forEach
      val (done, total) = RoutineRunLogic.totals(r, run)
      today.put(JSONObject().put("rid", run.rid).put("status", run.status.name.lowercase())
          .put("endedBy", run.endedBy ?: JSONObject.NULL)
          .put("endedAt", run.endedAtMs?.let(RoutineDates::minuteOfDay) ?: JSONObject.NULL)
          .put("deadline", RoutineDates.minuteOfDay(run.deadlineMs))
          .put("leftMin", ((run.deadlineMs - now) / RoutineDates.MINUTE_MS).toInt())
          .put("done", done).put("total", total))
    }
    return RoutineCodec.docJson(doc).put("ok", true).put("today", today)
        .put("day", RoutineDates.day(now)).put("minute", RoutineDates.minuteOfDay(now))
  }
}

/** The route envelope without touching Android classes (keeps [RoutinesApi] JVM-testable). */
internal object RoutineFeatureJson {
  fun ok(): JSONObject = JSONObject().put("ok", true)
  fun error(code: String): JSONObject = JSONObject().put("ok", false).put("error", code)
}
