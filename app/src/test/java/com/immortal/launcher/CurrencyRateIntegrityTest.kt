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

class CurrencyRateIntegrityTest {
  private fun decode(json: String, saved: Boolean = true) = CurrencyRateCodec.decode(json, "USD", saved)
  @Test fun providedUnitResponseKeepsQuotesDateAndSource() {
    assertEquals(mapOf("EUR" to CurrencyRate(0.9, "2026-10-16", false),
        "GBP" to CurrencyRate(0.8, "2026-10-16", false)),
        decode("""{"amount":1,"base":"USD","date":"2026-10-16","rates":{"EUR":0.9,"GBP":0.8}}""", false))
  }
  @Test fun legacyMissingBaseAndAmountRemainUsable() {
    assertEquals(CurrencyRate(0.9, null, true), decode("""{"rates":{"EUR":0.9}}""")?.get("EUR"))
  }
  @Test fun explicitWrongBaseCannotBeUsedForRequestedCurrency() {
    assertNull(decode("""{"base":"EUR","rates":{"EUR":0.9}}"""))
  }
  @Test fun explicitlyMalformedBaseIsNotLegacyMissingMetadata() {
    for (base in listOf("null", "true", "123", "{}", "[]", "\"\""))
      assertNull("base=$base", decode("{\"base\":$base,\"rates\":{\"EUR\":0.9}}"))
  }
  @Test fun scaledResponsesAreNotUnitRates() {
    for (amount in listOf("0", "-0.0", "2", "-1", "0.5", "null", "true", "{}", "\"2\""))
      assertNull("amount=$amount", decode("{\"amount\":$amount,\"rates\":{\"EUR\":0.9}}"))
  }
  @Test fun nonfiniteUnitMetadataIsRejected() {
    for (amount in listOf("\"NaN\"", "\"Infinity\"", "\"-Infinity\"", "\"1e400\""))
      assertNull("amount=$amount", decode("{\"amount\":$amount,\"rates\":{\"EUR\":0.9}}"))
  }
  @Test fun zeroAndNegativeZeroQuotesAreNotExchangeRates() {
    for (rate in listOf("0", "-0.0", "0.0", "\"0\""))
      assertNull("rate=$rate", decode("{\"rates\":{\"EUR\":$rate}}"))
  }
  @Test fun negativeQuotesAreNotExchangeRates() {
    for (rate in listOf("-0.9", "-1e-300", "\"-0.9\""))
      assertNull("rate=$rate", decode("{\"rates\":{\"EUR\":$rate}}"))
  }
  @Test fun nonfiniteQuotesCannotReachCalculation() {
    for (rate in listOf("\"NaN\"", "\"Infinity\"", "\"-Infinity\"", "\"1e400\""))
      assertNull("rate=$rate", decode("{\"rates\":{\"EUR\":$rate}}"))
  }
  @Test fun malformedQuotesStillRejectTheResponse() {
    for (rate in listOf("null", "true", "{}", "[]", "\"unknown\""))
      assertNull("rate=$rate", decode("{\"rates\":{\"EUR\":$rate}}"))
    assertNull(decode("""{"rates":{}}"""))
  }
  @Test fun invalidQuoteDoesNotBecomeAPartiallyTrustedSnapshot() {
    assertNull(decode("""{"rates":{"EUR":0.9,"GBP":0}}"""))
  }
  @Test fun validNumericStringsAndPositiveRangeAreRetained() {
    assertEquals(0.9, decode("""{"amount":"1","base":"USD","rates":{"EUR":"0.9"}}""")?.get("EUR")?.value)
    assertEquals(1e-300, decode("""{"rates":{"EUR":1e-300}}""")?.get("EUR")?.value)
    assertEquals(1e300, decode("""{"rates":{"EUR":1e300}}""")?.get("EUR")?.value)
  }
  @Test fun malformedDateDropsOnlyOptionalMetadata() {
    assertEquals(CurrencyRate(0.9, null, false), decode("""{"date":"2026-02-29","rates":{"EUR":0.9}}""", false)?.get("EUR"))
  }
  @Test fun freshProductionCacheRejectsBadQuoteWithoutWrites() {
    val (context, prefs) = cached("""{"base":"USD","amount":1,"rates":{"EUR":0}}""")
    assertNull(Converter.freshExchangeRateSnapshot(context, "USD", "EUR"))
    Mockito.verify(prefs, Mockito.never()).edit()
  }
  @Test fun invalidCacheCannotBypassFetchRecovery() = runBlocking {
    val (context, prefs) = cached("""{"base":"EUR","rates":{"EUR":0.9}}""")
    val order = mutableListOf<String>()
    val recovered = CurrencyRate(0.91, "2026-10-16", false)
    val result = loadCurrencyRate(
        cached = { order += "cache"; Converter.freshExchangeRateSnapshot(context, "USD", "EUR") },
        fetch = { order += "fetch"; recovered }, debounce = { order += "wait" })
    assertSame(recovered, result); assertEquals(listOf("cache", "wait", "fetch"), order)
    Mockito.verify(prefs, Mockito.never()).edit(); Unit
  }
  private fun cached(json: String): Pair<Context, SharedPreferences> {
    val context = Mockito.mock(Context::class.java); val prefs = Mockito.mock(SharedPreferences::class.java)
    Mockito.`when`(context.getSharedPreferences("immortal_converter", Context.MODE_PRIVATE)).thenReturn(prefs)
    Mockito.`when`(prefs.getLong("rates_USD_ts", 0L)).thenReturn(System.currentTimeMillis())
    Mockito.`when`(prefs.getString("rates_USD", null)).thenReturn(json)
    return context to prefs
  }
}
