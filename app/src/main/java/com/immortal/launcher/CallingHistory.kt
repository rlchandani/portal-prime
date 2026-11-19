/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/** One line of call history. Lives on this Portal only, for 30 days; no media is ever stored. */
@Immutable
internal data class CallHistoryEntry(
    val id: String,
    val peerId: String,
    val peerName: String,
    val direction: CallDirection,
    val kind: CallKind,
    val reason: CallEndReason,
    val atMs: Long,
    val durationMs: Long,
    val seen: Boolean = false,
) {
  /** Someone tried to reach this Portal and nobody picked up (including quiet hours). */
  val missed: Boolean get() = direction == CallDirection.INCOMING && durationMs == 0L && reason in MISSED_REASONS

  companion object {
    private val MISSED_REASONS = setOf(CallEndReason.MISSED, CallEndReason.QUIET, CallEndReason.BUSY)
  }
}

internal enum class CallHistoryFilter(val label: String) { ALL("All"), MISSED("Missed"), DROP_INS("Drop-ins"), MESSAGES("Messages") }

/** Pure history operations plus a strict codec (stored data is still validated). */
internal object CallHistory {
  const val CAPACITY = 200

  fun of(e: EndedCall): CallHistoryEntry = CallHistoryEntry(e.id, e.peer.deviceId, e.peer.name, e.direction, e.kind,
      e.reason, e.connectedMs ?: e.endedMs, e.durationMs, seen = e.direction == CallDirection.OUTGOING)

  /** A ring turned away before any call existed here (quiet hours, already in a call). */
  fun missed(id: String, peer: CallContact, kind: CallKind, now: Long, reason: CallEndReason) =
      CallHistoryEntry(id, peer.deviceId, peer.name, CallDirection.INCOMING, kind, reason, now, 0L)

  /** Newest first; replaces an entry with the same id; drops anything older than 30 days. */
  fun add(list: List<CallHistoryEntry>, entry: CallHistoryEntry, nowMs: Long): List<CallHistoryEntry> =
      prune(listOf(entry) + list.filterNot { it.id == entry.id }, nowMs)

  fun prune(list: List<CallHistoryEntry>, nowMs: Long): List<CallHistoryEntry> =
      list.filter { nowMs - it.atMs <= CallTimings.HISTORY_KEEP_MS }.sortedByDescending { it.atMs }.take(CAPACITY)

  fun unseenMissed(list: List<CallHistoryEntry>): Int = list.count { it.missed && !it.seen }

  fun markSeen(list: List<CallHistoryEntry>): List<CallHistoryEntry> =
      if (list.all { it.seen }) list else list.map { if (it.seen) it else it.copy(seen = true) }

  fun latestUnseenMissed(list: List<CallHistoryEntry>): CallHistoryEntry? = list.firstOrNull { it.missed && !it.seen }

  fun filter(list: List<CallHistoryEntry>, f: CallHistoryFilter): List<CallHistoryEntry> = when (f) {
    CallHistoryFilter.ALL -> list
    CallHistoryFilter.MISSED -> list.filter { it.missed }
    CallHistoryFilter.DROP_INS -> list.filter { it.kind == CallKind.DROP_IN }
    // Messages live in their own store; Recent joins them in (CallRecent).
    CallHistoryFilter.MESSAGES -> emptyList()
  }

  fun encode(list: List<CallHistoryEntry>): String = JSONArray(list.map { e ->
    JSONObject().put("i", e.id).put("p", e.peerId).put("n", e.peerName)
        .put("d", if (e.direction == CallDirection.OUTGOING) "o" else "i").put("k", e.kind.wire)
        .put("r", e.reason.wire).put("t", e.atMs).put("ms", e.durationMs).put("s", e.seen)
  }).toString()

  fun decode(raw: String?): List<CallHistoryEntry> {
    val arr = runCatching { JSONArray(raw ?: "[]") }.getOrNull() ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::entry) }.distinctBy { it.id }.take(CAPACITY)
  }

  private fun entry(o: JSONObject): CallHistoryEntry? {
    val id = o.optString("i").takeIf(CallWire::isCallId) ?: return null
    val peer = o.optString("p").takeIf(DinnerWire::isDeviceId) ?: return null
    val name = DinnerWire.cleanName(o.optString("n")) ?: return null
    val direction = when (o.optString("d")) { "o" -> CallDirection.OUTGOING; "i" -> CallDirection.INCOMING; else -> return null }
    val kind = CallKind.fromWire(o.optString("k")) ?: return null
    val reason = CallEndReason.fromWire(o.optString("r")) ?: return null
    val at = (o.opt("t") as? Number)?.toLong()?.takeIf { it > 0 } ?: return null
    val ms = (o.opt("ms") as? Number)?.toLong()?.coerceAtLeast(0) ?: 0L
    return CallHistoryEntry(id, peer, name, direction, kind, reason, at, ms, o.optBoolean("s", false))
  }
}
