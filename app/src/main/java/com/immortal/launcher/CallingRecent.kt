/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable

/**
 * One row of Calls › Recent: a call (with the video message left after it, when there is one), or
 * a message on its own — received, or sent from this Portal.
 */
@Immutable
internal sealed interface RecentItem {
  val key: String
  val atMs: Long
  val peerId: String
  val peerName: String
  /** The message this row plays, if any. */
  val message: CallMessage?

  data class Call(val e: CallHistoryEntry, override val message: CallMessage? = null) : RecentItem {
    override val key get() = "c-${e.id}"
    override val atMs get() = e.atMs
    override val peerId get() = e.peerId
    override val peerName get() = e.peerName
  }

  data class Message(val m: CallMessage) : RecentItem {
    override val key get() = "m-${m.id}"
    override val atMs get() = m.atMs
    override val peerId get() = m.fromId
    override val peerName get() = m.fromName
    override val message get() = m
  }
}

/** Pure: merges call history and video messages into Recent (unit tested). */
internal object CallRecent {
  fun items(history: List<CallHistoryEntry>, messages: List<CallMessage>, filter: CallHistoryFilter): List<RecentItem> {
    val used = HashSet<String>()
    val calls = history.map { e ->
      val m = linked(e, messages)?.takeIf { used.add(it.id) }
      RecentItem.Call(e, m)
    }
    val alone = messages.filter { it.id !in used }.map(RecentItem::Message)
    val all = (calls + alone).sortedByDescending { it.atMs }
    return when (filter) {
      CallHistoryFilter.ALL -> all
      CallHistoryFilter.MISSED -> all.filter { it is RecentItem.Call && it.e.missed }
      CallHistoryFilter.DROP_INS -> all.filter { it is RecentItem.Call && it.e.kind == CallKind.DROP_IN }
      CallHistoryFilter.MESSAGES -> all.filter { it.message != null }
    }
  }

  /**
   * A missed call's message from its caller ([CallInbox.messageFor]); an unanswered outgoing call's
   * message this Portal left after it (the sent copy names the call).
   */
  fun linked(e: CallHistoryEntry, messages: List<CallMessage>): CallMessage? = when {
    e.missed -> CallInbox.messageFor(e, messages)
    e.direction == CallDirection.OUTGOING -> messages.firstOrNull { it.outgoing && it.callId == e.id && it.fromId == e.peerId }
    else -> null
  }

  /** Row subtitle. */
  fun line(item: RecentItem, timeLabel: String): String = when (item) {
    is RecentItem.Call -> {
      val call = CallingCopy.historyLine(item.e, timeLabel)
      val m = item.message
      when {
        m == null -> call
        m.outgoing -> "$call · you left a ${what(m)}"
        else -> "$call · left a ${what(m)}"
      }
    }
    is RecentItem.Message ->
      if (item.m.outgoing) "$timeLabel · you sent a ${what(item.m)}" else "$timeLabel · ${what(item.m)}"
  }

  /** "0:48 video message". */
  private fun what(m: CallMessage) = "${CallingCopy.clock(m.durationMs)} ${if (m.audioOnly) "audio" else "video"} message"
}
