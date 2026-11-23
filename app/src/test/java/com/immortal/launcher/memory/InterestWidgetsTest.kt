/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import com.immortal.launcher.CryptoConfig
import com.immortal.launcher.NewsFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Learned tickers, coins and topics added to the household's own Stocks, Crypto and News widgets. Pure. */
class InterestWidgetsTest {
  private val now = MemoryFixtures.NOW
  private val day = MemoryFact.DAY_MS
  private val alex = MemoryScope.Person("alex")

  private fun fact(id: String, kind: MemoryFact.Kind, label: String, state: MemoryFact.State = MemoryFact.State.ACTIVE,
      firstSeen: Long = now - 10 * day, confidence: Double = .8, sensitive: Boolean = false) =
      MemoryFact(id, kind, label, state = state, confidence = confidence, firstSeenMs = firstSeen, lastSeenMs = now,
          sensitive = sensitive)

  private val nvda = fact("ticker:NVDA", MemoryFact.Kind.TICKER, "NVIDIA", confidence = .9)
  private val amd = fact("ticker:AMD", MemoryFact.Kind.TICKER, "AMD", firstSeen = now - day)
  private val sol = fact("crypto:solana", MemoryFact.Kind.CRYPTO, "Solana")
  private val chips = fact("news:ai and chips", MemoryFact.Kind.NEWS_TOPIC, "AI and chips")
  private val gsw = fact("team:nba.gsw", MemoryFact.Kind.TEAM, "Golden State Warriors")

  private fun picks(facts: List<MemoryFact>, feedback: ForYouFeedback = ForYouFeedback(), paused: Boolean = false) =
      InterestWidgets.picks(facts, feedback, alex, now, paused)

  @Test fun onlySureUnsensitiveFactsAreAddedBestFirst() {
    val tentative = fact("ticker:TSLA", MemoryFact.Kind.TICKER, "Tesla", state = MemoryFact.State.TENTATIVE)
    val sensitive = fact("news:health", MemoryFact.Kind.NEWS_TOPIC, "Health", sensitive = true)
    val suppressed = fact("ticker:IBM", MemoryFact.Kind.TICKER, "", state = MemoryFact.State.SUPPRESSED)
    val p = picks(listOf(amd, tentative, sensitive, suppressed, nvda, sol, chips, gsw))
    assertEquals(listOf("NVDA", "AMD"), p.tickers.map { it.key })
    assertEquals(listOf("solana"), p.coins.map { it.key })
    assertEquals(listOf("AI and chips"), p.topics.map { it.label })
    assertEquals(listOf("team:nba.gsw"), p.teams.map { it.id })
    assertFalse(p.empty)
    assertTrue(picks(listOf(tentative, sensitive, suppressed)).empty)
  }

  @Test fun aNewTagForThreeDaysButNotWhileLearningIsPaused() {
    val p = picks(listOf(nvda, amd))
    assertEquals(listOf(false, true), p.tickers.map { it.fresh })
    assertTrue(picks(listOf(nvda, amd), paused = true).tickers.none { it.fresh })
    val old = fact("ticker:AMD", MemoryFact.Kind.TICKER, "AMD", firstSeen = now - 3 * day)
    assertFalse(picks(listOf(old)).tickers.single().fresh)
  }

  @Test fun capsKeepWidgetsSecondary() {
    val many = ('A'..'I').map { fact("ticker:$it$it", MemoryFact.Kind.TICKER, "$it$it") }
    assertEquals(InterestWidgets.MAX_TICKERS, picks(many).tickers.size)
    val topics = (1..5).map { fact("news:t$it", MemoryFact.Kind.NEWS_TOPIC, "Topic $it") }
    assertEquals(InterestWidgets.MAX_TOPICS, picks(topics).topics.size)
  }

  @Test fun theHouseholdsOwnListAlwaysComesFirstAndIsNeverRepeated() {
    val rows = InterestWidgets.tickers(listOf("spx", "NVDA"), picks(listOf(nvda, amd)).tickers)
    assertEquals(listOf("SPX", "NVDA", "AMD"), rows.map { it.value })
    assertEquals(listOf(null, null, "AMD"), rows.map { it.learned?.key })
  }

