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
 * The queue a Portal hands to another one ("Move to…"): public video ids and their display text
 * only — no account, token or library data. [shuffleOrder] lists the original (unshuffled)
 * positions of [tracks] when the queue is shuffled, so the receiver can still turn shuffle off.
 */
internal data class MusicHandoffQueue(
    val tracks: List<YtTrack>,
    val index: Int,
    val positionMs: Long,
    val repeat: YtRepeat,
    val shuffleOrder: List<Int>?,
    val label: String,
) {
  /** A fresh engine queue (entry ids 1..n) on the sent song. */
  fun toQueue(): YtQueue {
    val entries = tracks.mapIndexed { i, t -> YtQueueEntry(i + 1L, t) }
    return YtQueue(entries = entries, currentIndex = index, sourceLabel = label, repeat = repeat,
        unshuffledIds = shuffleOrder?.map { it + 1L }, nextId = entries.size + 1L)
  }
}

/**
 * JSON codec for [MusicHandoffQueue]. The receiver treats the body as untrusted even after the
 * household signature checks: sizes are capped, ids validated, text cleaned, indices checked.
 */
internal object MusicHandoffCodec {
  const val MAX_TRACKS = 200
  /** How many songs before the current one a long queue keeps. */
  const val KEEP_BEFORE = 20
  const val MAX_TEXT = 200
  const val MAX_URL = 512
  const val MAX_POSITION_MS = 12 * 3_600_000L
  const val MAX_BODY_BYTES = 384 * 1024
  private const val MAX_DURATION_MS = 24 * 3_600_000L
  private const val VERSION = 1

  /** What to send for [queue] at [positionMs]: up to [MAX_TRACKS] around the current song, refused ones left out. */
  fun fromQueue(queue: YtQueue, positionMs: Long): MusicHandoffQueue? {
    val current = queue.current ?: return null
    val kept = queue.entries.filter { it.playable || it.id == current.id }
    val at = kept.indexOfFirst { it.id == current.id }
    val start = (at - KEEP_BEFORE).coerceIn(0, (kept.size - MAX_TRACKS).coerceAtLeast(0))
    val window = kept.subList(start, minOf(kept.size, start + MAX_TRACKS))
    val order = queue.unshuffledIds?.let { ids ->
      val position = window.withIndex().associate { it.value.id to it.index }
      val known = ids.mapNotNull { position[it] }
      known + window.indices.filter { it !in known }
    }
    return MusicHandoffQueue(window.map { clean(it.track) }, at - start, positionMs.coerceIn(0L, MAX_POSITION_MS),
        queue.repeat, order, text(queue.sourceLabel))
  }

  fun encode(q: MusicHandoffQueue): String = JSONObject()
      .put("v", VERSION)
      .put("i", q.index)
      .put("pos", q.positionMs)
      .put("rp", q.repeat.name)
      .put("l", q.label)
      .put("tracks", JSONArray(q.tracks.map(::trackJson)))
      .apply { q.shuffleOrder?.let { put("u", JSONArray(it)) } }
      .toString()

  /** The queue, or null when anything structural is off (too long, bad id, bad index, other version). */
  fun decode(text: String?): MusicHandoffQueue? {
    if (text == null || text.length > MAX_BODY_BYTES) return null
    val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
    if (o.optInt("v", -1) != VERSION) return null
    val array = o.optJSONArray("tracks") ?: return null
    if (array.length() !in 1..MAX_TRACKS) return null
    val tracks = (0 until array.length()).map { i -> array.optJSONObject(i)?.let(::parseTrack) ?: return null }
    val index = (o.opt("i") as? Number)?.toInt()?.takeIf { it in tracks.indices } ?: return null
    return MusicHandoffQueue(
        tracks = tracks,
        index = index,
        positionMs = o.optLong("pos", 0L).coerceIn(0L, MAX_POSITION_MS),
        repeat = YtRepeat.entries.firstOrNull { it.name == o.optString("rp") } ?: YtRepeat.OFF,
        shuffleOrder = o.optJSONArray("u")?.let { order(it, tracks.size) },
        label = text(o.optString("l")),
    )
  }

  private fun trackJson(t: YtTrack): JSONObject = JSONObject().put("id", t.videoId).put("t", t.title)
      .put("a", t.artist).put("art", t.thumbnailUrl).apply { t.durationMs?.let { put("d", it) } }

  private fun parseTrack(o: JSONObject): YtTrack? {
    val id = o.optString("id").takeIf(YtTrack::isValidVideoId) ?: return null
    val duration = if (o.has("d")) o.optLong("d", -1L).takeIf { it in 0..MAX_DURATION_MS } else null
    return clean(YtTrack(id, o.optString("t"), o.optString("a"), o.optString("art"), duration))
  }

  private fun clean(t: YtTrack) = t.copy(title = text(t.title), artist = text(t.artist), thumbnailUrl = url(t.thumbnailUrl))

  /** A permutation of 0 until [size], else null (shuffle is then simply off). */
  private fun order(array: JSONArray, size: Int): List<Int>? {
    if (array.length() != size) return null
    val list = (0 until size).map { (array.opt(it) as? Number)?.toInt() ?: return null }
    return list.takeIf { it.toSet() == (0 until size).toSet() }
  }

  private fun text(raw: String): String {
    val printable = raw.filterNot { it.isISOControl() }.take(MAX_TEXT)
    return if (printable.lastOrNull()?.isHighSurrogate() == true) printable.dropLast(1) else printable
  }

  private fun url(raw: String): String = raw.takeIf { it.length <= MAX_URL && it.startsWith("https://") }.orEmpty()
}
