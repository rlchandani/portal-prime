/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** The one quiet line an item shows after a conflict. Derived from state, so every device agrees. */
internal object HouseholdListNotes {
  enum class Kind(val wire: String) { KEPT("kept"), EDITED("edited") }

  data class Note(val kind: Kind, val text: String, val alt: String? = null, val altBy: String? = null)

  fun note(item: HouseholdItem): Note? {
    item.alt?.let { alt ->
      return Note(Kind.EDITED,
          "Edited in two places. ${item.text.stamp.by} changed it last; ${alt.by} had “${alt.value}”.",
          alt.value, alt.by)
    }
    val removal = item.removal ?: return null
    if (!item.keptAfterRemoval) return null
    val (who, verb) = when (item.contentAt) {
      item.done.stamp.at -> item.done.stamp.by to (if (item.done.value) "ticked it" else "put it back on the list")
      item.text.stamp.at -> item.text.stamp.by to "changed it"
      else -> item.added.by to "added it"
    }
    return Note(Kind.KEPT, "Kept: $who $verb while ${removal.stamp.by} removed it.")
  }
}
