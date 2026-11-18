/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * What the room sheet shows, derived purely from a [CcUiState] (unit tested): the devices in the
 * mock's order, the sensors as read-only rows, the room's thermostat and camera, its scenes,
 * who is there, and each tile's one-line status.
 */
@androidx.compose.runtime.Immutable
internal data class CcRoomContents(
    val tiles: List<CcEntity>,
    val sensors: List<CcEntity>,
    val cameraId: String?,
    val climate: CcEntity?,
    /** Things kids mode keeps off this Portal (counted, never named). */
    val hiddenCount: Int,
)

internal enum class CcTileTone { NONE, ON, WARN, LOCKED }

internal object CcSheetLogic {
  private const val MS_PER_MIN = 60_000L
  private const val MIN_PER_H = 60

  /** Tile groups in sheet order: lights, media, covers, fans, switches, appliances, locks & garage. */
  private val TILE_ORDER = listOf(
      setOf(CcKind.LIGHT), setOf(CcKind.MEDIA), setOf(CcKind.COVER), setOf(CcKind.FAN), setOf(CcKind.SWITCH),
      setOf(CcKind.APPLIANCE), setOf(CcKind.LOCK, CcKind.GARAGE, CcKind.VALVE), setOf(CcKind.CLIMATE))
  private val SENSOR_KINDS = setOf(CcKind.DOOR, CcKind.WINDOW, CcKind.MOTION, CcKind.LEAK, CcKind.SMOKE, CcKind.TEMPERATURE,
      CcKind.HUMIDITY, CcKind.POWER, CcKind.ENERGY, CcKind.BATTERY, CcKind.SENSOR)

  fun contents(s: CcUiState, room: CcRoom): CcRoomContents {
    val placed = room.devices.mapNotNull { s.entity(it.entityId) }.distinctBy { it.id }
    val visible = placed.filter { !CcPolicy.hidden(it, s.settings) }
    val climate = climateFor(s, room)
    val tiles = TILE_ORDER.flatMap { group -> visible.filter { CcKind.of(it) in group && it.id != climate?.id } }
    val sensors = visible.filter { CcKind.of(it) in SENSOR_KINDS }.sortedBy { it.name.lowercase() }
    val camera = visible.firstOrNull { it.domain == "camera" }?.id
    return CcRoomContents(tiles, sensors, camera, climate, placed.size - visible.size)
  }

  /** The thermostat serving [room]: one placed in it (kids-hidden ones excluded). */
  fun climateFor(s: CcUiState, room: CcRoom): CcEntity? {
    val climates = s.entities.values.filter { it.domain == "climate" }
    val id = CcRooms.view(room, s.entities, climates).climateId ?: return null
    return s.entity(id)?.takeIf { !CcPolicy.hidden(it, s.settings) }
  }

  /** Plan rooms this thermostat is placed in (honest: we don't guess which rooms it heats). */
  fun thermostatRooms(plan: CcPlan?, climateId: String): List<CcRoom> =
      plan?.floors?.flatMap { f -> f.rooms.filter { r -> r.devices.any { it.entityId == climateId } } }.orEmpty()

  /** HA scenes touching this room's devices (kids-hidden excluded), by name. */
  fun scenes(s: CcUiState, room: CcRoom): List<CcEntity> {
    val ids = room.devices.map { it.entityId }.toSet()
    return s.ofDomain("scene").filter { sc -> sc.members.any { it in ids } }
  }

  /** Lights in the room that are on (for "All off" and the "N on" chip). */
  fun lightsOn(s: CcUiState, room: CcRoom): List<CcEntity> =
      room.devices.mapNotNull { s.entity(it.entityId) }.filter { it.domain == "light" && it.isOn && !CcPolicy.hidden(it, s.settings) }

  /** Alerts about this room's devices. */
  fun alerts(s: CcUiState, room: CcRoom): List<CcAlert> {
    val ids = room.devices.map { it.entityId }.toSet()
    return s.alerts().filter { it.entityId in ids }
  }