  @Test fun learnedCoinsFollowTheConfiguredOnes() {
    val btc = fact("crypto:bitcoin", MemoryFact.Kind.CRYPTO, "Bitcoin")
    val rows = InterestWidgets.coins(CryptoConfig.DEFAULTS, picks(listOf(btc, sol)).coins)
    assertEquals(listOf("bitcoin", "ethereum", "solana"), rows.map { it.value.id })
    assertEquals(listOf("BTC", "ETH", ""), rows.map { it.value.symbol }) // a learned coin takes CoinGecko's symbol
    assertNull(rows[0].learned)
    assertEquals("solana", rows[2].learned?.key)
  }

  @Test fun aRemovedItemStaysOffTheWidgetsOnlyForThatScope() {
    val item = picks(listOf(amd)).tickers.single()
    assertEquals("widget:ticker:amd", item.surface)
    val removed = ForYouFeedback().with(alex, item.surface, ForYouFeedback.Kind.NEVER, now)
    assertTrue(picks(listOf(amd), removed).tickers.isEmpty())
    assertEquals(1, InterestWidgets.picks(listOf(amd), removed, MemoryScope.Household, now, false).tickers.size)
    assertEquals(1, picks(listOf(amd), removed.allowAgain(alex, item.surface)).tickers.size)
    val topic = picks(listOf(chips)).topics.single()
    assertEquals("widget:news:ai-and-chips", topic.surface)
  }

  @Test fun topicHeadlinesInterleaveAfterTheConfiguredFeedsAndNeverTakeOver() {
    val a = (1..4).map { NewsFeed.Headline("A$it", null) }
    val b = listOf(NewsFeed.Headline("B1", null))
    val topic = picks(listOf(chips)).topics.single()
    val t = listOf(NewsFeed.Headline("T1", "https://t/1"), NewsFeed.Headline("A1", null), NewsFeed.Headline("T2", null))
    val rows = InterestWidgets.news(listOf(a, b), listOf(topic to t.map { InterestWidgets.Dated(it, null) }), maxRows = 6)
    assertEquals(listOf("A1", "B1", "T1", "A2", "T2", "A3"), rows.map { it.headline.title })
    assertEquals(listOf(null, null, "AI and chips", null, "AI and chips", null), rows.map { it.topic?.label })
    // With no feeds of its own the widget shows the topics alone.
    assertEquals(listOf("T1", "A1", "T2"), InterestWidgets.news(emptyList(), listOf(topic to t.map { InterestWidgets.Dated(it, null) }), 6)
        .map { it.headline.title })
  }

  @Test fun headlineAgesReadLikeTheMock() {
    assertEquals("2 h", InterestWidgets.age(now - 2 * 3_600_000L - 60_000L, now))
    assertEquals("25 min", InterestWidgets.age(now - 25 * 60_000L, now))
    assertEquals("now", InterestWidgets.age(now - 30_000L, now))
    assertEquals("3 d", InterestWidgets.age(now - 3 * day, now))
    assertNull(InterestWidgets.age(null, now))
    assertNull(InterestWidgets.age(now + 3_600_000L, now))
  }

  @Test fun provenanceNeverSaysYouForTheHousehold() {
    assertEquals("From what you ask about", InterestWidgets.provenance(InterestWidgets.Kind.TICKER, household = false))
    assertEquals("Followed here", InterestWidgets.provenance(InterestWidgets.Kind.TICKER, household = true))
    assertEquals("Topics you follow", InterestWidgets.provenance(InterestWidgets.Kind.TOPIC, household = false))
    assertEquals("Topics followed here", InterestWidgets.provenance(InterestWidgets.Kind.TOPIC, household = true))
  }

  @Test fun topicFeedsKeepEachHeadlinesDate() {
    val dated = NewsFeed.parseDated("""<rss><channel>
      <item><title>Chipmaker outlines next-gen roadmap</title><link>https://n/1</link><pubDate>Mon, 23 Nov 2026 13:00:00 GMT</pubDate></item>
      <item><title>Undated</title></item>
    </channel></rss>""")
    assertEquals(listOf("Chipmaker outlines next-gen roadmap", "Undated"), dated.map { it.first.title })
    assertEquals(listOf(1_795_438_800_000L, null), dated.map { it.second })
    assertEquals("https://n/1", dated.first().first.link)
    assertTrue(NewsFeed.parseDated(null).isEmpty())
    assertTrue(NewsFeed.parseDated("not xml").isEmpty())
  }
}
