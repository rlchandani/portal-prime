/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.youtube

import org.json.JSONObject

/** Network-only library reads for one account (no caching — see [YtLibraryCache]). */
internal class YtLibraryApi(private val api: YtApiClient) {

  suspend fun myPlaylists(accountId: String): YtResult<List<YtPlaylist>> = collect { token ->
    api.get(accountId, "playlists", mapOf(
        "part" to "snippet,contentDetails", "mine" to "true", "maxResults" to PAGE_SIZE, "pageToken" to token))
        .map(YtParsers::playlists)
  }

  suspend fun playlistTracks(accountId: String, playlistId: String): YtResult<List<YtTrack>> = collect { token ->
    api.get(accountId, "playlistItems", mapOf(
        "part" to "snippet,contentDetails", "playlistId" to playlistId, "maxResults" to PAGE_SIZE, "pageToken" to token))
        .map(YtParsers::playlistItems)
  }

  /** YouTube Music likes (`LM`) when that playlist answers with items, else the channel's likes playlist. */
  suspend fun likedTracks(accountId: String): YtResult<List<YtTrack>> {
    val music = playlistTracks(accountId, MUSIC_LIKES)
    if (music is YtResult.Ok && music.value.isNotEmpty()) return music
    if (music is YtResult.Offline || music is YtResult.NeedsSignIn || music is YtResult.Resting) return music
    return relatedPlaylist(accountId, mapOf("mine" to "true"), "likes")
        .flatMap { id -> playlistTracks(accountId, id) }
  }

  suspend fun artists(accountId: String): YtResult<List<YtArtist>> = collect { token ->
    api.get(accountId, "subscriptions", mapOf(
        "part" to "snippet", "mine" to "true", "maxResults" to PAGE_SIZE, "pageToken" to token))
        .map(YtParsers::subscriptions)
  }

  suspend fun artistTracks(accountId: String, channelId: String): YtResult<List<YtTrack>> =
      relatedPlaylist(accountId, mapOf("id" to channelId), "uploads")
          .flatMap { id -> playlistTracks(accountId, id) }

  suspend fun identity(accountId: String): YtResult<YtAccount?> =
      api.get(accountId, "channels", mapOf("part" to "snippet", "mine" to "true")).map(YtParsers::channelIdentity)

  /** Durations + embeddability, batched 50 ids per call. */
  suspend fun videoFacts(accountId: String, videoIds: List<String>): YtResult<Map<String, YtVideoFacts>> {
    var facts = emptyMap<String, YtVideoFacts>()
    for (batch in videoIds.distinct().chunked(MAX_IDS)) {
      val result = api.get(accountId, "videos", mapOf(
          "part" to "contentDetails,status", "id" to batch.joinToString(","), "maxResults" to PAGE_SIZE))
      when (result) {
        is YtResult.Ok -> facts = facts + YtParsers.videoFacts(result.value)
        else -> return result.map { emptyMap() }
      }
    }
    return YtResult.Ok(facts)
  }

  /** One search.list call; [onSent] counts it against the budget once it reaches Google. */
  suspend fun search(accountId: String, query: String, onSent: () -> Unit): YtResult<List<YtTrack>> =
      api.get(accountId, "search", mapOf(
          "part" to "snippet", "type" to "video", "videoCategoryId" to MUSIC_CATEGORY,
          "videoEmbeddable" to "true", "videoSyndicated" to "true", "maxResults" to SEARCH_SIZE, "q" to query),
          onSent).map(YtParsers::searchResults)

  private suspend fun relatedPlaylist(accountId: String, selector: Map<String, String>, kind: String): YtResult<String> =
      api.get(accountId, "channels", mapOf("part" to "contentDetails") + selector).flatMap { json: JSONObject ->
        YtParsers.relatedPlaylist(json, kind)?.let { YtResult.Ok(it) } ?: YtResult.Failed(200, YtProblem.NOT_FOUND)
      }

  /** Follows nextPageToken up to [MAX_PAGES] pages. */
  private suspend fun <T> collect(fetch: suspend (String?) -> YtResult<YtPage<T>>): YtResult<List<T>> {
    var items = emptyList<T>()
    var token: String? = null
    repeat(MAX_PAGES) {
      when (val page = fetch(token)) {
        is YtResult.Ok -> {
          items = items + page.value.items
          token = page.value.nextPageToken ?: return YtResult.Ok(items)
        }
        else -> return page.map { emptyList() }
      }
    }
    return YtResult.Ok(items)
  }

  companion object {
    const val MUSIC_LIKES = "LM"
    private const val PAGE_SIZE = "50"
    private const val SEARCH_SIZE = "25"
    private const val MUSIC_CATEGORY = "10"
    private const val MAX_IDS = 50
    /** 20 × 50 = 1000 items: bounds quota and memory for huge playlists. */
    private const val MAX_PAGES = 20
  }
}
