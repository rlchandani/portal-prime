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
import java.time.ZoneOffset
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Jarvis's conversation open and end, end to end over the pure pieces: slice 1's acceptance 1–4. */
class MemoryHandoffTest {
  private val store = MemoryFixtures.FakeStore()
  private var access: MemoryAccess = MemoryAccess.On(MemoryScope.Household, null, false)
  private val usage = mutableListOf<String>()
  private val handoff = MemoryHandoff(store.runtime, { MemoryFixtures.dictionary }, { access }, { "Kitchen" },
      { store.clock }, { ZoneOffset.UTC }, { _, s -> usage += s })

  private fun open(id: String, screen: String? = null) =
      handoff.context(JSONObject().put("conversation", id).apply { screen?.let { put("screen", it) } }.toString())

  private fun end(id: String, vararg said: String): JSONObject =
      handoff.observe(JSONObject().put("conversation", id).put("user", JSONArray(said.toList())).toString())

  private fun facts(scope: MemoryScope = MemoryScope.Household) = store.runtime.profile(scope).facts

  private fun conversation(id: String, vararg said: String): JSONObject {
    open(id)
    return end(id, *said)
  }

  @Test fun twoConversationsAboutTheWarriorsPutThemInTheNextNote() {
    conversation("c1", "How did the Warriors do last night?")
    store.clock += DAY
    val second = conversation("c2", "What's the Warriors score?")
    assertEquals("team:nba.gsw", second.getJSONArray("learned").getJSONObject(0).getString("id"))
    val note = open("c3").getString("note")
    assertTrue(note, note.contains("Golden State Warriors"))
    assertTrue(note.contains("assume the Golden State Warriors"))
  }

  @Test fun oneIntentPhraseLearnsAtOnceAndForgetNeverComesBack() {
    val r = conversation("c1", "Keep an eye on AMD.")
    assertEquals("AMD", r.getJSONArray("learned").getJSONObject(0).getString("label"))
    assertEquals(1, handoff.undo(JSONObject().put("ids", JSONArray(listOf("ticker:AMD"))).toString()).getInt("forgotten"))
    conversation("c2", "How is AMD stock doing?")
    conversation("c3", "AMD earnings?")
    assertEquals(State.SUPPRESSED, facts().single { it.id == "ticker:AMD" }.state)
    assertFalse(open("c4").optString("note").contains("AMD"))
  }

  @Test fun guestModeAndAChildProduceNoEvidenceAtAll() {
    for (off in listOf(MemoryAccess.Reason.GUEST, MemoryAccess.Reason.CHILD)) {
      access = MemoryAccess.Off(off)
      val opened = open("g-$off")
      assertFalse(opened.getBoolean("learning"))
      assertFalse(opened.has("note"))
      assertTrue(end("g-$off", "I follow the Warriors").getBoolean("ignored"))
    }
    access = MemoryAccess.On(MemoryScope.Household, null, false)
    assertTrue(facts().isEmpty())
    assertTrue(store.saved.isEmpty())
  }

  @Test fun guestModeTurnedOnMidConversationStillLearnsNothing() {
    open("c1")
    access = MemoryAccess.Off(MemoryAccess.Reason.GUEST)
    assertTrue(end("c1", "I follow the Warriors").getBoolean("ignored"))
  }

  @Test fun theIdentityIsFrozenWhenTheConversationOpens() {
    access = MemoryAccess.On(MemoryScope.Person("p1"), "Alex", false)
    open("c1")
    access = MemoryAccess.On(MemoryScope.Household, null, false) // the personal view timed out mid-chat
    end("c1", "I follow the Warriors")
    assertTrue(facts(MemoryScope.Person("p1")).any { it.id == "team:nba.gsw" })
    assertTrue(facts().isEmpty())
  }

  @Test fun someoneElseRecognisedByTheEndMeansNobodyLearns() {
    access = MemoryAccess.On(MemoryScope.Person("p1"), "Alex", false)
    open("c1")
    access = MemoryAccess.On(MemoryScope.Person("p2"), "Sam", false)
    assertTrue(end("c1", "I follow the Warriors").getBoolean("ignored"))
    assertTrue(facts(MemoryScope.Person("p1")).isEmpty())
    assertTrue(facts(MemoryScope.Person("p2")).isEmpty())
  }

  @Test fun pausedConversationsAreNotHandedOver() {
    store.runtime.update(MemoryScope.Household) { MemoryLearner.setPaused(it, true, NOW) }
    assertFalse(open("c1").getBoolean("learning"))
  }

  @Test fun theDigestIsWrittenOnlyAsLearnedFactsDebounced() {
    conversation("c1", "Keep an eye on AMD.")
    assertTrue(store.saved.isEmpty())
    store.runTimers()
    val saved = MemoryProfileCodec.encode(store.saved.getValue(MemoryScope.Household))
    assertFalse(saved.contains("Keep an eye"))
    assertTrue(saved.contains("ticker:AMD"))
  }

  @Test fun undoOnlyForgetsWhatTheLatestConversationTouched() {
    conversation("c1", "Keep an eye on AMD.")
    assertEquals(0, handoff.undo(JSONObject().put("ids", JSONArray(listOf("ticker:NVDA"))).toString()).getInt("forgotten"))
  }

  @Test fun eachConversationIsCountedAsAUsageAndBadInputIsRefused() {
    open("c1")
    assertEquals(listOf(MemoryHandoff.JARVIS_SURFACE), usage)
    assertTrue(handoff.context("{\"conversation\":\"../x\"}").has("error"))
    assertTrue(handoff.observe("not json").has("error"))
  }

  @Test fun interestsAreTheConversationsUsableTeamsAndNothingWhenMemoryIsOff() {
    conversation("c1", "I follow the Warriors.")
    conversation("c2", "Keep an eye on AMD.")
    assertEquals(listOf("team:nba.gsw"), handoff.interests(MemoryFact.Kind.TEAM)?.map { it.id })
    assertEquals(listOf("ticker:AMD"), handoff.interests(MemoryFact.Kind.TICKER)?.map { it.id })
    access = MemoryAccess.Off(MemoryAccess.Reason.GUEST)
    assertNull(handoff.interests(MemoryFact.Kind.TEAM))
    access = MemoryAccess.On(MemoryScope.Person("p1"), "Maya", false)
    assertEquals("someone else's profile is empty", emptyList<MemoryFact>(), handoff.interests(MemoryFact.Kind.TEAM))
  }
}
