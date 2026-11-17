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
import com.immortal.launcher.ChoreFixtures.state
import com.immortal.launcher.ChoreFixtures.tick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replicas converge whatever order exchanges happen in: LWW by seq, tombstones, per-tick ids. */
class ChoreMergeTest {
  private fun assertLaws(a: ChoreState, b: ChoreState) {
    val ab = ChoreMerge.merge(a, b)
    assertEquals("commutative", ab, ChoreMerge.merge(b, a))
    assertEquals("idempotent", ab, ChoreMerge.merge(ab, b))
    assertEquals("idempotent", ab, ChoreMerge.merge(a, ab))
  }

  @Test fun theHigherSeqEditWins() {
    val living = ok(ChoreOps.saveKid(state, ChoreFixtures.mia.copy(goal = 10), LIVING))
    val kitchen = ok(ChoreOps.saveKid(living, ChoreFixtures.mia.copy(goal = 15), KITCHEN))
    assertEquals(15, ChoreMerge.merge(living, kitchen).kids.getValue("kidmia").goal)
    assertLaws(living, kitchen)
  }

  @Test fun concurrentEditsAtTheSameSeqAgreeOnOneWinner() {
    val living = ok(ChoreOps.saveKid(state, ChoreFixtures.mia.copy(goal = 10), LIVING))
    val kitchen = ok(ChoreOps.saveKid(state, ChoreFixtures.mia.copy(goal = 15), KITCHEN))
    val merged = ChoreMerge.merge(living, kitchen)
    assertEquals("device id breaks the tie", 10, merged.kids.getValue("kidmia").goal) // "living-room" > "kitchen"
    assertLaws(living, kitchen)
  }

  @Test fun aDeletionBeatsAConcurrentEditAndStaysDeleted() {
    val deleted = ok(ChoreOps.deleteChore(state, "chtoys", LIVING))
    val edited = ok(ChoreOps.saveChore(state, ChoreFixtures.toys.copy(stars = 5), KITCHEN))
    val merged = ChoreMerge.merge(deleted, edited)
    assertTrue(merged.chores.getValue("chtoys").deleted)
    assertTrue("a late old copy can't revive it", ChoreMerge.merge(merged, state).chores.getValue("chtoys").deleted)
    assertLaws(deleted, edited)
  }

  @Test fun anEditAfterSeeingTheDeleteRestoresIt() {
    val deleted = ok(ChoreOps.deleteChore(state, "chtoys", LIVING))
    val restored = ok(ChoreOps.saveChore(deleted, ChoreFixtures.toys, ChoreLimits.PHONE))
    assertTrue(!ChoreMerge.merge(deleted, restored).chores.getValue("chtoys").deleted)
  }

  @Test fun twoPortalsTickingTheSameChoreAwardStarsOnce() {
    val living = tick(state, "chbed", "kidmia", MONDAY, "aaaa0001", LIVING)
    val kitchen = tick(state, "chbed", "kidmia", MONDAY, "bbbb0001", KITCHEN)
    val merged = ChoreMerge.merge(living, kitchen)
    assertEquals(2, merged.ticks.size)
    assertEquals(2, ChoreBoard.week(merged, MONDAY).first { it.kid == "kidmia" }.stars)
    assertEquals("aaaa0001", ChoreBoard.holders(merged).values.single().id)
    assertLaws(living, kitchen)
  }

  @Test fun ticksFromBothRoomsAddUp() {
    val living = tick(state, "chbed", "kidmia", MONDAY, "aaaa0001", LIVING)
    val kitchen = tick(state, "chtoys", "kidmia", MONDAY, "bbbb0001", KITCHEN)
    assertEquals(5, ChoreBoard.week(ChoreMerge.merge(living, kitchen), MONDAY).first { it.kid == "kidmia" }.stars)
  }

  @Test fun anUndoOrDeclineBeatsAConcurrentApprovalAtTheSameSeq() {
    val waiting = tick(state, "chbath", "kidleo", MONDAY + 1, "tick0001")
    val approved = ok(ChoreOps.approve(waiting, "tick0001", ChoreLimits.PHONE))
    val undone = ok(ChoreOps.undo(waiting, "tick0001", LIVING))
    assertEquals(TickStatus.UNDONE, ChoreMerge.merge(approved, undone).ticks.getValue("tick0001").status)
    assertLaws(approved, undone)
  }

  @Test fun mergeAddsEntriesOnlyOneSideHas() {
    val newKid = ChoreFixtures.leo.copy(id = "kidzoe", name = "Zoe")
    val a = ok(ChoreOps.saveKid(state, newKid, LIVING))
    val b = tick(state, "chtoys", "kidleo", MONDAY, "tick0001")
    val merged = ChoreMerge.merge(a, b)
    assertTrue("kidzoe" in merged.kids && "tick0001" in merged.ticks)
    assertLaws(a, b)
  }
}
