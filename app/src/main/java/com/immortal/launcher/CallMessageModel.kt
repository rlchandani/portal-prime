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

/**
 * A video message ("Leave a message") stored on this Portal, the recipient. Metadata only: the
 * picture and sound live encrypted in the store's file and are never decrypted to disk.
 */
@Immutable
internal data class CallMessage(
    val id: String,
    val fromId: String,
    val fromName: String,
    val atMs: Long,
    val durationMs: Long,
    /** Size of the encrypted file on disk. */
    val sizeBytes: Long,
    /** Recorded with the camera off: sound only. */
    val audioOnly: Boolean = false,
    val seen: Boolean = false,
)

/** Named limits from the owner-approved mock and the 2a brief. */
internal object CallMessageLimits {
  /** "Up to 2 minutes." */
  const val MAX_DURATION_MS = 120_000L
  /** A message shorter than this is a mis-tap: it goes back to Ready instead of Review. */
  const val MIN_DURATION_MS = 1_000L
  /** 2 min at ~1.6 Mbps is ~25 MB; anything over this is refused before a byte is stored. */
  const val MAX_BYTES = 48L * 1024 * 1024
  const val MAX_STORED = 40
  /** No single room can take over the store. */
  const val MAX_PER_SENDER = 10
  /** A whole upload must arrive within this (a slow drip can't hold an HTTP thread for long). */
  const val UPLOAD_DEADLINE_MS = 120_000L
  /** Refuse a message rather than fill the Portal: keep at least this much free. */
  const val MIN_FREE_BYTES = 200L * 1024 * 1024
  /** Auto-delete after 30 days. */
  const val KEEP_MS = 30L * 24 * 60 * 60_000L
  /** Plaintext bytes per sealed chunk. */
  const val CHUNK = 64 * 1024
}

/** Why a recipient Portal turned a message away (also the route's error codes). */
internal enum class CallMessageRefusal(val code: String) {
  MESSAGES_OFF("messages_off"), TOO_LARGE("too_large"), FULL("full"), NO_SPACE("no_space"), DUPLICATE("duplicate"),
  TOO_LONG("too_long"),
  /** Another message is arriving right now; the sender may try again in a moment. */
  BUSY("busy");

  companion object {
    fun fromCode(code: String?): CallMessageRefusal? = entries.firstOrNull { it.code == code }
  }
}

/** Pure list rules for the recipient store, plus its strict codec (stored data is validated too). */
internal object CallMessageList {
  private val ID = Regex("^[a-f0-9]{16}$")

  /** Message ids are 16 lowercase hex: they name files, so nothing else (no `..`, no `/`) is accepted. */
  fun isId(text: String?): Boolean = text != null && ID.matches(text)

  fun canAccept(list: List<CallMessage>, id: String, plainBytes: Long, durationMs: Long, freeBytes: Long, on: Boolean,
                from: String = ""): CallMessageRefusal? =
      when {
        !on -> CallMessageRefusal.MESSAGES_OFF
        list.any { it.id == id } -> CallMessageRefusal.DUPLICATE
        plainBytes <= 0 || plainBytes > CallMessageLimits.MAX_BYTES -> CallMessageRefusal.TOO_LARGE
        durationMs <= 0 || durationMs > CallMessageLimits.MAX_DURATION_MS + DURATION_SLACK_MS -> CallMessageRefusal.TOO_LONG
        list.size >= CallMessageLimits.MAX_STORED || list.count { it.fromId == from } >= CallMessageLimits.MAX_PER_SENDER ->
          CallMessageRefusal.FULL
        freeBytes - plainBytes < CallMessageLimits.MIN_FREE_BYTES -> CallMessageRefusal.NO_SPACE
        else -> null
      }

  /** Newest first; anything older than 30 days drops off (the store deletes its file). */
  fun prune(list: List<CallMessage>, nowMs: Long): List<CallMessage> =
      list.filter { nowMs - it.atMs <= CallMessageLimits.KEEP_MS }.sortedByDescending { it.atMs }.take(CallMessageLimits.MAX_STORED)

  fun add(list: List<CallMessage>, m: CallMessage, nowMs: Long): List<CallMessage> = prune(listOf(m) + list.filterNot { it.id == m.id }, nowMs)

  fun unseen(list: List<CallMessage>): Int = list.count { !it.seen }

  fun markSeen(list: List<CallMessage>, id: String): List<CallMessage> = list.map { if (it.id == id) it.copy(seen = true) else it }

  fun encode(list: List<CallMessage>): String = JSONArray(list.map { m ->
    JSONObject().put("i", m.id).put("f", m.fromId).put("n", m.fromName).put("t", m.atMs).put("ms", m.durationMs)
        .put("z", m.sizeBytes).put("a", m.audioOnly).put("s", m.seen)
  }).toString()

  fun decode(raw: String?): List<CallMessage> {
    val arr = runCatching { JSONArray(raw ?: "[]") }.getOrNull() ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::entry) }.distinctBy { it.id }
  }

  private fun entry(o: JSONObject): CallMessage? {
    val id = o.optString("i").takeIf(::isId) ?: return null
    val from = o.optString("f").takeIf(DinnerWire::isDeviceId) ?: return null
    val name = DinnerWire.cleanName(o.optString("n")) ?: return null
    val at = (o.opt("t") as? Number)?.toLong()?.takeIf { it > 0 } ?: return null
    val ms = (o.opt("ms") as? Number)?.toLong()?.takeIf { it > 0 } ?: return null
    val size = (o.opt("z") as? Number)?.toLong()?.takeIf { it > 0 } ?: return null
    return CallMessage(id, from, name, at, ms, size, o.optBoolean("a", false), o.optBoolean("s", false))
  }

  /** MediaRecorder's own 2:00 cap can overshoot by a frame or two. */
  private const val DURATION_SLACK_MS = 2_000L
}
