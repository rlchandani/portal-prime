/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import org.json.JSONObject

/** Pure helpers for the pane's own search box (JVM-tested). */
object YtMusicSearch {
  const val MAX_QUERY_CHARS = 100

  /** A clean, bounded query, or null when nothing searchable remains. */
  fun normalize(raw: String?): String? {
    if (raw == null) return null
    // Map every Unicode space/line separator to a plain space before the shared cleaner.
    val spaced = buildString(minOf(raw.length, MAX_QUERY_CHARS * 4)) {
      for (ch in raw) {
        append(if (Character.isWhitespace(ch) || Character.isSpaceChar(ch)) ' ' else ch)
        if (length >= MAX_QUERY_CHARS * 4) break
      }
    }
    return YtMusicBridgeParser.cleanText(spaced, MAX_QUERY_CHARS).ifEmpty { null }
  }

  /** The YouTube Music search URL for [query] (fallback when in-page search is unavailable). */
  fun url(query: String?): String? {
    val q = normalize(query) ?: return null
    return "${YtMusicUrls.SEARCH}?q=" + URLEncoder.encode(q, "UTF-8").replace("+", "%20")
  }

  /** In-page search script; the query is embedded only as a JSON string literal. */
  fun script(query: String?): String? {
    val q = normalize(query) ?: return null
    return "window.__portalYtm?window.__portalYtm.search(${JSONObject.quote(q)}):false;"
  }

  /** The query a YouTube Music search page is showing, or null for any other page. */
  fun queryFromUrl(url: String?): String? {
    if (YtMusicTab.forUrl(url) != YtMusicTab.SEARCH) return null
    val raw = YtMusicUrls.httpsUri(url)?.rawQuery ?: return null
    val value = raw.split('&').firstOrNull { it.startsWith("q=") }?.removePrefix("q=") ?: return null
    val decoded = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull() ?: return null
    return normalize(decoded)
  }

  fun sameQuery(a: String?, b: String?): Boolean {
    val x = normalize(a) ?: return false
    val y = normalize(b) ?: return false
    return x.lowercase(Locale.ROOT) == y.lowercase(Locale.ROOT)
  }
}
