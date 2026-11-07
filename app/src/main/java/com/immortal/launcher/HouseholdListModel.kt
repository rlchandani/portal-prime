/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.security.MessageDigest
import java.util.Locale

/**
 * The shared household list as a state-based CRDT: every Portal (and, via /remote/list, every
 * phone) edits its own replica and replicas converge by [HouseholdListMerge.merge], which is
 * commutative, associative and idempotent. Each item is a set of last-writer-wins registers
 * (text, done, order, added) plus a removal register, so the README's rules fall out of the data:
 *
 * - **Removed in one place, changed in another → keep it** (Q5). A removal records the newest
 *   content change it had [Removal.seen]; a content change the remover never saw keeps the item.
 * - **Renamed in two places → last edit wins with a quiet note** (Q4). A rename records the text
 *   it replaced ([TextReg.baseAt]); a losing concurrent rename survives as [AltText].
 * - **Same thing added in two places → one item**: add ids derive from the normalized text.
 */
internal data class Stamp(val at: Long, val by: String) : Comparable<Stamp> {
  override fun compareTo(other: Stamp): Int =
      compareValuesBy(this, other, Stamp::at, Stamp::by)
}

internal data class Reg<T>(val value: T, val stamp: Stamp)

/** The item's name; [baseAt] is the stamp time of the name this edit replaced. */
internal data class TextReg(val value: String, val stamp: Stamp, val baseAt: Long)

/** [seen] is the newest content stamp the remover had when it removed (or restored) the item. */
internal data class Removal(val removed: Boolean, val stamp: Stamp, val seen: Long)

/** A concurrent rename that lost last-writer-wins; offered back as "Use “…” instead". */
internal data class AltText(val value: String, val by: String, val at: Long)

internal data class HouseholdItem(
    val id: String,
    val text: TextReg,
    val done: Reg<Boolean>,
    val order: Reg<Double>,
    val added: Stamp,
    val removal: Removal? = null,
    val alt: AltText? = null,
    /** Portals whose old local To-do list contributed this item (grow-only set, for the dialog). */
    val migratedFrom: Set<String> = emptySet(),
) {
  /** Newest change to what the item *is* (name, ticked, added). Reordering is not a content change. */
  val contentAt: Long get() = maxOf(text.stamp.at, done.stamp.at, added.at)

  val alive: Boolean get() = removal == null || !removal.removed || contentAt > removal.seen

  /** Removed somewhere, but kept because someone changed it without seeing the removal. */
  val keptAfterRemoval: Boolean get() = removal?.removed == true && alive
}

internal data class HouseholdListState(val items: Map<String, HouseholdItem> = emptyMap()) {
  fun live(): List<HouseholdItem> = items.values.filter { it.alive }

  fun open(): List<HouseholdItem> = live().filter { !it.done.value }
      .sortedWith(compareBy<HouseholdItem> { it.order.value }.thenBy { it.added.at }.thenBy { it.id })

  fun done(): List<HouseholdItem> = live().filter { it.done.value }
      .sortedWith(compareByDescending<HouseholdItem> { it.done.stamp.at }.thenBy { it.id })

  fun liveByText(text: String): HouseholdItem? = HouseholdListText.norm(text).let { n ->
    live().firstOrNull { HouseholdListText.norm(it.text.value) == n }
  }

  /** Short content hash; equal on every replica once they have converged. */
  val digest: String by lazy { HouseholdListCodec.digest(this) }
}

internal object HouseholdListText {
  const val MAX_TEXT = 60
  const val MAX_NAME = 40
  private val SPACE = Regex("\\s+")
  private val CONTROL = Regex("[\\p{Cc}\\p{Cf}]")

  fun norm(text: String): String = text.trim().lowercase(Locale.ROOT).replace(SPACE, " ")

  /** The cleaned item text, or null when empty or too long to be a list item. */
  fun clean(raw: String?): String? {
    val t = raw?.replace(CONTROL, " ")?.trim()?.replace(SPACE, " ") ?: return null
    return t.takeIf { it.isNotEmpty() && it.length <= MAX_TEXT }
  }

  /** A device display name ("Rohit’s Pixel"), or null when unusable. */
  fun cleanName(raw: String?): String? {
    val t = raw?.replace(CONTROL, " ")?.trim()?.replace(SPACE, " ") ?: return null
    return t.takeIf { it.isNotEmpty() && it.length <= MAX_NAME }
  }

  /** Shared with Toolkit: "h" + 15 hex of SHA-256("immortal-list-id:" + norm(text)). */
  fun idFor(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("immortal-list-id:${norm(text)}".toByteArray(Charsets.UTF_8))
    return "h" + digest.joinToString("") { "%02x".format(it) }.take(15)
  }

  private val ID = Regex("^h[0-9a-f]{15}$")
  fun isValidId(id: String?): Boolean = id != null && ID.matches(id)
}
