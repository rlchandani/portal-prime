/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import org.json.JSONArray

/**
 * The user's tracked stock tickers for the stocks quote widget. A simple JSON string-array of
 * symbols in SharedPreferences. Credentials live separately in [AlpacaConfig] (shared with the
 * options scanner).
 */
object StocksConfig {

  private const val PREFS = "immortal_stocks"
  private const val KEY = "tickers"

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun load(context: Context): List<String> {
    val raw = prefs(context).getString(KEY, null) ?: return emptyList()
    return runCatching {
          val arr = JSONArray(raw)
          (0 until arr.length()).map { arr.getString(it).trim().uppercase() }.filter { it.isNotBlank() }
        }
        .getOrDefault(emptyList())
  }

  private fun save(context: Context, tickers: List<String>) {
    val arr = JSONArray()
    tickers.forEach { arr.put(it) }
    prefs(context).edit().putString(KEY, arr.toString()).apply()
  }

  /** Add a ticker (uppercased). Ignores blanks and duplicates. */
  fun add(context: Context, ticker: String) {
    val clean = ticker.trim().uppercase()
    if (clean.isBlank()) return
    val current = load(context)
    if (current.contains(clean)) return
    save(context, current + clean)
  }

  fun remove(context: Context, ticker: String) =
      save(context, load(context).filterNot { it == ticker.trim().uppercase() })
}
