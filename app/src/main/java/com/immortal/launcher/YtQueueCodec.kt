/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject

/** A queue as saved on disk, with the position to resume (paused) at. */
data class YtPersistedQueue(val queue: YtQueue, val positionMs: Long)

/**
 * Pure JSON codec for the persisted queue. The file holds only public video metadata (no tokens,
 * no account data). Decoding treats the file as untrusted: invalid ids and duplicate entry ids
 * are dropped, text is capped, and indices are clamped.
 */
object YtQueueCodec {
  const val MAX_TEXT_CHARS = 300
  const val MAX_ENTRIES = 1_000
  private const val MAX_URL_CHARS = 2_048
  private const val VERSION = 1

  fun encode(queue: YtQueue, positionMs: Long): String = JSONObject()
      .put("v", VERSION)
      .put("index", queue.currentIndex)
      .put("label", queue.sourceLabel)
      .put("repeat", queue.repeat.name)
      .put("nextId", queue.nextId)
      .put("position", positionMs)
      .put("entries", JSONArray().apply { queue.entries.take(MAX_ENTRIES).forEach { put(entry(it)) } })
      .apply { queue.unshuffledIds?.let { ids -> put("unshuffled", JSONArray(ids)) } }
      .toString()

  /** The saved queue, or null when [json] is missing, corrupt, or from an unknown version. */
  fun decode(json: String?): YtPersistedQueue? {
    if (json.isNullOrBlank()) return null
    val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
    if (obj.optInt("v", -1) != VERSION) return null
    val entries = entries(obj.optJSONArray("entries"))
    val ids = entries.map { it.id }.toSet()
    val maxId = entries.maxOfOrNull { it.id } ?: 0L
    val queue = YtQueue(
        entries = entries,
        currentIndex = if (entries.isEmpty()) -1 else obj.optInt("index", 0).coerceIn(0, entries.lastIndex),
        sourceLabel = text(obj, "label"),
        repeat = YtRepeat.entries.firstOrNull { it.name == obj.optString("repeat") } ?: YtRepeat.OFF,
        unshuffledIds = obj.optJSONArray("unshuffled")?.let { longs(it).filter(ids::contains).distinct() },
        nextId = maxOf(obj.optLong("nextId", 1L), maxId + 1),
    )
    return YtPersistedQueue(queue, obj.optLong("position", 0L).coerceAtLeast(0L))
  }

  private fun entry(e: YtQueueEntry): JSONObject = JSONObject()
      .put("id", e.id)
      .put("vid", e.track.videoId)
      .put("t", e.track.title)
      .put("a", e.track.artist)
      .put("art", e.track.thumbnailUrl)
      .apply { e.track.durationMs?.let { put("d", it) } }
      .apply { e.unplayableReason?.let { put("x", it) } }

  private fun entries(array: JSONArray?): List<YtQueueEntry> {
    if (array == null) return emptyList()
    val seen = HashSet<Long>()
    return (0 until minOf(array.length(), MAX_ENTRIES)).mapNotNull { i ->
      array.optJSONObject(i)?.let(::parseEntry)?.takeIf { seen.add(it.id) }
    }
  }

  private fun parseEntry(o: JSONObject): YtQueueEntry? {
    val id = o.optLong("id", -1L).takeIf { it > 0 } ?: return null
    val videoId = o.optString("vid").takeIf(YtTrack::isValidVideoId) ?: return null
    val track = YtTrack(
        videoId = videoId,
        title = text(o, "t"),
        artist = text(o, "a"),
        thumbnailUrl = o.optString("art").takeIf { it.length <= MAX_URL_CHARS && it.startsWith("https://") }.orEmpty(),
        durationMs = if (o.has("d")) o.optLong("d", -1L).takeIf { it >= 0 } else null,
    )
    return YtQueueEntry(id, track, o.optString("x").takeIf { it.isNotEmpty() }?.take(MAX_TEXT_CHARS))
  }

  private fun text(o: JSONObject, key: String): String = o.optString(key).take(MAX_TEXT_CHARS)

  private fun longs(array: JSONArray): List<Long> =
      (0 until minOf(array.length(), MAX_ENTRIES)).mapNotNull { i -> array.optLong(i, -1L).takeIf { it > 0 } }
}
