/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable

/**
 * The SENSORS card as the mock reads it: [primary] holds the four summary rows (Doors, Windows,
 * Garage, Motion), after any safety group that needs attention; [more] holds the calm rest (alarm
 * panel, smoke & CO, tamper) behind one "More sensors" row, so every entity stays reachable.
 */
@Immutable
internal data class CcSecCard(val primary: List<CcSecSection>, val more: List<CcSecSection>) {
  val moreCount: Int get() = more.sumOf { it.items.size }
  val isEmpty: Boolean get() = primary.isEmpty() && more.isEmpty()
}

/** Groups, names and lines for the Security card (pure, unit tested); see [CcSecurityList] for the rows. */
internal object CcSecuritySummary {
  /** The mock's rows, in its order. Locks are folded into Doors ("Front & back closed and locked"). */
  private val PRIMARY = listOf(CcSecGroup.DOORS, CcSecGroup.WINDOWS, CcSecGroup.GARAGE, CcSecGroup.MOTION)
  /** Never hidden behind "More" while they need attention: smoke, tamper, a sounding alarm. */
  private val SAFETY = listOf(CcSecGroup.HAZARDS, CcSecGroup.TAMPER, CcSecGroup.ALARM)
  private const val IGNORED = "ignored in Home & Night"

  fun card(sections: List<CcSecSection>): CcSecCard {
    val by = sections.associateBy { it.group }
    val doors = doorsAndLocks(by[CcSecGroup.DOORS], by[CcSecGroup.LOCKS])
    val primary = PRIMARY.mapNotNull { if (it == CcSecGroup.DOORS) doors else by[it] }
    val safety = SAFETY.mapNotNull { by[it] }
    val (urgent, calm) = safety.partition { it.ok == false }
    return CcSecCard((urgent + primary).map(::renamed), calm.map(::renamed))
  }

  /** One Doors row for door sensors and locks; either alone stays as it is. */
  fun doorsAndLocks(doors: CcSecSection?, locks: CcSecSection?): CcSecSection? {
    if (doors == null || locks == null) return doors ?: locks
    val items = CcSecurityList.sortRows(doors.items + locks.items)
    val att = items.filter { it.attention }
    val tag = when {
      att.isEmpty() -> "OK"
      att.all { isLock(it) } -> "${att.size} unlocked"
      else -> "${att.size} open"
    }
    return CcSecSection(CcSecGroup.DOORS, items, tag, att.isEmpty())
  }

  private fun renamed(s: CcSecSection): CcSecSection = s.copy(items = CcSecurityList.sortRows(CcSecNames.rename(s.items, s.group)))

  private fun isLock(i: CcSecItem) = i.id.startsWith("lock.")

  /**
   * The line under a group title: "Open since 7:25 pm" for one open garage door, "3 rooms · ignored
   * in Home & Night" for quiet motion, "All 15 closed and locked" / "Pantry door, Front door unlocked"
   * for doors with locks; otherwise the list's own summary.
   */
  fun line(s: CcSecSection, now: Long, time: (Long) -> String, day: (Long) -> String): String {
    val att = s.attention
    return when {
      s.group == CcSecGroup.GARAGE && att.size == 1 && att[0].since > 0 ->
        "Open " + CcSecurityList.sinceLine(att[0].since, now, time, day) + unavailable(s)
      s.group == CcSecGroup.MOTION -> motion(s)
      s.group == CcSecGroup.DOORS && s.items.any(::isLock) && s.items.any { !isLock(it) } -> doors(s)
      else -> s.summary
    }
  }

  private fun motion(s: CcSecSection): String {
    val live = s.items.filter { !it.unavailable }
    if (live.isEmpty()) return s.summary
    val on = live.count(CcSecurityList::active)
    if (on > 0) return s.summary
    val rooms = live.mapNotNull { it.area?.trim()?.lowercase()?.takeIf(String::isNotEmpty) }.toSet().size
    val where = if (rooms > 0) plural(rooms, "room") else plural(live.size, "sensor")
    return "$where · $IGNORED" + unavailable(s)
  }

  private fun doors(s: CcSecSection): String {
    val att = s.attention
    if (att.isNotEmpty()) return att.joinToString(", ") { if (isLock(it)) "${it.name} unlocked" else it.name } + unavailable(s)
    val live = s.items.count { !it.unavailable }
    return (if (live == 0) "${s.items.size} unavailable" else "All $live closed and locked" + unavailable(s))
  }

  private fun unavailable(s: CcSecSection): String = s.items.count { it.unavailable }.let { if (it > 0) " · $it unavailable" else "" }

  private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

  /** Motion has no tag while it's quiet (the mock's row ends with its line). */
  fun showsTag(s: CcSecSection): Boolean = !(s.group == CcSecGroup.MOTION && s.items.none(CcSecurityList::active))

  /** "Alarm, Smoke, CO & leaks, Tamper" — what's behind the More row. */
  fun moreLine(card: CcSecCard): String = card.more.joinToString(", ") { it.group.title }

  /** The groups that start open: only safety groups that need attention. */
  fun startOpen(card: CcSecCard): Set<CcSecGroup> = card.primary.filter { it.group in SAFETY && it.ok == false }.map { it.group }.toSet()
}
