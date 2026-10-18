/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/** Public native fixtures. The only Context is a wrapper with no backing device Context. */
internal object CurrencyRateIntegrityDeviceChecks {
  private const val PREFS = "immortal_converter"
  private const val DATE = "2026-10-16"
  private const val EXPECTED_FIXTURE_COUNT = 52
  private const val EXPECTED_CHECK_COUNT = EXPECTED_FIXTURE_COUNT * 3 + 6
  private const val EXPECTED_CACHE_CASES = EXPECTED_FIXTURE_COUNT + 2

  private data class Fixture(
      val name: String,
      val json: String,
      val rates: Map<String, Double>? = null,
      val date: String? = null,
  ) {
    fun expected(saved: Boolean): Map<String, CurrencyRate>? =
        rates?.mapValues { CurrencyRate(it.value, date, saved) }
  }

  fun run(): Bundle {
    val started = SystemClock.elapsedRealtime()
    val fixtures = fixtures()
    val checks = JSONArray()
    val failures = JSONArray()
    var actualCacheCases = 0
    var actualCachePassed = 0
    var attemptedWrites = 0
    fun verify(name: String, block: () -> Boolean): Boolean {
      var exceptionType: String? = null
      val passed = try {
        block()
      } catch (error: Exception) {
        // Never emit exception messages, payloads, paths, or private data.
        exceptionType = error.javaClass.simpleName
        false
      }
      val detail = JSONObject().put("name", name).put("passed", passed)
      if (exceptionType != null) detail.put("exceptionType", exceptionType)
      checks.put(detail)
      if (!passed) failures.put(detail)
      return passed
    }
    for (fixture in fixtures) {
      for (saved in listOf(false, true)) {
        verify("decode.${fixture.name}.saved_$saved") {
          CurrencyRateCodec.decode(fixture.json, "USD", saved) == fixture.expected(saved)
        }
      }
      val prefs = MemoryPreferences(mapOf(
          "rates_USD" to fixture.json, "rates_USD_ts" to System.currentTimeMillis(),
          "public_sentinel" to "unchanged"))
      val context = MemoryContext(prefs)
      val before = prefs.all
      actualCacheCases++
      val passed = verify("cache.${fixture.name}") {
        val actual = Converter.freshExchangeRateSnapshot(context, "USD", "EUR")
        val expected = fixture.expected(saved = true)?.get("EUR")
        actual == expected && context.preferenceRequests == 1 &&
            prefs.stringReads == 1 && prefs.longReads == 1 &&
            prefs.attemptedWrites == 0 && prefs.all == before
      }
      if (passed) actualCachePassed++
      attemptedWrites += prefs.attemptedWrites
    }
    // Identity must bypass preferences altogether, even if every cached quote is invalid.
    val identityPrefs = MemoryPreferences(mapOf(
        "rates_USD" to """{"base":"GBP","amount":100,"rates":{"EUR":0}}""",
        "rates_USD_ts" to System.currentTimeMillis()))
    val identityContext = MemoryContext(identityPrefs)
    actualCacheCases++
    if (verify("cache.same_currency_bypasses_preferences") {
      Converter.freshExchangeRateSnapshot(identityContext, "USD", "USD") ==
          CurrencyRate(1.0, null, false) &&
          identityContext.preferenceRequests == 0 && identityPrefs.reads == 0 &&
          identityPrefs.attemptedWrites == 0
    }) actualCachePassed++
    attemptedWrites += identityPrefs.attemptedWrites

    val stalePrefs = MemoryPreferences(mapOf(
        "rates_USD" to """{"base":"USD","amount":1,"rates":{"EUR":0.92}}""",
        "rates_USD_ts" to System.currentTimeMillis() - 7L * 60 * 60 * 1000))
    val staleContext = MemoryContext(stalePrefs)
    actualCacheCases++
    if (verify("cache.stale_is_not_fresh") {
      Converter.freshExchangeRateSnapshot(staleContext, "USD", "EUR") == null &&
          staleContext.preferenceRequests == 1 && stalePrefs.longReads == 1 &&
          stalePrefs.stringReads == 0 && stalePrefs.attemptedWrites == 0
    }) actualCachePassed++
    attemptedWrites += stalePrefs.attemptedWrites

    verify("fixture_count_exact") { fixtures.size == EXPECTED_FIXTURE_COUNT }
    verify("no_persistence_attempts") { attemptedWrites == 0 }
    verify("runtime_under_ten_seconds") { SystemClock.elapsedRealtime() - started < 10_000 }
    // This assertion includes itself in the public count.
    verify("public_counts_exact") {
      checks.length() + 1 == EXPECTED_CHECK_COUNT && actualCacheCases == EXPECTED_CACHE_CASES
    }
    val completed = checks.length() == EXPECTED_CHECK_COUNT &&
        actualCacheCases == EXPECTED_CACHE_CASES
    val passed = completed && failures.length() == 0
    val report = JSONObject()
        .put("completed", completed).put("passed", passed)
        .put("failureCount", failures.length()).put("checkCount", checks.length())
        .put("passedCount", checks.length() - failures.length())
        .put("fixturesPublic", true).put("networkUsed", false).put("stateMutations", false)
        .put("actualCacheCases", actualCacheCases).put("actualCachePassed", actualCachePassed)
        .put("fixtureCount", fixtures.size).put("attemptedWrites", attemptedWrites)
        .put("elapsedMillis", SystemClock.elapsedRealtime() - started)
        .put("checks", checks).put("failures", failures)
    return Bundle().apply {
      putBoolean("currencyIntegrityCompleted", completed)
      putBoolean("currencyIntegrityPassed", passed)
      putString("currencyIntegrityJson", report.toString())
      putInt("currencyIntegrityCheckCount", checks.length())
      putInt("currencyIntegrityPassedCount", checks.length() - failures.length())
      putInt("currencyIntegrityFailureCount", failures.length())
      putInt("currencyIntegrityActualCacheCases", actualCacheCases)
      putInt("currencyIntegrityActualCachePassed", actualCachePassed)
    }
  }

