/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable

/** The Security screen's sensor groups, in the order the household reads them. */
internal enum class CcSecGroup(val title: String, val icon: String) {
  ALARM("Alarm", "shield"),
  LOCKS("Locks", "lock"),
  GARAGE("Garage", "garage"),
  DOORS("Doors", "door"),
  WINDOWS("Windows", "window"),
  HAZARDS("Smoke, CO & leaks", "warn"),
  MOTION("Motion & occupancy", "motion"),
  TAMPER("Tamper", "warn"),
}

/** What the inline button on a row does; it always goes through `CcUi.act` (policy, confirm, dry run). */
internal enum class CcSecAction(val label: String) { CLOSE("Close"), LOCK("Lock") }

/** One entity row: name, room, state and since when; [attention] = open, unlocked, smoke, tampered… */
@Immutable
internal data class CcSecItem(
    val id: String,
    val name: String,
    val area: String?,
    val state: String,
    val since: Long,
    val attention: Boolean,
    val unavailable: Boolean,
    val action: CcSecAction?,
)

/** A group: its rows (attention first) and the tag that sums it up ("4 open", "OK"). */
@Immutable
internal data class CcSecSection(val group: CcSecGroup, val items: List<CcSecItem>, val tag: String, val ok: Boolean?) {
  val attention: List<CcSecItem> get() = items.filter { it.attention }
  /** "Pantry door, Garage tilt", or "All 13 closed" — the line under the group title. */
  val summary: String get() = CcSecurityList.summary(this)
}

/**
 * Every security-relevant entity, grouped (pure, unit tested): locks, garage, doors, windows,
 * smoke/CO/gas/leak, motion & occupancy, tamper and the alarm panel. Kids mode drops what the
 * household hid; nothing is capped — the side column scrolls.
 */
internal object CcSecurityList {
  private val DOOR_CLASSES = setOf("door", "opening")
  private val GARAGE_BINARY = setOf("garage_door", "garage")
  private val GARAGE_COVER = setOf("garage", "gate")
  private val HAZARD_CLASSES = setOf("smoke", "carbon_monoxide", "gas", "moisture", "safety")
  private val MOTION_CLASSES = setOf("motion", "occupancy", "presence")
  /** Tamper sensors are usually filed as diagnostics; the registry still lets us find them. */
  const val TAMPER_CLASS = "tamper"

  fun groupOf(e: CcEntity): CcSecGroup? {
    val cls = e.deviceClass
    return when (e.domain) {
      "alarm_control_panel" -> CcSecGroup.ALARM
      "lock" -> CcSecGroup.LOCKS
      "cover" -> if (cls in GARAGE_COVER) CcSecGroup.GARAGE else null
      "binary_sensor" -> when (cls) {
        in GARAGE_BINARY -> CcSecGroup.GARAGE
        in DOOR_CLASSES -> CcSecGroup.DOORS
        "window" -> CcSecGroup.WINDOWS
        in HAZARD_CLASSES -> CcSecGroup.HAZARDS
        in MOTION_CLASSES -> CcSecGroup.MOTION
        TAMPER_CLASS -> CcSecGroup.TAMPER
        else -> null
      }
      else -> null
    }
  }

  /** Sections with at least one row; sections needing attention first, then the fixed order. */
  fun sections(entities: Collection<CcEntity>, settings: ControlCenterConfig.Settings, areaOf: (String) -> String?): List<CcSecSection> {
    val grouped = entities.filter { !CcPolicy.hidden(it, settings) }
        .mapNotNull { e -> groupOf(e)?.let { it to item(e, it, areaOf(e.id)) } }
        .groupBy({ it.first }, { it.second })
    return CcSecGroup.entries.mapNotNull { g -> grouped[g]?.let { section(g, it) } }
        .sortedWith(compareBy({ if (it.ok == false) 0 else 1 }, { it.group.ordinal }))
  }

  fun item(e: CcEntity, group: CcSecGroup, area: String?): CcSecItem {
    val attention = !e.unavailable && when (group) {
      CcSecGroup.ALARM -> e.state == "triggered" || e.state == "pending"
      CcSecGroup.LOCKS, CcSecGroup.GARAGE, CcSecGroup.DOORS, CcSecGroup.WINDOWS -> e.isOpen || e.state == "jammed" ||
          e.domain == "binary_sensor" && e.state == "on"
      CcSecGroup.HAZARDS, CcSecGroup.TAMPER -> e.state == "on"
      CcSecGroup.MOTION -> false
    }
    val action = when {
      e.unavailable -> null
      group == CcSecGroup.GARAGE && e.domain == "cover" && (e.state == "open" || e.state == "opening") -> CcSecAction.CLOSE
      group == CcSecGroup.LOCKS && (e.state == "unlocked" || e.state == "open") -> CcSecAction.LOCK
      else -> null
    }
    return CcSecItem(e.id, e.name, area, stateWord(e, group), e.lastChanged, attention, e.unavailable, action)
  }

