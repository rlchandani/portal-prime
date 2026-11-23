/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import android.content.Context
import android.util.Log
import com.immortal.launcher.CryptoConfig
import com.immortal.launcher.CryptoFeed
import com.immortal.launcher.Http
import com.immortal.launcher.ImmortalSettings
import com.immortal.launcher.NewsFeed
import com.immortal.launcher.jarvis.HearthpaneMarketTools
import com.immortal.launcher.jarvis.StockQuotes
import java.time.ZoneId

/**
 * Binds the widgets fed from learned interests ([InterestWidgets], [SportsCard]) and the briefing line
 * ([BriefingInterests]) to the live Portal. Behind the same switch as the rest of personalization (off by default):
 * with it off, [load] returns null at once and every widget draws exactly as before. Who it belongs to follows
 * [MemoryAccess] like the "For you right now" row: nothing for guests, children or teens, the recognised adult's own
 * interests, the household's when nobody is recognised or others are near. Network reads go through the same
 * feeds as the chips (quotes cached 60 s, ESPN team lists cached and game pages never served stale, topic searches
 * and learned coins in RAM only, so no learned item is written to disk). Call off the main thread. Logs carry counts
 * only.
 */
internal object InterestWidgetsHome {
  private const val TAG = "HearthpaneForYou"
  private const val COINS_TTL_MS = 5L * 60 * 1000
  private const val COINS_BUDGET_MS = 3_000

  /** Whose interests the widgets show right now, and what they add. */
  data class Snapshot(val scope: MemoryScope, val household: Boolean, val picks: InterestWidgets.Picks)

  /** Null with the switch off, for guests, children and teens. */
  fun load(context: Context): Snapshot? {
    if (!JarvisMemory.enabled(context)) return null
    val on = JarvisMemory.accessNow(context) as? MemoryAccess.On ?: return null
    val scope = if (on.othersNear) MemoryScope.Household else on.scope
    val runtime = JarvisMemory.runtime(context)
    val profile = runtime.profile(scope)
    val picks = InterestWidgets.picks(profile.facts, runtime.feedback(), scope, System.currentTimeMillis(), profile.paused)
    return Snapshot(scope, scope is MemoryScope.Household, picks)
  }

  /** Quotes for the learned symbols (the Stocks widget's Alpaca key, else Yahoo), cached 60 s. */
  fun quotes(context: Context, symbols: List<String>): List<StockQuotes.Quote> =
      if (symbols.isEmpty()) emptyList() else HearthpaneMarketTools.recentQuotes(context.applicationContext, symbols)

  private class Coins(val ids: String, val quotes: List<CryptoFeed.Quote>, val atMs: Long)

  @Volatile private var coinCache: Coins? = null

  /** CoinGecko prices for the learned coins, in RAM only (the ids are personal). */
  fun coins(coins: List<CryptoConfig.Coin>): List<CryptoFeed.Quote> {
    if (coins.isEmpty()) return emptyList()
    val ids = coins.joinToString(",") { it.id }
    val nowMs = System.currentTimeMillis()
    coinCache?.takeIf { it.ids == ids && nowMs - it.atMs < COINS_TTL_MS }?.let { return it.quotes }
    val body = Http.getString(CryptoFeed.url(ids), connectTimeoutMs = COINS_BUDGET_MS, readTimeoutMs = COINS_BUDGET_MS)
    val quotes = body?.let { CryptoFeed.parse(it, coins) }.orEmpty()
    if (quotes.isNotEmpty()) coinCache = Coins(ids, quotes, nowMs)
    return quotes.ifEmpty { coinCache?.takeIf { it.ids == ids }?.quotes.orEmpty() }
  }

  /** The learned topics' Google News searches (plan D5), dated, through the chips' RAM-only cache. */
  fun topics(topics: List<InterestWidgets.Item>): List<Pair<InterestWidgets.Item, List<InterestWidgets.Dated>>> {
    if (topics.isEmpty()) return emptyList()
    val bodies = ForYouHome.topicPages(topics.map { ForYouInterestFeed.topicUrl(it.label) })
    return topics.mapIndexed { i, item ->
      item to NewsFeed.parseDated(bodies.getOrNull(i)).map { (h, at) -> InterestWidgets.Dated(h, at) }
    }
  }

  /** One read of the followed teams' games and when it was made (a live score older than a minute isn't shown). */
  data class SportsRead(val teams: List<ForYouInterests.TeamGames>, val atMs: Long)

  /** The Sports widget's games for [snapshot]'s teams, read now. */
  fun sports(context: Context, snapshot: Snapshot): SportsRead {
    val nowMs = System.currentTimeMillis()
    val games = feed(context).teamGames(snapshot.picks.teams, nowMs)
    Log.i(TAG, "sports widget: ${snapshot.picks.teams.size} teams, ${games.size} read")
    return SportsRead(games, nowMs)
  }

  /**
   * The briefing's "Your teams / Your markets" lines for [personId]'s own briefing: only while that adult is the one
   * recognised, alone, with the switch on. Empty otherwise or with nothing followed.
   */
  fun briefingLines(context: Context, personId: String): List<String> {
    val snap = load(context) ?: return emptyList()
    if (snap.scope != MemoryScope.Person(personId)) return emptyList()
    val nowMs = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val use24 = ImmortalSettings.use24HourClock(context)
    val games = if (snap.picks.teams.isEmpty()) emptyList()
        else SportsCard.games(feed(context).teamGames(snap.picks.teams, nowMs), nowMs, nowMs, zone, use24)
    val symbols = snap.picks.tickers.map { it.key }.take(BriefingInterests.MAX_TICKERS)
    val moves = if (symbols.isEmpty() || !ForYouInterests.wantsQuotes(nowMs)) emptyList()
        else quotes(context, symbols).map { ForYouInterests.Move(it.symbol.uppercase(), it.changePct) }
    val lines = listOfNotNull(BriefingInterests.teams(games), BriefingInterests.markets(symbols, moves, nowMs, zone, use24))
    Log.i(TAG, "briefing interests: ${games.size} teams, ${symbols.size} tickers, ${lines.size} lines")
    return lines
  }

  /** A learned item removed from the widgets (until [restore]); Jarvis still knows it. */
  fun remove(context: Context, scope: MemoryScope, item: InterestWidgets.Item) {
    ForYouHome.note(context, scope, item.surface, ForYouFeedback.Kind.NEVER)
    Log.i(TAG, "widget item removed")
  }

  fun restore(context: Context, scope: MemoryScope, item: InterestWidgets.Item) =
      ForYouHome.allowAgain(context, scope, item.surface)

  private fun feed(context: Context): ForYouInterestFeed {
    val ctx = context.applicationContext
    return ForYouInterestFeed(
        espn = { paths, budget -> HearthpaneMarketTools.espn(ctx, paths, budget) },
        quotes = { symbols -> HearthpaneMarketTools.recentQuotes(ctx, symbols) },
        rss = ForYouHome::topicPages,
    )
  }
}
