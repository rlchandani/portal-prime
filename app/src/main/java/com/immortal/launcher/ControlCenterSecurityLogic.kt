/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable
import kotlin.math.ceil

/** One row of the Security checklist; [ok] null = informational (no tag colour). */
@Immutable
internal data class CcCheck(val icon: String, val title: String, val detail: String, val tag: String?, val ok: Boolean?)

/** A WHO'S HOME card: the person, their avatar colour slot, home or not, and the line under the name. */
@Immutable
internal data class CcPersonItem(val id: String, val name: String, val index: Int, val home: Boolean, val line: String)

/** The readiness card: "Armed · Away" and a short [line] under it (null = one line, e.g. "Ready to arm."). */
@Immutable
internal data class CcReadiness(val title: String, val line: String?, val tone: CcRingTone)

/** The side column's slice: equal (and skipped) while nothing it shows changed. */
@Immutable
internal data class CcSecuritySideSlice(val people: List<CcPersonItem>, val checks: List<CcCheck>, val open: Int,
    val readiness: CcReadiness = CcReadiness(CcSecurity.note(open), null, CcRingTone.OFF),
    /** Every security entity, grouped (the SENSORS card); see [CcSecurityList]. */
    val sections: List<CcSecSection> = emptyList(),
    val canControl: Boolean = false,
    val reason: String? = null)

/** How the big ring looks. */
internal enum class CcRingTone { OFF, ARMED, ARMING, ALERT }

/** Pure Security screen rules (unit tested): exit delay, ring state, checklist, who's home. */
internal object CcSecurity {
  private const val MS = 1000L
  private val VALVE_HINTS = listOf("valve", "water_main", "main_water")

  /** Seconds left of the exit delay, from when HA entered `arming`; 0..[delaySec]. */
  fun exitRemaining(armingSince: Long, delaySec: Int, now: Long): Int {
    if (delaySec <= 0 || armingSince <= 0) return 0
    val left = (armingSince + delaySec * MS - now).coerceAtLeast(0L)
    return ceil(left / MS.toDouble()).toInt().coerceIn(0, delaySec)
  }

  /** Share of the delay still to run, 1 → 0 (the countdown ring). */
  fun exitFraction(remaining: Int, delaySec: Int): Float = if (delaySec <= 0) 0f else (remaining.toFloat() / delaySec).coerceIn(0f, 1f)

  fun tone(state: String): CcRingTone = when {
    state == "arming" -> CcRingTone.ARMING
    state == "pending" || state == "triggered" -> CcRingTone.ALERT
    state.startsWith("armed") -> CcRingTone.ARMED
    else -> CcRingTone.OFF
  }

  /** The mode HA is in (or arming towards, from the `next_state` attribute some panels expose). */
  fun currentMode(panel: CcEntity): CcAlarmMode? = when (panel.state) {
    "disarmed" -> CcAlarmMode.DISARM
    "arming", "pending" -> (panel.attrs["next_state"] as? String)?.let(::modeOf)
    else -> modeOf(panel.state)
  }

  private fun modeOf(state: String): CcAlarmMode? = when (state) {
    "armed_home" -> CcAlarmMode.HOME
    "armed_away" -> CcAlarmMode.AWAY
    "armed_night" -> CcAlarmMode.NIGHT
    "armed_vacation" -> CcAlarmMode.VACATION
    "disarmed" -> CcAlarmMode.DISARM
    else -> null
  }

  /** "Arming Away" while arming, else the header's label ("Armed · Night"). */
  fun title(panel: CcEntity): String = if (panel.state == "arming")
    "Arming${currentMode(panel)?.let { " ${modeWord(it)}" } ?: ""}" else alarmLabel(panel.state)

  fun subtitle(panel: CcEntity, remaining: Int): String = when (tone(panel.state)) {
    CcRingTone.ARMING -> if (remaining > 0) "Leave now · ${remaining}s to go" else "Finishing arming…"
    CcRingTone.ARMED -> "Disarming needs the household PIN"
    CcRingTone.ALERT -> if (panel.state == "pending") "Enter the PIN to stop the alarm" else "The alarm is sounding · disarm with the PIN"
    CcRingTone.OFF -> "Arm with one tap · open doors & windows are checked first"
  }

