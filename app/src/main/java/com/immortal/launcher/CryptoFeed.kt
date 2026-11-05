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
 * Keyless crypto prices from CoinGecko's public `coins/markets` endpoint (no API key). One GET
 * returns price, 24h change AND a 7-day sparkline per coin — the sparkline feeds the widget's
 * mini price chart (Dusk Aurora design).
 *
 * Response shape (array, one object per coin):
 * ```
 * [ { "id": "bitcoin", "symbol": "btc", "current_price": 43000.0,
 *     "price_change_percentage_24h": 2.34,
 *     "sparkline_in_7d": { "price": [42800.1, 42910.5, …] } }, … ]
 * ```
 *
 * Results are cached via [HttpCache] so the widget renders instantly and survives CoinGecko's
 * rate limits (the free tier throttles hard) or brief offline periods by serving the last value.
 */
object CryptoFeed {

  private const val BASE = "https://api.coingecko.com/api/v3/coins/markets"
  private const val CACHE_KEY = "crypto_markets"
  // Fresh window for the cache — CoinGecko's free tier is rate-limited, and prices at a glance
  // don't need sub-5-minute precision on a wall display.
  private const val TTL_MS = 5L * 60 * 1000
  // Downsample the ~168-point 7d sparkline to this many points — plenty for a 50dp-wide chart.
  private const val SPARK_POINTS = 24

  /** A priced coin: symbol, USD price, 24h % change, and a downsampled 7d sparkline (may be empty). */
  data class Quote(
      val id: String,
      val symbol: String,
      val usd: Double,
      val change24h: Double?,
      val spark: List<Double> = emptyList(),
  )

  /**
   * Fetch quotes for [coins], preserving their order. Blocking — call from a background dispatcher.
   * Failed requests or malformed responses serve cached (possibly stale) prices when available.
   * A valid empty markets array replaces old prices. Coins the API doesn't return are dropped.
   */
  fun fetch(context: Context, coins: List<CryptoConfig.Coin>): List<Quote> {
    if (coins.isEmpty()) return emptyList()
    val ids = coins.joinToString(",") { it.id }
    val url = "$BASE?vs_currency=usd&ids=$ids&sparkline=true&price_change_percentage=24h"
    val body =
        HttpCache.getOrFetch(context, "$CACHE_KEY:$ids", TTL_MS) {
          // Admit the response before publication so an error page cannot replace usable prices.
          Http.getString(url)?.takeIf { decode(it, coins) != null }
        }
            ?: return emptyList()
    return parse(body, coins)
  }

  /** Parse the markets array into [Quote]s ordered like [coins], skipping coins the API omitted. */
  internal fun parse(body: String, coins: List<CryptoConfig.Coin>): List<Quote> =
      decode(body, coins).orEmpty()

  /** Null distinguishes failed decoding from a valid response with no configured coins. */
  private fun decode(body: String, coins: List<CryptoConfig.Coin>): List<Quote>? =
      runCatching {
            val arr = JSONArray(body)
            val byId = HashMap<String, Quote>(arr.length())
            for (i in 0 until arr.length()) {
              val o = arr.optJSONObject(i) ?: continue
              val id = o.optString("id") ?: continue
              val change = o.optDouble("price_change_percentage_24h")
              val sparkArr = o.optJSONObject("sparkline_in_7d")?.optJSONArray("price")
              byId[id] =
                  Quote(
                      id = id,
                      symbol = o.optString("symbol").uppercase(),
                      usd = o.optDouble("current_price", 0.0),
                      change24h = change.takeIf { it.isFinite() },
                      spark = downsample(sparkArr),
                  )
            }
            coins.mapNotNull { coin ->
              // Keep the user's configured symbol label (byId's is API-derived).
              byId[coin.id]?.copy(symbol = coin.symbol)
            }
          }
          .getOrNull()

  /** Evenly downsample a JSON price array to [SPARK_POINTS] doubles (empty when absent/short). */
  private fun downsample(arr: JSONArray?): List<Double> {
    if (arr == null || arr.length() < 2) return emptyList()
    val n = arr.length()
    val count = minOf(SPARK_POINTS, n)
    return (0 until count).map { i ->
      arr.optDouble(i * (n - 1) / (count - 1), 0.0)
    }
  }
}
