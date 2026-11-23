/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import java.net.URLEncoder
import org.json.JSONObject

/**
 * Stock quotes for [HearthpaneStocks]. The first source is the Stocks widget's own: Alpaca's market data with the
 * household's key (Settings › Stocks), real-time IEX prices. Without that key, the keyless fallback is Yahoo
 * Finance's public chart endpoint (`query1.finance.yahoo.com/v8/finance/chart/{symbol}`): no key or sign-up, but it
 * is unofficial, Yahoo's terms allow personal use only, prices can be delayed and it may change or rate-limit
 * without notice; a failure reads as "couldn't reach the prices". Pure parsing.
 */
internal object StockQuotes {
  data class Quote(val symbol: String, val name: String?, val last: Double, val change: Double, val changePct: Double,
      val currency: String?)

  private val SYMBOL = Regex("^[A-Z]{1,5}([.\\-][A-Z]{1,2})?$")

  /** A plausible ticker as typed ("brk.b" → BRK.B), or null. */
  fun symbol(raw: String): String? = raw.trim().removePrefix("$").uppercase().takeIf(SYMBOL::matches)

  fun yahooUrl(symbol: String): String =
      "https://query1.finance.yahoo.com/v8/finance/chart/${URLEncoder.encode(symbol, "UTF-8")}?range=1d&interval=1d"

  /** Yahoo's chart response → today's quote against the previous close; null when the shape isn't there. */
  fun parseYahoo(body: String?): Quote? = runCatching {
    val meta = JSONObject(body ?: return null).getJSONObject("chart").getJSONArray("result").getJSONObject(0)
        .getJSONObject("meta")
    val symbol = meta.getString("symbol").uppercase()
    val last = meta.getDouble("regularMarketPrice")
    val prev = meta.optDouble("previousClose").takeUnless { it.isNaN() } ?: meta.optDouble("chartPreviousClose")
    val change = if (prev.isNaN() || prev == 0.0) 0.0 else last - prev
    val pct = if (prev.isNaN() || prev == 0.0) 0.0 else change / prev * 100.0
    val name = meta.optString("shortName").ifEmpty { meta.optString("longName") }.trim().ifEmpty { null }
    Quote(symbol, name, last, change, pct, meta.optString("currency").ifEmpty { null })
  }.getOrNull()?.takeIf { it.last.isFinite() && it.last > 0 }
}
