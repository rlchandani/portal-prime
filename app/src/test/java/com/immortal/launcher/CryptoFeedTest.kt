/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CoinGecko `coins/markets` parsing — price, 24h change, 7d sparkline (no device/network). */
class CryptoFeedTest {

  private val coins =
      listOf(
          CryptoConfig.Coin("bitcoin", "BTC"),
          CryptoConfig.Coin("ethereum", "ETH"),
          CryptoConfig.Coin("solana", "SOL"),
      )

  private val sample =
      """
      [
        {"id": "ethereum", "symbol": "eth", "current_price": 2301.0,
         "price_change_percentage_24h": -1.100,
         "sparkline_in_7d": {"price": [2290.0, 2295.5, 2310.2, 2301.0]}},
        {"id": "bitcoin", "symbol": "btc", "current_price": 43210.5,
         "price_change_percentage_24h": 2.345,
         "sparkline_in_7d": {"price": [42800.0, 42910.5, 43100.0, 43210.5]}},
        {"id": "solana", "symbol": "sol", "current_price": 150.25}
      ]
      """.trimIndent()

  @Test
  fun `parses quotes in the coin list order, not API order`() {
    val quotes = CryptoFeed.parse(sample, coins)
    assertEquals(listOf("BTC", "ETH", "SOL"), quotes.map { it.symbol })
    assertEquals(43210.5, quotes[0].usd, 0.001)
    assertEquals(2.345, quotes[0].change24h!!, 0.001)
    assertEquals(-1.100, quotes[1].change24h!!, 0.001)
  }

  @Test
  fun `missing 24h change is null, not zero`() {
    val sol = CryptoFeed.parse(sample, coins).first { it.symbol == "SOL" }
    assertEquals(150.25, sol.usd, 0.001)
    assertNull(sol.change24h)
  }

  @Test
  fun `sparkline points survive parsing, missing sparkline is empty`() {
    val quotes = CryptoFeed.parse(sample, coins)
    val btc = quotes.first { it.symbol == "BTC" }
    assertTrue(btc.spark.isNotEmpty())
    assertEquals(42800.0, btc.spark.first(), 0.001)
    assertEquals(43210.5, btc.spark.last(), 0.001)
    assertTrue(quotes.first { it.symbol == "SOL" }.spark.isEmpty())
  }

  @Test
  fun `coins absent from the response are dropped`() {
    val withUnknown = coins + CryptoConfig.Coin("not-a-coin", "XXX")
    val quotes = CryptoFeed.parse(sample, withUnknown)
    assertEquals(3, quotes.size)
    assertTrue(quotes.none { it.symbol == "XXX" })
  }

  @Test
  fun `configured symbol label wins over the API's`() {
    val custom = listOf(CryptoConfig.Coin("bitcoin", "XBT"))
    assertEquals("XBT", CryptoFeed.parse(sample, custom).single().symbol)
  }

  @Test
  fun `malformed json yields an empty list, never throws`() {
    assertTrue(CryptoFeed.parse("not json", coins).isEmpty())
    assertTrue(CryptoFeed.parse("", coins).isEmpty())
    assertTrue(CryptoFeed.parse("{}", coins).isEmpty())
  }
}
