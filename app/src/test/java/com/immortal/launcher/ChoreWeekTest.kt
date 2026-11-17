/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.ChoreFixtures.MONDAY
import com.immortal.launcher.ChoreFixtures.state
import com.immortal.launcher.ChoreFixtures.tick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stars reset every Monday 00:00 local; the last four weeks stay as history; old ticks go. */
class ChoreWeekTest {
  private fun week(s: ChoreState, day: Int, kid: String = "kidmia") = ChoreBoard.week(s, day).first { it.kid == kid }

  @Test fun mondayStartsAFreshWeek() {
    val sunday = MONDAY + 6
    val s = tick(tick(state, "chbed", "kidmia", MONDAY, "tick0001"), "chbed", "kidmia", sunday, "tick0002")
    assertEquals(4, week(s, sunday).stars)
    assertEquals(0, week(s, sunday + 1).stars)
    assertEquals(4, ChoreBoard.history(s, sunday + 1).first().first { it.kid == "kidmia" }.stars)
  }

  @Test fun historyHoldsTheFourWeeksBeforeThisOneNewestFirst() {
    var s = state
    (0 until 5).forEach { w -> s = tick(s, "chbed", "kidmia", MONDAY + w * 7, "tickw00$w") }
    val today = MONDAY + 4 * 7 + 2
    val history = ChoreBoard.history(s, today)
    assertEquals(ChoreLimits.HISTORY_WEEKS, history.size)
    assertEquals(listOf(MONDAY + 21, MONDAY + 14, MONDAY + 7, MONDAY), history.map { it.first().start })
    assertTrue(history.all { weeks -> weeks.first { it.kid == "kidmia" }.stars == 2 })
    assertFalse(history.first().first { it.kid == "kidmia" }.reached)
  }

  @Test fun pruneDropsTicksOlderThanTheHistory() {
    var s = state
    (0 until 6).forEach { w -> s = tick(s, "chbed", "kidmia", MONDAY + w * 7, "tickw00$w") }
    val today = MONDAY + 5 * 7
    val pruned = ChoreOps.prune(s, today)
    assertEquals(setOf("tickw001", "tickw002", "tickw003", "tickw004", "tickw005"), pruned.ticks.keys)
    assertSame("nothing to prune returns the same state", pruned, ChoreOps.prune(pruned, today))
  }

  @Test fun pruneBoundsTombstones() {
    var s = state
    repeat(ChoreLimits.MAX_TOMBSTONES + 5) { i ->
      val c = ChoreFixtures.bed.copy(id = "chx%03d".format(i), seq = (i + 1).toLong(), deleted = true)
      s = s.copy(chores = s.chores + (c.id to c))
    }
    val pruned = ChoreOps.prune(s, MONDAY)
    assertEquals(ChoreLimits.MAX_TOMBSTONES, pruned.chores.values.count { it.deleted })
    assertEquals(3, pruned.chores.values.count { !it.deleted })
  }

  @Test fun goalLineAndProgress() {
    val w = ChoreBoard.Week("kidmia", MONDAY, stars = 12, pending = 0, goal = 20, reward = "Movie night")
    assertEquals("12 / 20 ★ — Movie night", ChoreBoard.goalLine(w))
    assertEquals(0.6f, ChoreBoard.progress(w), 0.0001f)
    assertEquals("12 / 20 ★", ChoreBoard.goalLine(w.copy(reward = "")))
    assertEquals("12 ★ this week", ChoreBoard.goalLine(w.copy(goal = 0)))
    assertEquals(1f, ChoreBoard.progress(w.copy(stars = 30)), 0f)
    assertTrue(w.copy(stars = 20).reached)
  }
}
