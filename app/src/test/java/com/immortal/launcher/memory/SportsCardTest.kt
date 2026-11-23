/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import com.immortal.launcher.jarvis.SportsFeed
import java.io.File
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Sports widget's game card and its layout at every Home slot size. Games come from the fixed ESPN fixtures
 * (src/test/resources/sports) through [ForYouInterestFeed]; nothing touches the network.
 */
class SportsCardTest {
  private fun fixture(name: String) = File("src/test/resources/sports/$name").readText()

  /** 2026-11-23 00:00 UTC: Sunday 22 November, 4 pm in California; the Warriors host Boston at 5:30 pm. */
  private val sunday = 1_795_392_000_000L
  private val hour = 3_600_000L
  private val la = ZoneId.of("America/Los_Angeles")
  private val nba = "basketball/nba"
  private val espn = mutableMapOf(
      "$nba/teams" to fixture("nba_teams.json"),
      "$nba/teams/9" to fixture("gsw_team.json"),
      "$nba/teams/9/schedule" to fixture("gsw_sched.json"),
      "$nba/scoreboard" to """{"events":[]}""",
  )
  private val feed = ForYouInterestFeed(espn = { paths, _ -> paths.map { espn[it] } }, quotes = { emptyList() },
      rss = { urls -> urls.map { null } })
  private val warriors = MemoryFact("team:nba.gsw", MemoryFact.Kind.TEAM, "Golden State Warriors", mapOf("league" to "NBA"),
      state = MemoryFact.State.ACTIVE, confidence = .8, firstSeenMs = sunday, lastSeenMs = sunday)

  private fun game(now: Long, readAt: Long = now, use24: Boolean = false): SportsCard.Game =
      SportsCard.games(feed.teamGames(listOf(warriors), now), readAt, now, la, use24).single()

  @Test fun tonightsGameReadsLikeTheMock() {
    val g = game(sunday)
    assertEquals(SportsCard.Phase.NEXT, g.phase)
    assertEquals("Tonight · 5:30", g.status)
    assertEquals("Warriors", g.us.name)
    assertEquals("GS", g.us.abbreviation)
    assertEquals("Celtics", g.them?.name)
    assertEquals("BOS", g.them?.abbreviation)
    assertNull(g.us.score)
    assertEquals("Last: lost 98–126 vs Grizzlies", g.detail)
    assertEquals("Tonight · 17:30", game(sunday, use24 = true).status)
  }

  @Test fun theLiveScoreIsTheScoreboardsAndNeverStale() {
    espn["$nba/scoreboard"] = fixture("nba_scoreboard_live.json")
    val now = sunday + hour
    val g = game(now)
    assertEquals(SportsCard.Phase.LIVE, g.phase)
    assertEquals("Live · 3:12 - 3rd", g.status)
    assertEquals("78" to "81", g.us.score to g.them?.score)
    assertEquals("Next: Wed vs Sacramento Kings · 7:00", g.detail)
    // A read older than a minute no longer shows its score as live.
    val old = game(now + SportsCard.LIVE_STALE_MS + 1, readAt = now)
    assertEquals(SportsCard.Phase.LIVE, old.phase)
    assertEquals("Live · updating", old.status)
    assertNull(old.us.score)
    assertNull(old.them?.score)
  }

  @Test fun aFinalShowsThatNightThenTheNextGame() {
    val thursdayNight = sunday - 3 * 24 * hour + 5 * hour
    val g = game(thursdayNight)
    assertEquals(SportsCard.Phase.FINAL, g.phase)
    assertEquals("Final", g.status)
    assertEquals("98" to "126", g.us.score to g.them?.score)
    assertEquals("Next: Sun vs Celtics · 5:30", g.detail)
    val friday = sunday - 2 * 24 * hour + 2 * hour // Friday 6 pm: the final has gone, Sunday's game is next
    assertEquals("Sun · 5:30", game(friday).status)
  }

  @Test fun aTeamWithNothingOnStillShowsItsName() {
    val t = ForYouInterests.TeamGames("nba.gsw", "9", "Warriors", live = null, last = null, next = null)
    val g = SportsCard.games(listOf(t), sunday, sunday, la, false).single()
    assertEquals(SportsCard.Phase.NONE, g.phase)
    assertEquals("No games scheduled", g.status)
    assertNull(g.them)
    assertEquals("WAR", g.us.abbreviation)
  }

