/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import com.immortal.launcher.memory.MemoryFact.State
import com.immortal.launcher.memory.MemoryFixtures.DAY
import com.immortal.launcher.memory.MemoryFixtures.NOW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Confidence, promotion, decay, Forget, Keep and Pause (plan §3.2 and slice 1's acceptance 1–3). */
class MemoryLearnerTest {
  private val household = MemoryProfile(MemoryScope.Household)

  private fun learn(p: MemoryProfile, conv: String, at: Long, vararg said: String) =
      MemoryLearner.learn(p, MemoryFixtures.observe(conv, *said, known = p.facts.map { it.id }.toSet()), conv, "Kitchen", at)

  private fun MemoryProfile.fact(id: String) = facts.firstOrNull { it.id == id }

  @Test fun oneCasualMentionIsStillLearningTwoConversationsMakeItKnown() {
    val first = learn(household, "c1", NOW, "How did the Warriors do last night?")
    assertEquals(State.TENTATIVE, first.profile.fact("team:nba.gsw")?.state)
    assertTrue(first.learned.isEmpty())
    val second = learn(first.profile, "c2", NOW + DAY, "What's the Warriors score?")
    val gsw = second.profile.fact("team:nba.gsw")!!
    assertEquals(State.ACTIVE, gsw.state)
    assertEquals(2, MemoryConfidence.conversations(gsw))
    assertEquals(listOf("team:nba.gsw"), second.learned.map { it.id })
  }

  @Test fun theSameConversationNeverCountsTwice() {
    val once = learn(household, "c1", NOW, "Warriors game?")
    val again = learn(once.profile, "c1", NOW + 1000, "Warriors score?")
    val gsw = again.profile.fact("team:nba.gsw")!!
    assertEquals(1, gsw.evidence.size)
    assertEquals(State.TENTATIVE, gsw.state)
  }

  @Test fun oneIntentPhraseIsEnough() {
    val r = learn(household, "c1", NOW, "Keep an eye on AMD.")
    assertEquals(State.ACTIVE, r.profile.fact("ticker:AMD")?.state)
    assertEquals(listOf("ticker:AMD"), r.learned.map { it.id })
  }

  @Test fun forgottenFactsAreNeverRelearnedFromCasualMentionsOnlyFromAConfirm() {
    val known = learn(household, "c1", NOW, "Keep an eye on AMD.").profile
    val forgotten = MemoryLearner.forget(known, setOf("ticker:AMD"), NOW + 1)
    assertEquals(State.SUPPRESSED, forgotten.fact("ticker:AMD")?.state)
    assertEquals("", forgotten.fact("ticker:AMD")?.label)
    val mentioned = learn(forgotten, "c2", NOW + DAY, "How is AMD stock?", "Keep an eye on AMD").profile
    assertEquals(State.SUPPRESSED, mentioned.fact("ticker:AMD")?.state)
    val amd = MemoryFixtures.dictionary.lookup("AMD").single()
    val confirmed = MemoryLearner.confirm(mentioned, amd, Evidence.Source.EXPLICIT, "c3", "Kitchen", NOW + 2 * DAY)
    assertEquals(State.CONFIRMED, confirmed.fact("ticker:AMD")?.state)
    assertEquals("AMD", confirmed.fact("ticker:AMD")?.label)
  }

  @Test fun decayTakesAnUnrepeatedInterestBackToStillLearningThenDropsIt() {
    val known = learn(learn(household, "c1", NOW, "Warriors game?").profile, "c2", NOW, "Warriors score?").profile
    assertEquals(State.ACTIVE, known.fact("team:nba.gsw")?.state)
    val later = MemoryLearner.refresh(known, NOW + 120 * DAY)
    assertEquals(State.TENTATIVE, later.fact("team:nba.gsw")?.state)
    assertNull(MemoryLearner.refresh(known, NOW + 181 * DAY).fact("team:nba.gsw"))
  }

  @Test fun forgottenKeysOutliveTheUnseenDrop() {
    val forgotten = MemoryLearner.forget(household, setOf("team:nba.gsw"), NOW)
    assertEquals(State.SUPPRESSED, MemoryLearner.refresh(forgotten, NOW + 400 * DAY).fact("team:nba.gsw")?.state)
  }

  @Test fun confirmedStaysFullWhateverTheAge() {
    val p = learn(household, "c1", NOW, "Warriors game?").profile
    val kept = MemoryLearner.keep(p, "team:nba.gsw", "Kitchen", NOW)
    val later = MemoryLearner.refresh(kept, NOW + 100 * DAY)
    assertEquals(State.CONFIRMED, later.fact("team:nba.gsw")?.state)
    assertEquals(1.0, later.fact("team:nba.gsw")!!.confidence, 0.0)
  }

  @Test fun pausedLearnsNothingButKeepsWhatItKnows() {
    val p = MemoryLearner.setPaused(learn(household, "c1", NOW, "Keep an eye on AMD").profile, true, NOW)
    val r = learn(p, "c2", NOW, "I follow the Warriors")
    assertNull(r.profile.fact("team:nba.gsw"))
    assertEquals(State.ACTIVE, r.profile.fact("ticker:AMD")?.state)
    assertTrue(r.profile.paused)
  }

  @Test fun recentTopicsKeepTheLastFewAndForgetRemovesThem() {
    var p = household
    listOf("Warriors game?", "NVIDIA stock?", "Bitcoin price?", "AMD stock?").forEachIndexed { i, s ->
      p = learn(p, "c$i", NOW + i, s).profile
    }
    assertEquals(MemoryLearner.RECENT_KEEP, p.recentTopics.size)
    assertEquals("ticker:AMD", p.recentTopics.first().topic)
    assertFalse(MemoryLearner.forget(p, setOf("ticker:AMD"), NOW).recentTopics.any { it.topic == "ticker:AMD" })
    assertTrue(MemoryLearner.refresh(p, NOW + 15 * DAY).recentTopics.isEmpty())
  }

  @Test fun confidenceGrowsWithEvidenceAndBarsFollowIt() {
    val one = learn(household, "c1", NOW, "Warriors game?").profile.fact("team:nba.gsw")!!
    assertEquals(1 - Math.exp(-0.3), one.confidence, 1e-9)
    assertEquals(1, MemoryConfidence.bars(one))
    var p = household
    repeat(6) { p = learn(p, "c$it", NOW, "Warriors game?").profile }
    assertEquals(4, MemoryConfidence.bars(p.fact("team:nba.gsw")!!))
  }
}
