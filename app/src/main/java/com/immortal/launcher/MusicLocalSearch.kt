/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.util.Log
import androidx.annotation.WorkerThread
import com.immortal.launcher.youtube.YtAndroid

/**
 * This Portal's recent searches (last [MusicRecentSearches.MAX_ENTRIES], in private prefs) and the
 * local data search suggestions come from. Nothing here spends YouTube search quota.
 */
internal object MusicLocalSearch {
  private const val TAG = "HearthpaneMusicSearch"
  private const val PREFS = "music_recent_searches"
  private const val KEY = "searches_v1"
  private val lock = Any()
  @Volatile private var cache: List<MusicRecentSearch>? = null

  /** Newest first. Reads prefs once per process. */
  fun recent(context: Context): List<MusicRecentSearch> = cache ?: synchronized(lock) {
    cache ?: runCatching { MusicRecentSearches.decode(prefs(context).getString(KEY, null)) }
        .onFailure { Log.w(TAG, "recent searches unreadable: ${it.javaClass.simpleName}") }
        .getOrDefault(emptyList()).also { cache = it }
  }

  /** Remembers a search that YouTube answered (or the cache did). */
  fun remember(context: Context, query: String) = write(context) {
    MusicRecentSearches.with(it, query, System.currentTimeMillis())
  }

  fun clear(context: Context) = write(context) { emptyList() }

  /** Everything suggestions can draw on: searches, household history, cached liked songs and artists. */
  @WorkerThread
  fun sources(context: Context): MusicSuggestSources {
    val library = runCatching { YtAndroid.library(context) }
        .onFailure { Log.w(TAG, "library unavailable: ${it.javaClass.simpleName}") }.getOrNull()
    return MusicSuggestSources(
        searches = recent(context),
        history = MusicHistoryFeature.plays.value,
        liked = library?.cachedLikedSongs().orEmpty().map { it.toQueueTrack() },
        artists = library?.cachedArtists().orEmpty().map { it.name })
  }

  @WorkerThread
  fun suggest(context: Context, query: String): List<MusicSuggestion> = MusicSuggest.suggest(query, sources(context))

  private fun write(context: Context, f: (List<MusicRecentSearch>) -> List<MusicRecentSearch>) {
    val next = synchronized(lock) { f(recent(context)).also { cache = it } }
    runCatching { prefs(context).edit().putString(KEY, MusicRecentSearches.encode(next)).apply() }
        .onFailure { Log.w(TAG, "recent searches save failed: ${it.javaClass.simpleName}") }
  }

  private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
