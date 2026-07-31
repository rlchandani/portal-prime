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
 * Configuration for the options scanner widget: which underlyings to scan, the delta band to filter
 * for, the contract type (calls/puts), and how far out in expiry to look. Credentials are shared via
 * [AlpacaConfig]. Tickers are a JSON string-array; the scalar params live as individual prefs.
 *
 * The delta band defaults to 0.20–0.30 (the user's requested "20–30 delta" range — a common
 * income/premium-selling target). Alpaca returns `greeks.delta` server-side, so the scanner is a
 * pure client-side filter (see [AlpacaClient.fetchOptionChain] + the widget's filter).
 */
object OptionsScanConfig {

  private const val PREFS = "immortal_options"
  private const val KEY_TICKERS = "tickers"
  private const val KEY_DELTA_MIN = "delta_min"
  private const val KEY_DELTA_MAX = "delta_max"
  private const val KEY_TYPE = "type"
  private const val KEY_EXPIRY_DAYS = "expiry_days"

  const val TYPE_CALL = "call"
  const val TYPE_PUT = "put"

  const val DEFAULT_DELTA_MIN = 0.20
  const val DEFAULT_DELTA_MAX = 0.30
  const val DEFAULT_EXPIRY_DAYS = 45

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun tickers(context: Context): List<String> {
    val raw = prefs(context).getString(KEY_TICKERS, null) ?: return emptyList()
    return runCatching {
          val arr = JSONArray(raw)
          (0 until arr.length()).map { arr.getString(it).trim().uppercase() }.filter { it.isNotBlank() }
        }
        .getOrDefault(emptyList())
  }

  private fun saveTickers(context: Context, tickers: List<String>) {
    val arr = JSONArray()
    tickers.forEach { arr.put(it) }
    prefs(context).edit().putString(KEY_TICKERS, arr.toString()).apply()
  }

  fun addTicker(context: Context, ticker: String) {
    val clean = ticker.trim().uppercase()
    if (clean.isBlank()) return
    val current = tickers(context)
    if (current.contains(clean)) return
    saveTickers(context, current + clean)
  }

  fun removeTicker(context: Context, ticker: String) =
      saveTickers(context, tickers(context).filterNot { it == ticker.trim().uppercase() })

  fun deltaMin(context: Context): Double = prefs(context).getFloat(KEY_DELTA_MIN, DEFAULT_DELTA_MIN.toFloat()).toDouble()

  fun deltaMax(context: Context): Double = prefs(context).getFloat(KEY_DELTA_MAX, DEFAULT_DELTA_MAX.toFloat()).toDouble()

  /** Store the delta band, keeping min <= max and clamping to a sane 0.01..0.99 range. */
  fun setDeltaBand(context: Context, min: Double, max: Double) {
    val lo = min.coerceIn(0.01, 0.99)
    val hi = max.coerceIn(0.01, 0.99)
    prefs(context).edit()
        .putFloat(KEY_DELTA_MIN, minOf(lo, hi).toFloat())
        .putFloat(KEY_DELTA_MAX, maxOf(lo, hi).toFloat())
        .apply()
  }

  fun type(context: Context): String =
      prefs(context).getString(KEY_TYPE, TYPE_CALL).takeIf { it == TYPE_CALL || it == TYPE_PUT } ?: TYPE_CALL

  fun setType(context: Context, type: String) {
    prefs(context).edit().putString(KEY_TYPE, if (type == TYPE_PUT) TYPE_PUT else TYPE_CALL).apply()
  }

  fun expiryDays(context: Context): Int = prefs(context).getInt(KEY_EXPIRY_DAYS, DEFAULT_EXPIRY_DAYS)

  fun setExpiryDays(context: Context, days: Int) {
    prefs(context).edit().putInt(KEY_EXPIRY_DAYS, days.coerceIn(1, 365)).apply()
  }
}