  private fun fixtures(): List<Fixture> {
    val common = linkedMapOf("EUR" to 0.92, "JPY" to 149.75, "GBP" to 0.77)
    val result = mutableListOf<Fixture>()
    fun valid(name: String, json: String, rates: Map<String, Double> = common,
        date: String? = DATE) {
      result += Fixture(name, json, rates, date)
    }
    fun rejected(name: String, json: String) { result += Fixture(name, json) }
    val quotes = """{"EUR":0.92,"JPY":149.75,"GBP":0.77}"""
    valid("all_fields_and_quotes", """{"base":"USD","amount":1,"date":"$DATE","rates":$quotes}""")
    valid("legacy_missing_base_and_amount", """{"date":"$DATE","rates":$quotes}""")
    valid("legacy_missing_base", """{"amount":1,"date":"$DATE","rates":$quotes}""")
    valid("legacy_missing_amount", """{"base":"USD","date":"$DATE","rates":$quotes}""")
    valid("floating_unit_amount", """{"base":"USD","amount":1.0,"date":"$DATE","rates":$quotes}""")
    valid("quoted_unit_amount", """{"base":"USD","amount":"1","date":"$DATE","rates":$quotes}""")
    valid("quoted_exponent_unit_amount", """{"base":"USD","amount":"1e0","date":"$DATE","rates":$quotes}""")
    valid("lowercase_base", """{"base":"usd","amount":1,"date":"$DATE","rates":$quotes}""")
    valid("mixed_case_base", """{"base":"uSd","amount":1,"date":"$DATE","rates":$quotes}""")
    valid("small_large_and_quoted_rates",
        """{"base":"USD","amount":1,"date":"$DATE","rates":{"EUR":1e-200,"JPY":1e200,"GBP":"0.77","CHF":"1e-100"}}""",
        linkedMapOf("EUR" to 1e-200, "JPY" to 1e200, "GBP" to 0.77, "CHF" to 1e-100))
    valid("finite_double_edges",
        """{"rates":{"EUR":"4.9e-324","JPY":"1.7976931348623157e308"}}""",
        linkedMapOf("EUR" to Double.MIN_VALUE, "JPY" to Double.MAX_VALUE), null)
    valid("valid_leap_date", """{"date":"2024-02-29","rates":$quotes}""", date = "2024-02-29")
    valid("missing_date", """{"rates":$quotes}""", date = null)
    for ((name, date) in listOf(
        "null_date" to "null", "numeric_date" to "20261016",
        "boolean_date" to "true", "bad_date_shape" to "\"2026-1-2\"",
        "impossible_date" to "\"2026-02-30\"", "non_leap_date" to "\"2025-02-29\"")) {
      valid(name, """{"date":$date,"rates":$quotes}""", date = null)
    }
    // EUR stays usable: every other invalid quote must reject the entire payload.
    for ((name, quote) in listOf(
        "zero_quote" to "0", "negative_quote" to "-0.2", "negative_zero_quote" to "-0.0",
        "nan_quote" to "\"NaN\"", "infinite_quote" to "\"Infinity\"",
        "negative_infinite_quote" to "\"-Infinity\"", "overflow_quote" to "1e309",
        "quoted_overflow_quote" to "\"1e309\"", "null_quote" to "null",
        "boolean_quote" to "true", "text_quote" to "\"invalid\"")) {
      rejected(name, """{"base":"USD","amount":1,"rates":{"EUR":0.92,"JPY":$quote}}""")
    }
    for ((name, base) in listOf(
        "wrong_base" to "\"GBP\"", "numeric_base" to "1",
        "null_base" to "null", "boolean_base" to "true",
        "leading_space_base" to "\" USD\"", "trailing_space_base" to "\"USD \"")) {
      rejected(name, """{"base":$base,"amount":1,"rates":$quotes}""")
    }
    for ((name, amount) in listOf(
        "hundred_unit_amount" to "100", "zero_amount" to "0", "negative_amount" to "-1",
        "fractional_amount" to "0.5",
        "null_amount" to "null", "boolean_amount" to "true",
        "nan_amount" to "\"NaN\"", "overflow_amount" to "1e309")) {
      rejected(name, """{"base":"USD","amount":$amount,"rates":$quotes}""")
    }
    rejected("empty_rates", """{"rates":{}}""")
    rejected("missing_rates", """{"base":"USD","amount":1}""")
    rejected("null_rates", """{"rates":null}""")
    rejected("array_rates", """{"rates":[]}""")
    rejected("malformed_json", "{")
    rejected("object_quote", """{"rates":{"EUR":0.92,"JPY":{}}}""")
    rejected("array_quote", """{"rates":{"EUR":0.92,"JPY":[]}}""")
    rejected("underflow_quote", """{"rates":{"EUR":0.92,"JPY":1e-999}}""")
    return result
  }

