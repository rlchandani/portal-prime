/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable

/**
 * Whether the car is talking to Home Assistant. The screen only reads what the integration already
 * has (its own polling respects the car's sleep); nothing here polls or wakes the car — waking is
 * the Wake button alone.
 */
internal enum class CcCarLife { AWAKE, ASLEEP, OFFLINE, STALE }

internal enum class CcChargeState { CHARGING, STARTING, STOPPED, COMPLETE, PLUGGED, UNPLUGGED, NO_POWER, UNKNOWN }

/** One tyre: pressure text ("42" in the entity's unit) and the car's soft warning. */
@Immutable
internal data class CcTyre(val pressure: Int?, val warn: Boolean)

/** Everything the Tesla screen shows about the car, already formatted where it's pure text. */
@Immutable
internal data class CcCarView(
    val life: CcCarLife,
    /** Epoch ms the car last reported (for "updated 2 h ago"); 0 when unknown. */
    val updatedAt: Long,
    val batteryPct: Int?,
    val range: String?,
    val charge: CcChargeState,
    val powerKw: Double?,
    val energyAddedKwh: Double?,
    /** Epoch ms the car expects to be full, when charging and the integration reports it. */
    val fullAt: Long?,
    val limitPct: Int?,
    val limitMin: Int,
    val limitMax: Int,
    val pluggedIn: Boolean?,
    val home: Boolean?,
    /** "Home", "Away" or the HA zone name; never coordinates. */
    val place: String?,
    val insideTemp: String?,
    val outsideTemp: String?,
    val climateOn: Boolean?,
    val locked: Boolean?,
    val sentry: Boolean?,
    val tyres: List<CcTyre>,
    val tyreUnit: String,
    /** Human names of everything open ("Frunk", "Rear left window"); empty = all closed. */
    val open: List<String>,
    /** False when no door, window, frunk or trunk sensor is reporting (then "All closed" would be a guess). */
    val openingsKnown: Boolean,
    val frunkOpen: Boolean?,
    val trunkOpen: Boolean?,
    val portOpen: Boolean?,
    val odometer: String?,
    val installed: String?,
    /** The pending software version, when an update is ready. */
    val updateTo: String?,
) {
  val charging: Boolean get() = charge == CcChargeState.CHARGING || charge == CcChargeState.STARTING
}

internal object CcTeslaCar {
  private const val PCT_MAX = 100
  private const val DEFAULT_LIMIT_MIN = 50
  private const val MS_PER_MIN = 60_000L
  private val ASLEEP_STATES = setOf("asleep", "sleeping", "suspended")
  private val OFFLINE_STATES = setOf("offline")
  private val DOORS = listOf(CcCarRole.DOOR_FL to "Front left door", CcCarRole.DOOR_FR to "Front right door",
      CcCarRole.DOOR_RL to "Rear left door", CcCarRole.DOOR_RR to "Rear right door")
  private val WINDOWS = listOf(CcCarRole.WINDOW_FL to "Front left window", CcCarRole.WINDOW_FR to "Front right window",
      CcCarRole.WINDOW_RL to "Rear left window", CcCarRole.WINDOW_RR to "Rear right window")
  private val TYRES = listOf(CcCarRole.TYRE_FL to CcCarRole.TYRE_WARN_FL, CcCarRole.TYRE_FR to CcCarRole.TYRE_WARN_FR,
      CcCarRole.TYRE_RL to CcCarRole.TYRE_WARN_RL, CcCarRole.TYRE_RR to CcCarRole.TYRE_WARN_RR)

