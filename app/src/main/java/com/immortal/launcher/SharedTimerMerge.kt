/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Household convergence rules for [SharedTimer]s, pure so every device (and the tests) agree.
 * Last writer wins by ([SharedTimer.seq], state rank, [SharedTimer.by]); an identical version is a
 * duplicate (a repeat datagram or a second relay) and changes nothing — in particular it does not
 * re-anchor a running countdown.
 */
internal object SharedTimerMerge {
  enum class Outcome { APPLIED, DUPLICATE, STALE, REJECTED }

  data class Result(val timers: List<SharedTimer>, val outcome: Outcome, val before: SharedTimer?, val after: SharedTimer?)

  private val order = compareBy<SharedTimer>({ it.seq }, { it.state.rank }, { it.by })

  /** Positive when [a] should replace [b]. */
  fun compare(a: SharedTimer, b: SharedTimer): Int = order.compare(a, b)

  /** Folds [incoming] into [timers]; a new live timer past [SharedTimer.MAX_LIVE] is refused. */
  fun merge(timers: List<SharedTimer>, incoming: SharedTimer): Result {
    val local = timers.firstOrNull { it.id == incoming.id }
    if (local == null) {
      if (incoming.state.live && timers.count { it.state.live } >= SharedTimer.MAX_LIVE) {
        return Result(timers, Outcome.REJECTED, null, null)
      }
      return Result(timers + incoming, Outcome.APPLIED, null, incoming)
    }
    val c = compare(incoming, local)
    return when {
      c > 0 -> Result(timers.map { if (it.id == incoming.id) incoming else it }, Outcome.APPLIED, local, incoming)
      c == 0 -> Result(timers, Outcome.DUPLICATE, local, local)
      else -> Result(timers, Outcome.STALE, local, local)
    }
  }

  /** Drops tombstones older than [SharedTimer.TOMBSTONE_MS] (and any from a clock jump backwards). */
  fun prune(timers: List<SharedTimer>, nowMs: Long): List<SharedTimer> = timers.filter { t ->
    t.state.live || (nowMs - t.changedAtMs) in 0L..SharedTimer.TOMBSTONE_MS
  }

  /** Ringing timers nobody stopped within [SharedTimer.RING_TIMEOUT_MS]. */
  fun unanswered(timers: List<SharedTimer>, nowMs: Long): List<SharedTimer> = timers.filter {
    it.state == SharedTimerState.RINGING && nowMs - it.changedAtMs >= SharedTimer.RING_TIMEOUT_MS
  }

  /** The order timers are listed in everywhere: ringing first, then soonest to end, paused last. */
  fun display(timers: List<SharedTimer>, nowMs: Long): List<SharedTimer> = timers.filter { it.state.live }
      .sortedWith(compareBy<SharedTimer>({ displayGroup(it) }, { it.remaining(nowMs) }, { it.id }))

  private fun displayGroup(t: SharedTimer): Int = when (t.state) {
    SharedTimerState.RINGING -> 0
    SharedTimerState.RUNNING -> 1
    else -> 2
  }
}
