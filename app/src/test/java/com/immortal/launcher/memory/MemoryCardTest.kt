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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Jarvis card is told at the open (mock "Talking with Alex · Kitchen Portal", "Uses what Jarvis knows about
 * Alex", "Warriors · your team"): who and where only for a recognised adult alone with Jarvis, and the facts the
 * note used, as short labels, a reason code and the words that name them. Nothing for an Off moment.
 */
class MemoryCardTest {
  private val alex = MemoryAccess.On(MemoryScope.Person("p1"), "Alex", othersNear = false)

  private fun evidence(conversation: String, source: Evidence.Source = Evidence.Source.UTTERANCE) =
      Evidence(source, NOW, "Kitchen", conversation, 1.0)

  private fun fact(id: String, kind: Kind, label: String, state: State = State.ACTIVE, conf: Double = 0.8,
      sensitive: Boolean = false, evidence: List<Evidence> = emptyList()) =
      MemoryFact(id, kind, label, emptyMap(), evidence, 0, conf, state, NOW, NOW, sensitive)

  private val warriors = fact("team:nba.gsw", Kind.TEAM, "Golden State Warriors",
      evidence = listOf(evidence("c1"), evidence("c2"), evidence("c3")))
  private val nvda = fact("ticker:NVDA", Kind.TICKER, "NVIDIA",
      evidence = listOf(evidence("c1"), evidence("c2"), evidence("c3", Evidence.Source.TOOL_CALL)))
  private val amd = fact("ticker:AMD", Kind.TICKER, "AMD", state = State.CONFIRMED, conf = 1.0,
      evidence = listOf(evidence("c1", Evidence.Source.INTENT_PHRASE)))

  private val profile = MemoryProfile(MemoryScope.Person("p1"), listOf(
      warriors, nvda, amd,
      fact("ticker:AAPL", Kind.TICKER, "Apple", state = State.TENTATIVE, conf = 0.4),
      fact("music:jazz", Kind.MUSIC_TASTE, "Jazz", sensitive = true),
  ))

  @Test fun theNoteSaysWhichFactsItUsed() {
    val note = MemoryNote.build(MemoryNote.Inputs(alex, profile, NOW, ZoneOffset.UTC))
    assertEquals(setOf("team:nba.gsw", "ticker:NVDA", "ticker:AMD"), note.used.map { it.id }.toSet())
    assertTrue(MemoryNote.build(MemoryNote.Inputs(MemoryAccess.Off(MemoryAccess.Reason.GUEST), profile, NOW,
        ZoneOffset.UTC)).used.isEmpty())
  }

  @Test fun aTeamIsItsShortNameAndYourTeam() {
    val badge = MemoryCard.badge(warriors, MemoryFixtures.dictionary)!!
    assertEquals("Warriors", badge.getString("label"))
    assertEquals("team", badge.getString("why"))
    val words = MemoryCard.words(badge)
    assertTrue(words.toString(), "warriors" in words && "golden state warriors" in words)
    assertTrue(words.size <= MemoryCard.MAX_WORDS)
  }

  @Test fun aTickerAskedAboutInSeveralConversationsIsAskedAboutOften() {
    val often = MemoryCard.badge(nvda, MemoryFixtures.dictionary)!!
    assertEquals("NVDA", often.getString("label"))
    assertEquals("often", often.getString("why"))
    assertTrue("nvidia" in MemoryCard.words(often))
    assertEquals("follow", MemoryCard.badge(amd, MemoryFixtures.dictionary)!!.getString("why"))
  }

  @Test fun personalKindsGetNoBadge() {
    val pref = fact("pref:celsius", Kind.PREFERENCE, "Celsius")
    assertNull(MemoryCard.badge(pref, MemoryFixtures.dictionary))
  }

  private val store = MemoryFixtures.FakeStore()
  private var access: MemoryAccess = alex
  private val handoff = MemoryHandoff(store.runtime, { MemoryFixtures.dictionary }, { access }, { "Kitchen" },
      { store.clock }, { ZoneOffset.UTC })

  private fun open(id: String) = handoff.context(JSONObject().put("conversation", id).toString())

  private fun seed(scope: MemoryScope) = store.runtime.update(scope, immediate = true) {
    it.copy(facts = listOf(warriors.copy(lastSeenMs = store.clock), nvda.copy(lastSeenMs = store.clock)))
  }

  @Test fun anAdultAloneIsNamedWithTheRoomAndTheFactsTheNoteUsed() {
    seed(MemoryScope.Person("p1"))
    val opened = open("c1")
    val who = opened.getJSONObject("who")
    assertEquals("Alex", who.getString("name"))
    assertEquals("Kitchen", who.getString("room"))
    val labels = (0 until opened.getJSONArray("facts").length())
        .map { opened.getJSONArray("facts").getJSONObject(it).getString("label") }
    assertEquals(setOf("Warriors", "NVDA"), labels.toSet())
  }

  @Test fun nobodyIsNamedWhenOthersAreNearOrForTheHousehold() {
    seed(MemoryScope.Person("p1"))
    access = MemoryAccess.On(MemoryScope.Person("p1"), "Alex", othersNear = true)
    val near = open("c1")
    assertFalse(near.has("who"))
    assertTrue(near.has("facts"))
    seed(MemoryScope.Household)
    access = MemoryAccess.On(MemoryScope.Household, null, othersNear = false)
    val household = open("c2")
    assertFalse(household.has("who"))
    assertTrue(household.has("facts"))
  }

  @Test fun guestsChildrenTeensAndTheSwitchOffGetNothingForTheCard() {
    seed(MemoryScope.Person("p1"))
    for (reason in MemoryAccess.Reason.entries) {
      access = MemoryAccess.Off(reason)
      val opened = open("off-$reason")
      assertFalse(opened.has("who"))
      assertFalse(opened.has("facts"))
    }
  }

  @Test fun nothingKnownMeansNoFactsButTheNameStill() {
    val opened = open("c1")
    assertEquals("Alex", opened.getJSONObject("who").getString("name"))
    assertFalse(opened.has("facts"))
  }
}
