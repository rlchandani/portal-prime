/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * The options-scanner engine: fetches option chains for the configured underlyings via
 * [AlpacaClient], filters to contracts whose (Alpaca-computed) delta falls in the user's band, and
 * decodes the OCC contract symbol for display. No Black-Scholes here — Alpaca returns `greeks.delta`
 * server-side, so this is a pure filter + presentation layer.
 *
 * OCC symbol format (21 chars): `ROOT`(≤6, left) + `YYMMDD` + `C|P` + `strike*1000`(8 digits).
 * e.g. `AAPL  241220C00300000` → AAPL, 2024-12-20, Call, strike 300.00.
 */
object OptionsScanner {

  /** A scanner result row: the underlying, expiry, type, strike, delta, IV, and a mid/last price. */
  data class Hit(
      val underlying: String,
      val expiry: String, // yyyy-MM-dd
      val call: Boolean,
      val strike: Double,
      val delta: Double,
      val impliedVolatility: Double?,
      val mid: Double?,
  )

  /**
   * Scan all [tickers] and return contracts in the [deltaMin]..[deltaMax] band, sorted so contracts
   * closest to the band's center appear first, capped at [max]. Blocking — call off the main thread.
   * Delta is compared by magnitude, so a puts scan (negative deltas) matches a positive band too.
   */
  fun scan(
      context: Context,
      tickers: List<String>,
      type: String,
      deltaMin: Double,
      deltaMax: Double,
      expiryDays: Int,
      max: Int = 8,
      now: Long = System.currentTimeMillis(),
  ): List<Hit> {
    if (tickers.isEmpty() || !AlpacaConfig.isConfigured(context)) return emptyList()
    val (gte, lte) = expiryWindow(now, expiryDays)
    val isCall = type != OptionsScanConfig.TYPE_PUT
    val hits = ArrayList<Hit>()
    for (ticker in tickers) {
      val chain = AlpacaClient.fetchOptionChain(context, ticker, type, gte, lte)
      hits.addAll(filterChain(ticker, chain, isCall, deltaMin, deltaMax))
    }
    val center = (deltaMin + deltaMax) / 2.0
    return hits.sortedBy { abs(abs(it.delta) - center) }.take(max)
  }

  /** Today and today+[days] as ISO yyyy-MM-dd strings (UTC), for the expiry_date_gte/lte params. */
  internal fun expiryWindow(now: Long, days: Int): Pair<String, String> {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    val gte = fmt.format(java.util.Date(now))
    val lte = fmt.format(java.util.Date(now + days.coerceIn(1, 365) * 24L * 60 * 60 * 1000))
    return gte to lte
  }

  /**
   * Pure: keep contracts whose delta magnitude is within [deltaMin]..[deltaMax], decoding each OCC
   * symbol. Contracts without a delta (Alpaca omits greeks for unpriced contracts) are dropped —
   * they can't be band-filtered. Compared by magnitude so put deltas (negative) match a positive band.
   */
  internal fun filterChain(
      underlying: String,
      chain: List<AlpacaClient.OptionContract>,
      isCall: Boolean,
      deltaMin: Double,
      deltaMax: Double,
  ): List<Hit> {
    val lo = minOf(deltaMin, deltaMax)
    val hi = maxOf(deltaMin, deltaMax)
    return chain.mapNotNull { c ->
      val d = c.delta ?: return@mapNotNull null
      val mag = abs(d)
      if (mag < lo || mag > hi) return@mapNotNull null
      val occ = parseOcc(c.symbol) ?: return@mapNotNull null
      val mid = midPrice(c)
      Hit(
          underlying = underlying,
          expiry = occ.expiry,
          call = occ.call,
          strike = occ.strike,
          delta = d,
          impliedVolatility = c.impliedVolatility,
          mid = mid,
      )
    }
  }

  /** Mid of bid/ask when both present, else last, else whichever side exists. */
  private fun midPrice(c: AlpacaClient.OptionContract): Double? {
    val b = c.bid
    val a = c.ask
    return when {
      b != null && a != null && (b > 0 || a > 0) -> (b + a) / 2.0
      c.last != null -> c.last
      a != null -> a
      b != null -> b
      else -> null
    }
  }

  private data class Occ(val root: String, val expiry: String, val call: Boolean, val strike: Double)

  /**
   * Decode an OCC option symbol. The last 15 characters are fixed-width (`YYMMDD` + `C|P` + 8-digit
   * strike×1000); the root is everything before that. Returns null if it doesn't match the shape.
   */
  private fun parseOcc(symbol: String): Occ? {
    val s = symbol.trim()
    if (s.length < 15) return null
    val tail = s.substring(s.length - 15)
    val root = s.substring(0, s.length - 15).trim()
    return runCatching {
          val yy = tail.substring(0, 2).toInt()
          val mm = tail.substring(2, 4).toInt()
          val dd = tail.substring(4, 6).toInt()
          val cp = tail[6]
          val strikeRaw = tail.substring(7).toLong()
          if (cp != 'C' && cp != 'P') return null
          Occ(
              root = root,
              expiry = "20%02d-%02d-%02d".format(yy, mm, dd),
              call = cp == 'C',
              strike = strikeRaw / 1000.0,
          )
        }
        .getOrNull()
  }
}