  private fun section(g: CcSecGroup, rows: List<CcSecItem>): CcSecSection {
    val items = sortRows(rows)
    val n = items.count { it.attention }
    val (tag, ok) = when {
      g == CcSecGroup.MOTION -> items.count(::active).let { if (it == 0) "Quiet" to null else "$it active" to null }
      g == CcSecGroup.ALARM -> (items.first().state) to (n == 0).takeIf { !items.first().unavailable }
      n == 0 -> "OK" to true
      else -> "$n ${problemWord(g, n)}" to false
    }
    return CcSecSection(g, items, tag, ok)
  }

  /** Attention first, then the ones that report, unavailable last; each by name. */
  fun sortRows(rows: List<CcSecItem>): List<CcSecItem> =
      rows.sortedWith(compareBy({ if (it.attention) 0 else if (it.unavailable) 2 else 1 }, { it.name.lowercase() }, { it.id }))

  fun active(i: CcSecItem) = i.state == "Motion" || i.state == "Occupied"

  private fun problemWord(g: CcSecGroup, n: Int): String = when (g) {
    CcSecGroup.LOCKS -> "unlocked"
    CcSecGroup.HAZARDS -> if (n == 1) "alert" else "alerts"
    CcSecGroup.TAMPER -> "tampered"
    else -> "open"
  }

  fun summary(s: CcSecSection): String {
    val live = s.items.filter { !it.unavailable }
    val off = s.items.size - live.size
    val unavailable = if (off > 0) " · $off unavailable" else ""
    val att = s.attention
    if (att.isNotEmpty()) return att.joinToString(", ") { it.name } + unavailable
    val all = live.size
    val line = when (s.group) {
      CcSecGroup.ALARM -> s.items.first().state
      CcSecGroup.LOCKS -> if (all == 1) "Locked" else "All $all locked"
      CcSecGroup.HAZARDS -> if (all == 1) "Clear" else "All $all clear"
      CcSecGroup.TAMPER -> if (all == 1) "No tampering" else "All $all OK"
      CcSecGroup.MOTION -> live.count(::active)
          .let { if (it == 0) "No motion right now · $all sensors" else "Active in $it of $all · ignored in Home & Night" }
      else -> if (all == 1) "Closed" else "All $all closed"
    }
    return if (all == 0) "${s.items.size} unavailable" else line + unavailable
  }

  /** The state in words: "Open", "Unlocked", "Smoke", "Motion", "Unavailable"… */
  fun stateWord(e: CcEntity, group: CcSecGroup): String {
    if (e.state == "unavailable") return "Unavailable"
    if (e.state == "unknown" || e.state.isEmpty()) return "Unknown"
    return when (group) {
      CcSecGroup.ALARM -> alarmLabel(e.state)
      CcSecGroup.LOCKS -> pretty(e.state)
      CcSecGroup.GARAGE, CcSecGroup.DOORS, CcSecGroup.WINDOWS ->
        if (e.domain == "binary_sensor") (if (e.state == "on") "Open" else "Closed") else pretty(e.state)
      CcSecGroup.HAZARDS -> if (e.state != "on") "Clear" else when (e.deviceClass) {
        "smoke" -> "Smoke"; "carbon_monoxide" -> "CO detected"; "gas" -> "Gas detected"; "moisture" -> "Wet"; else -> "Unsafe"
      }
      CcSecGroup.MOTION -> if (e.state == "on") (if (e.deviceClass == "motion") "Motion" else "Occupied") else "Clear"
      CcSecGroup.TAMPER -> if (e.state == "on") "Tampered" else "OK"
    }
  }

  private fun pretty(state: String) = state.replace('_', ' ').replaceFirstChar { it.uppercase() }

  /** "Open since 7:25 pm" / "since Tue 7:25 pm"; empty when HA gave no time. */
  fun sinceLine(since: Long, now: Long, time: (Long) -> String, day: (Long) -> String): String = when {
    since <= 0 -> ""
    now - since < DAY_MS && day(since) == day(now) -> "since ${time(since)}"
    else -> "since ${day(since)} ${time(since)}"
  }

  private const val DAY_MS = 24L * 60 * 60 * 1000
}
