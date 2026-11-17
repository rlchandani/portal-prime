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
 * [HouseholdBus] bodies for shared timers. Like follow-me, a running timer travels as the time
 * LEFT when it was sent (`rem`), never as a wall-clock deadline, so Portal clocks need not agree:
 * the receiver re-anchors it to its own clock on arrival.
 * - `timer_set` (started), `timer_update` (paused/resumed/+1 min/cancelled/stopped) and
 *   `timer_done` (rang here) all carry the full timer `{i, n, r, o, d, rem, s, q, by}`; receivers
 *   merge them the same way (dedupe by id, last writer wins by `q`), so a lost or reordered message
 *   is repaired by the next one.
 * - `timer_ask` `{}`: a Portal that just started asks the others to resend their live timers.
 * The bus already checks signature, freshness and replay; decoders still treat every field as untrusted.
 */
internal object SharedTimerWire {
  const val SET = "timer_set"
  const val UPDATE = "timer_update"
  const val DONE = "timer_done"
  const val ASK = "timer_ask"
  /** Messages that carry one timer. */
  val TIMER_TYPES = listOf(SET, UPDATE, DONE)

  /** New ids are 12 hex chars; kitchen timers adopted from before shared timers keep theirs. */
  private val ID = Regex("^[a-z0-9][a-z0-9-]{3,39}$")
  private const val MAX_SEQ = Long.MAX_VALUE / 2

  fun isId(text: String?): Boolean = text != null && ID.matches(text)

  fun encode(t: SharedTimer, nowMs: Long): JSONObject = JSONObject()
      .put("i", t.id).put("n", t.name).put("r", t.room).put("o", t.origin)
      .put("d", t.durationMs).put("rem", t.remaining(nowMs)).put("s", t.state.wire)
      .put("q", t.seq).put("by", t.by)

  /** A peer's timer re-anchored to this device's clock at [nowMs], or null when anything is off. */
  fun decode(b: JSONObject, nowMs: Long): SharedTimer? {
    val id = (b.opt("i") as? String)?.takeIf(::isId) ?: return null
    val name = DinnerWire.cleanName(b.opt("n") as? String) ?: return null
    val room = DinnerWire.cleanName(b.opt("r") as? String) ?: return null
    val origin = (b.opt("o") as? String)?.takeIf(::isOrigin) ?: return null
    val by = (b.opt("by") as? String)?.takeIf(::isOrigin) ?: return null
    val state = SharedTimerState.fromWire(b.opt("s") as? String) ?: return null
    val duration = number(b.opt("d"))?.takeIf { it in SharedTimer.MIN_DURATION_MS..SharedTimer.MAX_DURATION_MS } ?: return null
    val remaining = number(b.opt("rem"))?.takeIf { it in 0L..SharedTimer.MAX_DURATION_MS } ?: return null
    val seq = number(b.opt("q"))?.takeIf { it in 1L..MAX_SEQ } ?: return null
    return SharedTimer(id, name, room, origin, duration,
        endAtMs = if (state == SharedTimerState.RUNNING) nowMs + remaining else 0L,
        remainingMs = if (state == SharedTimerState.PAUSED || state == SharedTimerState.RUNNING) remaining else 0L,
        state = state, seq = seq, by = by, changedAtMs = nowMs)
  }

  fun isOrigin(text: String?): Boolean = text == SharedTimer.PHONE_ORIGIN || DinnerWire.isDeviceId(text)

  /** The phone's view (`/remote/timers`): durations only, never this Portal's clock. */
  fun toRouteJson(t: SharedTimer, nowMs: Long, selfId: String?): JSONObject = JSONObject()
      .put("id", t.id).put("name", t.name).put("room", t.room)
      .put("durationMs", t.durationMs).put("remainingMs", t.remaining(nowMs))
      .put("state", t.state.wire).put("paused", t.paused).put("seq", t.seq)
      .put("startedHere", selfId != null && t.origin == selfId)

  // --- persistence (this device only: local clock fields included) ----------------------------

  fun encodeStore(timers: List<SharedTimer>): String = JSONArray().apply {
    timers.forEach { t ->
      put(JSONObject().put("i", t.id).put("n", t.name).put("r", t.room).put("o", t.origin)
          .put("d", t.durationMs).put("end", t.endAtMs).put("rem", t.remainingMs).put("s", t.state.wire)
          .put("q", t.seq).put("by", t.by).put("at", t.changedAtMs))
    }
  }.toString()

  /** Saved timers; a corrupt entry is skipped, a corrupt file reads as empty. */
  fun decodeStore(raw: String?): List<SharedTimer> {
    if (raw.isNullOrBlank()) return emptyList()
    val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
      val o = arr.optJSONObject(i) ?: return@mapNotNull null
      val state = SharedTimerState.fromWire(o.optString("s")) ?: return@mapNotNull null
      val id = o.optString("i").takeIf(::isId) ?: return@mapNotNull null
      SharedTimer(id, o.optString("n").ifBlank { SharedTimer.DEFAULT_NAME }, o.optString("r"), o.optString("o"),
          o.optLong("d"), o.optLong("end"), o.optLong("rem"), state, o.optLong("q", 1L), o.optString("by"), o.optLong("at"))
    }.distinctBy { it.id }
  }

  /** Whole numbers only (org.json yields Int/Long for them); fractions and strings are junk. */
  private fun number(raw: Any?): Long? = when (raw) {
    is Int -> raw.toLong()
    is Long -> raw
    else -> null
  }
}
