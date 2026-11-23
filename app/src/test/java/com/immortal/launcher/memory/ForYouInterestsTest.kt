/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import com.immortal.launcher.jarvis.SportsFeed.Game
import com.immortal.launcher.jarvis.SportsFeed.Side
import com.immortal.launcher.jarvis.SportsFeed.State
import com.immortal.launcher.R
import com.immortal.launcher.approvedIconResource
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Interest chips (slice 3): a followed team's game, the market's open and close, big moves and fresh topics. */
class ForYouInterestsTest {
  private val la = ZoneId.of("America/Los_Angeles")
  private val minute = 60_000L
  private val hour = 60 * minute

  /** Monday 2026-11-23, 10:00 in New York (7:00 in California): the market is open. */
  private val open = MemoryFixtures.NOW

  private fun game(startMs: Long, state: State, us: String? = null, them: String? = null, won: Boolean? = null) =
      Game("g$startMs", startMs, state, "", listOf(
          Side("9", "Golden State Warriors", us, home = true, winner = won),
          Side("13", "Los Angeles Lakers", them, home = false, winner = won?.not())))

  private fun warriors(live: Game? = null, last: Game? = null, next: Game? = null) = ForYouInterests.TeamGames(
      "nba.gsw", "9", "Warriors", live, last, next, mapOf("9" to "Warriors", "13" to "Lakers"))

  private fun chips(inputs: ForYouInterests.Inputs, now: Long, household: Boolean = false, use24: Boolean = false) =
      ForYouInterests.candidates(inputs, household, now, la, use24)

  @Test fun aTeamPlayingTonightShowsTheMockChip() {
    val start = open + 12 * hour + 30 * minute // 19:30 in California, seen at 8:00
    val c = chips(ForYouInterests.Inputs(teams = listOf(warriors(next = game(start, State.PRE)))), open + hour).single()
    assertEquals("Warriors vs Lakers · 7:30", c.title)
    assertEquals("Your team plays tonight", c.why)
    assertEquals("interest:team:nba.gsw:next", c.surface)
    assertEquals("interest:team:nba.gsw", c.base)
    assertEquals(ForYouInterests.BALL, c.glyph)
    assertEquals(start, c.startsAtMs)
  }

  @Test fun aGameSoonOutranksOneLaterAndTomorrowsGameIsNotToday() {
    val soon = chips(ForYouInterests.Inputs(teams = listOf(warriors(next = game(open + 2 * hour, State.PRE)))), open).single()
    val later = chips(ForYouInterests.Inputs(teams = listOf(warriors(next = game(open + 10 * hour, State.PRE)))), open).single()
    assertTrue(soon.strength > later.strength)
    assertEquals("Your team plays today", soon.why)
    val tomorrow = game(open + 20 * hour, State.PRE)
    assertTrue(chips(ForYouInterests.Inputs(teams = listOf(warriors(next = tomorrow))), open).isEmpty())
  }

  @Test fun aLiveGameShowsTheScoreAndAsksForQuickRefreshes() {
    val c = chips(ForYouInterests.Inputs(teams = listOf(warriors(live = game(open - hour, State.LIVE, "54", "50"),
        next = game(open + 50 * hour, State.PRE)))), open).single()
    assertEquals("Warriors 54–50 Lakers", c.title)
    assertEquals("Live now · your team", c.why)
    assertTrue(c.live)
    assertEquals(ForYouInterests.LIVE_REFRESH_MS, ForYouInterests.refreshMs(listOf(c), open))
  }

  @Test fun aLiveGameWithoutAScoreShowsNothingRatherThanAGuess() {
    assertTrue(chips(ForYouInterests.Inputs(teams = listOf(warriors(live = game(open, State.LIVE)))), open).isEmpty())
  }

  @Test fun aFinalScoreShowsUntilTheNextMorningThenGoes() {
    val last = game(open - 3 * hour, State.FINAL, "118", "112", won = true)
    val c = chips(ForYouInterests.Inputs(teams = listOf(warriors(last = last))), open).single()
    assertEquals("Warriors won 118–112", c.title)
    assertEquals("Final score · your team", c.why)
    assertEquals("interest:team:nba.gsw:final", c.surface)
    val lost = game(open - 3 * hour, State.FINAL, "98", "126", won = false)
    assertEquals("Warriors lost 98–126", chips(ForYouInterests.Inputs(teams = listOf(warriors(last = lost))), open).single().title)
    assertTrue(chips(ForYouInterests.Inputs(teams = listOf(warriors(last = last))), open + 13 * hour).isEmpty())
  }

