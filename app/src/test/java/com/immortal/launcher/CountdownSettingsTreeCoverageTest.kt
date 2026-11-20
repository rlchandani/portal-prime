/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Countdown chips' phrase and legacy lenient dates; the Settings page tree's lookups and ancestry. */
class CountdownSettingsTreeCoverageTest {
  private val utc = TimeZone.getTimeZone("UTC")
  private fun day(y: Int, m: Int, d: Int, zone: TimeZone = utc): Calendar =
      Calendar.getInstance(zone).apply { clear(); set(y, m - 1, d, 15, 30) }
  private fun event(month: Int, day: Int, year: Int = 0) = CountdownConfig.Event(1, "E", "📅", month, day, year)

  @Test fun phraseForTodayTomorrowYesterdayFutureAndPast() {
    val now = day(2026, 3, 8)
    assertEquals("today", event(3, 8).phrase(now))
    assertEquals("tomorrow", event(3, 9).phrase(now))
    assertEquals("yesterday", event(3, 7, 2026).phrase(now))
    assertEquals("in 5 days", event(3, 13).phrase(now))
    assertEquals("10 days ago", event(2, 26, 2026).phrase(now))
    // A yearly date that already passed this year counts to next year.
    assertEquals("in 364 days", event(3, 7).phrase(now))
  }

  @Test fun anInvalidYearlyLegacyDateRollsOverLeniently() {
    // "30 February" stored by an old build reads as 2 March.
    assertEquals(29, event(2, 30).daysUntil(day(2026, 2, 1)))
    // "31 April" (→ 1 May) already passed this year: next year's.
    assertEquals(334, event(4, 31).daysUntil(day(2026, 6, 1)))
  }

  @Test fun anInvalidOneOffLegacyDateCanBeInThePast() {
    val past = event(2, 30, 2025) // → 2 March 2025
    assertEquals(-336, past.daysUntil(day(2026, 2, 1)))
    assertEquals("336 days ago", past.phrase(day(2026, 2, 1)))
  }

  @Test fun pageLookupsRejectNullAndUnknownIds() {
    assertNull(SettingsTree.page(null))
    assertNull(SettingsTree.page("nope"))
    assertFalse(SettingsTree.contains(null))
    assertFalse(SettingsTree.contains("nope"))
    assertTrue(SettingsTree.contains(SettingsPageIds.CALLS))
    assertEquals(SettingsPageIds.CALLS, SettingsTree.page(SettingsPageIds.CALLS)!!.id)
  }

  @Test fun chainRootAndAdvancedFollowParents() {
    assertEquals(emptyList<String>(), SettingsTree.chainOf("nope"))
    assertEquals(SettingsPageIds.DEFAULT, SettingsTree.rootOf("nope"))
    assertEquals(SettingsPageIds.FRAME, SettingsTree.chainOf(SettingsPageIds.DISMISS).first())
    assertEquals(SettingsPageIds.DISMISS, SettingsTree.chainOf(SettingsPageIds.DISMISS).last())
    assertEquals(SettingsPageIds.FRAME, SettingsTree.rootOf(SettingsPageIds.DISMISS))
    assertEquals(SettingsPageIds.DISPLAY, SettingsTree.rootOf(SettingsPageIds.DISPLAY))
    assertTrue("inherits Advanced from its parent", SettingsTree.needsAdvanced(SettingsPageIds.BOOT))
    assertFalse(SettingsTree.needsAdvanced(SettingsPageIds.DISMISS))
    assertFalse(SettingsTree.needsAdvanced("nope"))
  }

  @Test fun everyPageChainEndsAtATopLevelPageAndGroupsPartitionTheTopLevel() {
    val top = SettingsTree.topLevel.map { it.id }.toSet()
    assertTrue(SettingsTree.topLevel.all { it.parent == null })
    for (p in SettingsTree.pages) {
      val chain = SettingsTree.chainOf(p.id)
      assertEquals(p.id, chain.last())
      assertTrue(p.id, chain.first() in top)
      assertEquals(chain.size, chain.toSet().size)
    }
    val grouped = SettingsGroup.entries.flatMap { SettingsTree.groupPages(it) }
    assertEquals(grouped.size, grouped.toSet().size)
    assertTrue(grouped.all { it.group != null })
  }
}
