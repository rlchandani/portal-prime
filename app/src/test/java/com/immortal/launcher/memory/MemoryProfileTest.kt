/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import com.immortal.launcher.FleetBackupArchive
import com.immortal.launcher.memory.MemoryFact.Kind
import com.immortal.launcher.memory.MemoryFact.State
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The profile schema's rules (plan §3.2, §4.4) and its versioned JSON, pinned by a golden file. */
class MemoryProfileTest {
  private val person = MemoryScope.Person("p1")

  private fun ev(at: Long, source: Evidence.Source = Evidence.Source.UTTERANCE, conv: String? = "c$at") =
      Evidence(source, at, "family", conv, 0.3)

  private fun fact(id: String, kind: Kind = Kind.TEAM, last: Long = 1_000L, state: State = State.ACTIVE,
      sensitive: Boolean = false, evidence: List<Evidence> = listOf(ev(last))) =
      MemoryFact(id, kind, "Label $id", mapOf("league" to "nba"), evidence, 0, 0.7, state, 500L, last, sensitive)

  // --- caps and rules ---------------------------------------------------------------------

  @Test fun evidencePastTheCapIsFoldedIntoTheCounterNewestKept() {
    val many = (1..45L).map { ev(it) }
    val f = fact("team:nba.gsw", evidence = many.shuffled()).copy(foldedEvidence = 2).normalized()
    assertEquals(MemoryFact.MAX_EVIDENCE, f.evidence.size)
    assertEquals(45L, f.evidence.first().atMs)
    assertEquals(6L, f.evidence.last().atMs)
    assertEquals(7, f.foldedEvidence)
  }

  @Test fun factsPastTheCapDropTheLeastRecentlySeenButKeepSuppressedOnes() {
    val facts = (1..310).map { fact("ticker:t$it", Kind.TICKER, last = it.toLong()) } +
        fact("ticker:old", Kind.TICKER, last = 0L, state = State.SUPPRESSED)
    val kept = MemoryProfile(person, facts).normalized().facts
    assertEquals(MemoryProfile.MAX_FACTS, kept.size)
    assertTrue(kept.any { it.id == "ticker:old" })
    assertTrue(kept.none { it.id == "ticker:t1" || it.id == "ticker:t11" })
    assertTrue(kept.any { it.id == "ticker:t12" })
  }

  @Test fun aSuppressedFactKeepsOnlyItsKey() {
    val f = fact("team:nba.gsw", state = State.SUPPRESSED).normalized()
    assertEquals("team:nba.gsw", f.id)
    assertEquals("", f.label)
    assertTrue(f.attrs.isEmpty() && f.evidence.isEmpty())
    assertEquals(0.0, f.confidence, 0.0)
  }

  @Test fun theHouseholdScopeHoldsOnlyLowSensitivityKindsAndNothingSensitive() {
    val facts = Kind.values().map { fact("${it.name.lowercase()}:x", it) } +
        fact("team:secret", Kind.TEAM, sensitive = true)
    val kept = MemoryProfile(MemoryScope.Household, facts).normalized().facts.map { it.kind }.toSet()
    assertEquals(setOf(Kind.TEAM, Kind.TICKER, Kind.CRYPTO, Kind.NEWS_TOPIC, Kind.MUSIC_TASTE, Kind.ROUTINE), kept)
    val personal = MemoryProfile(person, facts).normalized().facts
    assertEquals(Kind.values().size + 1, personal.size)
  }

  @Test fun oneFactPerIdTheMostRecentWins() {
    val kept = MemoryProfile(person, listOf(fact("team:a", last = 5), fact("team:a", last = 9))).normalized().facts
    assertEquals(listOf(9L), kept.map { it.lastSeenMs })
  }

  @Test fun confidenceIsClampedAndRecentTopicsAreCappedNewestFirst() {
    val f = fact("team:a").copy(confidence = 4.0).normalized()
    assertEquals(1.0, f.confidence, 0.0)
    assertEquals(0.0, fact("team:b").copy(confidence = Double.NaN).normalized().confidence, 0.0)
    val topics = (1..8L).map { RecentTopic("t$it", "g".repeat(200), null, it) }
    val kept = MemoryProfile(person, recentTopics = topics).normalized().recentTopics
    assertEquals(listOf(8L, 7L, 6L, 5L, 4L), kept.map { it.atMs })
    assertEquals(RecentTopic.MAX_GIST, kept.first().gist.length)
  }

