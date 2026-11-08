/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.youtube

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject

/** A person's own choice for one playlist on this Portal; it always beats the automatic verdict. */
enum class YtPlaylistChoice(internal val wire: String) {
  HIDE("h"),
  SHOW("s");

  internal companion object {
    fun fromWire(text: String?): YtPlaylistChoice? = entries.firstOrNull { it.wire == text }
  }
}

/** The "mostly music" rule, by YouTube's own category (`snippet.categoryId` "10" = Music). */
internal object YtMusicRule {
  const val MUSIC_CATEGORY = "10"
  const val SAMPLE_SIZE = 25

  fun isMusic(categoryId: String?): Boolean = categoryId == MUSIC_CATEGORY

  /**
   * True when at least half of the first [SAMPLE_SIZE] items whose category is known are Music.
   * An empty sample, or one where nothing is known, counts as music: when unsure, show it.
   */
  fun mostlyMusic(sampleIds: List<String>, categories: Map<String, String>): Boolean {
    val known = sampleIds.take(SAMPLE_SIZE).mapNotNull { id -> categories[id]?.takeIf { it.isNotEmpty() } }
    if (known.isEmpty()) return true
    return known.count(::isMusic) * 2 >= known.size
  }

  /** Liked songs: keep Music and anything whose category is unknown. */
  fun keepAsSong(categoryId: String?): Boolean = categoryId.isNullOrEmpty() || isMusic(categoryId)
}

/**
 * Verdicts, categories and manual choices for hiding non-music playlists, stored under the
 * account's cache prefix so signing that account out deletes all of it. Verdicts and categories are
 * API data (≤ 30 days); a verdict is recomputed when the playlist's item count changes. Choices are
 * the person's own data, so they don't expire.
 */
internal class YtMusicFilter(
    private val api: YtLibraryApi,
    private val store: YtCacheStore,
    private val clock: YtClock,
) {
  /** Cached verdicts first, then more as each group of samples is classified. Failures stay unknown. */
  fun verdicts(accountId: String, playlists: List<YtPlaylist>): Flow<Map<String, Boolean>> = flow {
    var known = playlists.mapNotNull { pl -> cachedVerdict(accountId, pl)?.let { pl.id to it } }.toMap()
    emit(known)
    for (group in playlists.filter { it.id !in known }.chunked(PLAYLISTS_PER_BATCH)) {
      val samples = samples(accountId, group) ?: break
      val ids = samples.flatMap { it.second }
      val categories = if (ids.isEmpty()) emptyMap() else (categories(accountId, ids) as? YtResult.Ok)?.value ?: break
      samples.forEach { (pl, sample) ->
        val music = YtMusicRule.mostlyMusic(sample, categories)
        writeVerdict(accountId, pl, music)
        known = known + (pl.id to music)
      }
      emit(known)
    }
  }

  /** Categories for [videoIds] ("" = YouTube returned nothing for it), served from cache when fresh. */
  suspend fun categories(accountId: String, videoIds: List<String>): YtResult<Map<String, String>> {
    val now = clock.nowMs()
    val cached = readCategories(accountId).filterValues { now - it.second <= YtLibraryCache.MAX_AGE_MS }
    val missing = videoIds.distinct().filter { it !in cached }
    if (missing.isEmpty()) return YtResult.Ok(videoIds.associateWith { cached.getValue(it).first })
    return api.videoCategories(accountId, missing).map { fetched ->
      val fresh = missing.associateWith { (fetched[it].orEmpty()) to now }
      writeCategories(accountId, cached + fresh)
      videoIds.associateWith { (fresh[it] ?: cached[it])?.first.orEmpty() }
    }
  }

  fun choices(accountId: String): Map<String, YtPlaylistChoice> {
    val json = store.read(choicesKey(accountId))?.let(::parseJson) ?: return emptyMap()
    return json.keys().asSequence().mapNotNull { id ->
      YtPlaylistChoice.fromWire(json.optString(id))?.let { id to it }
    }.take(MAX_CHOICES).toMap()
  }

  /** Records [choice] for [playlistId]; null forgets it (back to automatic). */
  fun setChoice(accountId: String, playlistId: String, choice: YtPlaylistChoice?) {
    val current = choices(accountId)
    val next = if (choice == null) current - playlistId else (current - playlistId) + (playlistId to choice)
    val json = JSONObject()
    next.entries.toList().takeLast(MAX_CHOICES).forEach { (id, c) -> json.put(id, c.wire) }
    store.write(choicesKey(accountId), json.toString())
  }

  /** (playlist, sampled video ids) per playlist, or null when YouTube can't be asked right now. */
  private suspend fun samples(accountId: String, group: List<YtPlaylist>): List<Pair<YtPlaylist, List<String>>>? {
    val out = ArrayList<Pair<YtPlaylist, List<String>>>()
    for (pl in group) {
      when (val r = api.playlistSample(accountId, pl.id, YtMusicRule.SAMPLE_SIZE)) {
        is YtResult.Ok -> out += pl to r.value.map { it.videoId }
        is YtResult.Failed -> Unit // this one stays unknown (shown); try the rest
        else -> return null
      }
    }
    return out
  }

  private fun cachedVerdict(accountId: String, pl: YtPlaylist): Boolean? {
    val json = store.read(verdictKey(accountId, pl.id))?.let(::parseJson) ?: return null
    if (clock.nowMs() - json.optLong("at") > YtLibraryCache.MAX_AGE_MS) return null
    if (json.optInt("n", UNKNOWN_COUNT) != (pl.itemCount ?: UNKNOWN_COUNT)) return null
    return if (json.has("m")) json.optBoolean("m") else null
  }

  private fun writeVerdict(accountId: String, pl: YtPlaylist, music: Boolean) = store.write(verdictKey(accountId, pl.id),
      JSONObject().put("at", clock.nowMs()).put("n", pl.itemCount ?: UNKNOWN_COUNT).put("m", music).toString())

  private fun readCategories(accountId: String): Map<String, Pair<String, Long>> {
    val json = store.read(categoriesKey(accountId))?.let(::parseJson) ?: return emptyMap()
    return json.keys().asSequence().mapNotNull { id ->
      val entry = json.optJSONObject(id) ?: return@mapNotNull null
      id to (entry.optString("c") to entry.optLong("at"))
    }.toMap()
  }

  private fun writeCategories(accountId: String, all: Map<String, Pair<String, Long>>) {
    val json = JSONObject()
    all.entries.sortedByDescending { it.value.second }.take(MAX_CATEGORIES)
        .forEach { (id, v) -> json.put(id, JSONObject().put("c", v.first).put("at", v.second)) }
    store.write(categoriesKey(accountId), json.toString())
  }

  private fun verdictKey(accountId: String, playlistId: String) = YtLibraryCache.accountPrefix(accountId) + "verdict.$playlistId"
  private fun categoriesKey(accountId: String) = YtLibraryCache.accountPrefix(accountId) + "cats"
  private fun choicesKey(accountId: String) = YtLibraryCache.accountPrefix(accountId) + "choices"

  private companion object {
    /** Two 25-item samples fill one 50-id videos.list call. */
    const val PLAYLISTS_PER_BATCH = 2
    const val UNKNOWN_COUNT = -1
    const val MAX_CATEGORIES = 5_000
    const val MAX_CHOICES = 500
  }
}
