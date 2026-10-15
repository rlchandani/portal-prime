/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate
import org.json.JSONObject

/** A rate with its response's reference date. [saved] is true for either a fresh cache hit or
 * a stale cache fallback, and false for a successful network response or same-currency identity. */
internal data class CurrencyRate(val value: Double, val referenceDate: String?, val saved: Boolean)

/**
 * Walk-up-and-use unit + currency converter. Units convert fully offline; currency
 * uses the keyless Frankfurter API (European Central Bank reference rates), cached so
 * a mostly-offline Portal keeps converting at the last-known rate.
 */
object Converter {

  private const val PREFS = "immortal_converter"
  private val REFERENCE_DATE_SHAPE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

  /** A measurement family. [units] map a display name to its factor relative to the
   * family's base unit; temperature is special-cased (affine, not a pure factor). */
  data class Category(val name: String, val units: List<String>)

  val LENGTH =
      linkedMapOf(
          "m" to 1.0, "km" to 1000.0, "cm" to 0.01, "mm" to 0.001,
          "mi" to 1609.344, "yd" to 0.9144, "ft" to 0.3048, "in" to 0.0254)
  val MASS =
      linkedMapOf("kg" to 1.0, "g" to 0.001, "lb" to 0.45359237, "oz" to 0.028349523125, "st" to 6.35029318)
  val VOLUME =
      linkedMapOf(
          "L" to 1.0, "mL" to 0.001, "gal (US)" to 3.785411784, "qt (US)" to 0.946352946,
          "cup (US)" to 0.2365882365, "fl oz (US)" to 0.0295735295625)
  val SPEED =
      linkedMapOf("km/h" to 1.0, "m/s" to 3.6, "mph" to 1.609344, "kn" to 1.852)

  val UNIT_CATEGORIES =
      listOf(
          "Length" to LENGTH,
          "Mass" to MASS,
          "Volume" to VOLUME,
          "Speed" to SPEED,
          "Temperature" to linkedMapOf("°C" to 1.0, "°F" to 1.0, "K" to 1.0),
      )

  /** Convert [value] from [from] to [to] within [category]. Pure + unit-tested. */
  fun convert(category: String, from: String, to: String, value: Double): Double {
    if (category == "Temperature") return convertTemp(from, to, value)
    val map = UNIT_CATEGORIES.firstOrNull { it.first == category }?.second ?: return value
    val f = map[from] ?: return value
    val t = map[to] ?: return value
    return value * f / t // to base, then to target
  }

  /** Affine temperature conversion across °C / °F / K. */
  fun convertTemp(from: String, to: String, value: Double): Double {
    val celsius =
        when (from) {
          "°F" -> (value - 32.0) * 5.0 / 9.0
          "K" -> value - 273.15
          else -> value
        }
    return when (to) {
      "°F" -> celsius * 9.0 / 5.0 + 32.0
      "K" -> celsius + 273.15
      else -> celsius
    }
  }

  // ---- Currency (keyless Frankfurter / ECB) ------------------------------------

  /** A small, common set so the picker stays glanceable on a 50–100 cm screen. */
  val CURRENCIES =
      listOf("EUR", "USD", "GBP", "RON", "PLN", "CHF", "SEK", "NOK", "DKK", "CAD", "AUD", "JPY", "CZK", "HUF")

  /** Convert [amount] [from]→[to] using cached/fetched ECB rates. Null if no rate is
   * available (offline with an empty cache). */
  fun convertCurrency(context: Context, from: String, to: String, amount: Double): Double? {
    return exchangeRate(context, from, to)?.let { amount * it }
  }

  /** Fetch one unit rate; ToolsWork callers own and can disconnect the underlying request. */
  fun exchangeRate(context: Context, from: String, to: String): Double? =
      exchangeRateSnapshot(context, from, to)?.value

  /** Worker-only cache check so saved rates need not wait for network debounce. */
  internal fun freshExchangeRate(context: Context, from: String, to: String): Double? =
      freshExchangeRateSnapshot(context, from, to)?.value

  internal fun exchangeRateSnapshot(context: Context, from: String, to: String): CurrencyRate? {
    ToolsWork.checkpoint()
    if (from == to) return CurrencyRate(1.0, referenceDate = null, saved = false)
    val rates = rates(context, from) ?: return null
    ToolsWork.checkpoint()
    return rates[to]
  }

  internal fun freshExchangeRateSnapshot(context: Context, from: String, to: String): CurrencyRate? {
    ToolsWork.checkpoint()
    if (from == to) return CurrencyRate(1.0, referenceDate = null, saved = false)
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    return freshRates(prefs, from)?.get(to)
  }

  private fun freshRates(prefs: SharedPreferences, base: String): Map<String, CurrencyRate>? {
    ToolsWork.checkpoint()
    val fresh = System.currentTimeMillis() - prefs.getLong("rates_${base}_ts", 0L) < 6L * 60 * 60 * 1000
    ToolsWork.checkpoint()
    if (!fresh) return null
    val cached = prefs.getString("rates_$base", null)?.let { parseRates(it, saved = true) }
    ToolsWork.checkpoint()
    return cached
  }

  /** Rates with [base] = 1.0, fetched from Frankfurter and cached per base. */
  private fun rates(context: Context, base: String): Map<String, CurrencyRate>? {
    ToolsWork.checkpoint()
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val cacheKey = "rates_$base"
    val tsKey = "rates_${base}_ts"
    freshRates(prefs, base)?.let { return it }
    // Http tracks the socket in the current ToolsWork request and rethrows owned
    // cancellation. Do not swallow it into the ordinary stale-cache fallback.
    val fetched = Http.getString(
        "https://api.frankfurter.app/latest?from=$base&to=${CURRENCIES.filter { it != base }.joinToString(",")}",
        readTimeoutMs = 10000)
    ToolsWork.checkpoint()
    val parsed = fetched?.let { parseRates(it, saved = false) }
    if (parsed != null) {
      ToolsWork.checkpoint()
      prefs.edit().putString(cacheKey, fetched).putLong(tsKey, System.currentTimeMillis()).apply()
      ToolsWork.checkpoint()
      return parsed
    }
    // Stale cache beats nothing.
    val cached = prefs.getString(cacheKey, null)?.let { parseRates(it, saved = true) }
    ToolsWork.checkpoint()
    return cached
  }

  private fun parseRates(json: String, saved: Boolean): Map<String, CurrencyRate>? =
      runCatching {
            val response = JSONObject(json)
            val r = response.getJSONObject("rates")
            val referenceDate = parseReferenceDate(response.opt("date") as? String)
            buildMap {
              r.keys().forEach { k -> put(k, CurrencyRate(r.getDouble(k), referenceDate, saved)) }
            }
          }
          .getOrNull()
          ?.takeIf { it.isNotEmpty() }

  /** A bad date only drops metadata; otherwise usable rates keep their original semantics. */
  private fun parseReferenceDate(date: String?): String? {
    if (date == null || !REFERENCE_DATE_SHAPE.matches(date)) return null
    return runCatching { LocalDate.parse(date); date }.getOrNull()
  }

}
