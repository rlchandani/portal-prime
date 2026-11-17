/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.ChoreFixtures.KITCHEN
import com.immortal.launcher.ChoreFixtures.LIVING
import com.immortal.launcher.ChoreFixtures.MONDAY
import com.immortal.launcher.ChoreFixtures.ok
import com.immortal.launcher.ChoreFixtures.refused
import com.immortal.launcher.ChoreFixtures.state
import com.immortal.launcher.ChoreFixtures.tick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ticking awards stars, waits for a parent's OK when asked, and never counts a chore twice. */
class ChoreAwardTest {
  private fun stars(s: ChoreState, kid: String, day: Int = MONDAY) = ChoreBoard.week(s, day).first { it.kid == kid }

  @Test fun tickingAwardsTheChoresStarsAtOnce() {
    val s = tick(state, "chbed", "kidmia", MONDAY, "tick0001")
    assertEquals(TickStatus.AWARDED, s.ticks.getValue("tick0001").status)
    assertEquals(2, stars(s, "kidmia").stars)
    assertEquals("2 / 6 ★ — Movie night", ChoreBoard.goalLine(stars(s, "kidmia")))
  }

  @Test fun aChoreNeedingOkWaitsThenCountsWhenApproved() {
    val day = MONDAY + 1
    val s = tick(state, "chbath", "kidleo", day, "tick0002")
    assertEquals(0, stars(s, "kidleo", day).stars)
    assertEquals(1, stars(s, "kidleo", day).pending)
    assertEquals(listOf("tick0002"), ChoreBoard.pending(s).map { it.id })
    val approved = ok(ChoreOps.approve(s, "tick0002", ChoreLimits.PHONE))
    assertEquals(1, stars(approved, "kidleo", day).stars)
    assertTrue(ChoreBoard.pending(approved).isEmpty())
    assertEquals("invalid_state", refused(ChoreOps.approve(approved, "tick0002", LIVING)))
  }

  @Test fun aDeclinedTickFreesTheChoreToTryAgain() {
    val day = MONDAY + 1
    val s = ok(ChoreOps.decline(tick(state, "chbath", "kidleo", day, "tick0003"), "tick0003", LIVING))
    assertEquals(0, stars(s, "kidleo", day).stars + stars(s, "kidleo", day).pending)
    tick(s, "chbath", "kidleo", day, "tick0004")
  }

  @Test fun theSameChoreCannotBeTickedTwiceADay() {
    val s = tick(state, "chbed", "kidmia", MONDAY, "tick0001")
    assertEquals("already_done", refused(ChoreOps.tick(s, "chbed", "kidmia", MONDAY, "tick0009", KITCHEN)))
    tick(s, "chbed", "kidmia", MONDAY + 1, "tick0010")
  }

  @Test fun tickingIsRefusedForTheWrongKidDayOrChore() {
    assertEquals("not_their_chore", refused(ChoreOps.tick(state, "chbed", "kidleo", MONDAY, "tick0001", LIVING)))
    assertEquals("not_today", refused(ChoreOps.tick(state, "chtoys", "kidmia", MONDAY + 1, "tick0001", LIVING)))
    assertEquals("unknown_chore", refused(ChoreOps.tick(state, "nochore", "kidmia", MONDAY, "tick0001", LIVING)))
    assertEquals("unknown_kid", refused(ChoreOps.tick(state, "chtoys", "nokid", MONDAY, "tick0001", LIVING)))
  }

  @Test fun aMisTapCanBeTakenBackButAParentsOkStaysUnlessTheParentTakesItBack() {
    val s = tick(state, "chbed", "kidmia", MONDAY, "tick0001")
    val undone = ok(ChoreOps.undo(s, "tick0001", LIVING))
    assertEquals(0, stars(undone, "kidmia").stars)
    val approved = ok(ChoreOps.approve(tick(state, "chbath", "kidleo", MONDAY + 1, "tick0002"), "tick0002", ChoreLimits.PHONE))
    assertEquals("invalid_state", refused(ChoreOps.undo(approved, "tick0002", LIVING)))
    ok(ChoreOps.undo(approved, "tick0002", ChoreLimits.PHONE, parent = true))
  }

