/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.util.Log
import androidx.annotation.WorkerThread
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * YouTube-style search completions ("heart attack" → "heart attack demi lovato") from Google's
 * suggestion service, the same list YouTube's search box shows. Free and outside the YouTube Data
 * API quota (unlike a search, which costs one of the ~100 daily calls). Only the typed text is sent;
 * no account or token. Best effort: any failure just means no completions.
 */
internal object YtQueryCompletions {
  const val MAX = 8
  private const val MAX_LEN = 100
  private const val CONNECT_MS = 1_500
  private const val READ_MS = 2_000
  private const val MAX_REPLY_CHARS = 16_000
  private const val CACHE_MS = 10 * 60_000L
  private const val CACHE_SIZE = 64
  private const val TAG = "HearthpaneSuggest"
  private const val BASE = "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q="

  private data class Cached(val atMs: Long, val completions: List<String>)
  private val cache = object : LinkedHashMap<String, Cached>(CACHE_SIZE, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>) = size > CACHE_SIZE
  }

  fun url(query: String): String = BASE + URLEncoder.encode(query, Charsets.UTF_8.name())

  /** `["query",["completion", …], …]` → cleaned completions; anything else → empty. */
  fun parse(reply: String): List<String> = runCatching {
    val list = JSONArray(reply).optJSONArray(1) ?: return emptyList()
    (0 until list.length()).mapNotNull { i -> (list.opt(i) as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_LEN } }
        .distinct().take(MAX)
  }.getOrDefault(emptyList())

  @WorkerThread
  fun fetch(query: String, nowMs: Long = System.currentTimeMillis()): List<String> {
    val key = MusicSuggest.normalize(query)
    if (key.isEmpty()) return emptyList()
    synchronized(cache) { cache[key]?.takeIf { nowMs - it.atMs <= CACHE_MS }?.let { return it.completions } }
    val completions = runCatching { download(query) }
        .onFailure { Log.i(TAG, "completions unavailable: ${it.javaClass.simpleName}") }.getOrNull() ?: return emptyList()
    synchronized(cache) { cache[key] = Cached(nowMs, completions) }
    return completions
  }

  private fun download(query: String): List<String> {
    val conn = URL(url(query)).openConnection() as HttpURLConnection
    try {
      conn.connectTimeout = CONNECT_MS
      conn.readTimeout = READ_MS
      conn.instanceFollowRedirects = false
      if (conn.responseCode != HttpURLConnection.HTTP_OK) return emptyList()
      val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
        val buf = CharArray(MAX_REPLY_CHARS + 1)
        val n = r.read(buf)
        if (n <= 0 || n > MAX_REPLY_CHARS) return emptyList()
        String(buf, 0, n)
      }
      return parse(text)
    } finally {
      conn.disconnect()
    }
  }
}