  /** Null when there's no car; [phase] is the HA connection (a stale socket makes every value stale). */
  fun view(ids: CcTeslaIds, entities: Map<String, CcEntity>, phase: CcPhase): CcCarView? {
    if (!ids.hasCar) return null
    fun e(r: CcCarRole) = ids.car[r]?.let(entities::get)
    fun live(r: CcCarRole) = e(r)?.takeIf { !it.unavailable }
    val battery = live(CcCarRole.BATTERY)
    val limit = e(CcCarRole.CHARGE_LIMIT)
    val tracker = live(CcCarRole.LOCATION)
    val update = live(CcCarRole.UPDATE)
    return CcCarView(
        life = life(e(CcCarRole.STATUS), e(CcCarRole.STATE), battery, phase),
        updatedAt = updatedAt(ids, entities),
        batteryPct = battery?.numeric?.toInt()?.coerceIn(0, PCT_MAX),
        range = live(CcCarRole.RANGE)?.let { r -> r.numeric?.let { "${Math.round(it)} ${r.unit ?: "mi"}" } },
        charge = charge(live(CcCarRole.CHARGING)?.state, live(CcCarRole.CABLE)?.isOn),
        powerKw = live(CcCarRole.CHARGER_POWER)?.numeric,
        energyAddedKwh = live(CcCarRole.ENERGY_ADDED)?.numeric,
        fullAt = fullAt(live(CcCarRole.TIME_TO_FULL)),
        limitPct = limit?.takeIf { !it.unavailable }?.numeric?.toInt(),
        limitMin = (limit?.attrs?.get("min") as? Number)?.toInt() ?: DEFAULT_LIMIT_MIN,
        limitMax = (limit?.attrs?.get("max") as? Number)?.toInt() ?: PCT_MAX,
        pluggedIn = live(CcCarRole.CABLE)?.isOn,
        home = tracker?.let { it.state == "home" },
        place = tracker?.let(::place),
        insideTemp = temp(live(CcCarRole.INSIDE_TEMP)),
        outsideTemp = temp(live(CcCarRole.OUTSIDE_TEMP)),
        climateOn = live(CcCarRole.CLIMATE)?.let { it.state != "off" },
        locked = live(CcCarRole.LOCK)?.let { it.state == "locked" },
        sentry = live(CcCarRole.SENTRY)?.isOn,
        tyres = TYRES.map { (p, w) -> CcTyre(live(p)?.numeric?.let { Math.round(it).toInt() }, live(w)?.isOn == true) },
        tyreUnit = live(CcCarRole.TYRE_FL)?.unit ?: "psi",
        open = openings(::live),
        openingsKnown = (DOORS + WINDOWS).any { live(it.first) != null } || live(CcCarRole.FRUNK) != null || live(CcCarRole.TRUNK) != null,
        frunkOpen = live(CcCarRole.FRUNK)?.isOpen,
        trunkOpen = live(CcCarRole.TRUNK)?.isOpen,
        portOpen = live(CcCarRole.CHARGE_PORT)?.isOpen,
        odometer = live(CcCarRole.ODOMETER)?.let { o -> o.numeric?.let { "%,d %s".format(Math.round(it), o.unit ?: "mi") } },
        installed = update?.attrs?.get("installed_version") as? String,
        updateTo = update?.takeIf { it.state == "on" }?.let { it.attrs["latest_version"] as? String ?: "" },
    )
  }

  /**
   * HA gone stale beats everything; then the integration losing the car (battery unavailable or an
   * "offline" state); then sleep (Fleet/Tessie's connectivity sensor off, or an "asleep" state).
   */
  fun life(status: CcEntity?, state: CcEntity?, battery: CcEntity?, phase: CcPhase): CcCarLife {
    if (phase != CcPhase.LIVE) return CcCarLife.STALE
    val word = state?.takeIf { !it.unavailable }?.state?.lowercase()
    return when {
      battery == null || word in OFFLINE_STATES -> CcCarLife.OFFLINE
      word in ASLEEP_STATES -> CcCarLife.ASLEEP
      status != null && !status.unavailable && !status.isOn -> CcCarLife.ASLEEP
      else -> CcCarLife.AWAKE
    }
  }

  /** When the car last reported: the moment it fell asleep, else its freshest value. */
  private fun updatedAt(ids: CcTeslaIds, entities: Map<String, CcEntity>): Long {
    val status = ids.car[CcCarRole.STATUS]?.let(entities::get)
    if (status != null && !status.unavailable && !status.isOn && status.lastChanged > 0) return status.lastChanged
    return ids.car.values.mapNotNull { entities[it]?.lastChanged }.maxOrNull() ?: 0L
  }

  fun charge(state: String?, cable: Boolean?): CcChargeState = when (state?.lowercase()) {
    "charging" -> CcChargeState.CHARGING
    "starting" -> CcChargeState.STARTING
    "stopped" -> CcChargeState.STOPPED
    "complete" -> CcChargeState.COMPLETE
    "no_power" -> CcChargeState.NO_POWER
    "disconnected" -> if (cable == true) CcChargeState.PLUGGED else CcChargeState.UNPLUGGED
    else -> when (cable) { true -> CcChargeState.PLUGGED; false -> CcChargeState.UNPLUGGED; null -> CcChargeState.UNKNOWN }
  }

  /** Fleet/Teslemetry report a timestamp; Tessie and others report minutes (or hours, by unit). */
  private fun fullAt(e: CcEntity?): Long? {
    e ?: return null
    if (e.deviceClass == "timestamp") return CcEntityParser.isoMs(e.state).takeIf { it > 0 }
    val n = e.numeric?.takeIf { it > 0 } ?: return null
    val mins = if (e.unit == "h") n * MINUTES_PER_HOUR else n
    return e.lastChanged + (mins * MS_PER_MIN).toLong()
  }

  private const val MINUTES_PER_HOUR = 60.0

  private fun place(t: CcEntity): String = when (t.state) {
    "home" -> "Home"
    "not_home" -> "Away"
    else -> t.state.replace('_', ' ').replaceFirstChar { it.uppercase() }
  }

  private fun temp(e: CcEntity?): String? = e?.numeric?.let { "${Math.round(it)}°" }

  private fun openings(live: (CcCarRole) -> CcEntity?): List<String> {
    val sensors = (DOORS + WINDOWS).filter { (r, _) -> live(r)?.state == "on" }.map { it.second }
    val covers = listOf(CcCarRole.FRUNK to "Frunk", CcCarRole.TRUNK to "Trunk").filter { (r, _) -> live(r)?.isOpen == true }.map { it.second }
    return covers + sensors
  }
}
