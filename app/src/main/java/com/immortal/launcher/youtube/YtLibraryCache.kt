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

/** How one cached value is stored. */
internal class YtCodec<T>(val encode: (T) -> String, val decode: (String) -> T?)

/**
 * Library cache (memory over [YtCacheStore]) with the API-policy limits: entries older than
 * [MAX_AGE_MS] (30 days) are deleted, entries younger than [FRESH_MS] (10 minutes) are served
 * without a network call, anything in between is served stale while a refresh runs.
 */
internal class YtLibraryCache(private val store: YtCacheStore, private val clock: YtClock) {
  private class Entry(val text: String, val fetchedAtMs: Long)

  @Volatile private var memory: Map<String, Entry> = emptyMap()

  /**
   * Emits the cached value first (if any), then — unless it's fresh and [forceRefresh] is false —
   * the network result of [fetch]. A failed refresh is emitted as-is; the UI keeps the last Ok.
   */
  fun <T> stream(
      key: String,
      codec: YtCodec<T>,
      forceRefresh: Boolean,
      fetch: suspend () -> YtResult<T>,
  ): Flow<YtResult<YtLoaded<T>>> = flow {
    val cached = read(key, codec)
    val fresh = cached != null && clock.nowMs() - cached.fetchedAtMs < FRESH_MS
    if (cached != null) emit(YtResult.Ok(cached.copy(isRefreshing = forceRefresh || !fresh)))
    if (fresh && !forceRefresh) return@flow
    val result = fetch()
    if (result is YtResult.Ok) write(key, codec, result.value)
    emit(result.map { YtLoaded(it, clock.nowMs(), isRefreshing = false) })
  }

  /** The cached value only (never the network), or null; for local search suggestions. */
  fun <T> peek(key: String, codec: YtCodec<T>): T? = read(key, codec)?.value

  fun deletePrefix(prefix: String) {
    memory = memory.filterKeys { !it.startsWith(prefix) }
    store.deletePrefix(prefix)
  }

  private fun <T> read(key: String, codec: YtCodec<T>): YtLoaded<T>? {
    val entry = memory[key] ?: store.read(key)?.let(::decodeEntry) ?: return null
    if (clock.nowMs() - entry.fetchedAtMs > MAX_AGE_MS) {
      memory = memory - key
      store.delete(key)
      return null
    }
    memory = memory + (key to entry)
    val value = codec.decode(entry.text) ?: return null
    return YtLoaded(value, entry.fetchedAtMs, isRefreshing = false)
  }

  private fun <T> write(key: String, codec: YtCodec<T>, value: T) {
    val entry = Entry(codec.encode(value), clock.nowMs())
    memory = memory + (key to entry)
    store.write(key, JSONObject().put("at", entry.fetchedAtMs).put("body", entry.text).toString())
  }

  private fun decodeEntry(text: String): Entry? {
    val json = parseJson(text) ?: return null
    if (!json.has("at") || !json.has("body")) return null
    return Entry(json.optString("body"), json.optLong("at"))
  }

  companion object {
    const val FRESH_MS = 10 * 60_000L
    const val MAX_AGE_MS = 30L * 24 * 3_600_000L

    val TRACKS = YtCodec(YtModelCodec::encodeTracks, YtModelCodec::decodeTracks)
    val PLAYLISTS = YtCodec(YtModelCodec::encodePlaylists, YtModelCodec::decodePlaylists)
    val ARTISTS = YtCodec(YtModelCodec::encodeArtists, YtModelCodec::decodeArtists)

    /** Cache keys are namespaced per account so sign-out can delete exactly one account's data. */
    fun accountPrefix(accountId: String) = "a.$accountId."
  }
}
