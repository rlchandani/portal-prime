/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import com.immortal.launcher.memory.MemoryFact
import com.immortal.launcher.memory.MemoryFixtures
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `hearthpane.stocks`: the Stocks widget's quotes (Alpaca, else Yahoo's keyless chart) for named or followed stocks. */
class HearthpaneStocksTest {
  private fun q(symbol: String, last: Double, change: Double, pct: Double, name: String? = null) =
      StockQuotes.Quote(symbol, name, last, change, pct, "USD")

  private var prices = mapOf(
      "AAPL" to q("AAPL", 342.56, -6.65, -1.904, "Apple Inc."),
      "NVDA" to q("NVDA", 201.1, 3.2, 1.62),
      "MSFT" to q("MSFT", 500.0, 0.0, 0.0),
  )
  private val asked = mutableListOf<List<String>>()
  private var followed: List<MemoryFact>? = null
  private var widget = listOf<String>()

  private val tool = HearthpaneStocks(
      dictionary = { MemoryFixtures.dictionary },
      followed = { followed },
      widget = { widget },
      quotes = { symbols -> asked += symbols; symbols.mapNotNull { prices[it] } },
  )

  private fun call(vararg kv: Pair<String, Any>) =
      tool.invoke(JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }.toString())

  private fun ticker(symbol: String, label: String) = MemoryFact(
      id = "ticker:$symbol", kind = MemoryFact.Kind.TICKER, label = label, state = MemoryFact.State.ACTIVE,
      firstSeenMs = 0, lastSeenMs = 0)

  @Test fun companyNamesResolveThroughTheDictionary() {
    val r = call("symbols" to org.json.JSONArray(listOf("Apple", "nvda")))
    assertTrue(r.getBoolean("ok"))
    assertEquals(listOf("AAPL", "NVDA"), asked.single())
    assertEquals(
        "Apple (AAPL) is at $342.56, down 6.65, 1.90 percent, today. NVIDIA (NVDA) is at $201.10, up 3.20, 1.62 percent, today.",
        r.getString("message"))
    val first = r.getJSONArray("quotes").getJSONObject(0)
    assertEquals(-6.65, first.getDouble("change"), 0.001)
    assertEquals("asked", r.getString("from"))
  }

  @Test fun myStocksPreferMemoryThenTheWidget() {
    followed = listOf(ticker("MSFT", "Microsoft"))
    widget = listOf("NVDA")
    val r = call()
    assertEquals("followed", r.getString("from"))
    assertEquals("Microsoft (MSFT) is at $500.00, unchanged today.", r.getString("message"))
    followed = null
    assertEquals("widget", call().getString("from"))
    assertEquals(listOf("NVDA"), asked.last())
  }

  @Test fun noStockAnywhereAsksWhich() {
    followed = emptyList()
    val r = call()
    assertTrue(r.getBoolean("needs_symbol"))
    assertTrue(asked.isEmpty())
  }

  @Test fun unknownCompanyIsNotGuessedAsATicker() {
    val r = call("company" to "Acme Rocket Works")
    assertFalse(r.getBoolean("ok"))
    assertTrue(r.getString("message").contains("don't know the ticker"))
    assertTrue(asked.isEmpty())
  }

  @Test fun missingQuotesAreNamedAndNoneAtAllSaysUnreachable() {
    val r = call("symbols" to org.json.JSONArray(listOf("AAPL", "ZZZZ")))
    assertTrue(r.getString("message").endsWith("I couldn't get ZZZZ."))
    prices = emptyMap()
    assertEquals("I couldn't reach the stock prices just now.", call("symbol" to "AAPL").getString("message"))
  }

  @Test fun symbolsAreCappedAndDeduplicated() {
    call("symbols" to org.json.JSONArray(listOf("AAPL", "aapl", "MSFT", "NVDA", "AMD", "TSLA", "META", "GOOG")))
    assertEquals(listOf("AAPL", "MSFT", "NVDA", "AMD"), asked.single())
  }

  @Test fun yahooChartFixtureParses() {
    val quote = StockQuotes.parseYahoo(File("src/test/resources/stocks/yahoo_chart_aapl.json").readText())!!
    assertEquals("AAPL", quote.symbol)
    assertEquals(342.56, quote.last, 0.001)
    assertEquals(-6.65, quote.change, 0.001)
    assertEquals(-1.904, quote.changePct, 0.01)
    assertEquals("Apple Inc.", quote.name)
    assertNull(StockQuotes.parseYahoo("""{"chart":{"result":null,"error":{"code":"Not Found"}}}"""))
    assertNull(StockQuotes.parseYahoo("<html>"))
    assertEquals("BRK.B", StockQuotes.symbol("brk.b"))
    assertNull(StockQuotes.symbol("rivian motors"))
  }

  @Test fun badArgumentsAreErrors() {
    assertTrue(tool.invoke("[").has("error"))
  }
}
