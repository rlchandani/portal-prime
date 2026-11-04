/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real stock parser, literal snapshot bodies and independently fixed price expectations. */
class AlpacaStockFallbackTest {
  @Test fun missingLatestPriceUsesDailyClose() {
    assertDailyClose(
        """{"AAPL":{"latestTrade":{},"dailyBar":{"c":191},"prevDailyBar":{"c":188}}}""")
  }

  @Test fun nullLatestPriceUsesDailyClose() {
    assertDailyClose(
        """{"AAPL":{"latestTrade":{"p":null},"dailyBar":{"c":191},"prevDailyBar":{"c":188}}}""")
  }

  @Test fun nonnumericLatestPriceUsesDailyClose() {
    assertDailyClose(
        """{"AAPL":{"latestTrade":{"p":"unavailable"},"dailyBar":{"c":191},"prevDailyBar":{"c":188}}}""")
  }

  @Test fun nanLatestPriceUsesDailyClose() {
    assertDailyClose(
        """{"AAPL":{"latestTrade":{"p":"NaN"},"dailyBar":{"c":191},"prevDailyBar":{"c":188}}}""")
  }

  @Test fun validLatestPriceWinsOverDailyClose() {
    val quotes = AlpacaClient.parseStockQuotes(
        """{"AAPL":{"latestTrade":{"p":190.5},"dailyBar":{"c":191},"prevDailyBar":{"c":188}}}""",
        listOf("AAPL"))
    assertEquals(1, quotes.size)
    val quote = quotes.single()
    assertEquals("AAPL", quote.symbol)
    assertEquals(190.5, quote.last, 0.000001)
    assertEquals(2.5, quote.change, 0.000001)
    assertEquals(1.3297872340425532, quote.changePct, 0.000001)
  }

  @Test fun zeroLatestPriceRemainsUsableAndWinsOverDailyClose() {
    val quotes = AlpacaClient.parseStockQuotes(
        """{"AAPL":{"latestTrade":{"p":0},"dailyBar":{"c":191},"prevDailyBar":{"c":188}}}""",
        listOf("AAPL"))
    assertEquals(1, quotes.size)
    val quote = quotes.single()
    assertEquals("AAPL", quote.symbol)
    assertEquals(0.0, quote.last, 0.000001)
    assertEquals(-188.0, quote.change, 0.000001)
    assertEquals(-100.0, quote.changePct, 0.000001)
  }

  @Test fun absentLatestTradeRetainsDailyCloseFallback() {
    assertDailyClose(
        """{"AAPL":{"dailyBar":{"c":191},"prevDailyBar":{"c":188}}}""")
  }

  @Test fun unusableLatestAndDailyPricesOmitQuote() {
    val quotes = AlpacaClient.parseStockQuotes(
        """{"AAPL":{"latestTrade":{"p":"NaN"},"dailyBar":{"c":"NaN"},"prevDailyBar":{"c":188}}}""",
        listOf("AAPL"))
    assertTrue(quotes.isEmpty())
  }

  private fun assertDailyClose(body: String) {
    val quotes = AlpacaClient.parseStockQuotes(body, listOf("AAPL"))
    assertEquals(1, quotes.size)
    val quote = quotes.single()
    assertEquals("AAPL", quote.symbol)
    assertEquals(191.0, quote.last, 0.000001)
    assertEquals(3.0, quote.change, 0.000001)
    assertEquals(1.5957446808510638, quote.changePct, 0.000001)
  }
}
