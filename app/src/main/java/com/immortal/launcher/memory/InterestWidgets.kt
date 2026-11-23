/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import com.immortal.launcher.CryptoConfig
import com.immortal.launcher.NewsFeed
import com.immortal.launcher.jarvis.StockQuotes

/**
 * Widgets fed from learned interests (plan §3.7, slice 3; mock `mocks/jarvis-personalization-20261122`, Home): the
 * scope's ACTIVE tickers and coins are added to the Stocks and Crypto widgets, its news topics become topic feeds in
 * the News widget, and its teams drive the Sports widget ([SportsCard]).
 *
 * Rules: the household's own widget settings always win: their tickers, coins and feeds come first and are never
 * changed or repeated, and learned items follow them, marked (✦, a "new" tag for [NEW_MS]) and removable. A removed
 * item is a "Don't suggest this" on [Item.surface] for that scope, so it stays off the widgets until allowed again;
 * Jarvis still knows it ("What Jarvis knows" is where it is forgotten). Pure.
 */
internal object InterestWidgets {
  const val NEW_MS = 3 * MemoryFact.DAY_MS
  const val MAX_TICKERS = ForYouInterests.MAX_TICKERS
  const val MAX_COINS = 3
  const val MAX_TOPICS = ForYouInterests.MAX_TOPICS
  const val MAX_TEAMS = ForYouInterests.MAX_TEAMS
  const val PREFIX = "widget:"
  private val COIN_ID = Regex("[a-z0-9-]{1,40}")

  enum class Kind(val code: String) { TICKER("ticker"), CRYPTO("crypto"), TOPIC("news"), TEAM("team") }

  /**
   * One learned item a widget may add: [key] is the symbol (`NVDA`), the CoinGecko id (`solana`), the topic words or
   * the team's canonical id; [label] the name to show ("NVIDIA", "AI and chips"); [fresh] gets the "new" tag.
   */
  data class Item(val kind: Kind, val key: String, val label: String, val fresh: Boolean) {
    val surface: String get() = surfaceOf(kind, key)
  }

  /** What the scope's widgets may add right now. [teams] are the facts themselves (the sports feed reads them). */
  data class Picks(
      val tickers: List<Item> = emptyList(),
      val coins: List<Item> = emptyList(),
      val topics: List<Item> = emptyList(),
      val teams: List<MemoryFact> = emptyList(),
  ) {
    val empty: Boolean get() = tickers.isEmpty() && coins.isEmpty() && topics.isEmpty() && teams.isEmpty()
  }

  /** A widget row: a configured value ([learned] null) or a learned one. */
  data class Row<T>(val value: T, val learned: Item?)

  /** A headline and when it was published (null when the feed doesn't say). */
  data class Dated(val headline: NewsFeed.Headline, val publishedMs: Long?)

  /** A News widget row; [topic] is set for a learned topic's headline. */
  data class NewsRow(val headline: NewsFeed.Headline, val topic: Item?, val publishedMs: Long? = null)

  fun surfaceOf(kind: Kind, key: String): String = PREFIX + kind.code + ":" + ForYouInterests.keyOf(key)

  /** The scope's usable, non-sensitive facts as widget items, surest first, minus anything removed from widgets. */
  fun picks(facts: List<MemoryFact>, feedback: ForYouFeedback, scope: MemoryScope, nowMs: Long, paused: Boolean): Picks {
    val usable = facts.filter { MemoryConfidence.usable(it) && !it.sensitive && it.label.isNotBlank() }
        .sortedWith(compareByDescending<MemoryFact> { it.confidence }.thenByDescending { it.lastSeenMs }.thenBy { it.id })
    fun of(kind: MemoryFact.Kind) = usable.filter { it.kind == kind }
    fun item(kind: Kind, key: String, f: MemoryFact) =
        Item(kind, key, f.label, fresh = !paused && nowMs - f.firstSeenMs in 0 until NEW_MS)
    fun List<Item>.kept(max: Int) = distinctBy { it.key }.filterNot { feedback.suppressed(scope, it.surface) }.take(max)
    val tickers = of(MemoryFact.Kind.TICKER)
        .mapNotNull { f -> StockQuotes.symbol(f.id.substringAfter(':'))?.let { item(Kind.TICKER, it, f) } }.kept(MAX_TICKERS)
    val coins = of(MemoryFact.Kind.CRYPTO)
        .mapNotNull { f -> f.id.substringAfter(':').lowercase().takeIf(COIN_ID::matches)?.let { item(Kind.CRYPTO, it, f) } }
        .kept(MAX_COINS)
    val topics = of(MemoryFact.Kind.NEWS_TOPIC).map { item(Kind.TOPIC, it.label, it) }.kept(MAX_TOPICS)
    val teams = of(MemoryFact.Kind.TEAM)
        .filterNot { feedback.suppressed(scope, surfaceOf(Kind.TEAM, it.id.substringAfter(':'))) }.take(MAX_TEAMS)
    return Picks(tickers, coins, topics, teams)
  }