  /**
   * "Alex & Mia here" when people report this room (room-presence integrations set the person's
   * state to the area name), else motion/occupancy sensors; null when nothing in HA knows.
   */
  fun presence(s: CcUiState, room: CcRoom): Pair<String, Boolean>? {
    val here = s.people.filter { it.state.equals(room.name, ignoreCase = true) || it.state.equals(room.id, ignoreCase = true) }
    if (here.isNotEmpty()) return "${here.joinToString(" & ") { it.name }} here" to true
    val sensors = room.devices.mapNotNull { s.entity(it.entityId) }.filter { CcKind.of(it) == CcKind.MOTION && !it.unavailable }
    if (sensors.isEmpty()) return null
    return if (sensors.any { it.isOn }) "Motion now" to true else "No one here" to false
  }

  /** The garage camera: one in the same plan room, else a camera named like a garage. */
  fun garageCamera(s: CcUiState, e: CcEntity): String? {
    val inRoom = s.plan?.roomOf(e.id)?.devices?.map { it.entityId }?.firstOrNull { it.startsWith("camera.") }
    return (inRoom ?: s.entities.keys.firstOrNull { it.startsWith("camera.") && "garage" in it })
        ?.takeIf { id -> s.entity(id)?.let { !CcPolicy.hidden(it, s.settings) } != false }
  }

  /** Who did the last change: HA's changed_by, else the person behind the user id. */
  fun who(s: CcUiState, e: CcEntity): String? =
      e.changedBy ?: e.userId?.let { uid -> s.people.firstOrNull { it.attrs["user_id"] == uid }?.name }

  // --- tiles ----------------------------------------------------------------------------------

  fun tone(e: CcEntity, done: Boolean = false): CcTileTone = when (CcKind.of(e)) {
    CcKind.LIGHT, CcKind.SWITCH, CcKind.MEDIA, CcKind.FAN -> if (e.isOn) CcTileTone.ON else CcTileTone.NONE
    CcKind.LOCK -> when (e.state) { "locked" -> CcTileTone.LOCKED; "unlocked", "open", "jammed" -> CcTileTone.WARN; else -> CcTileTone.NONE }
    CcKind.GARAGE, CcKind.WINDOW, CcKind.DOOR -> if (e.isOpen) CcTileTone.WARN else CcTileTone.NONE
    CcKind.LEAK, CcKind.SMOKE -> if (e.isOn) CcTileTone.WARN else CcTileTone.NONE
    CcKind.VALVE -> if (e.state == "closed") CcTileTone.WARN else CcTileTone.NONE
    CcKind.APPLIANCE -> if (done) CcTileTone.WARN else if (e.isOn) CcTileTone.ON else CcTileTone.NONE
    else -> CcTileTone.NONE
  }

  /** Light/switch/media tiles carry a power button. */
  fun hasPower(e: CcEntity): Boolean = CcKind.of(e) in setOf(CcKind.LIGHT, CcKind.SWITCH, CcKind.MEDIA) ||
      CcKind.of(e) == CcKind.APPLIANCE && e.domain in setOf("switch", "input_boolean")

