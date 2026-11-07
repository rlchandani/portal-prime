/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject

internal enum class RunStatus { RUNNING, DONE, CLOSED }

/**
 * Today's run of one routine on this Portal. Times are local wall-clock ms ([RoutineDates]).
 * [ticks] maps a kid id (or "all") to the ticked card ids. [endedBy]: done, auto, parent, phone,
 * removed.
 */
internal data class RoutineRun(
    val rid: String,
    val day: Int,
    val startMs: Long,
    val deadlineMs: Long,
    val status: RunStatus = RunStatus.RUNNING,
    val endedAtMs: Long? = null,
    val endedBy: String? = null,
    val ticks: Map<String, List<String>> = emptyMap(),
)

/** Pure run transitions; every function returns a new [RoutineRun]. */
internal object RoutineRunLogic {
  const val ALL = "all"
  const val GRACE_MIN = 30 // README: an unfinished routine closes 30 min after Ready by

  enum class TickEvent { TICKED, UNTICKED, KID_DONE, ALL_DONE, IGNORED }
  data class TickResult(val run: RoutineRun, val event: TickEvent)
  data class TimeLine(val main: String, val sub: String, val goal: String, val late: Boolean, val fraction: Float)

  /** README: a tab per kid when two or more kids share a routine; otherwise one shared set. */
  fun kidKeys(r: Routine): List<String> = if (r.kids.size > 1) r.kids else listOf(ALL)

  fun doneCount(r: Routine, run: RoutineRun, kid: String): Int {
    val ticked = run.ticks[kid].orEmpty()
    return r.cards.count { it.id in ticked }
  }

  /** (done, total) across every kid. */
  fun totals(r: Routine, run: RoutineRun): Pair<Int, Int> =
      kidKeys(r).sumOf { doneCount(r, run, it) } to r.cards.size * kidKeys(r).size

  private fun kidDone(r: Routine, run: RoutineRun, kid: String) = doneCount(r, run, kid) == r.cards.size

  fun toggle(r: Routine, run: RoutineRun, cardId: String, kid: String, nowMs: Long): TickResult {
    if (run.status != RunStatus.RUNNING || kid !in kidKeys(r) || r.cards.none { it.id == cardId }) {
      return TickResult(run, TickEvent.IGNORED)
    }
    val list = run.ticks[kid].orEmpty()
    val on = cardId in list
    val ticked = run.copy(ticks = run.ticks + (kid to if (on) list - cardId else list + cardId))
    return when {
      on -> TickResult(ticked, TickEvent.UNTICKED)
      kidKeys(r).all { kidDone(r, ticked, it) } -> TickResult(close(ticked, "done", nowMs), TickEvent.ALL_DONE)
      kid != ALL && kidDone(r, ticked, kid) -> TickResult(ticked, TickEvent.KID_DONE)
      else -> TickResult(ticked, TickEvent.TICKED)
    }
  }

  /** The next kid with cards left after [kid], or [kid] itself when everyone else is done. */
  fun nextKid(r: Routine, run: RoutineRun, kid: String): String =
      kidKeys(r).firstOrNull { it != kid && !kidDone(r, run, it) } ?: kid

  fun close(run: RoutineRun, by: String, nowMs: Long): RoutineRun = run.copy(
      status = if (by == "done") RunStatus.DONE else RunStatus.CLOSED, endedAtMs = nowMs, endedBy = by)

  /** Undo for a parent/phone close (the toast's Undo); null for anything else. */
  fun undoClose(run: RoutineRun): RoutineRun? =
      if (run.status == RunStatus.CLOSED && run.endedBy in setOf("parent", "phone"))
        run.copy(status = RunStatus.RUNNING, endedAtMs = null, endedBy = null) else null

  fun graceEndMs(run: RoutineRun): Long =
      minOf(run.deadlineMs + GRACE_MIN * RoutineDates.MINUTE_MS, RoutineDates.localMs(run.day + 1, 0))

  /** The time bar: minutes left, or the warm "Time to go" once Ready by passes (README: no red). */
  fun timeLine(run: RoutineRun, nowMs: Long): TimeLine {
    val goal = RoutineDates.clock12(RoutineDates.minuteOfDay(run.deadlineMs))
    if (nowMs >= run.deadlineMs) {
      val over = maxOf(1L, (nowMs - run.deadlineMs) / RoutineDates.MINUTE_MS)
      return TimeLine("Time to go", "Finish what you can on the way", "$over min past $goal", true, 0f)
    }
    val leftMs = run.deadlineMs - nowMs
    val left = (leftMs + RoutineDates.MINUTE_MS - 1) / RoutineDates.MINUTE_MS
    val span = (run.deadlineMs - run.startMs).coerceAtLeast(1L)
    return TimeLine(if (left <= 1) "Almost time" else "$left min left", "Ready by $goal", goal, false,
        (leftMs.toFloat() / span).coerceIn(0f, 1f))
  }

  fun cheerLine(run: RoutineRun, nowMs: Long): String {
    val at = RoutineDates.clock12(RoutineDates.minuteOfDay(nowMs))
    val early = Math.round((run.deadlineMs - nowMs) / RoutineDates.MINUTE_MS.toDouble())
    return if (early > 0) "Ready at $at, $early min early" else "Ready at $at"
  }

  fun runJson(run: RoutineRun): JSONObject = JSONObject().put("rid", run.rid).put("day", run.day)
      .put("startMs", run.startMs).put("deadlineMs", run.deadlineMs).put("status", run.status.name)
      .put("endedAtMs", run.endedAtMs ?: JSONObject.NULL).put("endedBy", run.endedBy ?: JSONObject.NULL)
      .put("ticks", JSONObject().apply { run.ticks.forEach { (k, v) -> put(k, JSONArray(v)) } })

  fun parseRun(j: JSONObject): RoutineRun? = runCatching {
    val ticksJson = j.getJSONObject("ticks")
    val ticks = ticksJson.keys().asSequence().associateWith { k ->
      ticksJson.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } }
    }
    RoutineRun(j.getString("rid"), j.getInt("day"), j.getLong("startMs"), j.getLong("deadlineMs"),
        RunStatus.valueOf(j.getString("status")),
        if (j.isNull("endedAtMs")) null else j.getLong("endedAtMs"),
        if (j.isNull("endedBy")) null else j.getString("endedBy"), ticks)
  }.getOrNull()
}