  fun modeWord(m: CcAlarmMode): String = when (m) {
    CcAlarmMode.DISARM -> "Off"; CcAlarmMode.HOME -> "Home"; CcAlarmMode.AWAY -> "Away"
    CcAlarmMode.NIGHT -> "Night"; CcAlarmMode.VACATION -> "Vacation"
  }

  fun modeIcon(m: CcAlarmMode): String = when (m) {
    CcAlarmMode.DISARM -> "shield"; CcAlarmMode.HOME -> "home"; CcAlarmMode.AWAY -> "leave"
    CcAlarmMode.NIGHT -> "moon"; CcAlarmMode.VACATION -> "sun"
  }

  /** What tapping [mode] opens: the arm check, the PIN pad to disarm, or nothing (already there). */
  fun tap(panel: CcEntity, mode: CcAlarmMode): CcModal? = when {
    mode == CcAlarmMode.DISARM && panel.state == "disarmed" -> null
    mode == CcAlarmMode.DISARM -> CcModal.Pin(panel.id, CcAlarmMode.DISARM, "Disarm", "Alarm is ${alarmLabel(panel.state).lowercase()}")
    panel.state.startsWith("armed") && currentMode(panel) == mode -> null
    else -> CcModal.ArmCheck(panel.id, mode)
  }

  /** Doors & locks, windows, garage, water valve and motion — only the rows this house has. */
  fun checklist(entities: Collection<CcEntity>, time: (Long) -> String): List<CcCheck> {
    val live = entities.filter { !it.unavailable }
    return listOfNotNull(doors(live), windows(live), garage(live, time), valve(live), motion(live))
  }

  private fun doors(all: List<CcEntity>): CcCheck? {
    val doors = all.filter { CcKind.of(it) == CcKind.DOOR }
    val locks = all.filter { it.domain == "lock" }
    if (doors.isEmpty() && locks.isEmpty()) return null
    val open = doors.filter { it.isOpen }
    val unlocked = locks.filter { it.isOpen }
    val problems = open.map { "${it.name} open" } + unlocked.map { "${it.name} unlocked" }
    if (problems.isEmpty()) return CcCheck("door", "Doors & locks", allGood(doors.size, locks.size), "OK", true)
    val tag = if (open.isNotEmpty()) "${open.size} open" else "${unlocked.size} unlocked"
    return CcCheck("door", "Doors & locks", problems.joinToString(" · "), tag, false)
  }

  private fun allGood(doors: Int, locks: Int): String = when {
    locks == 0 -> "All $doors closed"
    doors == 0 -> if (locks == 1) "Locked" else "All $locks locked"
    else -> "All closed and locked"
  }

  private fun windows(all: List<CcEntity>): CcCheck? {
    val windows = all.filter { CcKind.of(it) == CcKind.WINDOW }
    if (windows.isEmpty()) return null
    val open = windows.filter { it.isOpen }
    return if (open.isEmpty()) CcCheck("window", "Windows", "All closed", "OK", true)
    else CcCheck("window", "Windows", open.joinToString(", ") { it.name }, "${open.size} open", false)
  }

  private fun garage(all: List<CcEntity>, time: (Long) -> String): CcCheck? {
    val garages = all.filter { CcKind.of(it) == CcKind.GARAGE }
    if (garages.isEmpty()) return null
    val open = garages.filter { it.isOpen }.minByOrNull { it.lastChanged }
    return if (open == null) CcCheck("garage", "Garage", "Closed", "OK", true)
    else CcCheck("garage", "Garage", if (open.lastChanged > 0) "Open since ${time(open.lastChanged)}" else "Open", "Open", false)
  }

  /** The water main: a valve entity or a valve-like switch. */
  fun valves(all: Collection<CcEntity>): List<CcEntity> = all.filter { e ->
    e.domain == "valve" || e.domain == "switch" && VALVE_HINTS.any { it in e.id }
  }

