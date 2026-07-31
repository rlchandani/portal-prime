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

/** Pure parsing of Alpaca stock-snapshot and options-snapshot responses (no network). */
class AlpacaClientTest {

  private val stockBody =
      """
      {
        "AAPL": {
          "latestTrade": {"p": 190.50},
          "dailyBar": {"c": 191.00},
          "prevDailyBar": {"c": 188.00}
        },
        "MSFT": {
          "latestTrade": {"p": 410.00},
          "prevDailyBar": {"c": 400.00}
        }
      }
      """.trimIndent()

  @Test
  fun `stock quotes compute change from prev close, in order`() {
    val quotes = AlpacaClient.parseStockQuotes(stockBody, listOf("AAPL", "MSFT"))
    assertEquals(listOf("AAPL", "MSFT"), quotes.map { it.symbol })
    assertEquals(190.50, quotes[0].last, 0.001)
    assertEquals(190.50 - 188.00, quotes[0].change, 0.001)
    assertEquals((190.50 - 188.00) / 188.00 * 100, quotes[0].changePct, 0.001)
    // MSFT has no latestTrade -> falls back nowhere for last, but has dailyBar? No -> uses latestTrade.
    assertEquals(410.00, quotes[1].last, 0.001)
    assertEquals(2.5, quotes[1].changePct, 0.01)
  }

  @Test
  fun `symbols absent from response are dropped`() {
    val quotes = AlpacaClient.parseStockQuotes(stockBody, listOf("AAPL", "TSLA"))
    assertEquals(1, quotes.size)
    assertEquals("AAPL", quotes[0].symbol)
  }

  @Test
  fun `malformed stock json is empty, never throws`() {
    assertTrue(AlpacaClient.parseStockQuotes("nope", listOf("AAPL")).isEmpty())
  }

  private val optionsBody =
      """
      {
        "snapshots": {
          "AAPL241220C00300000": {
            "latestTrade": {"p": 5.10},
            "latestQuote": {"bp": 5.00, "ap": 5.20},
            "impliedVolatility": 0.337,
            "greeks": {"delta": 0.25, "gamma": 0.01, "theta": -0.05, "vega": 0.1, "rho": 0.02}
          },
          "AAPL241220C00320000": {
            "latestQuote": {"bp": 2.00, "ap": 2.20},
            "greeks": {"delta": 0.12}
          },
          "AAPL241220C00280000": {
            "latestQuote": {"bp": 9.00, "ap": 9.40}
          }
        },
        "next_page_token": "abc123"
      }
      """.trimIndent()

  @Test
  fun `option chain parses greeks, iv, quotes and next token`() {
    val (contracts, next) = AlpacaClient.parseOptionChain(optionsBody)
    assertEquals("abc123", next)
    assertEquals(3, contracts.size)
    val c = contracts.first { it.symbol == "AAPL241220C00300000" }
    assertEquals(0.25, c.delta!!, 0.001)
    assertEquals(0.337, c.impliedVolatility!!, 0.001)
    assertEquals(5.00, c.bid!!, 0.001)
    assertEquals(5.20, c.ask!!, 0.001)
    assertEquals(5.10, c.last!!, 0.001)
  }

  @Test
  fun `contract without greeks keeps null delta, not dropped here`() {
    val (contracts, _) = AlpacaClient.parseOptionChain(optionsBody)
    val noGreeks = contracts.first { it.symbol == "AAPL241220C00280000" }
    assertNull(noGreeks.delta)
    assertNull(noGreeks.impliedVolatility)
  }

  @Test
  fun `malformed options json yields empty + null token`() {
    val (contracts, next) = AlpacaClient.parseOptionChain("garbage")
    assertTrue(contracts.isEmpty())
    assertNull(next)
  }
}
