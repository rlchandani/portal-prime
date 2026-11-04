/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The user's news feed subscriptions for the headlines widget. Stored as a small JSON array in
 * SharedPreferences (same shape as [CameraConfig]). Each feed is a public RSS/Atom URL plus an
 * optional short display name — the "paste a public URL" model, like [CalendarFeed]'s ICS links.
 * Keyless: RSS/Atom needs no account, so there's no secret here.
 */
object NewsConfig {

  private const val PREFS = "immortal_news"
  private const val KEY = "feeds"

  /** One subscription. [url] is the RSS/Atom endpoint; [name] labels the source on the tile. */
  data class Feed(val id: Long, val name: String, val url: String)

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  @Synchronized
  fun load(context: Context): List<Feed> {
    val raw = prefs(context).getString(KEY, null) ?: return emptyList()
    return runCatching {
          val arr = JSONArray(raw)
          val rows = (0 until arr.length()).map { arr.getJSONObject(it) }
              .filter { it.optString("url", "").isNotBlank() }
          val storedIds = rows.map(::storedId)
          // Reserve later rows too: a replacement must not steal another feed's ID.
          val reserved = storedIds.filterNotNull().toHashSet()
          val used = HashSet<Long>()
          var replacement = 0L
          rows.mapIndexed { index, o ->
            val original = storedIds[index]
            val id = if (original != null && original !in used) original else {
              while (replacement in reserved || replacement in used) replacement = nextId(replacement)
              replacement
            }
            used.add(id)
            Feed(id, o.optString("name", "").trim(), o.optString("url", "").trim())
          }
        }
        .getOrDefault(emptyList())
  }

  private fun storedId(o: JSONObject): Long? {
    val id = o.optLong("id", Long.MIN_VALUE)
    // Distinct defaults detect missing/unconvertible values while retaining a real minimum ID.
    return if (id != Long.MIN_VALUE || o.optLong("id", Long.MAX_VALUE) == id) id else null
  }

  private fun nextId(id: Long): Long = if (id == Long.MAX_VALUE) Long.MIN_VALUE else id + 1

  private fun save(context: Context, feeds: List<Feed>) {
    val arr = JSONArray()
    feeds.forEach { f -> arr.put(JSONObject().put("id", f.id).put("name", f.name).put("url", f.url)) }
    prefs(context).edit().putString(KEY, arr.toString()).apply()
  }

  @Synchronized
  fun add(context: Context, name: String, url: String) {
    val clean = url.trim()
    if (clean.isBlank()) return
    val feeds = load(context)
    val ids = feeds.mapTo(HashSet()) { it.id }
    var id = System.currentTimeMillis()
    while (id in ids) id = nextId(id)
    save(context, feeds + Feed(id, name.trim(), clean))
  }

  @Synchronized
  fun remove(context: Context, id: Long) = save(context, load(context).filterNot { it.id == id })
}
