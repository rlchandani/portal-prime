/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import com.immortal.launcher.memory.MemoryFact.Kind
import com.immortal.launcher.memory.MemoryFact.State
import com.immortal.launcher.memory.MemoryFixtures.NOW
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The note Jarvis gets at conversation start (plan §3.4): budgets, what may appear, and nothing for Off. */
class MemoryNoteTest {
  private val alex = MemoryAccess.On(MemoryScope.Person("p1"), "Alex", othersNear = false)

  private fun fact(id: String, kind: Kind, label: String, state: State = State.ACTIVE, conf: Double = 0.8,
      sensitive: Boolean = false, attrs: Map<String, String> = emptyMap()) =
      MemoryFact(id, kind, label, attrs, emptyList(), 0, conf, state, NOW, NOW, sensitive)

  private val profile = MemoryProfile(MemoryScope.Person("p1"), listOf(
      fact("team:nba.gsw", Kind.TEAM, "Golden State Warriors", attrs = mapOf("league" to "NBA")),
      fact("ticker:NVDA", Kind.TICKER, "NVIDIA"),
      fact("ticker:AMD", Kind.TICKER, "AMD", state = State.CONFIRMED, conf = 1.0),
      fact("ticker:AAPL", Kind.TICKER, "Apple", state = State.TENTATIVE, conf = 0.4),
      fact("news:ai", Kind.NEWS_TOPIC, "AI"),
      fact("music:jazz", Kind.MUSIC_TASTE, "Jazz", sensitive = true),
  ), listOf(RecentTopic("ticker:NVDA", "NVIDIA", null, NOW)))

  private fun lines(access: MemoryAccess, p: MemoryProfile = profile, screen: String? = null) =
      MemoryNote.lines(MemoryNote.Inputs(access, p, NOW, ZoneOffset.UTC, screen))

  @Test fun namesThePersonAndWhatTheyFollowWithinBudget() {
    val text = lines(alex).joinToString("\n")
    assertTrue(text, text.startsWith("You're talking with Alex."))
    assertTrue(text.contains("Follows: Golden State Warriors (NBA)."))
    assertTrue(text.contains("NVDA") && text.contains("AMD"))
    assertTrue(text.contains("News: AI."))
    assertTrue(text.contains("Recently talked about: NVIDIA (Mon)."))
    assertTrue(text.contains("assume the Golden State Warriors"))
    assertTrue(text.length <= MemoryNote.BUDGET)
  }

  @Test fun stillLearningAndSensitiveFactsNeverReachJarvis() {
    val text = lines(alex).joinToString("\n")
    assertFalse(text.contains("Apple"))
    assertFalse(text.contains("Jazz"))
  }

  @Test fun othersNearOrTheHouseholdGetTheShortNoteWithoutRecentTopics() {
    val near = lines(alex.copy(othersNear = true)).joinToString("\n")
    assertTrue(near.length <= MemoryNote.SHARED_BUDGET)
    assertFalse(near.contains("Recently"))
    val house = lines(MemoryAccess.On(MemoryScope.Household, null, false), profile.copy(scope = MemoryScope.Household))
    assertTrue(house.first().startsWith("Nobody is recognised"))
    assertTrue(house.joinToString("\n").length <= MemoryNote.SHARED_BUDGET)
  }

  @Test fun nothingForGuestsChildrenOrWithTheSwitchOff() {
    for (reason in MemoryAccess.Reason.entries) assertEquals(emptyList<String>(), lines(MemoryAccess.Off(reason)))
  }

  @Test fun nothingWhenNothingIsKnownForSure() =
      assertEquals(emptyList<String>(), lines(alex, MemoryProfile(MemoryScope.Person("p1"))))

  @Test fun aLargeProfileIsCutToTheBudgetBestFirst() {
    val many = (1..80).map { fact("ticker:T$it", Kind.TICKER, "Company $it", conf = 0.6 + it / 1000.0) }
    val text = lines(alex, MemoryProfile(MemoryScope.Person("p1"), many)).joinToString("\n")
    assertTrue(text.length <= MemoryNote.BUDGET)
    assertTrue(text.contains("T80"))
  }
}
