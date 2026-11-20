/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.HouseholdListCommands.Undo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** On-device list commands: feedback text, undo tokens and the no-op paths. */
class HouseholdListCommandsCoverageTest {
  private val now = 1_800_000_000_000L
  private val online = HouseholdListCommands("Kitchen") { true }
  private val offline = HouseholdListCommands("Kitchen") { false }

  private fun listWith(vararg texts: String): HouseholdListState =
      texts.foldIndexed(HouseholdListState()) { i, s, t -> online.add(s, t, now + i).state }

  private fun HouseholdListState.idOf(text: String) = liveByText(text)!!.id

  @Test fun addFeedbackCoversBlankOfflineDuplicateMovedBackAndTooLong() {
    assertEquals("Type something to add first.", online.add(HouseholdListState(), "  ", now).feedback.text)
    val off = offline.add(HouseholdListState(), "Milk", now)
    assertTrue(off.feedback.text.startsWith("Added Milk. Saved on this Portal."))
    assertEquals(Undo.Remove(off.focusId!!), off.feedback.undo)
    val dup = online.add(off.state, "milk", now + 1)
    assertEquals("Milk is already on the list.", dup.feedback.text)
    assertSame(off.state, dup.state)
    val ticked = online.toggle(off.state, off.focusId!!, now + 2).state
    val back = online.add(ticked, "MILK", now + 3)
    assertEquals("Moved Milk back to the list.", back.feedback.text)
    assertEquals(Undo.SetDone(off.focusId!!, true), back.feedback.undo)
    val long = online.add(HouseholdListState(), "x".repeat(HouseholdListText.MAX_TEXT + 1), now)
    assertTrue(long.feedback.text.contains("under ${HouseholdListText.MAX_TEXT} characters"))
    assertNull(long.feedback.undo)
  }

  @Test fun toggleBothWaysAndMissing() {
    val s = listWith("Milk")
    val id = s.idOf("Milk")
    val ticked = online.toggle(s, id, now + 5)
    assertEquals("Ticked Milk.", ticked.feedback.text)
    assertEquals(Undo.SetDone(id, false), ticked.feedback.undo)
    val back = online.toggle(ticked.state, id, now + 6)
    assertEquals("Put Milk back on the list.", back.feedback.text)
    assertEquals(Undo.SetDone(id, true), back.feedback.undo)
    val missing = online.toggle(s, "h000000000000000", now)
    assertSame(s, missing.state)
    assertEquals("", missing.feedback.text)
  }

  @Test fun removeAndRestoreViaUndo() {
    val s = listWith("Milk")
    val id = s.idOf("Milk")
    val removed = online.remove(s, id, now + 5)
    assertEquals("Removed Milk.", removed.feedback.text)
    assertFalse(removed.state.items.getValue(id).alive)
    assertSame(removed.state, online.remove(removed.state, id, now + 6).state)
    val undone = online.undo(removed.state, removed.feedback.undo!!, now + 7)
    assertEquals("Undone.", undone.feedback.text)
    assertTrue(undone.state.items.getValue(id).alive)
  }

  @Test fun clearDoneCountsItemsAndIsSilentWhenNothingIsDone() {
    val s = listWith("Milk", "Bread", "Eggs")
    assertSame(s, online.clearDone(s, now).state)
    val one = online.toggle(s, s.idOf("Milk"), now + 5).state
    assertEquals("Cleared 1 done item.", online.clearDone(one, now + 6).feedback.text)
    val two = online.toggle(one, s.idOf("Bread"), now + 7).state
    val cleared = online.clearDone(two, now + 8)
    assertEquals("Cleared 2 done items.", cleared.feedback.text)
    val restored = online.undo(cleared.state, cleared.feedback.undo!!, now + 9).state
    assertEquals(2, restored.done().size)
  }

  @Test fun renameRules() {
    val s = listWith("Milk")
    val id = s.idOf("Milk")
    assertSame(s, online.rename(s, "h000000000000000", "x", now).state)
    assertTrue(online.rename(s, id, "  ", now).feedback.text.startsWith("A name can’t be empty"))
    assertEquals("That name is too long.", online.rename(s, id, "y".repeat(HouseholdListText.MAX_TEXT + 1), now).feedback.text)
    val same = online.rename(s, id, "Milk", now + 1)
    assertSame(s, same.state)
    assertEquals("", same.feedback.text)
    val renamed = online.rename(s, id, "Oat milk", now + 1)
    assertEquals("Renamed to Oat milk.", renamed.feedback.text)
    assertEquals(Undo.Rename(id, "Milk"), renamed.feedback.undo)
    assertEquals("Milk", online.undo(renamed.state, renamed.feedback.undo!!, now + 2).state.items.getValue(id).text.value)
  }

  @Test fun useAltAndKeepNameReportTheKeptText() {
    val s = listWith("Milk")
    val id = s.idOf("Milk")
    val item = s.items.getValue(id)
    val withAlt = HouseholdListState(s.items + (id to item.copy(alt = AltText("Oat milk", "Den", now + 5))))
    assertEquals("Kept “Oat milk”.", online.useAlt(withAlt, id, now + 6).feedback.text)
    assertEquals("Kept “Milk”.", online.keepName(withAlt, id, now + 6).feedback.text)
    assertEquals("Kept “”.", online.useAlt(withAlt, "h000000000000000", now).feedback.text)
    assertEquals("Kept “”.", online.keepName(withAlt, "h000000000000000", now).feedback.text)
  }

  @Test fun acknowledgeKeptRestoresSilently() {
    val s = listWith("Milk")
    val id = s.idOf("Milk")
    val removed = online.remove(s, id, now + 1).state
    val r = online.acknowledgeKept(removed, id, now + 2)
    assertEquals("", r.feedback.text)
    assertTrue(r.state.items.getValue(id).alive)
  }

  @Test fun moveToTopAndItsNoOps() {
    val s = listWith("Milk", "Bread")
    val top = s.open().first().id
    assertSame(s, online.moveToTop(s, top, now + 5).state)
    assertSame("unknown id", s, online.moveToTop(s, "h000000000000000", now + 5).state)
    val bottom = s.open().last().id
    val moved = online.moveToTop(s, bottom, now + 5)
    assertEquals("Moved to the top.", moved.feedback.text)
    assertEquals(bottom, moved.state.open().first().id)
  }

  @Test fun moveBeforeComputesMidpointsAndEnds() {
    val s = listWith("A", "B", "C") // open order: C, B, A
    val (c, b, a) = s.open().map { it.id }
    assertEquals(listOf(b, c, a), online.moveBefore(s, b, c, now + 5).state.open().map { it.id })
    assertEquals(listOf(b, a, c), online.moveBefore(s, c, null, now + 5).state.open().map { it.id })
    assertEquals(listOf(c, a, b), online.moveBefore(s, a, b, now + 5).state.open().map { it.id })
    assertSame("unknown target", s, online.moveBefore(s, a, "h000000000000000", now + 5).state)
    val single = listWith("Only")
    val only = single.open().single()
    assertEquals(0.0, online.moveBefore(single, only.id, null, now + 5).state.items.getValue(only.id).order.value, 0.0)
  }

  @Test fun undoSetDone() {
    val s = listWith("Milk")
    val id = s.idOf("Milk")
    val ticked = online.toggle(s, id, now + 1)
    assertFalse(online.undo(ticked.state, ticked.feedback.undo!!, now + 2).state.items.getValue(id).done.value)
  }
}