  /** The tile's status line, as in the mock ("70%", "Off", "Playing · Title", "80% open"). */
  fun status(e: CcEntity, now: Long, done: Boolean = false): String {
    if (e.unavailable) return "Unavailable"
    return when (CcKind.of(e)) {
      CcKind.LIGHT -> if (e.isOn) e.brightnessPct?.let { "$it%" } ?: "On" else "Off"
      CcKind.SWITCH -> onOff(e)
      CcKind.FAN -> if (e.isOn) e.percentage?.let { "$it%" } ?: "On" else "Off"
      CcKind.LOCK -> lockText(e.state)
      CcKind.GARAGE -> garageText(e, now)
      CcKind.COVER -> coverText(e)
      CcKind.MEDIA -> mediaText(e)
      CcKind.CLIMATE -> climateText(e)
      CcKind.VALVE -> when (e.state) { "open" -> "Water on"; "closed" -> "Shut off"; "opening" -> "Opening…"; "closing" -> "Closing…"; else -> pretty(e.state) }
      CcKind.APPLIANCE -> if (done) "Done ✓" else if (e.domain == "binary_sensor" || e.domain == "switch") onOff(e, "Running") else valueText(e)
      CcKind.LEAK -> if (e.isOn) "WATER DETECTED" else "Dry"
      CcKind.SMOKE -> if (e.isOn) "DETECTED" else "Clear"
      CcKind.DOOR, CcKind.WINDOW -> if (e.isOpen) "Open" else "Closed"
      CcKind.MOTION -> if (e.isOn) "Motion now" else "Clear"
      CcKind.BATTERY -> if (e.domain == "binary_sensor") (if (e.isOn) "Low" else "OK") else valueText(e)
      else -> valueText(e)
    }
  }

  private fun onOff(e: CcEntity, on: String = "On") = if (e.isOn) on else "Off"

  fun lockText(state: String): String = when (state) {
    "locked" -> "Locked"; "unlocked" -> "Unlocked"; "locking" -> "Locking…"; "unlocking" -> "Unlocking…"
    "jammed" -> "Jammed"; "open" -> "Open"; "opening" -> "Opening…"; else -> pretty(state)
  }

  fun garageText(e: CcEntity, now: Long): String = when (e.state) {
    "opening" -> "Opening…"
    "closing" -> "Closing…"
    "closed", "off" -> "Closed"
    "open", "on" -> if (e.lastChanged > 0 && now > e.lastChanged) "Open · ${duration(now - e.lastChanged)}" else "Open"
    else -> pretty(e.state)
  }

  fun coverText(e: CcEntity): String = when {
    e.state == "opening" -> "Opening…"
    e.state == "closing" -> "Closing…"
    else -> when (val p = e.position) { null -> pretty(e.state); 0 -> "Closed"; 100 -> "Open"; else -> "$p% open" }
  }

  fun mediaText(e: CcEntity): String {
    val title = e.mediaTitle?.takeIf { it.isNotBlank() }
    return when (e.state) {
      "playing" -> title?.let { "Playing · $it" } ?: "Playing"
      "paused" -> title?.let { "Paused · $it" } ?: "Paused"
      "off", "standby" -> "Off"
      else -> pretty(e.state)
    }
  }

  fun climateText(e: CcEntity): String {
    val t = e.targetTemp?.let { temp(it) }
    return when {
      e.state == "off" -> "Off"
      t == null -> pretty(e.state)
      e.hvacAction == "heating" -> "Heating to $t"
      e.hvacAction == "cooling" -> "Cooling to $t"
      else -> "Set to $t"
    }
  }

  fun hvacLabel(mode: String): String = when (mode) {
    "heat" -> "Heat"; "cool" -> "Cool"; "auto" -> "Auto"; "heat_cool" -> "Heat/Cool"; "off" -> "Off"
    "dry" -> "Dry"; "fan_only" -> "Fan"; else -> pretty(mode)
  }

  fun valueText(e: CcEntity): String {
    val n = e.numeric ?: return pretty(e.state)
    val v = if (n % 1.0 == 0.0) n.toLong().toString() else "%.1f".format(n)
    val u = e.unit.orEmpty()
    return when { u.isEmpty() -> v; u == "%" || u.startsWith("°") -> "$v$u"; else -> "$v $u" }
  }

  fun temp(t: Double): String = "${Math.round(t)}°"

  /** "17 min", "2 h 5 min". */
  fun duration(ms: Long): String {
    val min = (ms / MS_PER_MIN).coerceAtLeast(0)
    if (min < MIN_PER_H) return "$min min"
    val h = min / MIN_PER_H; val m = min % MIN_PER_H
    return if (m == 0L) "$h h" else "$h h $m min"
  }

  fun pretty(state: String): String = state.replace('_', ' ').replaceFirstChar { it.uppercase() }
}
