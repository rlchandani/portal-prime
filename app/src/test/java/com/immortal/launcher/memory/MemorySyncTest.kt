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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Slice 5's merge across Portals (plan §3.8): idempotent unions, Forget wins everywhere, the latest choice wins. */
class MemorySyncTest {
  private val alex = MemoryScope.Person("h0123456789abcdef0123")
  private val key = ByteArray(32) { it.toByte() }

  private fun learned(profile: MemoryProfile, conversation: String, at: Long, portal: String, vararg said: String) =
      MemoryLearner.learn(profile, MemoryFixtures.observe(conversation, *said), conversation, portal, at).profile

  private val kitchen = learned(MemoryProfile(alex), "k1", NOW - 2 * DAY, "Kitchen", "How did the Warriors do?")
  private val living = learned(MemoryProfile(alex), "l1", NOW - DAY, "Living Area", "Did the Warriors win?")

  private fun fact(p: MemoryProfile, id: String) = p.facts.firstOrNull { it.id == id }

  @Test fun twoPortalsConversationsAddUpToOneFact() {
    val merged = MemorySync.merge(kitchen, living, NOW)
    val warriors = fact(merged, "team:nba.gsw")!!
    assertEquals(2, warriors.evidence.size)
    assertEquals(setOf("Kitchen", "Living Area"), warriors.evidence.map { it.portal }.toSet())
    assertEquals(State.ACTIVE, warriors.state)
  }

  @Test fun mergingIsIdempotentAndOrderFree() {
    val ab = MemorySync.merge(kitchen, living, NOW)
    assertEquals(ab, MemorySync.merge(living, kitchen, NOW))
    assertEquals(ab, MemorySync.merge(ab, living, NOW))
    assertEquals(ab, MemorySync.merge(ab, ab, NOW))
    assertEquals(MemorySync.rev(key, ab), MemorySync.rev(key, MemorySync.merge(ab, kitchen, NOW)))
  }

  @Test fun forgetOnOnePortalWinsOverLearningOnAnother() {
    val forgot = MemoryLearner.forget(kitchen, setOf("team:nba.gsw"), NOW - DAY / 2)
    val laterMention = learned(living, "l2", NOW, "Living Area", "Warriors score?")
    val merged = MemorySync.merge(forgot, laterMention, NOW)
    assertEquals(State.SUPPRESSED, fact(merged, "team:nba.gsw")!!.state)
    assertTrue(fact(merged, "team:nba.gsw")!!.evidence.isEmpty())
    assertEquals(merged, MemorySync.merge(laterMention, forgot, NOW))
  }

  @Test fun aLaterKeepBeatsAnEarlierForget() {
    val entry = MemoryFixtures.dictionary.entry("team:nba.gsw")!!
    val forgot = MemoryLearner.forget(kitchen, setOf("team:nba.gsw"), NOW - DAY)
    val kept = MemoryLearner.confirm(living, entry, Evidence.Source.EXPLICIT, "l2", "Living Area", NOW - DAY / 2)
    assertEquals(State.CONFIRMED, fact(MemorySync.merge(forgot, kept, NOW), "team:nba.gsw")!!.state)
    val forgotAgain = MemoryLearner.forget(forgot, setOf("team:nba.gsw"), NOW)
    assertEquals(State.SUPPRESSED, fact(MemorySync.merge(forgotAgain, kept, NOW), "team:nba.gsw")!!.state)
  }

  @Test fun aForgetAndAKeepAtTheSameMomentForget() {
    val entry = MemoryFixtures.dictionary.entry("team:nba.gsw")!!
    val forgot = MemoryLearner.forget(kitchen, setOf("team:nba.gsw"), NOW)
    val kept = MemoryLearner.confirm(living, entry, Evidence.Source.EXPLICIT, "l2", "Living Area", NOW)
    assertEquals(State.SUPPRESSED, fact(MemorySync.merge(kept, forgot, NOW), "team:nba.gsw")!!.state)
  }

  @Test fun forgetAllWipesWhatEveryPortalLearnedBeforeIt() {
    val wiped = MemoryLearner.forgotAll(alex, NOW - DAY / 2)
    val after = learned(living, "l2", NOW, "Living Area", "Keep an eye on AMD")
    val merged = MemorySync.merge(wiped, after, NOW)
    assertNull(fact(merged, "team:nba.gsw"))
    assertEquals(State.ACTIVE, fact(merged, "ticker:AMD")!!.state)
    assertEquals(NOW - DAY / 2, merged.forgotAllMs)
    assertTrue(merged.recentTopics.none { it.topic == "team:nba.gsw" })
    assertTrue(MemorySync.wipedBy(living, merged))
    assertFalse(MemorySync.wipedBy(merged, merged))
  }

  @Test fun theLatestPauseChoiceWins() {
    val paused = MemoryLearner.setPaused(kitchen, true, NOW - DAY)
    val resumed = MemoryLearner.setPaused(MemoryLearner.setPaused(living, true, NOW - 2 * DAY), false, NOW)
    assertFalse(MemorySync.merge(paused, resumed, NOW).paused)
    assertTrue(MemorySync.merge(paused, living, NOW).paused)
  }

