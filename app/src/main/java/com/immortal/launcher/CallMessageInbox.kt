/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable

/** One row of "Missed & messages": a video message, or a missed call that left none. */
@Immutable
internal sealed interface InboxItem {
  val key: String
  val atMs: Long
  val fromId: String
  val fromName: String

  data class Message(val m: CallMessage) : InboxItem {
    override val key get() = "m-${m.id}"
    override val atMs get() = m.atMs
    override val fromId get() = m.fromId
    override val fromName get() = m.fromName
  }

  data class Missed(val e: CallHistoryEntry) : InboxItem {
    override val key get() = "c-${e.id}"
    override val atMs get() = e.atMs
    override val fromId get() = e.peerId
    override val fromName get() = e.peerName
  }
}

/** Pure: merges messages and missed calls; a miss followed by that caller's message shows once, as the message. */
internal object CallInbox {
  /** A message counts as "left after" a missed call from the same caller within this window. */
  const val PAIR_WINDOW_MS = 10 * 60_000L

  fun items(messages: List<CallMessage>, history: List<CallHistoryEntry>): List<InboxItem> {
    val missed = history.filter { it.missed }.filterNot { e ->
      messages.any { it.fromId == e.peerId && it.atMs >= e.atMs && it.atMs - e.atMs <= PAIR_WINDOW_MS }
    }
    return (messages.map(InboxItem::Message) + missed.map(InboxItem::Missed)).sortedByDescending { it.atMs }
  }

  /** What the Home widget's missed card should show: the newest unseen message, else null. */
  fun latestUnseen(messages: List<CallMessage>): CallMessage? = messages.filter { !it.seen }.maxByOrNull { it.atMs }
}
