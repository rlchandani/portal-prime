/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.youtube

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Google's YouTube quota day: it resets at midnight America/Los_Angeles (DST-aware). */
internal object YtPacificDay {
  private val ZONE: TimeZone = TimeZone.getTimeZone("America/Los_Angeles")

  fun key(epochMs: Long): String {
    val cal = Calendar.getInstance(ZONE, Locale.US).apply { timeInMillis = epochMs }
    return String.format(Locale.US, "%04d-%02d-%02d",
        cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
  }
}

/**
 * Search on a budget: at most [DAILY_CAP] real `search.list` calls per Pacific day (a separate
 * bucket from library reads — each search costs 100 quota units), results cached 24 h by
 * normalized query in memory and on disk. Cache hits, rejected queries and offline attempts
 * don't count; Google's `quotaExceeded` spends the rest of the day.
 */
internal class YtSearch(
    private val api: YtLibraryApi,
    private val store: YtCacheStore,
    private val clock: YtClock,
) {
  private class Cached(val tracks: List<YtTrack>, val atMs: Long)

  private val mutex = Mutex()
  @Volatile private var used: Pair<String, Int>? = null
  @Volatile private var cache: Map<String, Cached>? = null

  fun remainingToday(): Int = (DAILY_CAP - usedToday()).coerceAtLeast(0)

  suspend fun search(accountId: String?, rawQuery: String): YtResult<YtSearchPage> = mutex.withLock {
    val query = normalize(rawQuery)
    if (query.length < MIN_QUERY) return YtResult.Failed(0, YtProblem.INVALID_REQUEST)
    cachedTracks(query)?.let { return YtResult.Ok(YtSearchPage(it, remainingToday(), fromCache = true)) }
    if (accountId == null) return YtResult.NeedsSignIn
    if (remainingToday() == 0) return YtResult.Resting
    val result = api.search(accountId, rawQuery.trim().replace(WHITESPACE, " ")) { spend(1) }
    when (result) {
      is YtResult.Ok -> remember(query, result.value)
      YtResult.Resting -> spend(DAILY_CAP)
      else -> Unit
    }
    result.map { YtSearchPage(it, remainingToday(), fromCache = false) }
  }

  private fun usedToday(): Int {
    val today = YtPacificDay.key(clock.nowMs())
    val current = used ?: loadBudget().also { used = it }
    return if (current.first == today) current.second else 0
  }

  private fun spend(calls: Int) {
    val today = YtPacificDay.key(clock.nowMs())
    val next = today to (usedToday() + calls).coerceAtMost(DAILY_CAP)
    used = next
    store.write(BUDGET_KEY, JSONObject().put("day", next.first).put("used", next.second).toString())
  }

  private fun loadBudget(): Pair<String, Int> {
    val json = store.read(BUDGET_KEY)?.let(::parseJson) ?: return "" to 0
    return json.optString("day") to json.optInt("used").coerceIn(0, DAILY_CAP)
  }

  private fun cachedTracks(query: String): List<YtTrack>? {
    val entry = entries()[query] ?: return null
    return entry.tracks.takeIf { clock.nowMs() - entry.atMs <= TTL_MS }
  }

  private fun remember(query: String, tracks: List<YtTrack>) {
    val now = clock.nowMs()
    // Map order is recency order (re-searched queries move to the end), so evict from the front.
    val live = entries().filterValues { now - it.atMs <= TTL_MS } - query
    val kept = live.entries.toList().takeLast(MAX_ENTRIES - 1).associate { it.key to it.value }
    val next = kept + (query to Cached(tracks, now))
    cache = next
    store.write(CACHE_KEY, encode(next))
  }

  private fun entries(): Map<String, Cached> = cache ?: (store.read(CACHE_KEY)?.let(::decode).orEmpty()).also { cache = it }

  private fun encode(entries: Map<String, Cached>): String {
    val list = JSONArray()
    entries.forEach { (q, c) -> list.put(JSONObject().put("q", q).put("at", c.atMs).put("t", YtModelCodec.encodeTracks(c.tracks))) }
    return list.toString()
  }

  private fun decode(text: String): Map<String, Cached>? {
    val list = try {
      JSONArray(text)
    } catch (e: org.json.JSONException) {
      return null
    }
    return (0 until list.length()).mapNotNull { i ->
      val json = list.optJSONObject(i) ?: return@mapNotNull null
      val tracks = YtModelCodec.decodeTracks(json.optString("t")) ?: return@mapNotNull null
      json.optString("q") to Cached(tracks, json.optLong("at"))
    }.toMap()
  }

  companion object {
    const val DAILY_CAP = 100
    const val MIN_QUERY = 2
    const val TTL_MS = 24 * 3_600_000L
    private const val MAX_ENTRIES = 60
    private const val BUDGET_KEY = "search.budget"
    private const val CACHE_KEY = "search.cache"
    private val WHITESPACE = Regex("\\s+")

    fun normalize(query: String): String = query.trim().lowercase(Locale.ROOT).replace(WHITESPACE, " ")
  }
}
