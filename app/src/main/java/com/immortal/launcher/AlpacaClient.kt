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
 * Thin client over the Alpaca Market Data API, shared by the stocks quote widget and the options
 * scanner. Handles auth ([AlpacaConfig.authHeaders]) and JSON fetching via the shared [Http] helper;
 * parsing lives in typed methods here so callers get plain data classes.
 *
 * Isolated behind this object because the options endpoint is a `v1beta1` beta namespace whose shape
 * could change — keeping the URL/parse in one place limits the churn if it does.
 *
 * Endpoints used:
 *  - Stocks:  `GET /v2/stocks/snapshots?symbols=…&feed=iex`
 *  - Options: `GET /v1beta1/options/snapshots/{underlying}?feed=indicative&type=…&expiration_date_*=…`
 */
object AlpacaClient {

  /** A stock quote for the widget: symbol, last price, and daily change (absolute + percent). */
  data class StockQuote(
      val symbol: String,
      val last: Double,
      val change: Double,
      val changePct: Double,
  )

  /**
   * A single option contract from a snapshot: the OCC contract symbol, its Black-Scholes delta and
   * implied volatility (server-computed by Alpaca; nullable per contract), and quote/last prices.
   */
  data class OptionContract(
      val symbol: String,
      val delta: Double?,
      val impliedVolatility: Double?,
      val bid: Double?,
      val ask: Double?,
      val last: Double?,
  )

  // --- stocks -----------------------------------------------------------------

  /**
   * Fetch snapshots for [symbols] and reduce to display quotes. Daily change is computed as
   * `dailyBar.c - prevDailyBar.c` (Alpaca has no change field). Blocking — call off the main thread.
   * Returns an empty list if unconfigured or on failure; symbols the API omits are dropped.
   */
  fun fetchStockQuotes(context: Context, symbols: List<String>): List<StockQuote> {
    if (symbols.isEmpty() || !AlpacaConfig.isConfigured(context)) return emptyList()
    val list = symbols.joinToString(",") { it.trim().uppercase() }.trim(',')
    if (list.isBlank()) return emptyList()
    val url = "${AlpacaConfig.DATA_HOST}/v2/stocks/snapshots?symbols=$list&feed=${AlpacaConfig.FEED}"
    val body = Http.getString(url, headers = AlpacaConfig.authHeaders(context)) ?: return emptyList()
    return parseStockQuotes(body, symbols)
  }

  /** Pure parse of the stocks/snapshots response into ordered [StockQuote]s. */
  internal fun parseStockQuotes(body: String, symbols: List<String>): List<StockQuote> =
      runCatching {
            val json = JSONObject(body)
            symbols.mapNotNull { raw ->
              val sym = raw.trim().uppercase()
              val snap = json.optJSONObject(sym) ?: return@mapNotNull null
              val last =
                  snap.optJSONObject("latestTrade")?.optDouble("p")
                      ?: snap.optJSONObject("dailyBar")?.optDouble("c")
                      ?: return@mapNotNull null
              if (last.isNaN()) return@mapNotNull null
              val prevClose = snap.optJSONObject("prevDailyBar")?.optDouble("c")
              val change = if (prevClose != null && !prevClose.isNaN() && prevClose != 0.0) last - prevClose else 0.0
              val pct = if (prevClose != null && !prevClose.isNaN() && prevClose != 0.0) change / prevClose * 100.0 else 0.0
              StockQuote(sym, last, change, pct)
            }
          }
          .getOrDefault(emptyList())

  // --- options ----------------------------------------------------------------

  /**
   * Fetch the option-chain snapshot for [underlying], narrowed server-side to [contractType]
   * ("call"/"put") within the expiry window [expiryGte]..[expiryLte] (ISO yyyy-MM-dd). Follows
   * `next_page_token` up to [maxPages] pages. Returns every contract (delta filtering is the
   * caller's job). Empty if unconfigured or on failure.
   */
  fun fetchOptionChain(
      context: Context,
      underlying: String,
      contractType: String,
      expiryGte: String,
      expiryLte: String,
      maxPages: Int = 5,
      checkCancelled: () -> Unit = {},
  ): List<OptionContract> {
    checkCancelled()
    val sym = underlying.trim().uppercase()
    if (sym.isBlank() || !AlpacaConfig.isConfigured(context)) {
      checkCancelled()
      return emptyList()
    }
    val headers = AlpacaConfig.authHeaders(context)
    val out = ArrayList<OptionContract>()
    var pageToken: String? = null
    var page = 0
    do {
      checkCancelled()
      val url = buildString {
        append("${AlpacaConfig.DATA_HOST}/v1beta1/options/snapshots/$sym")
        append("?feed=${AlpacaConfig.OPTIONS_FEED}")
        append("&type=$contractType")
        append("&expiration_date_gte=$expiryGte")
        append("&expiration_date_lte=$expiryLte")
        append("&limit=1000")
        pageToken?.let { append("&page_token=$it") }
      }
      val body = Http.getString(url, headers = headers)
      checkCancelled()
      if (body == null) break
      val (contracts, next) = parseOptionChain(body)
      out.addAll(contracts)
      pageToken = next
      page++
    } while (!pageToken.isNullOrBlank() && page < maxPages)
    checkCancelled()
    return out
  }

  /**
   * Pure parse of an options snapshot response. Returns the contracts plus the `next_page_token`
   * (null when there are no more pages). Greeks/IV are nullable per contract (Alpaca omits them for
   * contracts without priced quotes), so they flow through as null rather than dropping the contract.
   */
  internal fun parseOptionChain(body: String): Pair<List<OptionContract>, String?> =
      runCatching {
            val json = JSONObject(body)
            val snapshots = json.optJSONObject("snapshots") ?: return@runCatching emptyList<OptionContract>() to null
            val contracts =
                snapshots.keys().asSequence().mapNotNull { key ->
                  val snap = snapshots.optJSONObject(key) ?: return@mapNotNull null
                  val greeks = snap.optJSONObject("greeks")
                  val quote = snap.optJSONObject("latestQuote")
                  OptionContract(
                      symbol = key,
                      delta = greeks?.optDouble("delta")?.takeUnless { it.isNaN() },
                      impliedVolatility = snap.optDouble("impliedVolatility").takeUnless { it.isNaN() },
                      bid = quote?.optDouble("bp")?.takeUnless { it.isNaN() },
                      ask = quote?.optDouble("ap")?.takeUnless { it.isNaN() },
                      last = snap.optJSONObject("latestTrade")?.optDouble("p")?.takeUnless { it.isNaN() },
                  )
                }.toList()
            val next = json.optString("next_page_token", "").takeIf { it.isNotBlank() }
            contracts to next
          }
          .getOrDefault(emptyList<OptionContract>() to null)
}
