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
 * The user's tracked cryptocurrencies for the crypto price widget. Stored as a small JSON array in
 * SharedPreferences (same shape as [CameraConfig]). Each coin carries CoinGecko's `id` (e.g.
 * "bitcoin" — what the API is queried by) and a short display `symbol` (e.g. "BTC") shown on the tile.
 *
 * Keyless by design: CoinGecko's public `simple/price` endpoint needs no API key, so there's no
 * secret to store here — just the coin list.
 */
object CryptoConfig {

  private const val PREFS = "immortal_crypto"
  private const val KEY = "coins"

  /** One tracked coin. [id] is the CoinGecko id used to query; [symbol] is the short display label. */
  data class Coin(val id: String, val symbol: String)

  /** Sensible defaults so a freshly-added widget isn't empty before the user customizes it. */
  val DEFAULTS: List<Coin> = listOf(Coin("bitcoin", "BTC"), Coin("ethereum", "ETH"))

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun load(context: Context): List<Coin> {
    val raw = prefs(context).getString(KEY, null) ?: return DEFAULTS
    return runCatching {
          val arr = JSONArray(raw)
          (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Coin(o.optString("id", "").trim().lowercase(), o.optString("symbol", "").trim().uppercase())
          }.filter { it.id.isNotBlank() }
        }
        .getOrDefault(DEFAULTS)
  }

  private fun save(context: Context, coins: List<Coin>) {
    val arr = JSONArray()
    coins.forEach { c -> arr.put(JSONObject().put("id", c.id).put("symbol", c.symbol)) }
    prefs(context).edit().putString(KEY, arr.toString()).apply()
  }

  /** Add a coin (id required; symbol falls back to an uppercased id). Ignores duplicate ids. */
  fun add(context: Context, id: String, symbol: String) {
    val clean = id.trim().lowercase()
    if (clean.isBlank()) return
    val current = load(context)
    if (current.any { it.id == clean }) return
    val sym = symbol.trim().uppercase().ifBlank { clean.uppercase() }
    save(context, current + Coin(clean, sym))
  }

  fun remove(context: Context, id: String) = save(context, load(context).filterNot { it.id == id })
}
