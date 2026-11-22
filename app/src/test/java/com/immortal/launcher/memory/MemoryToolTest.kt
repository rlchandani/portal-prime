/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import com.immortal.launcher.memory.MemoryFact.State
import com.immortal.launcher.memory.MemoryFixtures.NOW
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `hearthpane.memory` (plan §3.5): recall, remember, forget, pause, and every refusal. */
class MemoryToolTest {
  private val store = MemoryFixtures.FakeStore()
  private var access: MemoryAccess = MemoryAccess.On(MemoryScope.Person("p1"), "Alex", false)
  private var touched = emptySet<String>()
  private var shown = 0
  private val tool = MemoryTool({ access }, store.runtime::profile,
      { s, c -> store.runtime.update(s, immediate = true, change = c) }, { MemoryFixtures.dictionary }, { touched },
      { "c1" }, { "Kitchen" }, { NOW }, { shown++ })
  private val alex = MemoryScope.Person("p1")

  private fun call(vararg kv: Pair<String, Any>) = tool.invoke(JSONObject(kv.toMap()).toString())

  private fun state(id: String) = store.runtime.profile(alex).facts.firstOrNull { it.id == id }?.state

  @Test fun rememberConfirmsADictionaryThingAndRecallSaysIt() {
    assertTrue(call("action" to "remember", "about" to "Arsenal").getString("message").contains("Arsenal"))
    assertEquals(State.CONFIRMED, state("team:epl.ars"))
    assertTrue(call("action" to "recall").getString("message").contains("you follow Arsenal"))
  }

  @Test fun rememberOnlyAcceptsWhatTheDictionaryKnowsAndAsksOnATie() {
    assertTrue(call("action" to "remember", "about" to "my address").getString("message").contains("couldn't match"))
    assertTrue(call("action" to "remember", "about" to "Giants").getBoolean("needs_choice"))
    assertTrue(store.runtime.profile(alex).facts.isEmpty())
  }

  @Test fun forgetThatMeansWhatTheLatestConversationTouched() {
    call("action" to "remember", "about" to "AMD")
    touched = setOf("ticker:AMD")
    assertTrue(call("action" to "forget", "about" to "that").getString("message").contains("AMD"))
    assertEquals(State.SUPPRESSED, state("ticker:AMD"))
  }

  @Test fun forgetByNameSuppressesEvenSomethingNotLearnedYet() {
    call("action" to "forget", "about" to "the Warriors")
    assertEquals(State.SUPPRESSED, state("team:nba.gsw"))
  }

  @Test fun forgetEverythingOpensTheScreenInsteadOfWipingByVoice() {
    call("action" to "remember", "about" to "AMD")
    call("action" to "forget", "about" to "everything")
    assertEquals(1, shown)
    assertEquals(State.CONFIRMED, state("ticker:AMD"))
  }

  @Test fun pauseAndResume() {
    call("action" to "pause")
    assertTrue(store.runtime.profile(alex).paused)
    call("action" to "resume")
    assertTrue(!store.runtime.profile(alex).paused)
  }

  @Test fun refusedForGuestsChildrenTeensAndWhenOff() {
    for (reason in MemoryAccess.Reason.entries) {
      access = MemoryAccess.Off(reason)
      val r = call("action" to "remember", "about" to "Arsenal")
      assertTrue(r.getBoolean("refused"))
    }
    assertTrue(store.saved.isEmpty())
  }

  @Test fun badArgumentsAreErrors() {
    assertTrue(tool.invoke("nope").has("error"))
    assertTrue(call("action" to "delete_all").has("error"))
    assertTrue(call("action" to "remember").has("error"))
    assertTrue(call("action" to "recall", "kind" to "place").getString("message").contains("don't keep"))
  }

  @Test fun recallWithShowOpensTheScreenAndSaysWhenNothingIsKnown() {
    val r = call("action" to "recall", "show" to true)
    assertEquals(1, shown)
    assertTrue(r.getString("message").contains("nothing to set up"))
  }
}
