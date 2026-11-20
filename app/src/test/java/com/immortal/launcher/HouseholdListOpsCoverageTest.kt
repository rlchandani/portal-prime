/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.HouseholdListOps.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Edit rules of the shared list: local clocks, tombstones, ids, caps and no-op edits. */
class HouseholdListOpsCoverageTest {
  private val now = 1_800_000_000_000L
  private val a = "Kitchen"
  private val b = "Den"

  private fun add(s: HouseholdListState, text: String, at: Long = now, by: String = a, local: Boolean = false) =
      HouseholdListOps.add(s, text, by, at, local)

  private fun withMilk(): Pair<HouseholdListState, String> {
    val r = add(HouseholdListState(), "Milk")
    return r.state to (r.outcome as Outcome.Applied).id
  }

  @Test fun addRejectsBlankAndOverlongText() {
    assertEquals(Outcome.Invalid, add(HouseholdListState(), "   ").outcome)
    assertEquals(Outcome.Invalid, add(HouseholdListState(), "x".repeat(HouseholdListText.MAX_TEXT + 1)).outcome)
  }

  @Test fun addDuplicateOpenItemIsReportedAndNotChanged() {
    val (s, id) = withMilk()
    val r = add(s, "  MILK ", at = now + 5)
    assertEquals(Outcome.Duplicate(id), r.outcome)
    assertSame(s, r.state)
  }

  @Test fun addingADoneItemMovesItBack() {
    val (s, id) = withMilk()
    val done = HouseholdListOps.setDone(s, id, true, a, now + 1).state
    val r = add(done, "milk", at = now + 2, by = b)
    assertEquals(Outcome.MovedBack(id), r.outcome)
    assertFalse(r.state.items.getValue(id).done.value)
    assertEquals(b, r.state.items.getValue(id).done.stamp.by)
  }

  @Test fun addCanStartDoneAndRecordsMigrationSources() {
    val r = HouseholdListOps.add(HouseholdListState(), "Eggs", a, now, migratedFrom = setOf("todo"), done = true)
    val item = r.state.items.getValue((r.outcome as Outcome.Applied).id)
    assertTrue(item.done.value)
    assertEquals(setOf("todo"), item.migratedFrom)
  }

  @Test fun reAddingARemovedItemReusesItsIdAndClearsTheTombstoneWithALaterLocalStamp() {
    val (s, id) = withMilk()
    val removed = HouseholdListOps.remove(s, id, a, now + 10).state
    assertFalse(removed.items.getValue(id).alive)
    val again = add(removed, "Milk", at = now, local = true, by = b)
    val item = again.state.items.getValue(id)
    assertEquals(Outcome.Applied(id), again.outcome)
    assertTrue(item.alive)
    assertEquals("local clock never goes backwards", now + 1, item.added.at)
    assertEquals(false, item.removal!!.removed)
    assertEquals(now, item.text.baseAt)
  }

  @Test fun aRenamedItemHoldingTheTextsIdForcesASuffixedId() {
    val (s, bananas) = add(HouseholdListState(), "Bananas").let { it.state to (it.outcome as Outcome.Applied).id }
    val renamed = HouseholdListOps.rename(s, bananas, "Plantains", a, now + 1).state
    val r = add(renamed, "Bananas", at = now + 2)
    val newId = (r.outcome as Outcome.Applied).id
    assertNotEquals(bananas, newId)
    assertEquals(HouseholdListText.idFor("bananas#1"), newId)
    assertEquals(2, r.state.live().size)
  }

  @Test fun theItemCapRefusesNewItemsButNotRevivals() {
    var s = HouseholdListState()
    repeat(HouseholdListOps.MAX_ITEMS) { s = add(s, "item $it").state }
    assertEquals(Outcome.Invalid, add(s, "one too many").outcome)
    val id = s.liveByText("item 0")!!.id
    val removed = HouseholdListOps.remove(s, id, a, now + 1).state
    assertEquals(Outcome.Applied(id), add(removed, "item 0", at = now + 2).outcome)
  }

  @Test fun newItemsGoOnTop() {
    val (s, milk) = withMilk()
    val r = add(s, "Bread", at = now + 1)
    val bread = (r.outcome as Outcome.Applied).id
    assertEquals(listOf(bread, milk), r.state.open().map { it.id })
  }

  @Test fun editsOfMissingOrRemovedItemsAreMissing() {
    val (s, id) = withMilk()
    val removed = HouseholdListOps.remove(s, id, a, now + 1).state
    assertEquals(Outcome.Missing, HouseholdListOps.setDone(removed, id, true, a, now + 2).outcome)
    assertEquals(Outcome.Missing, HouseholdListOps.rename(removed, id, "Oat milk", a, now + 2).outcome)
    assertEquals(Outcome.Missing, HouseholdListOps.useAlt(removed, id, a, now + 2).outcome)
    assertEquals(Outcome.Missing, HouseholdListOps.keepName(removed, id, a, now + 2).outcome)
    assertEquals(Outcome.Missing, HouseholdListOps.remove(s, "hffffffffffffff0", a, now).outcome)
    assertEquals(Outcome.Missing, HouseholdListOps.restore(s, "hffffffffffffff0", a, now).outcome)
  }

