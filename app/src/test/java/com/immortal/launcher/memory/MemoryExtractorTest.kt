/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import com.immortal.launcher.memory.MemoryFact.Kind
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-device extractor against the synthetic golden set (plan §5's quality gate: precision ≥ 0.9 and recall
 * ≥ 0.7 for teams and tickers, no sensitive leaks), plus the rules one at a time.
 */
class MemoryExtractorTest {
  private val extractor = MemoryExtractor(MemoryFixtures.dictionary)

  /**
   * One golden transcript. [expect] is what a person would expect; [miss] (a subset of it) and [extra] are the known
   * misses and false positives of the dictionary, so the extractor must yield exactly [yields] while precision and
   * recall are still measured against [expect].
   */
  private data class Case(val id: String, val digest: MemoryDigest, val expect: Set<String>, val intent: Set<String>,
      val sensitive: Boolean, val miss: Set<String>, val extra: Set<String>) {
    val yields: Set<String> get() = expect - miss + extra
  }

  private val cases: List<Case> by lazy {
    val root = JSONObject(File("src/test/resources/memory/transcripts.json").readText())
    val a = root.getJSONArray("transcripts")
    (0 until a.length()).map { i ->
      val o = a.getJSONObject(i)
      fun strings(key: String) = o.optJSONArray(key)?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }.orEmpty()
      val tools = o.optJSONArray("tools")?.let { arr ->
        (0 until arr.length()).map { arr.getJSONObject(it) }.map { MemoryDigest.ToolCall(it.getString("name"), it.getString("query")) }
      }.orEmpty()
      Case(o.getString("id"), MemoryDigest(o.getString("id"), 0L, null, strings("user"), tools),
          strings("expect").toSet(), strings("intent").toSet(), o.optBoolean("sensitive"), strings("miss").toSet(),
          strings("extra").toSet())
    }
  }

  private fun found(c: Case) = extractor.extract(c.digest)

  @Test fun goldenSetHasAtLeastOneHundredFiftySyntheticTranscripts() {
    assertTrue(cases.size >= 150)
    assertEquals(cases.size, cases.map { it.id }.toSet().size)
    for (c in cases) assertTrue("${c.id}: miss must be part of expect", c.expect.containsAll(c.miss))
    for (c in cases) assertTrue("${c.id}: extra must not be expected", c.extra.none { it in c.expect })
  }

  @Test fun teamsAndTickersMeetThePrecisionAndRecallGate() {
    for (kind in listOf(Kind.TEAM, Kind.TICKER)) {
      val prefix = MemoryDictionary.factId(kind, "")
      var tp = 0
      var fp = 0
      var fn = 0
      val misses = mutableListOf<String>()
      for (c in cases) {
        val got = found(c).map { it.factId }.filter { it.startsWith(prefix) }.toSet()
        val want = c.expect.filter { it.startsWith(prefix) }.toSet()
        tp += (got intersect want).size
        fp += (got - want).size
        fn += (want - got).size
        if (got != want) misses += "${c.id}: got $got want $want"
      }
      val precision = tp.toDouble() / (tp + fp)
      val recall = tp.toDouble() / (tp + fn)
      assertTrue("$kind precision $precision $misses", precision >= 0.9)
      assertTrue("$kind recall $recall $misses", recall >= 0.7)
    }
  }

  @Test fun everyTranscriptYieldsExactlyItsExpectedFacts() {
    val wrong = cases.mapNotNull { c ->
      val got = found(c).map { it.factId }.toSet()
      if (got == c.yields) null else "${c.id}: got $got want ${c.yields}"
    }
    assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
  }

  @Test fun intentPhrasesAreMarkedAsIntent() {
    for (c in cases.filter { it.intent.isNotEmpty() }) {
      val intent = found(c).filter { it.source == Evidence.Source.INTENT_PHRASE }.map { it.factId }.toSet()
      assertEquals(c.id, c.intent, intent)
    }
  }

  @Test fun sensitiveConversationsYieldNothing() {
    val sensitive = cases.filter { it.sensitive }
    assertTrue(sensitive.size >= 12)
    for (c in sensitive) assertEquals(c.id, emptyList<Any>(), found(c))
  }

  @Test fun onlyDictionaryKindsCanEverComeOut() {
    val kinds = cases.flatMap(::found).map { it.entry.kind }.toSet()
    assertTrue(kinds.all { it in setOf(Kind.TEAM, Kind.TICKER, Kind.CRYPTO, Kind.NEWS_TOPIC, Kind.MUSIC_TASTE) })
  }

  @Test fun repeatingANameInOneConversationIsOneObservation() {
    val obs = MemoryFixtures.observe("c1", "Warriors? The Warriors!", "Warriors again, the Warriors game")
    assertEquals(listOf("team:nba.gsw"), obs.map { it.factId })
  }

  @Test fun aKnownTeamBreaksANameTie() {
    val tie = "Did the Giants win?"
    assertEquals(emptyList<String>(), MemoryFixtures.observe("c", tie).map { it.factId })
    assertEquals(listOf("team:mlb.sf"), MemoryFixtures.observe("c", tie, known = setOf("team:mlb.sf")).map { it.factId })
  }

  @Test fun anIntentPhraseLetsACommonWordCountWithoutAnotherCue() {
    assertEquals(listOf("ticker:AAPL"), MemoryFixtures.observe("c", "Keep an eye on Apple for me").map { it.factId })
  }

  @Test fun musicComesOnlyFromWhatJarvisWasAskedToPlay() {
    assertTrue(MemoryFixtures.observe("c", "I like jazz and classical").isEmpty())
    val played = extractor.extract(MemoryFixtures.digest("c", tools = listOf(MemoryDigest.ToolCall("portal.play_music", "jazz"))))
    assertEquals(listOf("music:jazz"), played.map { it.factId })
  }

  @Test fun otherToolsArgumentsAreNeverRead() {
    val d = MemoryFixtures.digest("c", tools = listOf(MemoryDigest.ToolCall("hearthpane.reminders", "Warriors tickets")))
    assertTrue(extractor.extract(d).isEmpty())
  }

  @Test fun aNegationAnywhereInTheConversationCancelsTheMention() {
    val obs = MemoryFixtures.observe("c", "How did the Lakers do?", "Actually I don't care about the Lakers.")
    assertTrue(obs.isEmpty())
  }

  private fun tool(name: String, query: String) =
      extractor.extract(MemoryFixtures.digest("c", tools = listOf(MemoryDigest.ToolCall(name, query)))).map { it.factId }

  @Test fun theSportsToolsTeamCountsWithoutACueButOnlyAsATeam() {
    assertEquals(listOf("team:nba.gsw"), tool("hearthpane.sports", "Warriors"))
    assertEquals(listOf("team:epl.bha"), tool("hearthpane.sports", "Brighton"))
    assertEquals("Apple is a ticker, not a team", emptyList<String>(), tool("hearthpane.sports", "Apple"))
    assertEquals("same-named teams still need a league", emptyList<String>(), tool("hearthpane.sports", "Giants"))
    assertEquals(listOf("team:mlb.sf"), tool("hearthpane.sports", "Giants baseball"))
  }

  @Test fun theStocksToolsSymbolsCountOnlyAsTickers() {
    assertEquals(listOf("ticker:AAPL", "ticker:NVDA"), tool("hearthpane.stocks", "Apple, NVDA"))
    assertEquals(emptyList<String>(), tool("hearthpane.stocks", "Warriors"))
  }
}
