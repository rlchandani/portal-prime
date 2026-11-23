/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import com.immortal.launcher.memory.MemoryFixtures.NOW
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The in-memory profiles: one debounced write, Forget at once, failed writes retried, Forget all. */
class MemoryRuntimeTest {
  private val store = MemoryFixtures.FakeStore()
  private val house = MemoryScope.Household
  private val amd = MemoryFixtures.dictionary.lookup("AMD").single()

  private fun remember() = store.runtime.update(house) {
    MemoryLearner.confirm(it, amd, Evidence.Source.EXPLICIT, null, "Kitchen", NOW)
  }

  @Test fun manyChangesMakeOneScheduledWrite() {
    remember()
    remember()
    store.runtime.recordUsage { it.record(house, "jarvis", NOW, ZoneOffset.UTC) }
    assertEquals(1, store.scheduled.size)
    assertEquals(0, store.saves)
    store.runTimers()
    assertEquals(1, store.saves)
    assertEquals(1, store.usage.buckets.size)
  }

  @Test fun forgetIsWrittenAtOnce() {
    store.runtime.update(house, immediate = true) { MemoryLearner.forget(it, setOf("ticker:AMD"), NOW) }
    assertEquals(1, store.saves)
  }

  @Test fun aFailedWriteIsTriedAgain() {
    store.failSaves = true
    remember()
    store.runTimers()
    store.failSaves = false
    remember()
    store.runTimers()
    assertTrue(store.saved.containsKey(house))
  }

  @Test fun forgetAllForAScopeLeavesOnlyItsTombstoneAndDropsItsCounts() {
    remember()
    store.runtime.recordUsage { it.record(house, "jarvis", NOW, ZoneOffset.UTC) }
    store.runTimers()
    assertTrue(store.runtime.forgetScope(house))
    assertEquals(MemoryProfile(house, pausedMs = NOW, forgotAllMs = NOW), store.saved[house])
    assertTrue(store.usage.buckets.isEmpty())
    assertTrue(store.runtime.profile(house).facts.isEmpty())
  }

  @Test fun chipFeedbackIsWrittenWithTheNextWriteAndForgottenWithItsScope() {
    val alex = MemoryScope.Person("p1")
    store.runtime.recordFeedback { it.with(house, "screen:music", ForYouFeedback.Kind.NEVER, NOW) }
    store.runtime.recordFeedback { it.with(alex, "screen:music", ForYouFeedback.Kind.NEVER, NOW) }
    assertTrue(store.feedback.events.isEmpty())
    store.runTimers()
    assertEquals(2, store.feedback.events.size)
    assertTrue(store.runtime.forgetScope(house))
    assertEquals(listOf(alex.token), store.feedback.events.map { it.scope })
    assertTrue(store.runtime.forgetAll())
    assertTrue(store.runtime.feedback().events.isEmpty())
  }

  @Test fun dontSuggestIsWrittenAtOnce() {
    store.runtime.recordFeedback(immediate = true) { it.with(house, "app:a", ForYouFeedback.Kind.NEVER, NOW) }
    assertTrue(store.feedback.suppressed(house, "app:a"))
  }

  @Test fun everyChangeBumpsTheRevision() {
    val before = store.runtime.revision.value
    remember()
    assertEquals(before + 1, store.runtime.revision.value)
  }
}