  @Test fun tonightsGameTakesThePlaceOfLastNightsResult() {
    val c = chips(ForYouInterests.Inputs(teams = listOf(warriors(last = game(open - 10 * hour, State.FINAL, "1", "0", true),
        next = game(open + 3 * hour, State.PRE)))), open).single()
    assertEquals("interest:team:nba.gsw:next", c.surface)
  }

  @Test fun theMarketOpenAndCloseChipsFollowNewYorkHours() {
    val tickers = ForYouInterests.Inputs(tickers = listOf("NVDA", "AMD", "SPY", "AAPL"))
    val before = chips(tickers, open - hour + 3 * minute).single() // 9:03 New York
    assertEquals("NVDA opens in 30 min", before.title)
    assertEquals("A stock you follow", before.why)
    assertEquals(ForYouInterests.MARKET_REFRESH_MS, ForYouInterests.refreshMs(listOf(before), open))
    val closing = chips(tickers, open + 5 * hour + 30 * minute).single() // 15:30 New York
    assertEquals("Markets close at 1 pm", closing.title)
    assertEquals("NVDA, AMD, SPY", closing.why)
    assertEquals("Markets close at 13:00", chips(tickers, open + 5 * hour + 30 * minute, use24 = true).single().title)
    assertTrue(chips(tickers, open).isEmpty())
    assertTrue(chips(tickers, open + 5 * 24 * hour - hour + 3 * minute).isEmpty()) // Saturday
    assertTrue(chips(ForYouInterests.Inputs(), open - hour + 3 * minute).isEmpty())
  }

  @Test fun onlyBigMovesInFollowedStocksAfterTheOpenAreChips() {
    val inputs = ForYouInterests.Inputs(tickers = listOf("AMD", "NVDA", "AAPL"), moves = listOf(
        ForYouInterests.Move("AMD", 4.3), ForYouInterests.Move("NVDA", -6.6), ForYouInterests.Move("AAPL", 3.9),
        ForYouInterests.Move("TSLA", 12.0)))
    val c = chips(inputs, open)
    assertEquals(listOf("NVDA −7% today", "AMD +4% today"), c.map { it.title })
    assertEquals("interest:ticker:amd:move", c[1].surface)
    assertEquals("interest:ticker:amd", c[1].base)
    assertTrue(c[0].strength > c[1].strength)
    assertTrue("before the open a move is yesterday's", chips(inputs, open - 2 * hour).isEmpty())
    assertFalse(ForYouInterests.wantsQuotes(open - 2 * hour))
    assertTrue(ForYouInterests.wantsQuotes(open + 8 * hour))
  }

  @Test fun aTopicWithFreshHeadlinesIsAChip() {
    val fresh = ForYouInterests.Topic("ai and chips", "AI and chips", open - 2 * hour)
    val c = chips(ForYouInterests.Inputs(topics = listOf(fresh)), open).single()
    assertEquals("AI and chips headlines", c.title)
    assertEquals("A topic you follow", c.why)
    assertEquals("interest:news:ai-and-chips:fresh", c.surface)
    assertTrue(chips(ForYouInterests.Inputs(topics = listOf(fresh.copy(newestMs = open - 7 * hour))), open).isEmpty())
    assertTrue(chips(ForYouInterests.Inputs(topics = listOf(fresh.copy(newestMs = null))), open).isEmpty())
  }

  @Test fun householdChipsNeverSayYou() {
    val inputs = ForYouInterests.Inputs(
        teams = listOf(warriors(next = game(open + 12 * hour, State.PRE))),
        tickers = listOf("AMD"), moves = listOf(ForYouInterests.Move("AMD", 5.0)),
        topics = listOf(ForYouInterests.Topic("chips", "Chips", open - hour)))
    val c = chips(inputs, open, household = true)
    assertEquals(3, c.size)
    assertTrue(c.all { !it.why.contains("you", ignoreCase = true) })
    val live = chips(ForYouInterests.Inputs(teams = listOf(warriors(live = game(open, State.LIVE, "1", "2")))), open, household = true)
    assertEquals("Live now · followed here", live.single().why)
  }

