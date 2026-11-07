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

class HouseholdListMergeTest {
  private val fa = "Family Area"
  private val la = "Living Area"
  private val ph = "Rohit’s Pixel"
  private val empty = HouseholdListState()

  private fun add(s: HouseholdListState, text: String, by: String, at: Long) = HouseholdListOps.add(s, text, by, at).state
  private fun idOf(s: HouseholdListState, text: String) = s.liveByText(text)!!.id

  @Test fun idRule_matchesToolkitKnownAnswers() {
    assertEquals("h213e23d0419e211", HouseholdListText.idFor("Milk"))
    assertEquals("h7a2c4d4f5f96886", HouseholdListText.idFor("eggs"))
    assertEquals("hd2af6bed4b1b915", HouseholdListText.idFor("Paper  Towels "))
  }

  @Test fun merge_isCommutativeIdempotentAndConverges() {
    val base = add(add(empty, "Eggs", fa, 1_000), "Bread", fa, 1_100)
    val a = HouseholdListOps.setDone(add(base, "Milk", fa, 2_000), idOf(base, "Eggs"), true, fa, 2_100).state
    val b = HouseholdListOps.rename(add(base, "Yogurt", ph, 2_050), idOf(base, "Bread"), "Sourdough", ph, 2_200).state
    val ab = HouseholdListMerge.merge(a, b)
    val ba = HouseholdListMerge.merge(b, a)
    assertEquals(ab, ba)
    assertEquals(ab, HouseholdListMerge.merge(ab, a))
    assertEquals(ab.digest, ba.digest)
    assertEquals(setOf("Milk", "Yogurt", "Sourdough"), ab.open().map { it.text.value }.toSet())
    assertEquals(listOf("Eggs"), ab.done().map { it.text.value })
  }

  @Test fun sameTextAddedInTwoPlaces_isOneItem() {
    val a = add(empty, "Milk", fa, 1_000)
    val b = add(empty, " milk ", ph, 1_200)
    val merged = HouseholdListMerge.merge(a, b)
    assertEquals(1, merged.live().size)
  }

  @Test fun removeVsConcurrentUntick_keepsTheItemWithAReason() {
    val start = HouseholdListOps.setDone(add(empty, "Apples", fa, 1_000), HouseholdListText.idFor("Apples"), true, ph, 1_100).state
    val id = idOf(start, "Apples")
    val livingClears = HouseholdListOps.clearDone(start, listOf(id), la, 5_000).state
    val phoneUnticks = HouseholdListOps.setDone(start, id, false, ph, 3_000).state // earlier wall time, never saw the clear
    val merged = HouseholdListMerge.merge(livingClears, phoneUnticks)
    val apples = merged.items.getValue(id)
    assertTrue(apples.alive)
    assertFalse(apples.done.value)
    assertEquals("Kept: Rohit’s Pixel put it back on the list while Living Area removed it.",
        HouseholdListNotes.note(apples)?.text)
    assertEquals(merged, HouseholdListMerge.merge(phoneUnticks, livingClears))
  }

  @Test fun removeAfterSeeingTheChange_wins() {
    val start = add(empty, "Coffee", fa, 1_000)
    val id = idOf(start, "Coffee")
    val ticked = HouseholdListOps.setDone(start, id, true, ph, 2_000).state
    val removed = HouseholdListOps.remove(ticked, id, la, 3_000).state // Living Area saw the tick
    val merged = HouseholdListMerge.merge(ticked, removed)
    assertFalse(merged.items.getValue(id).alive)
    assertTrue(merged.live().isEmpty())
  }

  @Test fun reorderDoesNotResurrectARemovedItem() {
    val start = add(empty, "Rice", fa, 1_000)
    val id = idOf(start, "Rice")
    val moved = HouseholdListOps.move(start, id, -5.0, ph, 4_000).state
    val removed = HouseholdListOps.remove(start, id, la, 3_000).state
    assertFalse(HouseholdListMerge.merge(moved, removed).items.getValue(id).alive)
  }

  @Test fun concurrentRenames_lastEditWinsWithTheOtherNameOffered() {
    val start = add(empty, "Bananas", fa, 1_000)
    val id = idOf(start, "Bananas")
    val family = HouseholdListOps.rename(start, id, "Bananas 6", fa, 2_000).state
    val phone = HouseholdListOps.rename(start, id, "Plantains", ph, 3_000).state
    val merged = HouseholdListMerge.merge(family, phone)
    val item = merged.items.getValue(id)
    assertEquals("Plantains", item.text.value)
    assertEquals(AltText("Bananas 6", fa, 2_000), item.alt)
    assertEquals("Edited in two places. Rohit’s Pixel changed it last; Family Area had “Bananas 6”.",
        HouseholdListNotes.note(item)?.text)
    assertEquals(merged, HouseholdListMerge.merge(phone, family))
    // "Use “Bananas 6” instead" settles it everywhere, including against the stale replicas.
    val chosen = HouseholdListOps.useAlt(merged, id, la, 4_000).state
    val settled = HouseholdListMerge.merge(HouseholdListMerge.merge(chosen, phone), family)
    assertEquals("Bananas 6", settled.items.getValue(id).text.value)
    assertNull(settled.items.getValue(id).alt)
  }

  @Test fun sequentialRenames_haveNoNote() {
    val start = add(empty, "Bananas", fa, 1_000)
    val id = idOf(start, "Bananas")
    val first = HouseholdListOps.rename(start, id, "Bananas 6", fa, 2_000).state
    val second = HouseholdListOps.rename(first, id, "Plantains", ph, 3_000).state
    val merged = HouseholdListMerge.merge(first, second)
    assertNull(merged.items.getValue(id).alt)
    assertNull(HouseholdListNotes.note(merged.items.getValue(id)))
  }

  @Test fun keepingTheCurrentName_clearsTheNote() {
    val start = add(empty, "Bananas", fa, 1_000)
    val id = idOf(start, "Bananas")
    val merged = HouseholdListMerge.merge(HouseholdListOps.rename(start, id, "Bananas 6", fa, 2_000).state,
        HouseholdListOps.rename(start, id, "Plantains", ph, 3_000).state)
    val kept = HouseholdListOps.keepName(merged, id, fa, 4_000).state
    assertEquals("Plantains", kept.items.getValue(id).text.value)
    assertNull(kept.items.getValue(id).alt)
  }

  @Test fun clearDoneThenUndo_restoresEverywhere() {
    var s = add(add(empty, "Butter", fa, 1_000), "Rice", fa, 1_001)
    s = HouseholdListOps.setDone(s, idOf(s, "Butter"), true, fa, 1_100).state
    s = HouseholdListOps.setDone(s, idOf(s, "Rice"), true, fa, 1_101).state
    val ids = s.done().map { it.id }
    val cleared = HouseholdListOps.clearDone(s, ids, fa, 2_000).state
    assertTrue(cleared.done().isEmpty())
    val undone = ids.fold(cleared) { acc, id -> HouseholdListOps.restore(acc, id, fa, 2_500).state }
    val other = HouseholdListMerge.merge(cleared, undone)
    assertEquals(2, other.done().size)
    assertTrue(other.done().none { HouseholdListNotes.note(it) != null })
  }

  @Test fun reAddingARemovedItem_bringsItBack() {
    val s = add(empty, "Milk", fa, 1_000)
    val removed = HouseholdListOps.remove(s, idOf(s, "Milk"), fa, 2_000).state
    val result = HouseholdListOps.add(removed, "Milk", ph, 3_000)
    assertTrue(result.outcome is Outcome.Applied)
    assertEquals(ph, result.state.liveByText("Milk")!!.added.by)
  }
}
