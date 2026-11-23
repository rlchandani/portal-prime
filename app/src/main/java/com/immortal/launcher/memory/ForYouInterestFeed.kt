/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import com.immortal.launcher.NewsFeed
import com.immortal.launcher.jarvis.HearthpaneSports
import com.immortal.launcher.jarvis.SportsFeed
import com.immortal.launcher.jarvis.StockQuotes
import java.net.URLEncoder

/**
 * Reads the live data behind the interest chips ([ForYouInterests]) for one scope's ACTIVE facts, through the same
 * feeds Jarvis's tools use: ESPN's keyless scoreboards ([SportsFeed], at most [ForYouInterests.MAX_TEAMS] teams, team
 * lists cached, game pages never served stale), stock quotes (Alpaca with the household's key, else Yahoo; only once
 * the market has opened today) and Google News topic search (D5; at most [ForYouInterests.MAX_TOPICS] topics). A
 * live score is taken only from the league scoreboard, never from an older team page. Anything unreachable simply
 * gives no chip. Pure apart from its seams, so every rule is JVM-tested with fixtures.
 */
internal class ForYouInterestFeed(
    /** ESPN paths side by side within the budget; a late or failed one is null. */
    private val espn: (paths: List<String>, budgetMs: Long) -> List<String?>,
    /** Quotes for the symbols, within their budget; symbols without one are left out. */
    private val quotes: (List<String>) -> List<StockQuotes.Quote>,
    /** RSS bodies for the URLs, side by side within their budget; null when unreachable. */
    private val rss: (List<String>) -> List<String?>,
) {
  /** Counts only, for the log. */
  data class Read(val inputs: ForYouInterests.Inputs, val teams: Int, val tickers: Int, val topics: Int)

  fun read(facts: List<MemoryFact>, nowMs: Long): Read {
    val usable = facts.filter { MemoryConfidence.usable(it) && !it.sensitive && it.label.isNotBlank() }
        .sortedWith(compareByDescending<MemoryFact> { it.confidence }.thenByDescending { it.lastSeenMs })
    val teamFacts = usable.filter { it.kind == MemoryFact.Kind.TEAM }.take(ForYouInterests.MAX_TEAMS)
    val tickers = usable.filter { it.kind == MemoryFact.Kind.TICKER }
        .mapNotNull { StockQuotes.symbol(it.id.substringAfter(':')) }.distinct().take(ForYouInterests.MAX_TICKERS)
    val topicFacts = usable.filter { it.kind == MemoryFact.Kind.NEWS_TOPIC }.take(ForYouInterests.MAX_TOPICS)
    val teams = if (teamFacts.isEmpty()) emptyList() else teams(teamFacts, nowMs)
    val moves = if (tickers.isEmpty() || !ForYouInterests.wantsQuotes(nowMs)) emptyList()
        else quotes(tickers).map { ForYouInterests.Move(it.symbol.uppercase(), it.changePct) }
    val topics = if (topicFacts.isEmpty()) emptyList() else {
      val bodies = rss(topicFacts.map { topicUrl(it.label) })
      topicFacts.mapIndexed { i, f ->
        ForYouInterests.Topic(f.id.substringAfter(':'), f.label, NewsFeed.newestPublishedMs(bodies.getOrNull(i)))
      }
    }
    return Read(ForYouInterests.Inputs(teams, tickers, moves, topics), teamFacts.size, tickers.size, topicFacts.size)
  }

  /**
   * The games of the scope's followed teams for the Sports widget and the briefing: the same reads and rules as the
   * chips (at most [ForYouInterests.MAX_TEAMS], team lists cached, a live score only from the scoreboard).
   */
  fun teamGames(facts: List<MemoryFact>, nowMs: Long): List<ForYouInterests.TeamGames> {
    val teamFacts = facts.filter { it.kind == MemoryFact.Kind.TEAM && MemoryConfidence.usable(it) && !it.sensitive && it.label.isNotBlank() }
        .take(ForYouInterests.MAX_TEAMS)
    return if (teamFacts.isEmpty()) emptyList() else teams(teamFacts, nowMs)
  }

  private class Wanted(val fact: MemoryFact, val league: String)

  private fun teams(facts: List<MemoryFact>, nowMs: Long): List<ForYouInterests.TeamGames> {
    val wanted = facts.mapNotNull { f ->
      SportsFeed.pathFor(f.id.substringAfter(':'), f.attrs[MemoryDictionary.ATTR_LEAGUE])?.let { Wanted(f, it) }
    }
    if (wanted.isEmpty()) return emptyList()
    val leagues = wanted.map { it.league }.distinct()
    val lists = leagues.zip(espn(leagues.map(SportsFeed::teamsPath), HearthpaneSports.TEAMS_BUDGET_MS))
        .associate { (league, body) -> league to SportsFeed.parseTeams(body) }
    val found = wanted.mapNotNull { w -> SportsFeed.match(w.fact.label, lists[w.league].orEmpty())?.let { w to it } }
        .distinctBy { (w, team) -> w.league to team.id }
    if (found.isEmpty()) return emptyList()
    val paths = found.flatMap { (w, t) ->
      listOf(SportsFeed.teamPath(w.league, t.id), SportsFeed.schedulePath(w.league, t.id), SportsFeed.scoreboardPath(w.league))
    }.distinct()
    val bodies = paths.zip(espn(paths, HearthpaneSports.GAMES_BUDGET_MS)).toMap()
    return found.map { (w, t) ->
      val info = SportsFeed.parseTeam(bodies[SportsFeed.teamPath(w.league, t.id)])
      val schedule = SportsFeed.parseGames(bodies[SportsFeed.schedulePath(w.league, t.id)])
      val board = SportsFeed.parseGames(bodies[SportsFeed.scoreboardPath(w.league)])
      val live = SportsFeed.live(board, t.id)
      val next = info?.next?.takeIf { it.state == SportsFeed.State.PRE && it.id != live?.id }
          ?: SportsFeed.upcoming((schedule + board).filter { it.id != live?.id }, t.id, nowMs)
      val shortNames = lists[w.league].orEmpty().associate { it.id to it.short.ifBlank { it.name } }
      ForYouInterests.TeamGames(w.fact.id.substringAfter(':'), t.id, t.short.ifBlank { t.name }, live,
          SportsFeed.lastResult(schedule + board, t.id), next, shortNames, lists[w.league].orEmpty().associateBy { it.id })
    }
  }

  companion object {
    /** Google News RSS search for a topic (plan D5: the topic words go to Google). */
    fun topicUrl(topic: String): String =
        "https://news.google.com/rss/search?q=" + URLEncoder.encode(topic.take(MAX_TOPIC), "UTF-8") +
            "&hl=en-US&gl=US&ceid=US:en"

    private const val MAX_TOPIC = 60
  }
}
