/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * What the scene preview and the arming check list (pure, unit tested): the scene's members
 * grouped by kind with the rooms it touches, and the doors, windows, garages and locks that are
 * open right now.
 */
internal data class CcCheckLine(val icon: String, val text: String, val warn: Boolean = false, val entityId: String? = null)

internal object CcModalLogic {
  private val KIND_WORDS = listOf(
      CcKind.LIGHT to ("bulb" to ("light" to "lights")),
      CcKind.MEDIA to ("tv" to ("TV or speaker" to "TVs & speakers")),
      CcKind.COVER to ("blinds" to ("blind" to "blinds")),
      CcKind.GARAGE to ("garage" to ("garage door" to "garage doors")),
      CcKind.LOCK to ("lock" to ("lock" to "locks")),
      CcKind.CLIMATE to ("therm" to ("thermostat" to "thermostats")),
      CcKind.FAN to ("fan" to ("fan" to "fans")),
      CcKind.SWITCH to ("power" to ("switch" to "switches")),
  )

  /** "Sets 7 lights", "Sets 2 blinds", … then anything else as "N other devices". */
  fun sceneLines(members: List<String>, entities: Map<String, CcEntity>): List<CcCheckLine> {
    val kinds = members.map { id -> entities[id]?.let { CcKind.of(it) } }
    val lines = KIND_WORDS.mapNotNull { (kind, words) ->
      val n = kinds.count { it == kind }
      if (n == 0) null else CcCheckLine(words.first, "Sets ${count(n, words.second.first, words.second.second)}")
    }
    val known = KIND_WORDS.map { it.first }.toSet()
    val other = kinds.count { it !in known }
    return if (other == 0) lines else lines + CcCheckLine("sparkle", "Sets ${count(other, "other device", "other devices")}")
  }

  /** "Living room, Kitchen and 2 more". */
  fun roomsLine(plan: CcPlan?, roomIds: List<String>): String? {
    val names = roomIds.mapNotNull { plan?.room(it)?.name }
    if (names.isEmpty()) return null
    return if (names.size <= MAX_ROOM_NAMES) joinAnd(names)
    else names.take(MAX_ROOM_NAMES).joinToString(", ") + " and ${names.size - MAX_ROOM_NAMES} more"
  }

  /** Open doors, windows and garages, and unlocked locks — kids-hidden ones still count. */
  fun openThings(entities: Map<String, CcEntity>): List<CcEntity> =
      entities.values.filter { e ->
        when (CcKind.of(e)) {
          CcKind.DOOR, CcKind.WINDOW, CcKind.GARAGE -> e.isOpen
          CcKind.LOCK -> e.state == "unlocked" || e.state == "open" || e.state == "jammed"
          else -> false
        }
      }.sortedWith(compareBy({ CcKind.of(it) != CcKind.GARAGE }, { it.name.lowercase() }))

  fun openLine(e: CcEntity): CcCheckLine = when (CcKind.of(e)) {
    CcKind.GARAGE -> CcCheckLine("garage", "${e.name} is open", warn = true, entityId = e.id)
    CcKind.LOCK -> CcCheckLine("unlock", "${e.name} is ${if (e.state == "jammed") "jammed" else "unlocked"}", warn = true, entityId = e.id)
    CcKind.DOOR -> CcCheckLine("door", "${e.name} is open", warn = true, entityId = e.id)
    else -> CcCheckLine("window", "${e.name} is open", warn = true, entityId = e.id)
  }

  /** A garage we can close for the household (a cover, not just a sensor). */
  fun closable(e: CcEntity): Boolean = e.domain == "cover" && CcKind.of(e) == CcKind.GARAGE && e.isOpen && e.state != "closing"

  fun modeName(mode: CcAlarmMode): String = when (mode) {
    CcAlarmMode.DISARM -> "Disarm"; CcAlarmMode.HOME -> "Home"; CcAlarmMode.AWAY -> "Away"
    CcAlarmMode.NIGHT -> "Night"; CcAlarmMode.VACATION -> "Vacation"
  }

  fun openCountLine(n: Int): String = when (n) {
    0 -> "Everything is closed and locked."
    1 -> "1 thing is open:"
    else -> "$n things are open:"
  }

  /** A confirm title. The device's name stays exactly as Home Assistant gives it ("Unlock Stargate Lock?"). */
  fun confirmTitle(e: CcEntity, call: CcCall): String = when (e.domain) {
    "script" -> "Run ${e.name}?"
    "valve" -> if (call.service == "close_valve") "Shut off the water?" else "Turn the water back on?"
    else -> "Unlock ${e.name}?"
  }

  private fun count(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"

  private fun joinAnd(names: List<String>): String =
      if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " and " + names.last()

  private const val MAX_ROOM_NAMES = 3
}
