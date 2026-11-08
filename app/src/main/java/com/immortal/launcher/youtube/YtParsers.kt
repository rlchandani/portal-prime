/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.youtube

import org.json.JSONArray
import org.json.JSONObject

/** One page of a paged list response. */
internal data class YtPage<T>(val items: List<T>, val nextPageToken: String?)

/** `videos.list` facts needed before queueing. */
internal data class YtVideoFacts(val durationMs: Long?, val embeddable: Boolean)

/** Pure JSON → model mapping for YouTube Data API v3 responses. Malformed entries are skipped. */
internal object YtParsers {
  private val UNAVAILABLE_TITLES = setOf("Deleted video", "Private video")
  private const val TOPIC_SUFFIX = " - Topic"

  fun playlists(json: JSONObject): YtPage<YtPlaylist> = page(json) { item ->
    val id = item.optString("id").ifEmpty { return@page null }
    val snippet = item.optJSONObject("snippet") ?: return@page null
    val count = item.optJSONObject("contentDetails")?.takeIf { it.has("itemCount") }?.optInt("itemCount")
    YtPlaylist(id, text(snippet, "title"), thumbnail(snippet), count)
  }

  /** Playlist items; deleted/private entries (no owner, placeholder title) are dropped. */
  fun playlistItems(json: JSONObject): YtPage<YtTrack> = page(json) { item ->
    val snippet = item.optJSONObject("snippet") ?: return@page null
    val videoId = item.optJSONObject("contentDetails")?.optString("videoId")
        ?: snippet.optJSONObject("resourceId")?.optString("videoId")
    val title = text(snippet, "title")
    val owner = text(snippet, "videoOwnerChannelTitle")
    if (videoId.isNullOrEmpty() || title in UNAVAILABLE_TITLES || owner.isEmpty()) return@page null
    YtTrack(videoId, title, artistName(owner), thumbnail(snippet))
  }

  fun subscriptions(json: JSONObject): YtPage<YtArtist> = page(json) { item ->
    val snippet = item.optJSONObject("snippet") ?: return@page null
    val channelId = snippet.optJSONObject("resourceId")?.optString("channelId").orEmpty()
    if (channelId.isEmpty()) return@page null
    YtArtist(channelId, text(snippet, "title"), thumbnail(snippet))
  }

  fun searchResults(json: JSONObject): List<YtTrack> = page(json) { item ->
    val videoId = item.optJSONObject("id")?.optString("videoId").orEmpty()
    val snippet = item.optJSONObject("snippet") ?: return@page null
    if (videoId.isEmpty()) return@page null
    YtTrack(videoId, text(snippet, "title"), artistName(text(snippet, "channelTitle")), thumbnail(snippet))
  }.items

  fun videoFacts(json: JSONObject): Map<String, YtVideoFacts> =
      objects(json.optJSONArray("items")).mapNotNull { item ->
        val id = item.optString("id").ifEmpty { return@mapNotNull null }
        val duration = item.optJSONObject("contentDetails")?.optString("duration")?.let(::durationMs)
        val embeddable = item.optJSONObject("status")?.optBoolean("embeddable", false) ?: false
        id to YtVideoFacts(duration, embeddable)
      }.toMap()

  /** `channels.list?part=contentDetails` → `relatedPlaylists.[kind]` (e.g. "likes", "uploads"). */
  fun relatedPlaylist(json: JSONObject, kind: String): String? =
      objects(json.optJSONArray("items")).firstOrNull()
          ?.optJSONObject("contentDetails")?.optJSONObject("relatedPlaylists")
          ?.optString(kind)?.ifEmpty { null }

  /** `channels.list?part=snippet&mine=true` → the signed-in channel, or null for channel-less accounts. */
  fun channelIdentity(json: JSONObject): YtAccount? {
    val item = objects(json.optJSONArray("items")).firstOrNull() ?: return null
    val id = item.optString("id").ifEmpty { return null }
    val snippet = item.optJSONObject("snippet")
    return YtAccount(id, snippet?.let { text(it, "title") }.orEmpty(), snippet?.let(::thumbnail))
  }

  /** ISO-8601 duration (`PT1H2M3S`, `P1DT1S`) → milliseconds, or null when unparseable. */
  fun durationMs(iso: String): Long? {
    val match = DURATION.matchEntire(iso.trim()) ?: return null
    if (match.groupValues.drop(1).all { it.isEmpty() }) return null
    val (d, h, m, s) = match.destructured
    val seconds = (d.toLongOrNull() ?: 0) * 86_400 + (h.toLongOrNull() ?: 0) * 3_600 +
        (m.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)
    return seconds * 1000
  }

  private val DURATION = Regex("P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?")

  private fun artistName(channel: String): String = channel.removeSuffix(TOPIC_SUFFIX)

  /** Best available thumbnail URL, largest first. */
  private fun thumbnail(snippet: JSONObject): String? {
    val thumbs = snippet.optJSONObject("thumbnails") ?: return null
    return listOf("high", "medium", "standard", "default", "maxres")
        .firstNotNullOfOrNull { thumbs.optJSONObject(it)?.optString("url")?.ifEmpty { null } }
  }

  /** A snippet string with the HTML entities search.list leaves in titles decoded. */
  private fun text(json: JSONObject, key: String): String = unescapeHtml(json.optString(key))

  internal fun unescapeHtml(text: String): String =
      if ('&' !in text) text
      else text.replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<")
          .replace("&gt;", ">").replace("&amp;", "&")

  private inline fun <T> page(json: JSONObject, map: (JSONObject) -> T?): YtPage<T> =
      YtPage(objects(json.optJSONArray("items")).mapNotNull(map), json.optString("nextPageToken").ifEmpty { null })

  private fun objects(array: JSONArray?): List<JSONObject> =
      if (array == null) emptyList() else (0 until array.length()).mapNotNull(array::optJSONObject)
}
