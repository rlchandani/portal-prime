/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import java.net.URI
import java.net.URISyntaxException

/** The custom dashboard address. An empty address disables the dashboard. */
object HomeDashboardConfig {
  private const val PREFS = "immortal_home_dashboard"
  private const val KEY_URL = "url"

  data class Settings(val url: String = "")

  private fun prefs(context: Context) =
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun load(context: Context): Settings =
      Settings(url = normalizeUrl(prefs(context).getString(KEY_URL, "").orEmpty()) ?: "")

  /** Invalid input leaves the saved address unchanged; a blank address clears it. */
  fun setUrl(context: Context, url: String) {
    val normalized = normalizeUrl(url) ?: return
    prefs(context).edit().putString(KEY_URL, normalized).apply()
  }

  fun configured(context: Context): Boolean = load(context).url.isNotEmpty()

  /**
   * Validate with the JVM URI parser and trim outer whitespace only. Keep the original path,
   * query, fragment, escaping and trailing slash; never include the address in diagnostics.
   */
  fun normalizeUrl(raw: String): String? {
    if (raw.any { Character.isISOControl(it) }) return null
    val url = raw.trim()
    if (url.isEmpty()) return ""
    val uri = try {
      URI(url)
    } catch (_: URISyntaxException) {
      return null
    }
    if (!uri.isAbsolute ||
        !(uri.scheme.equals("http", ignoreCase = true) ||
            uri.scheme.equals("https", ignoreCase = true)) ||
        uri.host.isNullOrEmpty() ||
        uri.rawUserInfo != null ||
        uri.port !in -1..65535) return null
    return url
  }
}
