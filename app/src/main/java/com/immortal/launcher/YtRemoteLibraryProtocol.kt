/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.youtube.YtProblem
import com.immortal.launcher.youtube.YtResult
import com.immortal.launcher.youtube.YtSearchPage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure wire format of the phone's Music search and history routes:
 * - `GET /remote/ytmusic/history` → `{ok, history:[{videoId,title,artist,thumb,playedAtMs,room}], total}`
 * - `POST /remote/ytmusic/history` `{action:"clear"}` | `{action:"remove", videoId}` → `{ok}`
 * - `POST /remote/ytmusic/suggest` `{q}` → `{ok, suggestions:[{text, kind, videoId?, artist?, thumb?}]}`
 * - `POST /remote/ytmusic/search` `{q}` → `{ok, tracks:[{videoId,title,artist,thumb,durationMs?}], quotaLeft}`,
 *   429 `{error:"quota"}` once today's searches are used up.
 */
internal object YtRemoteLibraryProtocol {
  const val MAX_QUERY = 100

  sealed interface HistoryAction {
    data object Clear : HistoryAction
    data class Remove(val videoId: String) : HistoryAction
    data class Invalid(val code: String) : HistoryAction
  }

  /** A status + body pair, so the route only wraps it. */
  data class Reply(val status: Int, val body: JSONObject)

  fun history(listing: List<MusicPlay>): JSONObject {
    val rows = listing.take(MusicHistory.MAX_ENTRIES)
    return ok().put("history", JSONArray().also { a -> rows.forEach { a.put(MusicHistoryCodec.phoneJson(it)) } })
        .put("total", rows.size)
  }

  fun historyAction(body: JSONObject): HistoryAction = when (body.optString("action")) {
    "clear" -> HistoryAction.Clear
    "remove" -> body.optString("videoId").takeIf(YtTrack::isValidVideoId)?.let { HistoryAction.Remove(it) }
        ?: HistoryAction.Invalid("invalid_video")
    else -> HistoryAction.Invalid("unknown_action")
  }

  /** The trimmed query when it has at least [minChars] and at most [MAX_QUERY] characters, else null. */
  fun query(body: JSONObject, minChars: Int): String? =
      (body.opt("q") as? String)?.trim()?.takeIf { it.length in minChars..MAX_QUERY }

  fun suggestions(list: List<MusicSuggestion>): JSONObject =
      ok().put("suggestions", JSONArray().also { a -> list.forEach { a.put(suggestion(it)) } })

  fun search(result: YtResult<YtSearchPage>, quotaLeftNow: Int): Reply = when (result) {
    is YtResult.Ok -> Reply(200, ok().put("tracks", JSONArray().also { a -> result.value.tracks.forEach { a.put(track(it)) } })
        .put("quotaLeft", result.value.remainingToday))
    YtResult.Resting -> Reply(429, error("quota").put("quotaLeft", 0))
    YtResult.Offline -> Reply(503, error("offline").put("quotaLeft", quotaLeftNow))
    YtResult.NeedsSignIn -> Reply(409, error("not_signed_in").put("quotaLeft", quotaLeftNow))
    is YtResult.Failed -> if (result.problem == YtProblem.INVALID_REQUEST) Reply(400, error("invalid_query"))
        else Reply(502, error("youtube_failed").put("quotaLeft", quotaLeftNow))
  }

  private fun suggestion(s: MusicSuggestion): JSONObject = JSONObject().put("text", s.text).put("kind", s.kind.wire)
      .apply {
        s.videoId?.let { put("videoId", it) }
        s.artist?.let { put("artist", it) }
        s.thumb?.let { put("thumb", it) }
      }

  private fun track(t: com.immortal.launcher.youtube.YtTrack): JSONObject = JSONObject()
      .put("videoId", t.videoId)
      .put("title", t.title.take(YtRemoteProtocol.MAX_TEXT))
      .put("artist", t.artist.take(YtRemoteProtocol.MAX_TEXT))
      .put("thumb", t.thumbnailUrl?.ifBlank { null } ?: MusicShareQueue.thumbnailFor(t.videoId))
      .apply { t.durationMs?.let { put("durationMs", it) } }

  private fun ok() = JSONObject().put("ok", true)
  private fun error(code: String) = JSONObject().put("ok", false).put("error", code)
}
