/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import com.immortal.launcher.memory.MemoryFixtures.DAY
import com.immortal.launcher.memory.MemoryFixtures.NOW
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What "What Jarvis knows" shows (mock screen 2): provenance, still-learning, categories, never forgotten keys. */
class KnowsRowsTest {
  private val house = MemoryProfile(MemoryScope.Household)

  private fun learn(p: MemoryProfile, conv: String, at: Long, vararg said: String) =
      MemoryLearner.learn(p, MemoryFixtures.observe(conv, *said), conv, "Kitchen", at).profile

  private fun rows(p: MemoryProfile, now: Long = NOW) = KnowsRows.rows(p, now, ZoneOffset.UTC)

  @Test fun twoConversationsReadAsLearnedFromTwoConversations() {
    val p = learn(learn(house, "c1", NOW - 2 * DAY, "Warriors game?"), "c2", NOW - DAY, "Warriors score?")
    val row = rows(p).single()
    assertEquals("Golden State Warriors", row.title)
    assertFalse(row.tentative)
    assertEquals(KnowsRows.Category.TEAMS, row.category)
    assertEquals("Learned from 2 conversations · last mentioned yesterday", row.detail)
    assertEquals("GSW", row.badge)
  }

  @Test fun aFirstMentionTodayIsDashedAndNew() {
    val row = rows(learn(house, "c1", NOW, "How is AMD stock?")).single()
    assertTrue(row.tentative)
    assertTrue(row.fresh)
    assertEquals("Learned from today's chat · new today · still learning", row.detail)
    assertEquals("AMD", row.badge)
    assertEquals(KnowsRows.Category.MARKETS, row.category)
  }

  @Test fun forgottenItemsAreNeverListedAndConfirmedOnesSayso() {
    val p = learn(house, "c1", NOW, "Keep an eye on AMD", "I follow the Warriors")
    val forgot = MemoryLearner.forget(p, setOf("ticker:AMD"), NOW)
    val kept = MemoryLearner.keep(forgot, "team:nba.gsw", "Kitchen", NOW)
    val rows = rows(kept)
    assertEquals(listOf("team:nba.gsw"), rows.map { it.id })
    assertTrue(rows.single().detail.startsWith("You confirmed it"))
    assertEquals(4, rows.single().bars)
    assertEquals(1, KnowsRows.count(rows, KnowsRows.Category.EVERYTHING))
    assertEquals(0, KnowsRows.count(rows, KnowsRows.Category.MARKETS))
  }
}
