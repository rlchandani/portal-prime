/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * What a tap on this Portal does to the list, and the toast it shows (with an optional Undo).
 * Pure: the screen applies the returned state through [HouseholdListStore.update].
 */
internal class HouseholdListCommands(private val by: String, private val online: () -> Boolean) {
  sealed class Undo {
    data class SetDone(val id: String, val done: Boolean) : Undo()
    data class Remove(val id: String) : Undo()
    data class Restore(val ids: List<String>) : Undo()
    data class Rename(val id: String, val text: String) : Undo()
  }

  data class Feedback(val text: String, val undo: Undo? = null)
  data class Result(val state: HouseholdListState, val feedback: Feedback, val focusId: String? = null)

  private fun same(s: HouseholdListState, text: String = "") = Result(s, Feedback(text))

  fun add(s: HouseholdListState, raw: String, now: Long): Result {
    if (raw.isBlank()) return same(s, "Type something to add first.")
    val r = HouseholdListOps.add(s, raw, by, now, local = true)
    return when (val o = r.outcome) {
      is HouseholdListOps.Outcome.Applied -> {
        val text = r.state.items.getValue(o.id).text.value
        val tail = if (online()) "" else " Saved on this Portal. It syncs when the network is back."
        Result(r.state, Feedback("Added $text.$tail", Undo.Remove(o.id)), o.id)
      }
      is HouseholdListOps.Outcome.Duplicate -> Result(s, Feedback("${s.items.getValue(o.id).text.value} is already on the list."), o.id)
      is HouseholdListOps.Outcome.MovedBack ->
        Result(r.state, Feedback("Moved ${r.state.items.getValue(o.id).text.value} back to the list.", Undo.SetDone(o.id, true)), o.id)
      else -> same(s, "That’s too long for the list. Keep it under ${HouseholdListText.MAX_TEXT} characters.")
    }
  }

  fun toggle(s: HouseholdListState, id: String, now: Long): Result {
    val item = s.items[id]?.takeIf { it.alive } ?: return same(s)
    val wasDone = item.done.value
    val r = HouseholdListOps.setDone(s, id, !wasDone, by, now, local = true)
    val text = if (wasDone) "Put ${item.text.value} back on the list." else "Ticked ${item.text.value}."
    return Result(r.state, Feedback(text, Undo.SetDone(id, wasDone)))
  }

  fun remove(s: HouseholdListState, id: String, now: Long): Result {
    val item = s.items[id]?.takeIf { it.alive } ?: return same(s)
    return Result(HouseholdListOps.remove(s, id, by, now, local = true).state, Feedback("Removed ${item.text.value}.", Undo.Restore(listOf(id))))
  }

  fun clearDone(s: HouseholdListState, now: Long): Result {
    val ids = s.done().map { it.id }
    if (ids.isEmpty()) return same(s)
    val noun = if (ids.size == 1) "1 done item" else "${ids.size} done items"
    return Result(HouseholdListOps.clearDone(s, ids, by, now, local = true).state, Feedback("Cleared $noun.", Undo.Restore(ids)))
  }

  fun rename(s: HouseholdListState, id: String, raw: String, now: Long): Result {
    val item = s.items[id]?.takeIf { it.alive } ?: return same(s)
    if (raw.isBlank()) return same(s, "A name can’t be empty. Use Remove to take it off the list.")
    val r = HouseholdListOps.rename(s, id, raw, by, now, local = true)
    if (r.outcome == HouseholdListOps.Outcome.Invalid) return same(s, "That name is too long.")
    if (r.state === s) return same(s)
    return Result(r.state, Feedback("Renamed to ${r.state.items.getValue(id).text.value}.", Undo.Rename(id, item.text.value)))
  }

  fun useAlt(s: HouseholdListState, id: String, now: Long): Result {
    val r = HouseholdListOps.useAlt(s, id, by, now, local = true)
    return Result(r.state, Feedback("Kept “${r.state.items[id]?.text?.value.orEmpty()}”."))
  }

  fun keepName(s: HouseholdListState, id: String, now: Long): Result {
    val r = HouseholdListOps.keepName(s, id, by, now, local = true)
    return Result(r.state, Feedback("Kept “${r.state.items[id]?.text?.value.orEmpty()}”."))
  }

  /** "OK" on a kept-after-removal note: settles it for everyone. */
  fun acknowledgeKept(s: HouseholdListState, id: String, now: Long): Result =
      Result(HouseholdListOps.restore(s, id, by, now, local = true).state, Feedback(""))

  fun moveToTop(s: HouseholdListState, id: String, now: Long): Result {
    if (s.open().firstOrNull()?.id == id) return same(s)
    val r = HouseholdListOps.moveToTop(s, id, by, now, local = true)
    return if (r.state === s) same(s) else Result(r.state, Feedback("Moved to the top."))
  }

  /** Drag-to-reorder: puts [id] just before [beforeId] (null = the end of the open list). */
  fun moveBefore(s: HouseholdListState, id: String, beforeId: String?, now: Long): Result {
    val rest = s.open().filter { it.id != id }
    val idx = if (beforeId == null) rest.size else rest.indexOfFirst { it.id == beforeId }.takeIf { it >= 0 } ?: return same(s)
    val prev = rest.getOrNull(idx - 1)?.order?.value
    val next = rest.getOrNull(idx)?.order?.value
    val order = when {
      prev == null && next == null -> 0.0
      prev == null -> next!! - 1.0
      next == null -> prev + 1.0
      else -> (prev + next) / 2.0
    }
    return Result(HouseholdListOps.move(s, id, order, by, now, local = true).state, Feedback(""))
  }

  fun undo(s: HouseholdListState, undo: Undo, now: Long): Result {
    val next = when (undo) {
      is Undo.SetDone -> HouseholdListOps.setDone(s, undo.id, undo.done, by, now, local = true).state
      is Undo.Remove -> HouseholdListOps.remove(s, undo.id, by, now, local = true).state
      is Undo.Restore -> undo.ids.fold(s) { acc, id -> HouseholdListOps.restore(acc, id, by, now, local = true).state }
      is Undo.Rename -> HouseholdListOps.rename(s, undo.id, undo.text, by, now, local = true).state
    }
    return Result(next, Feedback("Undone."))
  }
}
