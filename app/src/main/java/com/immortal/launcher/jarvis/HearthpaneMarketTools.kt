/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import android.content.Context
import android.util.Log
import com.immortal.launcher.AlpacaClient
import com.immortal.launcher.AlpacaConfig
import com.immortal.launcher.Http
import com.immortal.launcher.HttpCache
import com.immortal.launcher.ImmortalSettings
import com.immortal.launcher.StocksConfig
import com.immortal.launcher.memory.JarvisMemory
import com.immortal.launcher.memory.MemoryDictionary
import com.immortal.launcher.memory.MemoryFact
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Binds [HearthpaneSports] and [HearthpaneStocks] to the live Portal: keyless network reads (ESPN's site API, the
 * Stocks widget's Alpaca key or Yahoo's chart), [HttpCache] for slow-changing pages, Jarvis's memory for "my team"
 * and "my stocks", and a budget inside Jarvis's 5 s tool timeout (a late read counts as unreachable). Runs on the
 * provider's binder thread. Logs carry counts only, never teams or tickers.
 */
internal object HearthpaneMarketTools {
  private const val TAG = "HearthpaneTools"
  private const val STOCKS_BUDGET_MS = 3_000L
  private const val TEAMS_TTL_MS = 7L * 24 * 60 * 60 * 1000
  private const val SCHEDULE_TTL_MS = 10L * 60 * 1000
  private const val TEAM_TTL_MS = 2L * 60 * 1000
  private const val SCOREBOARD_TTL_MS = 20L * 1000
  private const val CACHE_PREFIX = "jarvis.espn:"
  private const val MAX_RECENT_PAGES = 24
  private val io = Executors.newCachedThreadPool { r -> Thread(r, "hearthpane-jarvis-market").apply { isDaemon = true } }

  fun build(context: Context): Map<String, (String?) -> JSONObject> {
    val ctx = context.applicationContext
    val dictionary = { runCatching { MemoryDictionary.load(ctx) }.getOrNull() }
    val sports = HearthpaneSports(
        dictionary = dictionary, followed = { followed(ctx, MemoryFact.Kind.TEAM) },
        fetch = { paths, budget -> espn(ctx, paths, budget) }, now = System::currentTimeMillis,
        zone = TimeZone::getDefault, locale = Locale::getDefault, use24 = { ImmortalSettings.use24HourClock(ctx) })
    val stocks = HearthpaneStocks(
        dictionary = dictionary, followed = { followed(ctx, MemoryFact.Kind.TICKER) },
        widget = { StocksConfig.load(ctx) }, quotes = { symbols -> quotes(ctx, symbols) })
    return mapOf(HearthpaneSports.NAME to sports::invoke, HearthpaneStocks.NAME to stocks::invoke)
  }

  /** What memory knows for the conversation under way; null when it is off (then the tools ask). */
  private fun followed(ctx: Context, kind: MemoryFact.Kind): List<MemoryFact>? = runCatching {
    if (!JarvisMemory.enabled(ctx)) null else JarvisMemory.handoff(ctx).interests(kind)
  }.getOrNull()

  /**
   * ESPN paths side by side, each reduced by [SportsFeed.compact]. Team lists (a few KB, they change yearly) persist
   * in [HttpCache] and a stale one still serves; game pages live in a small in-memory cache for their short TTL and
   * are never served stale (an old "live" score would mislead).
   */
  private fun espn(ctx: Context, paths: List<String>, budgetMs: Long): List<String?> {
    val deadline = System.currentTimeMillis() + budgetMs
    val reads: List<Pair<Future<String?>?, String?>> = paths.map { path ->
      val persistent = path.endsWith("/teams")
      val key = CACHE_PREFIX + path
      val nowMs = System.currentTimeMillis()
      val cached = if (persistent) HttpCache.get(ctx, key) else recent(path, nowMs)
      val fresh = !persistent || HttpCache.ageMs(ctx, key)?.let { it < TEAMS_TTL_MS } == true
      if (cached != null && fresh) return@map Pair(null, cached)
      val read = io.submit<String?> {
        SportsFeed.compact(path, Http.getString(SportsFeed.BASE + path, connectTimeoutMs = budgetMs.toInt(),
            readTimeoutMs = budgetMs.toInt()))?.also { if (persistent) HttpCache.put(ctx, key, it) else remember(path, it) }
      }
      Pair(read, cached.takeIf { persistent })
    }
    val out = reads.map { (future, fallback) -> if (future == null) fallback else within(future, deadline) ?: fallback }
    Log.i(TAG, "sports: ${paths.size} pages, ${out.count { it == null }} missing")
    return out
  }

  private class Recent(val body: String, val atMs: Long)

  /** Game pages by path, at most [MAX_RECENT_PAGES] (the oldest go first). */
  private val recentPages = LinkedHashMap<String, Recent>()

  private fun recent(path: String, nowMs: Long): String? = synchronized(recentPages) {
    recentPages[path]?.takeIf { nowMs - it.atMs < ttlOf(path) }?.body
  }

  private fun remember(path: String, body: String) = synchronized(recentPages) {
    recentPages.remove(path)
    recentPages[path] = Recent(body, System.currentTimeMillis())
    while (recentPages.size > MAX_RECENT_PAGES) recentPages.remove(recentPages.keys.first())
  }

  private fun ttlOf(path: String): Long = when {
    path.endsWith("/schedule") -> SCHEDULE_TTL_MS
    path.endsWith("/scoreboard") -> SCOREBOARD_TTL_MS
    else -> TEAM_TTL_MS
  }

  /** The Stocks widget's Alpaca quotes when the household has a key, then Yahoo's keyless chart for the rest. */
  private fun quotes(ctx: Context, symbols: List<String>): List<StockQuotes.Quote> {
    val deadline = System.currentTimeMillis() + STOCKS_BUDGET_MS
    val alpaca = if (!AlpacaConfig.isConfigured(ctx)) emptyList() else {
      val read = io.submit<List<AlpacaClient.StockQuote>> { AlpacaClient.fetchStockQuotes(ctx, symbols) }
      within(read, deadline).orEmpty().map { StockQuotes.Quote(it.symbol, null, it.last, it.change, it.changePct, "USD") }
    }
    val rest = symbols.filter { s -> alpaca.none { it.symbol.equals(s, ignoreCase = true) } }
    val timeout = maxOf(1L, deadline - System.currentTimeMillis()).toInt()
    val yahoo = rest.map { s ->
      io.submit<StockQuotes.Quote?> {
        StockQuotes.parseYahoo(Http.getString(StockQuotes.yahooUrl(s), connectTimeoutMs = timeout, readTimeoutMs = timeout))
      }
    }.mapNotNull { within(it, deadline) }
    Log.i(TAG, "stocks: ${symbols.size} asked, ${alpaca.size} alpaca, ${yahoo.size} keyless")
    return alpaca + yahoo
  }

  private fun <T> within(f: Future<T>, deadline: Long): T? = runCatching {
    f.get(maxOf(0, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS)
  }.getOrElse { f.cancel(true); Log.i(TAG, "market source late: ${it.javaClass.simpleName}"); null }
}
