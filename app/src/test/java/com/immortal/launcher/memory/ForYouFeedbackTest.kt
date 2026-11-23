/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import com.immortal.launcher.memory.ForYouFeedback.Kind
import com.immortal.launcher.memory.MemoryFixtures.DAY
import com.immortal.launcher.memory.MemoryFixtures.NOW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What people did with the chips: deduplicated, kept only as long as it matters, sealed as counts. */
class ForYouFeedbackTest {
  private val house = MemoryScope.Household
  private val alex = MemoryScope.Person("p1")

  @Test fun aChipShownAllHourIsOneImpression() {
    val f = ForYouFeedback().with(house, "app:a", Kind.SHOWN, NOW).with(house, "app:a", Kind.SHOWN, NOW + 60_000)
    assertEquals(1, f.events.size)
    assertEquals(ForYouFeedback.FATIGUE, f.factor(house, "app:a", NOW + DAY), 1e-9)
  }

  @Test fun impressionsAndNotNowTireForAWeekOnly() {
    val f = ForYouFeedback().with(house, "app:a", Kind.SHOWN, NOW).with(house, "app:a", Kind.NOT_NOW, NOW)
    assertEquals(ForYouFeedback.FATIGUE * ForYouFeedback.FATIGUE, f.factor(house, "app:a", NOW + DAY), 1e-9)
    assertEquals(1.0, f.factor(house, "app:a", NOW + 8 * DAY), 1e-9)
    assertTrue(f.pruned(NOW + 8 * DAY).events.isEmpty())
  }

  @Test fun showLessOftenLastsNinetyDays() {
    val f = ForYouFeedback().with(house, "app:a", Kind.LESS_OFTEN, NOW)
    assertEquals(ForYouFeedback.LESS_OFTEN, f.factor(house, "app:a", NOW + 89 * DAY), 1e-9)
    assertEquals(1.0, f.factor(house, "app:a", NOW + 91 * DAY), 1e-9)
  }

  @Test fun dontSuggestStaysUntilUndone() {
    val f = ForYouFeedback().with(house, "app:a", Kind.NEVER, NOW)
    assertTrue(f.pruned(NOW + 365 * DAY).suppressed(house, "app:a"))
    assertFalse(f.suppressed(alex, "app:a"))
    assertFalse(f.allowAgain(house, "app:a").suppressed(house, "app:a"))
  }

  @Test fun notNowSnoozesForTwoHours() {
    val f = ForYouFeedback().with(house, "app:a", Kind.NOT_NOW, NOW)
    assertTrue(f.snoozed(house, "app:a", NOW + ForYouFeedback.SNOOZE_MS - 1))
    assertFalse(f.snoozed(house, "app:a", NOW + ForYouFeedback.SNOOZE_MS))
  }

  @Test fun forgetAllForOneScopeLeavesTheOthers() {
    val f = ForYouFeedback().with(house, "app:a", Kind.NEVER, NOW).with(alex, "app:a", Kind.NEVER, NOW)
    assertEquals(listOf(alex.token), f.without(house).events.map { it.scope })
  }

  @Test fun theLogIsCapped() {
    val f = (0 until ForYouFeedback.MAX_EVENTS + 50).fold(ForYouFeedback()) { acc, i ->
      acc.with(house, "app:a$i", Kind.SHOWN, NOW)
    }
    assertEquals(ForYouFeedback.MAX_EVENTS, f.events.size)
  }

  @Test fun roundTripsAndDropsAnythingUnreadable() {
    val f = ForYouFeedback().with(house, "screen:music", Kind.NEVER, NOW).with(alex, "app:a", Kind.SHOWN, NOW)
    assertEquals(f, ForYouFeedback.decode(f.encode()))
    assertEquals(ForYouFeedback(), ForYouFeedback.decode("nope"))
    assertEquals(ForYouFeedback(), ForYouFeedback.decode(null))
    val bad = """{"v":1,"e":[["household","app:a","s",1],["evil scope","app:a","s",1],["household","BAD SURFACE","s",1],""" +
        """["household","app:a","?",1]]}"""
    assertEquals(1, ForYouFeedback.decode(bad).events.size)
  }

  @Test fun anUnknownSurfaceIsIgnored() {
    assertEquals(ForYouFeedback(), ForYouFeedback().with(house, "Not A Surface!", Kind.SHOWN, NOW))
  }
}
