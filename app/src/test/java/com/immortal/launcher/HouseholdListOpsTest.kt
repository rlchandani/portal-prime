/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.HouseholdListOps.Outcome
import org.junit.Assert.*
import org.junit.Test

class HouseholdListOpsTest {
  private val fa = "Family Area"
  private val empty = HouseholdListState()

  @Test fun add_cleansText_andPutsNewItemsOnTop() {
    val one = HouseholdListOps.add(empty, "  Dish   soap ", fa, 1_000).state
    val two = HouseholdListOps.add(one, "Eggs", fa, 2_000).state
    assertEquals(listOf("Eggs", "Dish soap"), two.open().map { it.text.value })
  }

  @Test fun add_rejectsBlankAndTooLong() {
    assertEquals(Outcome.Invalid, HouseholdListOps.add(empty, "   ", fa, 1).outcome)
    assertEquals(Outcome.Invalid, HouseholdListOps.add(empty, "x".repeat(61), fa, 1).outcome)
  }

  @Test fun add_ofAnOpenItem_isADuplicate_andOfADoneItem_movesItBack() {
    val s = HouseholdListOps.add(empty, "Eggs", fa, 1_000).state
    val dup = HouseholdListOps.add(s, "eggs", fa, 1_500)
    assertTrue(dup.outcome is Outcome.Duplicate)
    assertSame(s, dup.state)
    val done = HouseholdListOps.setDone(s, HouseholdListText.idFor("Eggs"), true, fa, 2_000).state
    val back = HouseholdListOps.add(done, "Eggs", fa, 3_000)
    assertTrue(back.outcome is Outcome.MovedBack)
    assertEquals(listOf("Eggs"), back.state.open().map { it.text.value })
  }

  @Test fun add_ofAnOldNameNowUsedByARenamedItem_getsItsOwnId() {
    val s = HouseholdListOps.add(empty, "Bananas", fa, 1_000).state
    val renamed = HouseholdListOps.rename(s, HouseholdListText.idFor("Bananas"), "Plantains", fa, 2_000).state
    val again = HouseholdListOps.add(renamed, "Bananas", fa, 3_000).state
    assertEquals(setOf("Bananas", "Plantains"), again.open().map { it.text.value }.toSet())
  }

  @Test fun localEdits_winAgainstAClockThatIsBehind() {
    val s = HouseholdListOps.add(empty, "Tea", fa, 10_000).state
    val id = HouseholdListText.idFor("Tea")
    val ticked = HouseholdListOps.setDone(s, id, true, fa, 5_000, local = true).state
    assertTrue(ticked.items.getValue(id).done.value)
  }

  @Test fun missingOrRemovedIds_areReported() {
    assertEquals(Outcome.Missing, HouseholdListOps.setDone(empty, "h000000000000000", true, fa, 1).outcome)
    assertEquals(Outcome.Missing, HouseholdListOps.remove(empty, "h000000000000000", fa, 1).outcome)
  }

  @Test fun moveToTop_ordersBeforeTheFirstOpenItem() {
    var s = HouseholdListOps.add(empty, "A", fa, 1).state
    s = HouseholdListOps.add(s, "B", fa, 2).state
    s = HouseholdListOps.add(s, "C", fa, 3).state
    val moved = HouseholdListOps.moveToTop(s, HouseholdListText.idFor("A"), fa, 4).state
    assertEquals(listOf("A", "C", "B"), moved.open().map { it.text.value })
  }

  @Test fun rename_rejectsEmpty_andKeepsTheSameNameAsNoChange() {
    val s = HouseholdListOps.add(empty, "Milk", fa, 1).state
    val id = HouseholdListText.idFor("Milk")
    assertEquals(Outcome.Invalid, HouseholdListOps.rename(s, id, " ", fa, 2).outcome)
    assertSame(s, HouseholdListOps.rename(s, id, "Milk", fa, 2).state)
  }

  @Test fun prune_dropsOldTombstones_only() {
    var s = HouseholdListOps.add(empty, "Old", fa, 1_000).state
    s = HouseholdListOps.add(s, "Keep", fa, 1_000).state
    s = HouseholdListOps.remove(s, HouseholdListText.idFor("Old"), fa, 2_000).state
    val pruned = HouseholdListOps.prune(s, nowMs = 2_000 + HouseholdListOps.TOMBSTONE_TTL_MS + 1)
    assertEquals(setOf(HouseholdListText.idFor("Keep")), pruned.items.keys)
    assertEquals(2, HouseholdListOps.prune(s, nowMs = 3_000).items.size)
  }

  @Test fun usedBefore_offersRecentlyClearedThings_notWhatIsOpen() {
    var s = HouseholdListOps.add(empty, "Milk", fa, 1_000).state
    s = HouseholdListOps.add(s, "Bread", fa, 1_001).state
    s = HouseholdListOps.add(s, "Eggs", fa, 1_002).state
    s = HouseholdListOps.remove(s, HouseholdListText.idFor("Milk"), fa, 2_000).state
    s = HouseholdListOps.setDone(s, HouseholdListText.idFor("Bread"), true, fa, 3_000).state
    assertEquals(listOf("Bread", "Milk"), HouseholdListOps.usedBefore(s, limit = 6))
  }
}