  private fun valve(all: List<CcEntity>): CcCheck? {
    val v = valves(all).firstOrNull() ?: return null
    val closed = v.state == "closed" || v.state == "off"
    return if (closed) CcCheck("valve", "Water", "${v.name} is shut off", "Off", false)
    else CcCheck("valve", "Water", "${v.name} is on", "OK", true)
  }

  private fun motion(all: List<CcEntity>): CcCheck? {
    val sensors = all.filter { CcKind.of(it) == CcKind.MOTION }
    if (sensors.isEmpty()) return null
    val on = sensors.count { it.state == "on" }
    val what = if (on == 0) "No motion right now" else "Motion in $on ${if (on == 1) "place" else "places"}"
    return CcCheck("motion", "Motion", "$what · ignored in Home & Night", null, null)
  }

  /**
   * The side column. With [named] (the map may show names, [CcPeoplePolicy]) a person at home also gets
   * the room Who's there? last saw them in from [occupancy] ("Home · Kitchen · 3 min ago").
   */
  fun side(people: List<CcEntity>, entities: Collection<CcEntity>, time: (Long) -> String, panel: CcEntity? = null,
           occupancy: CcOccupancy = CcOccupancy.NONE, named: Boolean = false, now: Long = 0L): CcSecuritySideSlice {
    val open = openCount(entities)
    return CcSecuritySideSlice(people.mapIndexed { i, p ->
      CcPersonItem(p.id, p.name, i, isHome(p), CcPeoplePolicy.whoLine(personLine(p), p.name, isHome(p), occupancy, named, now))
    }, checklist(entities, time), open, readiness(panel, open))
  }

  /** The readiness card, from the alarm_control_panel state; disarmed (or no panel) says what arming will ask about. */
  fun readiness(panel: CcEntity?, open: Int): CcReadiness {
    if (panel == null || panel.state == "disarmed") return CcReadiness(note(open), null, CcRingTone.OFF)
    if (panel.unavailable) return CcReadiness("Alarm unavailable", "Home Assistant can't reach the alarm panel.", CcRingTone.OFF)
    val line = when (panel.state) {
      "armed_away", "armed_vacation" -> "The whole house is watched."
      "armed_home", "armed_night" -> "Doors and windows are watched · motion is ignored."
      "arming" -> "Exit delay running · leave now."
      "pending" -> "Enter the PIN to stop the alarm."
      "triggered" -> "The alarm is sounding · disarm with the PIN."
      else -> null
    }
    return CcReadiness(title(panel), line, tone(panel.state))
  }

  /** Things still open that arming will ask about (doors, windows, garage). */
  fun openCount(entities: Collection<CcEntity>): Int = entities.count { e ->
    !e.unavailable && CcKind.of(e) in setOf(CcKind.DOOR, CcKind.WINDOW, CcKind.GARAGE) && e.isOpen
  }

  fun note(open: Int): String = when (open) {
    0 -> "Ready to arm."
    1 -> "1 thing open — you'll be asked before arming."
    else -> "$open things open — you'll be asked before arming."
  }

  fun isHome(person: CcEntity): Boolean = person.state == "home"

  /** "Home · Phone · Wi-Fi", "Away", or the zone ("Work"). */
  fun personLine(person: CcEntity): String {
    val where = when (person.state) {
      "home" -> "Home"
      "not_home", "away" -> "Away"
      "unknown", "unavailable", "" -> "Unknown"
      else -> person.state.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
    return listOfNotNull(where, sourceLabel(person.attrs["source"] as? String)).joinToString(" · ")
  }

  /** A tracker id read as people say it: "device_tracker.alex_phone_wifi" → "Phone · Wi-Fi". */
  fun sourceLabel(source: String?): String? {
    val s = source?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    val parts = listOfNotNull(
        "Phone".takeIf { "phone" in s || "pixel" in s || "iphone" in s || "mobile" in s },
        "Watch".takeIf { "watch" in s },
        "Wi-Fi".takeIf { listOf("wifi", "wi_fi", "router", "unifi", "ping", "nmap", "fritz").any { it in s } },
        "Room sensor".takeIf { listOf("ble", "bermuda", "espresense", "room").any { it in s } },
        "GPS".takeIf { "gps" in s },
    )
    return if (parts.isNotEmpty()) parts.joinToString(" · ") else null
  }
}
