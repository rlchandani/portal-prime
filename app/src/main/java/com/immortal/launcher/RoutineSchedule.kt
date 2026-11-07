/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Pure scheduling: when routines start, when runs close, and the Home/sheet status copy. */
internal object RoutineSchedule {
  private const val LOOKAHEAD_DAYS = 14

  enum class Skip { OFF, DAY, DATE }
  enum class Tone { NORMAL, LIVE, DONE }
  data class Status(val text: String, val tone: Tone)
  data class Occurrence(val day: Int, val startMs: Long, val label: String)

  fun skipReason(r: Routine, day: Int): Skip? = when {
    !r.enabled -> Skip.OFF
    !r.days[RoutineDates.weekday(day)] -> Skip.DAY
    day in r.skips -> Skip.DATE
    else -> null
  }

  /** A running run, if any (one at a time per Portal). */
  fun active(runs: Map<String, RoutineRun>): RoutineRun? = runs.values.firstOrNull { it.status == RunStatus.RUNNING }

  /** The routine that should take over now: inside its window, not skipped, not yet run today. */
  fun dueToStart(doc: RoutineDoc, runs: Map<String, RoutineRun>, nowMs: Long): Routine? {
    if (active(runs) != null) return null
    val day = RoutineDates.day(nowMs); val m = RoutineDates.minuteOfDay(nowMs)
    return doc.routines.sortedBy { it.start }.firstOrNull { r ->
      skipReason(r, day) == null && m >= r.start && m < r.end && runs[r.id]?.day != day
    }
  }

  /** A scheduled run ends at Ready by; "Start now" gets a full routine length from now. */
  fun startRun(r: Routine, nowMs: Long, manual: Boolean): RoutineRun {
    val day = RoutineDates.day(nowMs)
    val scheduled = RoutineDates.localMs(day, r.end)
    val deadline = if (manual) maxOf(scheduled, nowMs + (r.end - r.start) * RoutineDates.MINUTE_MS) else scheduled
    return RoutineRun(r.id, day, nowMs, deadline)
  }

  /** Running runs that must close now, with why ("auto" past grace or midnight, "removed"). */
  fun expired(doc: RoutineDoc, runs: Map<String, RoutineRun>, nowMs: Long): List<Pair<RoutineRun, String>> =
      runs.values.filter { it.status == RunStatus.RUNNING }.mapNotNull { run ->
        when {
          doc.routines.none { it.id == run.rid } -> run to "removed"
          nowMs >= RoutineRunLogic.graceEndMs(run) -> run to "auto"
          else -> null
        }
      }

  /** The next local time anything must happen (a start or an auto-close), for one alarm. */
  fun nextWake(doc: RoutineDoc, runs: Map<String, RoutineRun>, nowMs: Long): Long? {
    val closes = runs.values.filter { it.status == RunStatus.RUNNING }.map(RoutineRunLogic::graceEndMs)
    val starts = doc.routines.mapNotNull { nextOccurrence(it, runs, nowMs)?.startMs }
    // Only future times: a past one would re-fire immediately (busy retries are armed separately).
    return (closes + starts).filter { it > nowMs }.minOrNull()
  }

  fun nextOccurrence(r: Routine, runs: Map<String, RoutineRun>, nowMs: Long): Occurrence? {
    val today = RoutineDates.day(nowMs)
    for (d in 0 until LOOKAHEAD_DAYS) {
      val day = today + d
      if (skipReason(r, day) != null) continue
      if (d == 0 && (RoutineDates.minuteOfDay(nowMs) >= r.end || runs[r.id]?.day == day)) continue
      val label = when {
        d == 0 -> "Today"
        d == 1 -> "Tomorrow"
        d < 7 -> RoutineDates.DAY_NAMES[RoutineDates.weekday(day)]
        else -> shortDate(day)
      }
      return Occurrence(day, RoutineDates.localMs(day, r.start), label)
    }
    return null
  }

  private fun shortDate(day: Int): String {
    val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val iso = RoutineDates.isoOf(day)
    return "${RoutineDates.DAY_NAMES[RoutineDates.weekday(day)].take(3)} ${iso.substring(8).toInt()} ${months[iso.substring(5, 7).toInt() - 1]}"
  }

  private fun clockOf(ms: Long) = RoutineDates.clock12(RoutineDates.minuteOfDay(ms))

  /** The Routines widget row. */
  fun widgetStatus(r: Routine, runs: Map<String, RoutineRun>, nowMs: Long): Status {
    val day = RoutineDates.day(nowMs)
    val run = runs[r.id]?.takeIf { it.day == day }
    run?.let {
      return when (it.status) {
        RunStatus.RUNNING -> Status("On now", Tone.LIVE)
        RunStatus.DONE -> Status("Done at ${clockOf(it.endedAtMs ?: nowMs)}", Tone.DONE)
        RunStatus.CLOSED -> Status("Closed at ${clockOf(it.endedAtMs ?: nowMs)}", Tone.NORMAL)
      }
    }
    val why = skipReason(r, day)
    if (why == Skip.OFF) return Status("Off", Tone.NORMAL)
    if (why == Skip.DATE && RoutineDates.minuteOfDay(nowMs) < r.end) return Status("Skipped today", Tone.NORMAL)
    val next = nextOccurrence(r, runs, nowMs) ?: return Status("Not scheduled", Tone.NORMAL)
    return Status("${next.label}, ${RoutineDates.clock12(r.start)}", Tone.NORMAL)
  }

  /** The Routines sheet line. */
  fun statusLine(r: Routine, runs: Map<String, RoutineRun>, nowMs: Long): String {
    val run = runs[r.id]?.takeIf { it.day == RoutineDates.day(nowMs) }
    return when {
      run?.status == RunStatus.RUNNING -> "On now"
      run?.status == RunStatus.DONE -> "Done today at ${clockOf(run.endedAtMs ?: nowMs)}"
      run?.status == RunStatus.CLOSED -> "Closed today at ${clockOf(run.endedAtMs ?: nowMs)}"
      !r.enabled -> "Switched off in Toolkit"
      else -> nextOccurrence(r, runs, nowMs)?.let { "Next: ${it.label}, ${RoutineDates.clock12(r.start)}" } ?: "Not scheduled"
    }
  }

  fun daysText(days: List<Boolean>): String = when {
    days.all { it } -> "Every day"
    days.take(5).all { it } && !days[5] && !days[6] -> "Monday to Friday"
    else -> days.indices.filter { days[it] }.joinToString(", ") { RoutineDates.DAY_NAMES[it].take(3) }
  }
}
