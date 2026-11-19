/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Car commands (pure, unit tested). Every one is guarded ([CcGuard]): kids mode never sends them,
 * and unlock / open frunk / open trunk / open charge port always confirm first. They go through
 * [CcUi.act] → [CcPolicy] → [ControlCenter.send] like every other control, so dry run applies.
 */
internal object CcTeslaCalls {
  fun charge(e: CcEntity, on: Boolean) = CcCall("switch", if (on) "turn_on" else "turn_off", e.id,
      optimistic = CcOptimistic(state = if (on) "on" else "off"), label = if (on) "Charging started" else "Charging stopped", guard = CcGuard.VEHICLE)

  fun limit(e: CcEntity, pct: Int) = CcCall("number", "set_value", e.id, mapOf("value" to pct),
      CcOptimistic(state = pct.toString()), "Charge limit $pct%", CcGuard.VEHICLE)

  fun climate(e: CcEntity, on: Boolean) = CcCall("climate", if (on) "turn_on" else "turn_off", e.id,
      optimistic = CcOptimistic(hvacMode = if (on) "heat_cool" else "off"), label = if (on) "Climate on" else "Climate off", guard = CcGuard.VEHICLE)

  fun lock(e: CcEntity) = CcCall("lock", "lock", e.id, optimistic = CcOptimistic(state = "locked"), label = "Car locked", guard = CcGuard.VEHICLE)

  fun unlock(e: CcEntity) = CcCall("lock", "unlock", e.id, optimistic = CcOptimistic(state = "unlocked"), label = "Car unlocked", guard = CcGuard.CONFIRM)

  /** Frunk, trunk or charge port: opening always confirms; closing (where the car can) doesn't. */
  fun cover(e: CcEntity, open: Boolean, what: String) = CcCall("cover", if (open) "open_cover" else "close_cover", e.id,
      optimistic = CcOptimistic(state = if (open) "open" else "closed"), label = "$what ${if (open) "opened" else "closed"}",
      guard = if (open) CcGuard.CONFIRM else CcGuard.VEHICLE)

  fun press(e: CcEntity, label: String) = CcCall("button", "press", e.id, label = label, guard = CcGuard.VEHICLE)

  /** The confirm dialog for a [CcGuard.CONFIRM] call: title, body, button. */
  fun confirm(car: String, call: CcCall): Triple<String, String, String> = when {
    call.domain == "lock" -> Triple("Unlock $car?", "Anyone next to the car can open it once it's unlocked.", "Unlock")
    "frunk" in call.label.lowercase() -> Triple("Open the frunk?", "The frunk opens now and has to be closed by hand.", "Open frunk")
    "trunk" in call.label.lowercase() -> Triple("Open the trunk?", "The trunk opens now. Make sure nothing is behind the car.", "Open trunk")
    else -> Triple("Open the charge port?", "The charge port door opens now so a cable can go in.", "Open port")
  }

  /** Charge limit after a −/+ tap: steps of [STEP], clamped to the car's range. */
  fun stepLimit(current: Int, up: Boolean, min: Int, max: Int): Int = (current + if (up) STEP else -STEP).coerceIn(min, max)

  const val STEP = 5
}

/** One control tile: what it shows and the call it makes ([on] = the thing is currently on/open). */
@androidx.compose.runtime.Immutable
internal data class CcCarAction(val key: String, val icon: String, val label: String, val call: CcCall, val on: Boolean = false)

/** The controls the car offers right now (pure): only roles HA has, each with its current direction. */
internal object CcTeslaActions {
  fun list(ids: CcTeslaIds, entities: Map<String, CcEntity>, v: CcCarView): List<CcCarAction> {
    fun e(r: CcCarRole) = ids.car[r]?.let(entities::get)?.takeIf { !it.unavailable }
    // A button's state is the time it was last pressed: "unknown" until the first press, so only "unavailable" counts.
    fun button(r: CcCarRole) = ids.car[r]?.let(entities::get)?.takeIf { it.state != "unavailable" }
    return listOfNotNull(
        e(CcCarRole.CHARGE_SWITCH)?.takeIf { v.pluggedIn != false }?.let { sw ->
          CcCarAction("charge", "bolt", if (sw.isOn) "Stop charging" else "Start charging", CcTeslaCalls.charge(sw, !sw.isOn), sw.isOn)
        },
        e(CcCarRole.CLIMATE)?.let { cl ->
          val on = cl.state != "off"
          CcCarAction("climate", "fan", if (on) "Climate off" else "Precondition", CcTeslaCalls.climate(cl, !on), on)
        },
        e(CcCarRole.LOCK)?.let { l ->
          val locked = l.state == "locked"
          CcCarAction("lock", if (locked) "unlock" else "lock", if (locked) "Unlock" else "Lock", if (locked) CcTeslaCalls.unlock(l) else CcTeslaCalls.lock(l), !locked)
        },
        button(CcCarRole.FLASH)?.let { CcCarAction("flash", "bulb", "Flash lights", CcTeslaCalls.press(it, "Lights flashed")) },
        button(CcCarRole.HORN)?.let { CcCarAction("horn", "vol", "Honk", CcTeslaCalls.press(it, "Horn honked")) },
        e(CcCarRole.FRUNK)?.takeIf { !it.isOpen }?.let { CcCarAction("frunk", "cube", "Open frunk", CcTeslaCalls.cover(it, true, "Frunk")) },
        e(CcCarRole.TRUNK)?.let { t ->
          CcCarAction("trunk", "cube", if (t.isOpen) "Close trunk" else "Open trunk", CcTeslaCalls.cover(t, !t.isOpen, "Trunk"), t.isOpen)
        },
        e(CcCarRole.CHARGE_PORT)?.let { p ->
          CcCarAction("port", "charger", if (p.isOpen) "Close port" else "Open port", CcTeslaCalls.cover(p, !p.isOpen, "Charge port"), p.isOpen)
        },
    )
  }
}
