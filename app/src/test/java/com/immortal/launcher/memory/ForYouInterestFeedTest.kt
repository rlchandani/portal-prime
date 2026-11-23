/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import com.immortal.launcher.NewsFeed
import com.immortal.launcher.jarvis.StockQuotes
import java.io.File
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live data behind the interest chips, through the same ESPN/quotes/news seams as Jarvis's tools. Every payload
 * is a fixed fixture (src/test/resources/sports); nothing touches the network.
 */
class ForYouInterestFeedTest {
  private fun fixture(name: String) = File("src/test/resources/sports/$name").readText()

  /** 2026-11-23 00:00 UTC: Sunday 22 November, 4 pm in California; the Warriors host Boston at 5:30 pm. */
  private val sunday = 1_795_392_000_000L
  private val la = ZoneId.of("America/Los_Angeles")
  private val nba = "basketball/nba"

  private val espn = mutableMapOf(
      "$nba/teams" to fixture("nba_teams.json"),
      "$nba/teams/9" to fixture("gsw_team.json"),
      "$nba/teams/9/schedule" to fixture("gsw_sched.json"),
      "$nba/scoreboard" to """{"events":[]}""",
  )
  private val espnAsked = mutableListOf<String>()
  private val quotesAsked = mutableListOf<List<String>>()
  private val rssAsked = mutableListOf<List<String>>()
  private var rssBody: String? = null

  private val feed = ForYouInterestFeed(
      espn = { paths, _ -> espnAsked += paths; paths.map { espn[it] } },
      quotes = { s -> quotesAsked += s; s.map { StockQuotes.Quote(it, null, 100.0, 5.0, if (it == "AMD") 4.4 else 1.0, "USD") } },
      rss = { urls -> rssAsked += urls; urls.map { rssBody } },
  )

  private fun fact(id: String, kind: MemoryFact.Kind, label: String, state: MemoryFact.State = MemoryFact.State.ACTIVE,
      attrs: Map<String, String> = emptyMap(), sensitive: Boolean = false) =
      MemoryFact(id, kind, label, attrs, state = state, confidence = .8, firstSeenMs = sunday, lastSeenMs = sunday,
          sensitive = sensitive)

  private val warriors = fact("team:nba.gsw", MemoryFact.Kind.TEAM, "Golden State Warriors", attrs = mapOf("league" to "NBA"))

  private fun chips(facts: List<MemoryFact>, now: Long) =
      ForYouInterests.candidates(feed.read(facts, now).inputs, household = false, nowMs = now, zone = la, use24 = false)

  @Test fun tonightsHomeGameFromTheTeamPage() {
    val c = chips(listOf(warriors), sunday).single()
    assertEquals("Warriors vs Celtics · 5:30", c.title)
    assertEquals("Your team plays tonight", c.why)
    assertTrue(espnAsked.containsAll(listOf("$nba/teams", "$nba/teams/9", "$nba/teams/9/schedule", "$nba/scoreboard")))
  }

  @Test fun theLiveScoreComesFromTheScoreboard() {
    espn["$nba/scoreboard"] = fixture("nba_scoreboard_live.json")
    val c = chips(listOf(warriors), sunday + 3_600_000L).single()
    assertEquals("Warriors 78–81 Celtics", c.title)
    assertTrue(c.live)
  }

  @Test fun anUnreachableScoreboardNeverLeavesAnOldScore() {
    espn["$nba/scoreboard"] = fixture("nba_scoreboard_live.json")
    assertTrue(chips(listOf(warriors), sunday + 3_600_000L).single().live)
    espn.remove("$nba/scoreboard")
    espn.remove("$nba/teams/9")
    assertTrue(chips(listOf(warriors), sunday + 3_600_000L).none { it.live })
  }

  @Test fun lastThursdaysLossShowsThatNight() {
    val c = chips(listOf(warriors), sunday - 3L * 24 * 3_600_000L + 5 * 3_600_000L).single() // 4 h after tip-off
    assertEquals("Warriors lost 98–126", c.title)
  }

  @Test fun quotesAreReadOnlyOnceTheMarketHasOpened() {
    val facts = listOf(fact("ticker:AMD", MemoryFact.Kind.TICKER, "AMD"), fact("ticker:NVDA", MemoryFact.Kind.TICKER, "NVIDIA"))
    val monday = MemoryFixtures.NOW
    assertTrue(chips(facts, monday - 2 * 3_600_000L).isEmpty())
    assertTrue(quotesAsked.isEmpty())
    assertEquals(listOf("AMD +4% today"), chips(facts, monday).map { it.title })
    assertEquals(listOf(listOf("AMD", "NVDA")), quotesAsked)
  }

  @Test fun onlySureUnsensitiveFactsAreLookedUpAndAtMostThreeTeams() {
    val tentative = fact("ticker:AMD", MemoryFact.Kind.TICKER, "AMD", state = MemoryFact.State.TENTATIVE)
    val sensitive = fact("news:health", MemoryFact.Kind.NEWS_TOPIC, "Health", sensitive = true)
    val suppressed = fact("team:nba.lal", MemoryFact.Kind.TEAM, "", state = MemoryFact.State.SUPPRESSED)
    val read = feed.read(listOf(tentative, sensitive, suppressed), MemoryFixtures.NOW)
    assertEquals(Triple(0, 0, 0), Triple(read.teams, read.tickers, read.topics))
    assertTrue(espnAsked.isEmpty() && quotesAsked.isEmpty() && rssAsked.isEmpty())

    val many = (1..5).map { fact("team:nba.t$it", MemoryFact.Kind.TEAM, "Team $it", attrs = mapOf("league" to "NBA")) }
    assertEquals(ForYouInterests.MAX_TEAMS, feed.read(many, sunday).teams)
  }

  @Test fun aTopicIsSearchedOnGoogleNewsAndItsNewestHeadlineDated() {
    rssBody = """<rss><channel>
      <item><title>Chipmaker outlines next-gen roadmap</title><pubDate>Mon, 23 Nov 2026 13:00:00 GMT</pubDate></item>
      <item><title>Older</title><pubDate>Sun, 22 Nov 2026 09:00:00 GMT</pubDate></item>
    </channel></rss>"""
    val c = chips(listOf(fact("news:ai and chips", MemoryFact.Kind.NEWS_TOPIC, "AI and chips")), MemoryFixtures.NOW).single()
    assertEquals("AI and chips headlines", c.title)
    assertEquals(listOf("https://news.google.com/rss/search?q=AI+and+chips&hl=en-US&gl=US&ceid=US:en"), rssAsked.single())
  }

  @Test fun newsDatesReadRssAndAtomAndNothingElse() {
    assertEquals(1_795_438_800_000L, NewsFeed.newestPublishedMs(
        "<rss><item><title>a</title><pubDate>Mon, 23 Nov 2026 13:00:00 +0000</pubDate></item></rss>"))
    assertEquals(1_795_438_800_000L, NewsFeed.newestPublishedMs(
        "<feed><entry><title>a</title><updated>2026-11-23T13:00:00Z</updated></entry></feed>"))
    assertNull(NewsFeed.newestPublishedMs("<rss><item><title>a</title><pubDate>soon</pubDate></item></rss>"))
    assertNull(NewsFeed.newestPublishedMs(null))
    assertNull(NewsFeed.newestPublishedMs("not xml"))
  }
}