  @Test fun reachingTheGoalCelebratesOnceAndOnlyForFreshStars() {
    val a = tick(state, "chbed", "kidmia", MONDAY, "tick0001")
    val b = tick(a, "chtoys", "kidmia", MONDAY, "tick0002") // 2 + 3 = 5 of 6
    val c = tick(b, "chbed", "kidmia", MONDAY + 1, "tick0003") // 7 of 6
    assertTrue(ChoreBoard.goalsReached(a, b, MONDAY + 1).isEmpty())
    assertEquals(listOf("kidmia"), ChoreBoard.goalsReached(b, c, MONDAY + 1))
    assertTrue("already reached", ChoreBoard.goalsReached(c, tick(c, "chbed", "kidmia", MONDAY + 2, "tick0004"), MONDAY + 2).isEmpty())
    // A Portal catching up on Monday's and Tuesday's ticks on Wednesday doesn't celebrate.
    assertTrue(ChoreBoard.goalsReached(state, c, MONDAY + 2).isEmpty())
    assertEquals("Mia reached 6 ★ — Movie night!", ChoresCelebration.line(stars(c, "kidmia", MONDAY + 1), "Mia"))
  }

  @Test fun aParentsOkCanReachTheGoalForAnEarlierDay() {
    val needsOk = state.copy(chores = state.chores + ("chbed" to ChoreFixtures.bed.copy(needsOk = true, stars = 5)))
    val a = tick(tick(needsOk, "chtoys", "kidmia", MONDAY, "tick0001"), "chbed", "kidmia", MONDAY, "tick0002")
    val b = ok(ChoreOps.approve(a, "tick0002", ChoreLimits.PHONE))
    assertEquals(listOf("kidmia"), ChoreBoard.goalsReached(a, b, MONDAY + 2))
  }

  @Test fun celebrationsChimeOnlyWhereTheyHappenedAndNeverInQuietHours() {
    assertTrue(ChoresCelebration.chimes(ChoreStore.Source.LOCAL, quietHours = false))
    assertTrue(ChoresCelebration.chimes(ChoreStore.Source.PHONE, quietHours = false))
    assertEquals(false, ChoresCelebration.chimes(ChoreStore.Source.PEER, quietHours = false))
    assertEquals(false, ChoresCelebration.chimes(ChoreStore.Source.LOCAL, quietHours = true))
  }

  @Test fun limitsAreEnforced() {
    var s = state
    repeat(ChoreLimits.MAX_KIDS - 2) { i ->
      s = ok(ChoreOps.saveKid(s, ChoreFixtures.leo.copy(id = "kidx$i"), LIVING))
    }
    assertEquals("too_many_kids", refused(ChoreOps.saveKid(s, ChoreFixtures.leo.copy(id = "kidmore"), LIVING)))
    ok(ChoreOps.saveKid(s, ChoreFixtures.leo.copy(name = "Leon"), LIVING)) // editing still works
    assertEquals("unknown_kid", refused(ChoreOps.saveChore(state, ChoreFixtures.bed.copy(kid = "nokid"), LIVING)))
  }

  @Test fun anEditWinsOverTheNewestCopyThePhoneSaw() {
    val edited = ok(ChoreOps.saveKid(state, ChoreFixtures.mia.copy(goal = 10), ChoreLimits.PHONE, baseSeq = 7))
    assertEquals(8L, edited.kids.getValue("kidmia").seq)
    val again = ok(ChoreOps.saveKid(edited, ChoreFixtures.mia.copy(goal = 12), LIVING))
    assertEquals(9L, again.kids.getValue("kidmia").seq)
  }
}
