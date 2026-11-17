/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Lifecycle of a household timer. [wire] is the bus/route code. [rank] breaks a same-[SharedTimer.seq]
 * tie so every device settles on the same winner: an ending (stop/cancel) beats a ring, which beats
 * an edit. [live] timers are shown; the rest are short-lived tombstones that stop a late, older
 * message from bringing a finished timer back.
 */
internal enum class SharedTimerState(val wire: String, val rank: Int, val live: Boolean) {
  RUNNING("run", 0, true),
  PAUSED("pause", 0, true),
  RINGING("ring", 1, true),
  DONE("done", 2, false),
  CANCELLED("cancel", 2, false);

  companion object {
    fun fromWire(code: String?): SharedTimerState? = entries.firstOrNull { it.wire == code }
  }
}

/**
 * One household timer, the same model on every Portal and the phone. Clocks never need to agree:
 * [endAtMs] is THIS device's wall clock, derived on arrival from the remaining time the sender
 * measured (see [SharedTimerWire]). [seq] grows by one per change; the highest wins everywhere.
 *
 * @property origin the Portal that started it, or [PHONE_ORIGIN]; that room keeps its sound in
 *   quiet hours (see [SharedTimerRing]).
 * @property by the device that made the last change (the tie-break after [seq] and state rank).
 * @property changedAtMs this device's clock when the last change was accepted (tombstone pruning
 *   and the unanswered-ring timeout).
 */
internal data class SharedTimer(
    val id: String,
    val name: String,
    val room: String,
    val origin: String,
    val durationMs: Long,
    val endAtMs: Long,
    val remainingMs: Long,
    val state: SharedTimerState,
    val seq: Long,
    val by: String,
    val changedAtMs: Long,
) {
  val paused: Boolean get() = state == SharedTimerState.PAUSED

  /** Milliseconds left at [nowMs]: counted down while running, frozen while paused, else zero. */
  fun remaining(nowMs: Long): Long = when (state) {
    SharedTimerState.RUNNING -> (endAtMs - nowMs).coerceAtLeast(0L)
    SharedTimerState.PAUSED -> remainingMs.coerceAtLeast(0L)
    else -> 0L
  }

  companion object {
    const val PHONE_ORIGIN = "phone"
    const val DEFAULT_NAME = "Timer"
    const val MIN_DURATION_MS = 1_000L
    /** Tools allows up to 23:59:59; a few +1 min taps may go a little past a day. */
    const val MAX_DURATION_MS = 24 * 3_600_000L + 60 * 60_000L
    const val ADD_MS = 60_000L
    /** Live timers kept per household; more starts are refused. */
    const val MAX_LIVE = 20
    /** Stopped/cancelled timers are remembered this long so stale messages cannot revive them. */
    const val TOMBSTONE_MS = 10 * 60_000L
    /** A ring nobody stops ends on its own after this long (each device locally, no message). */
    const val RING_TIMEOUT_MS = 10 * 60_000L
  }
}

/**
 * Pure state changes. Each returns a NEW timer with [SharedTimer.seq] + 1, or null when the
 * action doesn't apply to the current state (pausing a paused timer, stopping one not ringing).
 */
internal object SharedTimerOps {
  fun create(
      id: String, name: String, room: String, origin: String, durationMs: Long, nowMs: Long, by: String,
  ): SharedTimer? {
    if (durationMs !in SharedTimer.MIN_DURATION_MS..SharedTimer.MAX_DURATION_MS) return null
    return SharedTimer(id, name.ifBlank { SharedTimer.DEFAULT_NAME }, room, origin, durationMs,
        endAtMs = nowMs + durationMs, remainingMs = durationMs, state = SharedTimerState.RUNNING,
        seq = 1L, by = by, changedAtMs = nowMs)
  }

  fun pause(t: SharedTimer, nowMs: Long, by: String): SharedTimer? =
      if (t.state != SharedTimerState.RUNNING) null
      else next(t, SharedTimerState.PAUSED, nowMs, by, endAt = 0L, remaining = t.remaining(nowMs))

  fun resume(t: SharedTimer, nowMs: Long, by: String): SharedTimer? =
      if (t.state != SharedTimerState.PAUSED) null
      else next(t, SharedTimerState.RUNNING, nowMs, by, endAt = nowMs + t.remainingMs, remaining = t.remainingMs)

  /** +1 min: a running or paused timer gets a minute more; a ringing one runs one more minute. */
  fun addMinute(t: SharedTimer, nowMs: Long, by: String): SharedTimer? {
    val duration = (t.durationMs + SharedTimer.ADD_MS).coerceAtMost(SharedTimer.MAX_DURATION_MS)
    return when (t.state) {
      SharedTimerState.RUNNING -> {
        val left = (t.remaining(nowMs) + SharedTimer.ADD_MS).coerceAtMost(SharedTimer.MAX_DURATION_MS)
        next(t, SharedTimerState.RUNNING, nowMs, by, endAt = nowMs + left, remaining = left).copy(durationMs = duration)
      }
      SharedTimerState.PAUSED -> {
        val left = (t.remainingMs + SharedTimer.ADD_MS).coerceAtMost(SharedTimer.MAX_DURATION_MS)
        next(t, SharedTimerState.PAUSED, nowMs, by, endAt = 0L, remaining = left).copy(durationMs = duration)
      }
      SharedTimerState.RINGING ->
        next(t, SharedTimerState.RUNNING, nowMs, by, endAt = nowMs + SharedTimer.ADD_MS, remaining = SharedTimer.ADD_MS)
            .copy(durationMs = duration)
      else -> null
    }
  }

  fun cancel(t: SharedTimer, nowMs: Long, by: String): SharedTimer? =
      if (!t.state.live) null else next(t, SharedTimerState.CANCELLED, nowMs, by, endAt = 0L, remaining = 0L)

  /** The countdown reached zero here (this device's alarm). */
  fun ring(t: SharedTimer, nowMs: Long, by: String): SharedTimer? =
      if (t.state != SharedTimerState.RUNNING) null
      else next(t, SharedTimerState.RINGING, nowMs, by, endAt = 0L, remaining = 0L)

  fun stop(t: SharedTimer, nowMs: Long, by: String): SharedTimer? =
      if (t.state != SharedTimerState.RINGING) null else next(t, SharedTimerState.DONE, nowMs, by, endAt = 0L, remaining = 0L)

  private fun next(t: SharedTimer, state: SharedTimerState, nowMs: Long, by: String, endAt: Long, remaining: Long) =
      t.copy(state = state, endAtMs = endAt, remainingMs = remaining, seq = t.seq + 1, by = by, changedAtMs = nowMs)
}
