/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Pure list edits. Each returns a new [HouseholdListState] plus what happened; inputs are never
 * mutated. [local] edits (made on this Portal's screen) are stamped just after the register they
 * overwrite, so a Portal clock that lags another device's can't make a tap appear to do nothing.
 * Phone actions are applied with their own (clamped) time and simply lose to newer edits.
 */
internal object HouseholdListOps {
  const val TOMBSTONE_TTL_MS = 30L * 24 * 60 * 60 * 1000
  const val MAX_ITEMS = 600

  sealed class Outcome {
    data class Applied(val id: String) : Outcome()
    data class Duplicate(val id: String) : Outcome()
    data class MovedBack(val id: String) : Outcome()
    object Missing : Outcome()
    object Invalid : Outcome()
  }

  data class Result(val state: HouseholdListState, val outcome: Outcome)

  private fun stamp(at: Long, by: String, prior: Long?, local: Boolean) =
      Stamp(if (local && prior != null) maxOf(at, prior + 1) else at, by)

  private fun HouseholdListState.with(item: HouseholdItem) = HouseholdListState(items + (item.id to item))

  private fun topOrder(s: HouseholdListState): Double = (s.open().firstOrNull()?.order?.value ?: 1.0) - 1.0

  fun add(s: HouseholdListState, raw: String, by: String, at: Long, local: Boolean = false,
          migratedFrom: Set<String> = emptySet(), done: Boolean = false): Result {
    val text = HouseholdListText.clean(raw) ?: return Result(s, Outcome.Invalid)
    s.liveByText(text)?.let { same ->
      if (!same.done.value) return Result(s, Outcome.Duplicate(same.id))
      val back = setDone(s, same.id, false, by, at, local).state
      return Result(back, Outcome.MovedBack(same.id))
    }
    val id = freeId(s, text) ?: return Result(s, Outcome.Invalid)
    if (s.items.size >= MAX_ITEMS && id !in s.items) return Result(s, Outcome.Invalid)
    val prior = s.items[id]
    val st = stamp(at, by, prior?.contentAt, local)
    val item = HouseholdItem(
        id = id,
        text = TextReg(text, st, prior?.text?.stamp?.at ?: 0L),
        done = Reg(done, st),
        order = Reg(topOrder(s), st),
        added = st,
        removal = prior?.removal?.let { Removal(false, st, st.at) },
        alt = null,
        migratedFrom = (prior?.migratedFrom ?: emptySet()) + migratedFrom,
    )
    return Result(s.with(item), Outcome.Applied(id))
  }

  /** The text's own id unless a renamed live item already holds it ("Bananas" → "Plantains"). */
  private fun freeId(s: HouseholdListState, text: String): String? {
    val n = HouseholdListText.norm(text)
    for (attempt in 0 until 16) {
      val id = HouseholdListText.idFor(if (attempt == 0) n else "$n#$attempt")
      val held = s.items[id]
      if (held == null || !held.alive || HouseholdListText.norm(held.text.value) == n) return id
    }
    return null
  }

  private inline fun edit(s: HouseholdListState, id: String, f: (HouseholdItem) -> HouseholdItem?): Result {
    val item = s.items[id]?.takeIf { it.alive } ?: return Result(s, Outcome.Missing)
    val next = f(item) ?: return Result(s, Outcome.Invalid)
    return Result(if (next == item) s else s.with(next), Outcome.Applied(id))
  }

  fun setDone(s: HouseholdListState, id: String, done: Boolean, by: String, at: Long, local: Boolean = false) =
      edit(s, id) { it.copy(done = Reg(done, stamp(at, by, it.done.stamp.at, local))) }

  fun remove(s: HouseholdListState, id: String, by: String, at: Long, local: Boolean = false) =
      edit(s, id) { it.copy(removal = Removal(true, stamp(at, by, it.removal?.stamp?.at, local), it.contentAt)) }

  /** Undo of a remove / clear done. Works on removed items, so it bypasses [edit]'s alive check. */
  fun restore(s: HouseholdListState, id: String, by: String, at: Long, local: Boolean = false): Result {
    val item = s.items[id] ?: return Result(s, Outcome.Missing)
    if (item.removal?.removed != true) return Result(s, Outcome.Applied(id))
    val st = stamp(at, by, item.removal.stamp.at, local)
    return Result(s.with(item.copy(removal = Removal(false, st, item.contentAt))), Outcome.Applied(id))
  }

  fun rename(s: HouseholdListState, id: String, raw: String, by: String, at: Long, local: Boolean = false): Result {
    val text = HouseholdListText.clean(raw) ?: return Result(s, Outcome.Invalid)
    val item = s.items[id]?.takeIf { it.alive } ?: return Result(s, Outcome.Missing)
    if (text == item.text.value) return Result(s, Outcome.Applied(id))
    return setText(s, item, text, by, at, local, base = item.text.stamp.at)
  }

  /** "Use “alt” instead": the other name wins and the note clears everywhere. */
  fun useAlt(s: HouseholdListState, id: String, by: String, at: Long, local: Boolean = false): Result {
    val item = s.items[id]?.takeIf { it.alive } ?: return Result(s, Outcome.Missing)
    val alt = item.alt ?: return Result(s, Outcome.Applied(id))
    return setText(s, item, alt.value, by, at, local, base = maxOf(item.text.stamp.at, alt.at))
  }

  /** "Keep “current”": same name, but stamped as having seen the other one, so the note clears. */
  fun keepName(s: HouseholdListState, id: String, by: String, at: Long, local: Boolean = false): Result {
    val item = s.items[id]?.takeIf { it.alive } ?: return Result(s, Outcome.Missing)
    val alt = item.alt ?: return Result(s, Outcome.Applied(id))
    return setText(s, item, item.text.value, by, at, local, base = maxOf(item.text.stamp.at, alt.at))
  }

  private fun setText(s: HouseholdListState, item: HouseholdItem, text: String, by: String, at: Long,
                      local: Boolean, base: Long): Result {
    val reg = TextReg(text, stamp(at, by, item.text.stamp.at, local), base)
    return Result(s.with(item.copy(text = reg, alt = item.alt?.takeIf { it.at > base && it.value != text })),
        Outcome.Applied(item.id))
  }

  fun move(s: HouseholdListState, id: String, order: Double, by: String, at: Long, local: Boolean = false): Result {
    if (!order.isFinite()) return Result(s, Outcome.Invalid)
    return edit(s, id) { if (it.order.value == order) it else it.copy(order = Reg(order, stamp(at, by, it.order.stamp.at, local))) }
  }

  fun moveToTop(s: HouseholdListState, id: String, by: String, at: Long, local: Boolean = false): Result {
    val first = s.open().firstOrNull() ?: return Result(s, Outcome.Missing)
    if (first.id == id) return Result(s, Outcome.Applied(id))
    return move(s, id, first.order.value - 1.0, by, at, local)
  }

  fun clearDone(s: HouseholdListState, ids: List<String>, by: String, at: Long, local: Boolean = false): Result {
    val done = ids.filter { s.items[it]?.let { item -> item.alive && item.done.value } == true }
    val next = done.fold(s) { acc, id -> remove(acc, id, by, at, local).state }
    return Result(next, if (done.isEmpty()) Outcome.Missing else Outcome.Applied(done.first()))
  }

  /** Forgets removals older than [TOMBSTONE_TTL_MS] so the list can't grow without bound. */
  fun prune(s: HouseholdListState, nowMs: Long): HouseholdListState {
    val keep = s.items.filterValues { it.alive || nowMs - it.removal!!.stamp.at <= TOMBSTONE_TTL_MS }
    return if (keep.size == s.items.size) s else HouseholdListState(keep)
  }

  /** "Used before" chips: recently ticked or removed things that aren't on the list right now. */
  fun usedBefore(s: HouseholdListState, limit: Int = 6): List<String> {
    val open = s.open().map { HouseholdListText.norm(it.text.value) }.toSet()
    return s.items.values.filter { !it.alive || it.done.value }
        .sortedByDescending { if (it.alive) it.done.stamp.at else it.removal!!.stamp.at }
        .map { it.text.value }
        .distinctBy(HouseholdListText::norm)
        .filter { HouseholdListText.norm(it) !in open }
        .take(limit)
  }
}
