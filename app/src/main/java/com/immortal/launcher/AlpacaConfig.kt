/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context

/**
 * Shared Alpaca Market Data API credentials, used by BOTH the stocks quote widget and the options
 * scanner. Alpaca authenticates market-data requests with two headers — `APCA-API-KEY-ID` and
 * `APCA-API-SECRET-KEY` (no OAuth) — against host `https://data.alpaca.markets`.
 *
 * Storage: plain SharedPreferences, matching the app's existing precedent ([MqttConfig] stores its
 * password the same way; the project has no androidx.security-crypto dependency). Market-data keys
 * are read-only (they cannot place orders), so the blast radius of on-device storage is small;
 * users are still advised to mint a market-data-scoped key. See the approved design doc.
 *
 * Feed: fixed to `iex` per the approved design — a free Basic key returns real-time IEX-only quotes.
 */
object AlpacaConfig {

  private const val PREFS = "immortal_alpaca"
  private const val KEY_ID = "key_id"
  private const val KEY_SECRET = "key_secret"

  /** The market-data host (distinct from the trading host api.alpaca.markets). */
  const val DATA_HOST = "https://data.alpaca.markets"

  /** Stock/options data feed. `iex` = free Basic, real-time, IEX-only. */
  const val FEED = "iex"

  /** The feed for options snapshots on a free Basic key (real-time OPRA needs a paid plan). */
  const val OPTIONS_FEED = "indicative"

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun keyId(context: Context): String = prefs(context).getString(KEY_ID, "").orEmpty()

  fun keySecret(context: Context): String = prefs(context).getString(KEY_SECRET, "").orEmpty()

  fun setCredentials(context: Context, keyId: String, keySecret: String) {
    prefs(context).edit().putString(KEY_ID, keyId.trim()).putString(KEY_SECRET, keySecret.trim()).apply()
  }

  /** True once both key and secret are present — the widgets show a "connect" prompt until then. */
  fun isConfigured(context: Context): Boolean =
      keyId(context).isNotBlank() && keySecret(context).isNotBlank()

  /** The auth headers for every market-data request, or an empty map if not configured. */
  fun authHeaders(context: Context): Map<String, String> {
    val id = keyId(context)
    val secret = keySecret(context)
    if (id.isBlank() || secret.isBlank()) return emptyMap()
    return mapOf("APCA-API-KEY-ID" to id, "APCA-API-SECRET-KEY" to secret)
  }
}
