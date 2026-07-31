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

  fun load(context: Context): List<Feed> {
    val raw = prefs(context).getString(KEY, null) ?: return emptyList()
    return runCatching {
          val arr = JSONArray(raw)
          (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Feed(o.optLong("id", System.nanoTime()), o.optString("name", "").trim(), o.optString("url", "").trim())
          }.filter { it.url.isNotBlank() }
        }
        .getOrDefault(emptyList())
  }

  private fun save(context: Context, feeds: List<Feed>) {
    val arr = JSONArray()
    feeds.forEach { f -> arr.put(JSONObject().put("id", f.id).put("name", f.name).put("url", f.url)) }
    prefs(context).edit().putString(KEY, arr.toString()).apply()
  }

  fun add(context: Context, name: String, url: String) {
    val clean = url.trim()
    if (clean.isBlank()) return
    save(context, load(context) + Feed(System.currentTimeMillis(), name.trim(), clean))
  }

  fun remove(context: Context, id: Long) = save(context, load(context).filterNot { it.id == id })
}
