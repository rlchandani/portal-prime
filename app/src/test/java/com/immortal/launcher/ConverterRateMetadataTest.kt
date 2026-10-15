/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.Mockito

class ConverterRateMetadataTest {
  @Test fun datedCachedRateRetainsValueDateAndSavedSource() {
    val (context, prefs) = cachedContext("""{"date":"2026-10-14","rates":{"USD":1.25}}""")
    val expected = CurrencyRate(1.25, "2026-10-14", saved = true)
    assertEquals(expected, Converter.freshExchangeRateSnapshot(context, "EUR", "USD"))
    assertEquals(expected, Converter.exchangeRateSnapshot(context, "EUR", "USD"))
    assertEquals(12.5, Converter.convertCurrency(context, "EUR", "USD", 10.0)!!, 0.0)
    Mockito.verify(prefs, Mockito.never()).edit()
  }

  @Test fun missingOrMalformedDatesKeepUsableRates() {
    val dates = listOf(
        null, "null", "123", "\"\"", "\"2026-02-29\"", "\"2026-04-31\"",
        "\"2026-13-01\"", "\"2026-00-01\"", "\"2026-1-04\"",
        "\"26-10-14\"", "\"2026-10-14T00:00:00Z\"", "\" 2026-10-14\"",
        "\"2026-10-14 \"", "\"+2026-10-14\"")
    for (date in dates) {
      val field = date?.let { "\"date\":$it," } ?: ""
      val (context, prefs) = cachedContext("{$field\"rates\":{\"USD\":1.25}}")
      val expected = CurrencyRate(1.25, referenceDate = null, saved = true)
      assertEquals("Fresh cache date: $date", expected,
          Converter.freshExchangeRateSnapshot(context, "EUR", "USD"))
      assertEquals("Exchange date: $date", expected,
          Converter.exchangeRateSnapshot(context, "EUR", "USD"))
      Mockito.verify(prefs, Mockito.never()).edit()
    }
  }

  @Test fun validLeapDayAndTwoDestinationsShareTheReferenceDate() {
    val (context, prefs) = cachedContext(
        """{"date":"2024-02-29","rates":{"USD":1.25,"GBP":0.85}}""")
    assertEquals(CurrencyRate(1.25, "2024-02-29", saved = true),
        Converter.freshExchangeRateSnapshot(context, "EUR", "USD"))
    assertEquals(CurrencyRate(0.85, "2024-02-29", saved = true),
        Converter.freshExchangeRateSnapshot(context, "EUR", "GBP"))
    assertNull(Converter.freshExchangeRateSnapshot(context, "EUR", "JPY"))
    Mockito.verify(prefs, Mockito.never()).edit()
  }

  @Test fun rateAndDateComeFromOneCacheRead() {
    val (context, prefs) = cachedContext("""{"date":"2026-10-13","rates":{"USD":1.2}}""")
    Mockito.`when`(prefs.getString("rates_EUR", null)).thenReturn(
        """{"date":"2026-10-13","rates":{"USD":1.2}}""",
        """{"date":"2026-10-14","rates":{"USD":1.25}}""")
    assertEquals(CurrencyRate(1.2, "2026-10-13", saved = true),
        Converter.exchangeRateSnapshot(context, "EUR", "USD"))
    Mockito.verify(prefs, Mockito.times(1)).getString("rates_EUR", null)
    Mockito.verify(prefs, Mockito.never()).edit()
  }

  @Test fun identitySnapshotNeedsNoCacheWork() {
    val context = Mockito.mock(Context::class.java)
    Mockito.`when`(context.getSharedPreferences(Mockito.anyString(), Mockito.anyInt()))
        .thenThrow(AssertionError("Identity conversion must not access a cache"))
    val expected = CurrencyRate(1.0, referenceDate = null, saved = false)
    assertEquals(expected, Converter.freshExchangeRateSnapshot(context, "JPY", "JPY"))
    assertEquals(expected, Converter.exchangeRateSnapshot(context, "JPY", "JPY"))
    Mockito.verify(context, Mockito.never()).getSharedPreferences(Mockito.anyString(), Mockito.anyInt())
  }

  @Test fun expiredCacheCannotBypassDebounceOrWritePreferences() = runBlocking {
    val (context, prefs) = cachedContext("""{"date":"2026-10-14","rates":{"USD":1.25}}""")
    Mockito.`when`(prefs.getLong("rates_EUR_ts", 0L)).thenReturn(1L)
    val order = mutableListOf<String>()
    val fetched = CurrencyRate(1.3, "2026-10-15", saved = false)
    val result = loadCurrencyRate(
        cached = { order += "cache"; Converter.freshExchangeRateSnapshot(context, "EUR", "USD") },
        fetch = { order += "fetch"; fetched },
        debounce = { order += "wait" })
    assertEquals(listOf("cache", "wait", "fetch"), order)
    assertSame(fetched, result)
    Mockito.verify(prefs, Mockito.never()).getString("rates_EUR", null)
    Mockito.verify(prefs, Mockito.never()).edit()
    Unit
  }

  @Test fun freshSnapshotBypassesDebounceAndFetchWithoutWritingPreferences() = runBlocking {
    val (context, prefs) = cachedContext("""{"date":"2026-10-14","rates":{"USD":1.25}}""")
    val result = loadCurrencyRate(
        cached = { Converter.freshExchangeRateSnapshot(context, "EUR", "USD") },
        fetch = { error("Fresh snapshot must not fetch") },
        debounce = { error("Fresh snapshot must not wait") })
    assertEquals(CurrencyRate(1.25, "2026-10-14", saved = true), result)
    Mockito.verify(prefs).getLong("rates_EUR_ts", 0L)
    Mockito.verify(prefs).getString("rates_EUR", null)
    Mockito.verify(prefs, Mockito.never()).edit()
    Unit
  }

  private fun cachedContext(json: String): Pair<Context, SharedPreferences> {
    val context = Mockito.mock(Context::class.java)
    val prefs = Mockito.mock(SharedPreferences::class.java)
    Mockito.`when`(context.getSharedPreferences("immortal_converter", Context.MODE_PRIVATE)).thenReturn(prefs)
    Mockito.`when`(prefs.getLong("rates_EUR_ts", 0L)).thenReturn(System.currentTimeMillis())
    Mockito.`when`(prefs.getString("rates_EUR", null)).thenReturn(json)
    return context to prefs
  }
}
