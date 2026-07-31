/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import org.json.JSONObject

/**
 * Keyless crypto prices from CoinGecko's public `simple/price` endpoint. No API key required, so
 * this is the cleanest of the network widgets — one GET, a flat JSON object keyed by coin id.
 *
 * Response shape (for `ids=bitcoin,ethereum&vs_currencies=usd&include_24hr_change=true`):
 * ```
 * { "bitcoin":  {"usd": 43000.0, "usd_24h_change":  2.34},
 *   "ethereum": {"usd":  2300.0, "usd_24h_change": -1.10} }
 * ```
 *
 * Results are cached via [HttpCache] so the widget renders instantly and survives CoinGecko's
 * rate limits (the free tier throttles hard) or brief offline periods by serving the last value.
 */
object CryptoFeed {

  private const val BASE = "https://api.coingecko.com/api/v3/simple/price"
  private const val CACHE_KEY = "crypto_prices"
  // Fresh window for the cache — CoinGecko's free tier is rate-limited, and prices at a glance
  // don't need sub-5-minute precision on a wall display.
  private const val TTL_MS = 5L * 60 * 1000

  /** A priced coin ready to render: symbol, USD price, and 24h % change (may be null if absent). */
  data class Quote(val id: String, val symbol: String, val usd: Double, val change24h: Double?)

  /**
   * Fetch quotes for [coins], preserving their order. Blocking — call from a background dispatcher.
   * Returns an empty list only when there's no cached data AND the network fails; otherwise serves
   * cached (possibly stale) prices. Coins the API doesn't return are dropped.
   */
  fun fetch(context: Context, coins: List<CryptoConfig.Coin>): List<Quote> {
    if (coins.isEmpty()) return emptyList()
    val ids = coins.joinToString(",") { it.id }
    val url = "$BASE?ids=$ids&vs_currencies=usd&include_24hr_change=true"
    val body =
        HttpCache.getOrFetch(context, "$CACHE_KEY:$ids", TTL_MS) { Http.getString(url) }
            ?: return emptyList()
    return parse(body, coins)
  }

  /** Parse the flat CoinGecko object into ordered [Quote]s, skipping any coin the API omitted. */
  internal fun parse(body: String, coins: List<CryptoConfig.Coin>): List<Quote> =
      runCatching {
            val json = JSONObject(body)
            coins.mapNotNull { coin ->
              val o = json.optJSONObject(coin.id) ?: return@mapNotNull null
              if (!o.has("usd")) return@mapNotNull null
              val change = if (o.has("usd_24h_change")) o.optDouble("usd_24h_change") else null
              Quote(
                  id = coin.id,
                  symbol = coin.symbol,
                  usd = o.optDouble("usd", 0.0),
                  change24h = change?.takeUnless { it.isNaN() },
              )
            }
          }
          .getOrDefault(emptyList())
}