  /** Never delegates to real Android preferences or a backing device Context. */
  private class MemoryContext(private val prefs: MemoryPreferences) : ContextWrapper(null) {
    var preferenceRequests = 0
      private set
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
      preferenceRequests++
      check(name == PREFS && mode == Context.MODE_PRIVATE)
      return prefs
    }
  }

  /** Read semantics are Android's; values and copies live only in this invocation's heap. */
  private class MemoryPreferences(initial: Map<String, Any?>) : SharedPreferences {
    private val values = initial.toMap()
    var reads = 0
      private set
    var stringReads = 0
      private set
    var longReads = 0
      private set
    var attemptedWrites = 0
      private set
    private fun value(key: String?, fallback: Any?): Any? {
      reads++
      return if (values.containsKey(key)) values[key] else fallback
    }
    override fun getAll(): MutableMap<String, *> {
      reads++
      return values.toMutableMap()
    }
    override fun getString(key: String?, defValue: String?): String? {
      stringReads++
      return value(key, defValue) as String?
    }
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (value(key, defValues) as Set<String>?)?.toMutableSet()
    override fun getInt(key: String?, defValue: Int): Int = value(key, defValue) as Int
    override fun getLong(key: String?, defValue: Long): Long {
      longReads++
      return value(key, defValue) as Long
    }
    override fun getFloat(key: String?, defValue: Float): Float = value(key, defValue) as Float
    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        value(key, defValue) as Boolean
    override fun contains(key: String?): Boolean {
      reads++
      return values.containsKey(key)
    }
    override fun edit(): SharedPreferences.Editor {
      attemptedWrites++
      throw UnsupportedOperationException("Public currency fixtures prohibit persistence")
    }
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
  }
}
