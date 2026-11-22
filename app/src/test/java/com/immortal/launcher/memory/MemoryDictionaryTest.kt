/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import com.immortal.launcher.memory.MemoryFact.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bundled dictionary: well formed, unique, household-safe, and free of sensitive topics. */
class MemoryDictionaryTest {
  private val d = MemoryFixtures.dictionary

  @Test fun coversTheSlice1Kinds() {
    val byKind = d.entries.groupBy { it.kind }
    assertTrue((byKind[Kind.TEAM]?.size ?: 0) >= 150)
    assertTrue((byKind[Kind.TICKER]?.size ?: 0) >= 100)
    assertTrue((byKind[Kind.CRYPTO]?.size ?: 0) >= 25)
    assertTrue((byKind[Kind.NEWS_TOPIC]?.size ?: 0) >= 40)
    assertTrue(byKind.keys.all { it.householdAllowed })
    assertEquals(1, d.version)
  }

  @Test fun idsAreUniqueAndEveryEntryHasAName() {
    assertEquals(d.entries.size, d.entries.map { it.factId }.toSet().size)
    for (e in d.entries) assertTrue(e.factId, (e.names + e.cuedNames).any { MemoryText.tokens(it).isNotEmpty() })
  }

  @Test fun everyTeamHasAKnownLeague() {
    for (e in d.entries.filter { it.kind == Kind.TEAM }) assertTrue(e.factId, e.attrs["league"] in d.leagueWords)
  }

  @Test fun noNewsTopicTouchesASensitiveSubject() {
    val deny = listOf("politic", "election", "religio", "church", "health", "medic", "sex", "immigra", "abortion",
        "war", "crime", "covid", "vaccine", "drug", "court")
    val words = d.entries.filter { it.kind == Kind.NEWS_TOPIC }.flatMap { it.names + it.cuedNames + it.label }
        .map { it.lowercase() }
    for (w in words) assertTrue(w, deny.none { w.split(' ').any { t -> t.startsWith(it) } })
  }

  @Test fun lookupFindsNamesWholeAndIgnoresALeadingThe() {
    assertEquals(listOf("team:nba.gsw"), d.lookup("the Dubs").map { it.factId })
    assertEquals(listOf("ticker:NVDA"), d.lookup("nvda").map { it.factId })
    assertEquals(listOf("team:epl.ars"), d.lookup("Arsenal", Kind.TEAM).map { it.factId })
    assertTrue(d.lookup("Arsenal", Kind.TICKER).isEmpty())
  }

  @Test fun normalizationMeetsTheDictionaryHalfway() {
    assertEquals(listOf("st", "louis", "cardinals"), MemoryText.tokens("St. Louis Cardinals'"))
    assertEquals(listOf("nvidia", "earnings"), MemoryText.tokens("NVIDIA’s earnings"))
    assertEquals(listOf("s&p", "500"), MemoryText.tokens("S&P 500!"))
    assertEquals(2, MemoryText.sentences("One. Two? ").size)
  }
}
