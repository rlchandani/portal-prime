/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Wire and storage forms of [MusicPlay]. Household messages must fit one UDP datagram
 * ([HouseholdMessage.MAX_WIRE_CHARS]), so text is capped and a long thumbnail URL is dropped
 * (receivers fall back to YouTube's standard thumbnail). Everything decoded is validated.
 */
internal object MusicHistoryCodec {
  /** Household message: one new (or replayed) entry. */
  const val PLAYED = "music_played"
  /** Household message: `{action: clear, at}` or `{action: remove, v}`. */
  const val CHANGE = "music_history"
  const val ACTION_CLEAR = "clear"
  const val ACTION_REMOVE = "remove"

  const val MAX_TITLE = 120
  const val MAX_ARTIST = 80
  const val MAX_THUMB = 300
  const val MAX_ROOM = 40
  /** A play claiming to be further in the future than this is refused (clock skew allowance). */
  const val MAX_FUTURE_MS = 5 * 60_000L

  /** A household change other than a new play. */
  sealed interface Change {
    data class Clear(val atMs: Long) : Change
    data class Remove(val videoId: String) : Change
  }

  fun encodeWire(play: MusicPlay): JSONObject = JSONObject()
      .put("v", play.videoId)
      .put("t", play.title.take(MAX_TITLE))
      .put("a", play.artist.take(MAX_ARTIST))
      .put("th", play.thumb.takeIf { it.length <= MAX_THUMB }.orEmpty())
      .put("at", play.playedAtMs)
      .put("r", play.room.take(MAX_ROOM))

  fun decodeWire(body: JSONObject, nowMs: Long): MusicPlay? {
    val videoId = body.optString("v")
    val at = (body.opt("at") as? Number)?.toLong() ?: return null
    if (!YtTrack.isValidVideoId(videoId) || at <= 0L || at > nowMs + MAX_FUTURE_MS) return null
    val thumb = body.optString("th").takeIf { it.length <= MAX_THUMB && it.startsWith(HTTPS) }.orEmpty()
    return MusicPlay(videoId, body.optString("t").take(MAX_TITLE), body.optString("a").take(MAX_ARTIST), thumb, at,
        body.optString("r").take(MAX_ROOM))
  }

  fun encodeChange(change: Change): JSONObject = when (change) {
    is Change.Clear -> JSONObject().put("action", ACTION_CLEAR).put("at", change.atMs)
    is Change.Remove -> JSONObject().put("action", ACTION_REMOVE).put("v", change.videoId)
  }

  fun decodeChange(body: JSONObject): Change? = when (body.optString("action")) {
    ACTION_CLEAR -> (body.opt("at") as? Number)?.toLong()?.takeIf { it > 0L }?.let { Change.Clear(it) }
    ACTION_REMOVE -> body.optString("v").takeIf(YtTrack::isValidVideoId)?.let { Change.Remove(it) }
    else -> null
  }

  /** Stored list (newest first); unreadable rows are skipped, an unreadable file reads as empty. */
  fun encodeList(history: List<MusicPlay>): String =
      JSONArray().also { array -> history.forEach { array.put(encodeWire(it)) } }.toString()

  fun decodeList(text: String?, nowMs: Long): List<MusicPlay> {
    if (text.isNullOrBlank()) return emptyList()
    val array = try { JSONArray(text) } catch (_: JSONException) { return emptyList() }
    val plays = (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { decodeWire(it, nowMs) } }
    return MusicHistory.mergeAll(emptyList(), plays)
  }

  /** Phone row (`/remote/ytmusic/history`); a missing thumbnail becomes YouTube's standard one. */
  fun phoneJson(play: MusicPlay): JSONObject = JSONObject()
      .put("videoId", play.videoId)
      .put("title", play.title)
      .put("artist", play.artist)
      .put("thumb", play.thumb.ifBlank { MusicShareQueue.thumbnailFor(play.videoId) })
      .put("playedAtMs", play.playedAtMs)
      .put("room", play.room)

  private const val HTTPS = "https://"
}
