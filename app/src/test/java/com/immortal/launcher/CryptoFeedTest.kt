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

/** CoinGecko `simple/price` parsing (no device/network needed). */
class CryptoFeedTest {

  private val coins =
      listOf(
          CryptoConfig.Coin("bitcoin", "BTC"),
          CryptoConfig.Coin("ethereum", "ETH"),
          CryptoConfig.Coin("solana", "SOL"),
      )

  private val sample =
      """
      {
        "bitcoin":  {"usd": 43210.5, "usd_24h_change":  2.345},
        "ethereum": {"usd":  2301.0, "usd_24h_change": -1.100},
        "solana":   {"usd":   150.25}
      }
      """.trimIndent()

  @Test
  fun `parses quotes in the coin list order`() {
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
  fun `coins absent from the response are dropped`() {
    val withUnknown = coins + CryptoConfig.Coin("not-a-coin", "XXX")
    val quotes = CryptoFeed.parse(sample, withUnknown)
    assertEquals(3, quotes.size)
    assertTrue(quotes.none { it.symbol == "XXX" })
  }

  @Test
  fun `an entry without a usd price is dropped`() {
    val body = """{"bitcoin": {"usd_24h_change": 1.0}}"""
    assertTrue(CryptoFeed.parse(body, coins).isEmpty())
  }

  @Test
  fun `malformed json yields an empty list, never throws`() {
    assertTrue(CryptoFeed.parse("not json", coins).isEmpty())
    assertTrue(CryptoFeed.parse("", coins).isEmpty())
  }
}