  @Test fun renameRejectsInvalidAndIgnoresSameText() {
    val (s, id) = withMilk()
    assertEquals(Outcome.Invalid, HouseholdListOps.rename(s, id, "\t", a, now + 1).outcome)
    val same = HouseholdListOps.rename(s, id, "Milk", a, now + 1)
    assertEquals(Outcome.Applied(id), same.outcome)
    assertSame(s, same.state)
  }

  @Test fun useAltAndKeepNameWithoutAnAltAreNoOps() {
    val (s, id) = withMilk()
    assertSame(s, HouseholdListOps.useAlt(s, id, a, now + 1).state)
    assertSame(s, HouseholdListOps.keepName(s, id, a, now + 1).state)
  }

  @Test fun useAltAdoptsTheOtherNameAndKeepNameClearsTheNote() {
    val (s, id) = withMilk()
    val item = s.items.getValue(id)
    val withAlt = HouseholdListState(s.items + (id to item.copy(alt = AltText("Oat milk", b, now + 5))))
    val used = HouseholdListOps.useAlt(withAlt, id, a, now + 6).state.items.getValue(id)
    assertEquals("Oat milk", used.text.value)
    assertEquals(null, used.alt)
    assertEquals(now + 5, used.text.baseAt)
    val kept = HouseholdListOps.keepName(withAlt, id, a, now + 6).state.items.getValue(id)
    assertEquals("Milk", kept.text.value)
    assertEquals(null, kept.alt)
  }

  @Test fun restoreOfALiveItemIsANoOpAndOfARemovedOneRevivesIt() {
    val (s, id) = withMilk()
    assertSame(s, HouseholdListOps.restore(s, id, a, now + 1).state)
    val removed = HouseholdListOps.remove(s, id, a, now + 5).state
    val back = HouseholdListOps.restore(removed, id, b, now + 1, local = true).state.items.getValue(id)
    assertTrue(back.alive)
    assertEquals(now + 6, back.removal!!.stamp.at)
  }

  @Test fun moveRejectsNonFiniteAndSkipsSameOrder() {
    val (s, id) = withMilk()
    assertEquals(Outcome.Invalid, HouseholdListOps.move(s, id, Double.NaN, a, now).outcome)
    assertEquals(Outcome.Invalid, HouseholdListOps.move(s, id, Double.POSITIVE_INFINITY, a, now).outcome)
    val order = s.items.getValue(id).order.value
    assertSame(s, HouseholdListOps.move(s, id, order, a, now + 1).state)
    assertEquals(5.0, HouseholdListOps.move(s, id, 5.0, a, now + 1).state.items.getValue(id).order.value, 0.0)
  }

  @Test fun moveToTopOnEmptyListIsMissingAndOnTheTopItemIsANoOp() {
    assertEquals(Outcome.Missing, HouseholdListOps.moveToTop(HouseholdListState(), "h000000000000000", a, now).outcome)
    val (s, milk) = withMilk()
    val two = add(s, "Bread", at = now + 1).state
    val bread = two.liveByText("Bread")!!.id
    assertSame(two, HouseholdListOps.moveToTop(two, bread, a, now + 2).state)
    val moved = HouseholdListOps.moveToTop(two, milk, a, now + 2).state
    assertEquals(milk, moved.open().first().id)
  }

  @Test fun clearDoneOnlyRemovesLiveDoneItems() {
    val (s, milk) = withMilk()
    assertEquals(Outcome.Missing, HouseholdListOps.clearDone(s, listOf(milk, "nope"), a, now + 1).outcome)
    val done = HouseholdListOps.setDone(s, milk, true, a, now + 1).state
    val r = HouseholdListOps.clearDone(done, listOf("nope", milk), a, now + 2)
    assertEquals(Outcome.Applied(milk), r.outcome)
    assertFalse(r.state.items.getValue(milk).alive)
  }

  @Test fun pruneForgetsOnlyTombstonesPastTheTtl() {
    val (s, id) = withMilk()
    assertSame(s, HouseholdListOps.prune(s, now + 10 * HouseholdListOps.TOMBSTONE_TTL_MS))
    val removed = HouseholdListOps.remove(s, id, a, now + 1).state
    assertSame(removed, HouseholdListOps.prune(removed, now + 1 + HouseholdListOps.TOMBSTONE_TTL_MS))
    assertTrue(HouseholdListOps.prune(removed, now + 2 + HouseholdListOps.TOMBSTONE_TTL_MS).items.isEmpty())
  }

  @Test fun usedBeforeListsDoneAndRemovedTextsNewestFirstWithoutOpenOnes() {
    var s = HouseholdListState()
    for ((i, t) in listOf("Milk", "Bread", "Eggs", "Jam").withIndex()) s = add(s, t, at = now + i).state
    fun id(t: String) = s.liveByText(t)!!.id
    s = HouseholdListOps.setDone(s, id("Milk"), true, a, now + 10).state
    s = HouseholdListOps.remove(s, id("Bread"), a, now + 20).state
    s = HouseholdListOps.setDone(s, id("Eggs"), true, a, now + 30).state
    assertEquals(listOf("Eggs", "Bread", "Milk"), HouseholdListOps.usedBefore(s))
    assertEquals(listOf("Eggs"), HouseholdListOps.usedBefore(s, limit = 1))
    val readded = add(s, "bread", at = now + 40).state
    assertEquals(listOf("Eggs", "Milk"), HouseholdListOps.usedBefore(readded))
  }
}