  /** The Stocks widget's rows: the configured tickers as they are, then learned ones it doesn't already list. */
  fun tickers(configured: List<String>, learned: List<Item>): List<Row<String>> {
    val mine = configured.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
    return mine.map { Row(it, null) } + learned.filter { it.key !in mine }.map { Row(it.key, it) }
  }

  /** The Crypto widget's coins: the configured ones, then learned ones (blank symbol: CoinGecko's is shown). */
  fun coins(configured: List<CryptoConfig.Coin>, learned: List<Item>): List<Row<CryptoConfig.Coin>> {
    val ids = configured.map { it.id }.toSet()
    return configured.map { Row(it, null) } +
        learned.filter { it.key !in ids }.map { Row(CryptoConfig.Coin(it.key, ""), it) }
  }

  /**
   * The News widget's rows: the configured feeds round-robin as before, each round followed by the topic feeds'
   * headlines (titles already shown are dropped). Topic headlines take at most half of [maxRows] while the
   * configured feeds still have headlines to show.
   */
  fun news(configured: List<List<NewsFeed.Headline>>, topics: List<Pair<Item, List<Dated>>>, maxRows: Int): List<NewsRow> {
    val seen = configured.flatten().map { key(it.title) }.toMutableSet()
    val topicRows = topics.map { (item, dated) ->
      dated.filter { seen.add(key(it.headline.title)) }.map { NewsRow(it.headline, item, it.publishedMs) }
    }
    val own = configured.map { list -> list.map { NewsRow(it, null) } }
    val rounds = (own + topicRows).maxOfOrNull { it.size } ?: 0
    val order = (0 until rounds).flatMap { i -> own.mapNotNull { it.getOrNull(i) } + topicRows.mapNotNull { it.getOrNull(i) } }
    val cap = maxRows / 2
    var ownLeft = own.sumOf { it.size }
    var learned = 0
    val out = ArrayList<NewsRow>()
    val later = ArrayList<NewsRow>()
    for (row in order) {
      if (row.topic == null) { out += row; ownLeft-- }
      else if (learned < cap || ownLeft == 0) { out += row; learned++ }
      else later += row
    }
    return (out + later).take(maxRows)
  }

  /** A headline's age as the mock writes it: "now", "25 min", "2 h", "3 d"; null when unknown or in the future. */
  fun age(publishedMs: Long?, nowMs: Long): String? {
    val ms = nowMs - (publishedMs ?: return null)
    return when {
      ms < 0 -> null
      ms < MINUTE_MS -> "now"
      ms < HOUR_MS -> "${ms / MINUTE_MS} min"
      ms < MemoryFact.DAY_MS -> "${ms / HOUR_MS} h"
      else -> "${ms / MemoryFact.DAY_MS} d"
    }
  }

  /** The ✦ line on a widget with learned rows (mock: "From what you ask about", "Topics you follow"). */
  fun provenance(kind: Kind, household: Boolean): String = when (kind) {
    Kind.TOPIC -> if (household) "Topics followed here" else "Topics you follow"
    Kind.TEAM -> if (household) "Followed here" else "Your team"
    else -> if (household) "Followed here" else "From what you ask about"
  }

  private fun key(title: String) = title.trim().lowercase()

  private const val MINUTE_MS = 60_000L
  private const val HOUR_MS = 60 * MINUTE_MS
}