  @Test fun halfLivesFollowThePlan() {
    assertEquals(90 * MemoryFact.DAY_MS, Kind.TEAM.halfLifeMs)
    assertEquals(21 * MemoryFact.DAY_MS, Kind.ROUTINE.halfLifeMs)
    assertEquals(7 * MemoryFact.DAY_MS, RecentTopic.HALF_LIFE_MS)
  }

  @Test fun scopesAreFileSafeTokensAndRoundTrip() {
    assertEquals("household", MemoryScope.Household.token)
    assertEquals("person-p1", person.token)
    assertEquals(person, MemoryScope.fromToken("person-p1"))
    assertEquals(MemoryScope.Household, MemoryScope.fromToken("household"))
    assertNull(MemoryScope.fromToken("person-../x"))
    assertNull(MemoryScope.fromToken("guest"))
    assertTrue(runCatching { MemoryScope.Person("../etc") }.isFailure)
  }

  @Test fun memoryNeverRidesInASealedSettingsBackup() {
    assertFalse(FleetBackupArchive.DATA_FILES.any { it.contains(MemoryProfileStore.DIR) })
  }

  // --- golden JSON ------------------------------------------------------------------------

  private val golden = MemoryProfile(
      person,
      listOf(
          MemoryFact("team:nba.gsw", Kind.TEAM, "Golden State Warriors", mapOf("league" to "nba"),
              listOf(Evidence(Evidence.Source.UTTERANCE, 2_000L, "family", "conv-2", 0.3),
                  Evidence(Evidence.Source.TOOL_CALL, 1_000L, "kitchen", null, 0.5)),
              3, 0.62, State.ACTIVE, 1_000L, 2_000L, false),
          MemoryFact("ticker:nvda", Kind.TICKER, "", emptyMap(), emptyList(), 0, 0.0, State.SUPPRESSED, 10L, 20L),
      ),
      listOf(RecentTopic("warriors", "Asked how the Warriors did", "Next game?", 3_000L)),
  )

  private val goldenJson = """
    {"v":1,"scope":"person-p1",
     "facts":[
      {"id":"team:nba.gsw","kind":"TEAM","label":"Golden State Warriors","attrs":{"league":"nba"},
       "evidence":[{"src":"UTTERANCE","at":2000,"portal":"family","conv":"conv-2","w":0.3},
                   {"src":"TOOL_CALL","at":1000,"portal":"kitchen","w":0.5}],
       "folded":3,"conf":0.62,"state":"ACTIVE","first":1000,"last":2000,"sensitive":false},
      {"id":"ticker:nvda","kind":"TICKER","label":"","attrs":{},"evidence":[],
       "folded":0,"conf":0.0,"state":"SUPPRESSED","first":10,"last":20,"sensitive":false}],
     "topics":[{"topic":"warriors","gist":"Asked how the Warriors did","open":"Next game?","at":3000}]}
  """

  @Test fun encodingMatchesTheGoldenFile() {
    assertEquals(plain(JSONObject(goldenJson)), plain(JSONObject(MemoryProfileCodec.encode(golden))))
  }

  @Test fun theGoldenFileDecodesToTheProfile() {
    assertEquals(golden.normalized(), MemoryProfileCodec.decode(goldenJson, person))
  }

  @Test fun decodeRefusesAnotherVersionAnotherScopeOrJunk() {
    val other = goldenJson.replace("\"v\":1", "\"v\":2")
    assertTrue(runCatching { MemoryProfileCodec.decode(other, person) }.exceptionOrNull() is MemoryProfileCodec.FormatException)
    assertTrue(runCatching { MemoryProfileCodec.decode(goldenJson, MemoryScope.Household) }.isFailure)
    assertTrue(runCatching { MemoryProfileCodec.decode("not json", person) }.isFailure)
  }

  @Test fun anUnreadableItemIsDroppedNotTheWholeProfile() {
    val future = goldenJson.replace("\"kind\":\"TEAM\"", "\"kind\":\"SPORTS_LEAGUE\"")
        .replace("\"topic\":\"warriors\"", "\"topic\":\"${"x".repeat(500)}\"")
    val p = MemoryProfileCodec.decode(future, person)
    assertEquals(listOf("ticker:nvda"), p.facts.map { it.id })
    assertTrue(p.recentTopics.isEmpty())
  }

  /** JSON as plain maps/lists so key order and number formatting don't matter. */
  private fun plain(v: Any?): Any? = when (v) {
    is JSONObject -> v.keys().asSequence().associateWith { plain(v.get(it)) }
    is JSONArray -> (0 until v.length()).map { plain(v.get(it)) }
    is Number -> v.toDouble()
    else -> v
  }
}
