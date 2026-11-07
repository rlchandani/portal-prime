/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.HouseholdListCommands.Undo
import org.junit.Assert.*
import org.junit.Test

class HouseholdListCommandsTest {
  private val fa = "Family Area"
  private val now = 1_800_000_000_000L
  private val c = HouseholdListCommands(by = fa, online = { true })
  private val eggs = HouseholdListText.idFor("Eggs")
  private val start = HouseholdListOps.add(HouseholdListState(), "Eggs", fa, now - 1_000).state

  @Test fun add_feedbackAndUndo() {
    val r = c.add(start, "Milk", now)
    assertEquals("Added Milk.", r.feedback.text)
    assertEquals(Undo.Remove(HouseholdListText.idFor("Milk")), r.feedback.undo)
    assertEquals("Eggs is already on the list.", c.add(start, "eggs", now).feedback.text)
    assertEquals("Type something to add first.", c.add(start, "  ", now).feedback.text)
    val offline = HouseholdListCommands(fa, online = { false }).add(start, "Milk", now)
    assertEquals("Added Milk. Saved on this Portal. It syncs when the network is back.", offline.feedback.text)
  }

  @Test fun tickRemoveAndUndo_roundTrip() {
    val ticked = c.toggle(start, eggs, now)
    assertEquals("Ticked Eggs.", ticked.feedback.text)
    val undone = c.undo(ticked.state, ticked.feedback.undo!!, now + 10)
    assertFalse(undone.state.items.getValue(eggs).done.value)
    assertEquals("Undone.", undone.feedback.text)
    assertEquals("Put Eggs back on the list.", c.toggle(ticked.state, eggs, now + 20).feedback.text)
    val removed = c.remove(start, eggs, now)
    assertEquals("Removed Eggs.", removed.feedback.text)
    assertTrue(c.undo(removed.state, removed.feedback.undo!!, now + 5).state.items.getValue(eggs).alive)
  }

  @Test fun clearDone_countsAndUndoes() {
    var s = HouseholdListOps.add(start, "Rice", fa, now - 900).state
    s = c.toggle(s, eggs, now - 800).state
    s = c.toggle(s, HouseholdListText.idFor("Rice"), now - 700).state
    val cleared = c.clearDone(s, now)
    assertEquals("Cleared 2 done items.", cleared.feedback.text)
    assertTrue(cleared.state.done().isEmpty())
    assertEquals(2, c.undo(cleared.state, cleared.feedback.undo!!, now + 1).state.done().size)
  }

  @Test fun renameAndConflictChoices() {
    assertEquals("A name can’t be empty. Use Remove to take it off the list.", c.rename(start, eggs, " ", now).feedback.text)
    val r = c.rename(start, eggs, "Free-range eggs", now)
    assertEquals("Renamed to Free-range eggs.", r.feedback.text)
    assertEquals(Undo.Rename(eggs, "Eggs"), r.feedback.undo)
    assertEquals("Kept “Eggs”.", c.keepName(start, eggs, now).feedback.text)
  }

  @Test fun moveToTop_onlyWhenNotAlreadyFirst() {
    val s = HouseholdListOps.add(start, "Bread", fa, now - 500).state
    assertEquals("Moved to the top.", c.moveToTop(s, eggs, now).feedback.text)
    assertNull(c.moveToTop(s, HouseholdListText.idFor("Bread"), now).feedback.text.takeIf { it.isNotEmpty() })
  }

  @Test fun reorder_placesBetweenNeighbours() {
    var s = start
    listOf("A", "B", "C").forEach { s = HouseholdListOps.add(s, it, fa, now).state } // open: C, B, A, Eggs
    val moved = c.moveBefore(s, eggs, beforeId = HouseholdListText.idFor("B"), now = now + 1).state
    assertEquals(listOf("C", "Eggs", "B", "A"), moved.open().map { it.text.value })
    val toEnd = c.moveBefore(s, HouseholdListText.idFor("C"), beforeId = null, now = now + 2).state
    assertEquals(listOf("B", "A", "Eggs", "C"), toEnd.open().map { it.text.value })
  }
}