  @Test fun aTopicIsFollowedUpOnceAcrossPortals() {
    val topic = RecentTopic("topic:nvda-earnings", "NVIDIA earnings", "when they report", NOW - DAY)
    val a = kitchen.copy(recentTopics = listOf(topic.copy(shownCount = 1)))
    val b = living.copy(recentTopics = listOf(topic))
    assertEquals(1, MemorySync.merge(a, b, NOW).recentTopics.single { it.topic == topic.topic }.shownCount)
  }

  @Test fun aForgottenFactTakesItsThreadOnTheOtherPortalToo() {
    val thread = RecentTopic("topic:warriors-trade", "Golden State Warriors trade rumours", null, NOW - DAY)
    val other = living.copy(recentTopics = living.recentTopics + thread)
    val forgot = MemoryLearner.forget(kitchen, setOf("team:nba.gsw"), NOW)
    assertTrue(MemorySync.merge(forgot, other, NOW).recentTopics.none { it.topic == thread.topic })
  }

  @Test fun theRevisionIgnoresDecayButSeesEveryChange() {
    assertEquals(MemorySync.rev(key, kitchen), MemorySync.rev(key, MemoryLearner.refresh(kitchen, NOW + DAY)))
    assertNotEquals(MemorySync.rev(key, kitchen), MemorySync.rev(key, MemorySync.merge(kitchen, living, NOW)))
    assertNotEquals(MemorySync.rev(key, kitchen), MemorySync.rev(key, MemoryLearner.setPaused(kitchen, true, NOW)))
    assertNotEquals(MemorySync.rev(key, kitchen), MemorySync.rev(ByteArray(32), kitchen))
  }

  @Test fun theSignalNamesScopesOnlyByKeyedIds() {
    val body = MemorySync.signalBody(key, "192.168.1.20", 8080,
        listOf(MemoryProfile(MemoryScope.Household), kitchen))
    assertFalse(body.toString().contains(alex.token))
    assertFalse(body.toString().contains("household"))
    val signal = MemorySync.parseSignal(body)!!
    assertEquals("192.168.1.20", signal.host)
    val shared = MemorySync.differing(key, signal, listOf(living, MemoryProfile(MemoryScope.Person("hzzzzzzzzzzzzzzzzzzzz"))))
    assertEquals(listOf(alex), shared)
    assertEquals(emptyList<MemoryScope>(), MemorySync.differing(key, signal, listOf(kitchen)))
  }

  @Test fun aSignalFromOutsideTheLanOrMalformedIsIgnored() {
    val good = MemorySync.signalBody(key, "192.168.1.20", 8080, listOf(kitchen))
    assertNull(MemorySync.parseSignal(good.put("h", "8.8.8.8")))
    assertNull(MemorySync.parseSignal(org.json.JSONObject().put("h", "10.0.0.2").put("p", 80).put("s", "x")))
  }

  @Test fun theSignalFitsOneDatagramForAFullHousehold() {
    val scopes = listOf(MemoryProfile(MemoryScope.Household)) +
        (1..8).map { MemoryProfile(MemoryScope.Person(HouseholdPersonId.of("Person $it")!!)) }
    val body = MemorySync.signalBody(key, "192.168.100.200", 65535, scopes)
    assertTrue(body.toString().length < 900)
  }

  @Test fun profilesTravelSealedAndOnlyForTheScopesAsked() {
    val sealed = MemorySync.sealedBody(key, "n1", "req", listOf(kitchen))
    assertFalse(sealed.contains("Warriors"))
    assertEquals(listOf(kitchen.normalized()), MemorySync.openBody(key, "n1", "req", sealed, setOf(alex)))
    assertEquals(emptyList<MemoryProfile>(), MemorySync.openBody(key, "n1", "req", sealed, setOf(MemoryScope.Household)))
    assertNull(MemorySync.openBody(key, "n2", "req", sealed, setOf(alex)))
    assertNull(MemorySync.openBody(key, "n1", "resp", sealed, setOf(alex)))
    assertNull(MemorySync.openBody(ByteArray(32), "n1", "req", sealed, setOf(alex)))
  }

  @Test fun theHouseholdNeverTakesSensitiveFactsFromAPeer() {
    val place = MemoryFact("place:san-jose", MemoryFact.Kind.PLACE, "San Jose", firstSeenMs = NOW, lastSeenMs = NOW,
        sensitive = true, evidence = listOf(Evidence(Evidence.Source.UTTERANCE, NOW, "Kitchen", "k", 0.3)))
    val house = MemoryProfile(MemoryScope.Household, facts = listOf(place))
    val sealed = MemorySync.sealedBody(key, "n", "req", listOf(house))
    assertTrue(MemorySync.openBody(key, "n", "req", sealed, setOf(MemoryScope.Household))!!.single().facts.isEmpty())
  }
}
