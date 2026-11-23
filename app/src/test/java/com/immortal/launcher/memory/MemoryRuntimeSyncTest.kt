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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The runtime's side of slice 5: what a peer's profile does here, and moving pre-slice-5 profiles to stable ids. */
class MemoryRuntimeSyncTest {
  private val store = MemoryFixtures.FakeStore()
  private val alex = MemoryScope.Person(HouseholdPersonId.of("Alex")!!)

  private fun learnedOn(portal: String, conversation: String, at: Long, vararg said: String) =
      MemoryLearner.learn(MemoryProfile(alex), MemoryFixtures.observe(conversation, *said), conversation, portal, at)
          .profile

  @Test fun aPeersProfileIsMergedWrittenAtOnceAndAnnounced() {
    store.runtime.update(alex, immediate = true) { learnedOn("Kitchen", "k1", NOW - DAY, "Did the Warriors win?") }
    val signals = store.signals
    val merged = store.runtime.mergeIn(learnedOn("Living Area", "l1", NOW - DAY / 2, "Warriors score?"))
    assertEquals(State.ACTIVE, merged.facts.single { it.id == "team:nba.gsw" }.state)
    assertEquals(merged, store.saved[alex])
    assertEquals(signals + 1, store.signals)
  }

  @Test fun nothingNewFromAPeerWritesAndAnnouncesNothing() {
    val mine = learnedOn("Kitchen", "k1", NOW - DAY, "Did the Warriors win?")
    store.runtime.update(alex, immediate = true) { mine }
    val saves = store.saves
    val signals = store.signals
    store.runtime.mergeIn(mine)
    assertEquals(saves, store.saves)
    assertEquals(signals, store.signals)
  }

  @Test fun aPeersForgetAllDropsThisPortalsCountsForThatPersonOnly() {
    store.runtime.update(alex, immediate = true) { learnedOn("Kitchen", "k1", NOW - DAY, "Did the Warriors win?") }
    store.runtime.recordUsage { it.record(alex, "jarvis", NOW - DAY, ZoneOffset.UTC) }
    store.runtime.recordUsage { it.record(MemoryScope.Household, "jarvis", NOW - DAY, ZoneOffset.UTC) }
    store.runtime.recordFeedback { it.with(alex, "screen:music", ForYouFeedback.Kind.NEVER, NOW - DAY) }
    store.runTimers()
    store.runtime.mergeIn(MemoryLearner.forgotAll(alex, NOW - 1))
    assertTrue(store.runtime.profile(alex).facts.isEmpty())
    assertTrue(store.runtime.usage().buckets.none { it.scope == alex.token })
    assertTrue(store.runtime.usage().buckets.any { it.scope == MemoryScope.Household.token })
    assertTrue(store.feedback.events.isEmpty())
  }

  @Test fun forgetAllHereAnnouncesItsTombstone() {
    store.runtime.update(alex, immediate = true) { learnedOn("Kitchen", "k1", NOW - DAY, "Did the Warriors win?") }
    val signals = store.signals
    assertTrue(store.runtime.forgetScope(alex))
    assertEquals(NOW, store.saved[alex]!!.forgotAllMs)
    assertEquals(signals + 1, store.signals)
  }

  @Test fun aProfileUnderALocalIdMovesToTheStableOne() {
    val local = MemoryScope.Person("localId12345")
    store.saved[local] = learnedOn("Kitchen", "k1", NOW - DAY, "Keep an eye on AMD").copy(scope = local)
    store.runtime.update(alex, immediate = true) { learnedOn("Kitchen", "k2", NOW, "Did the Warriors win?") }
    store.runtime.adopt(local, alex)
    assertNull(store.saved[local])
    assertEquals(setOf("ticker:AMD", "team:nba.gsw"), store.saved[alex]!!.facts.map { it.id }.toSet())
  }

  @Test fun anEmptyLocalProfileMovesNothing() {
    val local = MemoryScope.Person("localId12345")
    val saves = store.saves
    store.runtime.adopt(local, alex)
    assertEquals(saves, store.saves)
    assertFalse(store.saved.containsKey(alex))
  }
}