  @Test fun feedbackTiresOnePhaseButTurnsDownTheWholeTeam() {
    val scope = MemoryScope.Household
    val next = chips(ForYouInterests.Inputs(teams = listOf(warriors(next = game(open + 2 * hour, State.PRE)))), open)
    val live = chips(ForYouInterests.Inputs(teams = listOf(warriors(live = game(open, State.LIVE, "1", "2")))), open)
    val fresh = ForYouFeedback()
    assertEquals(ForYouInterests.INTEREST_WEIGHT * 0.9, ForYouInterests.rank(next, fresh, scope, open).single().score, 1e-9)

    val notNow = fresh.with(scope, next.single().surface, ForYouFeedback.Kind.NOT_NOW, open)
    assertTrue(ForYouInterests.rank(next, notNow, scope, open).isEmpty())
    assertEquals(1, ForYouInterests.rank(live, notNow, scope, open).size)

    val less = fresh.with(scope, "interest:team:nba.gsw", ForYouFeedback.Kind.LESS_OFTEN, open)
    assertEquals(ForYouInterests.INTEREST_WEIGHT * 0.5, ForYouInterests.rank(live, less, scope, open).single().score, 1e-9)

    val never = fresh.with(scope, "interest:team:nba.gsw", ForYouFeedback.Kind.NEVER, open)
    assertTrue(ForYouInterests.rank(next + live, never, scope, open).isEmpty())
    assertEquals(2, ForYouInterests.rank(next + live, never, MemoryScope.Person("alex"), open).size)
  }

  @Test fun refreshWaitsForKickOffButNeverBelowHalfAMinute() {
    val c = ForYouInterests.Candidate("interest:team:x:next", "interest:team:x", "t", "w", "g", 0.9, startsAtMs = open + 4 * minute)
    assertEquals(4 * minute, ForYouInterests.refreshMs(listOf(c), open))
    assertEquals(30_000L, ForYouInterests.refreshMs(listOf(c.copy(startsAtMs = open + 1_000)), open))
    assertEquals(ForYouInterests.REFRESH_MS, ForYouInterests.refreshMs(emptyList(), open))
  }

  @Test fun interestChipsCarryTheirOwnWhyAndFeedbackKeyIntoTheRow() {
    val c = chips(ForYouInterests.Inputs(teams = listOf(warriors(next = game(open + 12 * hour + 30 * minute, State.PRE)))),
        open + hour).single()
    val targets = mapOf(c.surface to ForYouInterests.target(c))
    val row = ForYouChips.build(listOf(ForYouRanker.Suggestion(c.surface, .4, 0), ForYouRanker.Suggestion("screen:music", .3, 5)),
        household = false) { targets[it] ?: ForYouChips.screenTarget(it) }
    assertEquals(listOf("Warriors vs Lakers · 7:30", "Music"), row.map { it.title })
    assertEquals("Your team plays tonight", row[0].why)
    assertEquals("interest:team:nba.gsw", row[0].feedbackKey)
    assertEquals("screen:music", row[1].feedbackKey)
    assertTrue(row[0].top)
  }

  @Test fun keysAreSafeFeedbackSurfaces() {
    assertEquals("brk.b", ForYouInterests.keyOf("BRK.B"))
    assertEquals("s-p-500", ForYouInterests.keyOf("S&P 500"))
    assertEquals("x", ForYouInterests.keyOf("!!"))
    val surface = "interest:news:${ForYouInterests.keyOf("a".repeat(200))}:fresh"
    assertEquals(surface, ForYouFeedback().with(MemoryScope.Household, surface, ForYouFeedback.Kind.NEVER, open).events.single().surface)
  }

  @Test fun theMockGlyphsHaveTheirOwnDrawables() {
    assertEquals(R.drawable.memory_icon_ball, approvedIconResource(ForYouInterests.BALL))
    assertEquals(R.drawable.memory_icon_trend, approvedIconResource(ForYouInterests.TREND))
    assertEquals(R.drawable.memory_icon_news, approvedIconResource(ForYouInterests.NEWS))
  }
}
