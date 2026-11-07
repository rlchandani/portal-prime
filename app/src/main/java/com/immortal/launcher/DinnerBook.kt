/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject

internal data class DinnerRow(val roomId: String, val roomName: String, val status: DinnerStatus, val reply: DinnerReply? = null)

/** One call this Portal sent (or relayed for the phone) and how each room answered. */
internal data class DinnerSend(
    val id: String,
    val message: DinnerMessage,
    val senderName: String,
    val senderIsPhone: Boolean,
    val atMs: Long,
    val rows: List<DinnerRow>,
) {
  val pending: Boolean get() = rows.any { it.status == DinnerStatus.SENDING }
  val allFailed: Boolean get() = rows.isNotEmpty() && rows.all { it.status.failed }

  fun toJson(): JSONObject = JSONObject().put("id", id).put("kind", message.kind.name.lowercase())
      .put("text", message.text).put("at", atMs).put("pending", pending)
      .put("rows", JSONArray(rows.map { r ->
        JSONObject().put("room", r.roomId).put("name", r.roomName).put("status", r.status.wire)
            .put("text", r.status.rowText).put("retry", r.status.retryable)
            .put("reply", r.reply?.label ?: JSONObject.NULL)
      }))
}

/** Sender-side results, newest last. Every transition returns a new book; unknown ids are no-ops. */
internal data class DinnerBook(val sends: List<DinnerSend> = emptyList()) {
  fun find(id: String): DinnerSend? = sends.firstOrNull { it.id == id }

  fun start(send: DinnerSend): DinnerBook = copy(sends = (sends.filterNot { it.id == send.id } + send).takeLast(CAPACITY))

  /** A room answered. Late answers still count; a room we didn't know about gets its own row. */
  fun ack(id: String, roomId: String, roomName: String, status: DinnerStatus): DinnerBook = change(id) { send ->
    val row = DinnerRow(roomId, roomName, status)
    if (send.rows.any { it.roomId == roomId }) send.copy(rows = send.rows.map { if (it.roomId == roomId) it.copy(roomName = roomName, status = status) else it })
    else send.copy(rows = send.rows + row)
  }

  fun reply(id: String, roomId: String, reply: DinnerReply): DinnerBook {
    val send = find(id) ?: return this
    if (send.rows.none { it.roomId == roomId }) return this
    return change(id) { s -> s.copy(rows = s.rows.map { if (it.roomId == roomId) it.copy(reply = reply) else it }) }
  }

  /** Rooms still sending when the answer window closes become "No answer". */
  fun timeout(id: String): DinnerBook = change(id) { send ->
    send.copy(rows = send.rows.map { if (it.status == DinnerStatus.SENDING) it.copy(status = DinnerStatus.NO_ANSWER) else it })
  }

  fun retrying(id: String, roomId: String): DinnerBook = change(id) { send ->
    send.copy(rows = send.rows.map { if (it.roomId == roomId) it.copy(status = DinnerStatus.SENDING, reply = null) else it })
  }

  fun setStatus(id: String, roomIds: Collection<String>, status: DinnerStatus): DinnerBook = change(id) { send ->
    send.copy(rows = send.rows.map { if (it.roomId in roomIds) it.copy(status = status) else it })
  }

  private fun change(id: String, transform: (DinnerSend) -> DinnerSend): DinnerBook {
    if (find(id) == null) return this
    return copy(sends = sends.map { if (it.id == id) transform(it) else it })
  }

  companion object {
    const val CAPACITY = 8
  }
}