  @Test fun itReadsAgainOftenWhileLiveAndAtTipOff() {
    espn["$nba/scoreboard"] = fixture("nba_scoreboard_live.json")
    val live = SportsCard.games(feed.teamGames(listOf(warriors), sunday + hour), sunday + hour, sunday + hour, la, false)
    assertEquals(SportsCard.LIVE_REFRESH_MS, SportsCard.refreshMs(live, sunday + hour))
    espn["$nba/scoreboard"] = """{"events":[]}"""
    val pre = SportsCard.games(feed.teamGames(listOf(warriors), sunday + hour), sunday + hour, sunday + hour, la, false)
    assertEquals(SportsCard.REFRESH_MS, SportsCard.refreshMs(pre, sunday + hour))
    assertEquals(5 * 60_000L, SportsCard.refreshMs(pre, sunday + hour + 25 * 60_000L)) // tip-off at 5:30: read again then
    assertEquals(SportsCard.REFRESH_MS, SportsCard.refreshMs(emptyList(), sunday))
    assertEquals(SportsCard.LIVE_REFRESH_MS, SportsCard.refreshMs(pre, sunday + 2 * hour)) // past tip-off: soon live
  }

  /** The widget body at each Home slot (dp after the grid's own density): Portal+ and the Mini both ways. */
  private val portalOne = 399f to 262f
  private val portalWide = 858f to 262f
  private val portalTall = 399f to 638f
  private val portalBig = 858f to 638f
  private val miniTurned = 339f to 139f
  private val miniUpright = 375f to 276f

  private fun fits(l: SportsCard.Layout, size: Pair<Float, Float>) {
    val cellW = (size.first - SportsCard.CELL_GAP_DP * (l.columns - 1)) / l.columns
    val cellH = (size.second - SportsCard.CELL_GAP_DP * (l.rows - 1)) / l.rows
    assertTrue("width at $size", SportsCard.NATURAL_WIDTH_DP * l.scale <= cellW + 0.5f)
    assertTrue("height at $size", SportsCard.naturalHeight(l.details) * l.scale <= cellH + 0.5f)
  }

  @Test fun everySlotGetsAFittingLayoutThatGrowsWithIt() {
    val sizes = listOf(portalOne, portalWide, portalTall, portalBig, miniTurned, miniUpright)
    for (size in sizes) for (teams in 1..3) {
      val l = SportsCard.layout(size.first, size.second, teams)
      fits(l, size)
      assertTrue(l.scale >= SportsCard.MIN_SCALE && l.scale <= SportsCard.MAX_SCALE)
      assertEquals(minOf(teams, l.columns * l.rows), l.cells)
      assertTrue((l.cells + l.columns - 1) / l.columns == l.rows) // no empty row
    }
    val one = SportsCard.layout(portalOne.first, portalOne.second, 1)
    assertEquals(1 to 1, one.columns to one.rows)
    assertTrue(one.details)
    assertTrue(one.scale > 1f) // a Portal+ slot is a little bigger than the design canvas: everything grows
    assertTrue(SportsCard.layout(portalBig.first, portalBig.second, 1).scale > one.scale * 1.5f)
  }

  @Test fun biggerSlotsShowMoreTeams() {
    assertEquals(1 to 1, SportsCard.layout(portalOne.first, portalOne.second, 3).let { it.columns to it.rows })
    assertEquals(2 to 1, SportsCard.layout(portalWide.first, portalWide.second, 3).let { it.columns to it.rows })
    assertEquals(1 to 2, SportsCard.layout(portalTall.first, portalTall.second, 3).let { it.columns to it.rows })
    val big = SportsCard.layout(portalBig.first, portalBig.second, 3)
    assertEquals(3, big.cells)
    assertEquals(2 to 2, big.columns to big.rows)
  }

  @Test fun theTurnedMiniDropsTheDetailLineRatherThanShrinkingPastTheFloor() {
    val l = SportsCard.layout(miniTurned.first, miniTurned.second, 1)
    assertFalse(l.details)
    assertTrue(l.scale >= SportsCard.MIN_SCALE)
  }

  @Test fun teamColoursComeFromEspnWhenGiven() {
    val body = """{"sports":[{"leagues":[{"teams":[{"team":{"id":"9","displayName":"Golden State Warriors",
      "shortDisplayName":"Warriors","abbreviation":"GS","nickname":"Golden State","color":"1D428A"}},
      {"team":{"id":"2","displayName":"Boston Celtics","color":"not a colour"}}]}]}]}"""
    val teams = SportsFeed.parseTeams(body)
    assertEquals(listOf("1d428a", ""), teams.map { it.color })
    assertEquals(teams, SportsFeed.parseTeams(SportsFeed.compact("$nba/teams", body)))
  }
}
