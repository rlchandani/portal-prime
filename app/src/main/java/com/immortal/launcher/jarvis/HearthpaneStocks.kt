/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import com.immortal.launcher.memory.MemoryDictionary
import com.immortal.launcher.memory.MemoryFact
import java.util.Locale
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * `hearthpane.stocks` for Jarvis: "how's Apple doing", "what's NVDA at", "how are my stocks" → the price and today's
 * change, from the Stocks widget's source ([StockQuotes]: Alpaca with the household's key, else Yahoo's keyless
 * chart), so it works on every Jarvis model without a Search key. Symbols come from the user's words (company names
 * through the memory dictionary, else tickers as said); with none named, the tickers Jarvis has learned the person
 * follows, else the Stocks widget's list. At most [MAX_SYMBOLS]. Arguments are untrusted (model output). Pure apart
 * from its seams, so every rule is JVM-tested.
 */
internal class HearthpaneStocks(
    private val dictionary: () -> MemoryDictionary?,
    /** The tickers memory knows this person (or the household) follows, most sure first; null when memory is off. */
    private val followed: () -> List<MemoryFact>?,
    /** The Stocks widget's tickers (Settings › Stocks). */
    private val widget: () -> List<String>,
    /** Quotes for the symbols, within the tool's budget; symbols without one are left out. */
    private val quotes: (List<String>) -> List<StockQuotes.Quote>,
) {
  private data class Wanted(val symbol: String, val name: String?)

  fun invoke(argsJson: String?): JSONObject {
    val args = try { JSONObject(argsJson ?: "{}") } catch (_: JSONException) { return error("arguments must be a JSON object") }
    return runCatching { answer(args) }.getOrElse { error("stocks unavailable") }
  }

  private fun answer(args: JSONObject): JSONObject {
    val said = said(args)
    val wanted: List<Wanted>
    var origin = "asked"
    if (said.isNotEmpty()) {
      val resolved = said.map { it to resolve(it) }
      resolved.firstOrNull { it.second == null }?.let { (words, _) ->
        return reply("I don't know the ticker for ${words.take(MAX_SAID)}. Ask with its ticker symbol, like AAPL.")
      }
      wanted = resolved.mapNotNull { it.second }
    } else {
      val learned = followed().orEmpty().mapNotNull { f ->
        StockQuotes.symbol(f.id.substringAfter(':'))?.let { Wanted(it, f.label.ifBlank { null }) }
      }
      wanted = learned.ifEmpty { widget().mapNotNull { StockQuotes.symbol(it)?.let { s -> Wanted(s, nameOf(s)) } } }
      origin = if (learned.isNotEmpty()) "followed" else "widget"
      if (wanted.isEmpty()) {
        return JSONObject().put("ok", false).put("needs_symbol", true)
            .put("message", "Which stock? Say the company or its ticker. The household can also add tickers in Settings, under Stocks.")
      }
    }
    val picked = wanted.distinctBy { it.symbol }.take(MAX_SYMBOLS)
    val got = quotes(picked.map { it.symbol }).associateBy { it.symbol.uppercase(Locale.ROOT) }
    val lines = picked.mapNotNull { w -> got[w.symbol]?.let { w to it } }
    if (lines.isEmpty()) return reply("I couldn't reach the stock prices just now.")
    val missing = picked.filter { it.symbol !in got }.map { it.symbol }
    val out = JSONObject().put("ok", true).put("from", origin)
        .put("quotes", JSONArray(lines.map { (w, q) -> json(w, q) }))
        .put("message", lines.joinToString(" ") { (w, q) -> spoken(w, q) } +
            (if (missing.isEmpty()) "" else " I couldn't get ${missing.joinToString(", ")}."))
    return out.put("note", "Prices can be delayed; say they are as of now, not a recommendation.")
  }

  private fun said(args: JSONObject): List<String> {
    val list = args.optJSONArray("symbols")?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String } }.orEmpty()
    val one = listOfNotNull(args.opt("symbol") as? String, args.opt("company") as? String)
    return (list + one).map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_SYMBOLS)
  }

  /** A company name the dictionary knows → its ticker; else the words as a ticker. */
  private fun resolve(words: String): Wanted? {
    dictionary()?.lookup(words, MemoryFact.Kind.TICKER)?.singleOrNull()?.let { return Wanted(it.canonical, it.label) }
    return StockQuotes.symbol(words)?.let { Wanted(it, nameOf(it)) }
  }

  private fun nameOf(symbol: String): String? =
      dictionary()?.entry(MemoryDictionary.factId(MemoryFact.Kind.TICKER, symbol))?.label

  private fun spoken(w: Wanted, q: StockQuotes.Quote): String {
    val name = w.name ?: q.name
    val who = if (name == null || name.equals(q.symbol, ignoreCase = true)) q.symbol else "$name (${q.symbol})"
    val money = money(q.last, q.currency)
    val move = when {
      q.change == 0.0 -> "unchanged today"
      else -> (if (q.change > 0) "up " else "down ") + number(kotlin.math.abs(q.change)) +
          ", ${number(kotlin.math.abs(q.changePct))} percent, today"
    }
    return "$who is at $money, $move."
  }

  private fun json(w: Wanted, q: StockQuotes.Quote) = JSONObject().put("symbol", q.symbol)
      .apply { (w.name ?: q.name)?.let { put("name", it.take(MAX_SAID)) } }
      .put("price", round(q.last)).put("change", round(q.change)).put("change_percent", round(q.changePct))
      .apply { q.currency?.let { put("currency", it) } }

  private fun money(v: Double, currency: String?) =
      if (currency == null || currency == "USD") "$" + number(v) else "${number(v)} $currency"

  private fun number(v: Double) = String.format(Locale.US, "%.2f", v)

  private fun round(v: Double) = Math.round(v * 100) / 100.0

  private fun reply(message: String) = JSONObject().put("ok", false).put("message", message)

  private fun error(message: String) = JSONObject().put("error", message)

  companion object {
    const val NAME = "hearthpane.stocks"
    const val MAX_SYMBOLS = 5
    private const val MAX_SAID = 40
  }
}
