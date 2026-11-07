/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Deterministic replica merge (see [HouseholdItem]). Pure: never mutates its inputs, and
 * `merge(a, b) == merge(b, a)`, `merge(a, merge(a, b)) == merge(a, b)`.
 */
internal object HouseholdListMerge {
  fun merge(a: HouseholdListState, b: HouseholdListState): HouseholdListState {
    if (a === b) return a
    val ids = a.items.keys + b.items.keys
    return HouseholdListState(ids.associateWith { id ->
      val x = a.items[id]
      val y = b.items[id]
      when {
        x == null -> y!!
        y == null -> x
        else -> mergeItem(x, y)
      }
    })
  }

  fun mergeItem(a: HouseholdItem, b: HouseholdItem): HouseholdItem {
    val text = maxOf(a.text, b.text, compareBy<TextReg> { it.stamp }.thenBy { it.value }.thenBy { it.baseAt })
    val lost = if (text === a.text) b.text else a.text
    val conflict = lost.value != text.value && lost.stamp.at > text.baseAt
    val candidates = listOfNotNull(a.alt, b.alt, if (conflict) AltText(lost.value, lost.stamp.by, lost.stamp.at) else null)
    val alt = candidates.filter { it.value != text.value && it.at > text.baseAt }
        .maxWithOrNull(compareBy<AltText> { it.at }.thenBy { it.by }.thenBy { it.value })
    return HouseholdItem(
        id = a.id,
        text = text,
        done = maxReg(a.done, b.done) { if (it) 1 else 0 },
        order = maxReg(a.order, b.order) { it },
        added = maxOf(a.added, b.added),
        removal = listOfNotNull(a.removal, b.removal)
            .maxWithOrNull(compareBy<Removal> { it.stamp }.thenBy { it.removed }.thenBy { it.seen }),
        alt = alt,
        migratedFrom = a.migratedFrom + b.migratedFrom,
    )
  }

  private fun <T, K : Comparable<K>> maxReg(a: Reg<T>, b: Reg<T>, tie: (T) -> K): Reg<T> =
      maxOf(a, b, compareBy<Reg<T>> { it.stamp }.thenBy { tie(it.value) })
}
