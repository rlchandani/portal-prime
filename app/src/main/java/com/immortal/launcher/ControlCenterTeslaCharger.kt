/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/** The home charger now (Wall Connector / ChargePoint session); null fields = the charger doesn't say. */
@Immutable
internal data class CcChargerView(
    val source: String,
    val powerKw: Double?,
    val sessionKwh: Double?,
    /** "Charging", "Plugged in", "Not connected", …; null when there's no status sensor. */
    val status: String?,
    val connected: Boolean?,
    val sessionCost: Double?,
    /** Mains voltage at the Wall Connector (for the idle charge-rate estimate). */
    val gridVolts: Double?,
) {
  val delivering: Boolean get() = (powerKw ?: 0.0) > CcTeslaCharger.MIN_KW
}

/** kWh per local day for the last 7 days (oldest first), plus the price when HA has one. */
@Immutable
internal data class CcChargeWeek(val starts: List<Long>, val kwh: List<Double>, val pricePerKwh: Double?) {
  val today: Double get() = kwh.lastOrNull() ?: 0.0
  val total: Double get() = kwh.sum()
  val todayCost: Double? get() = pricePerKwh?.let { it * today }
  val weekCost: Double? get() = pricePerKwh?.let { it * total }
}

internal object CcTeslaCharger {
  const val MIN_KW = 0.05
  /** Below this the Wall Connector is reporting its idle contactor, not the mains. */
  private const val MIN_GRID_V = 100.0
  private const val W_PER_KW = 1000.0

  fun view(ids: CcTeslaIds, entities: Map<String, CcEntity>): CcChargerView? {
    if (ids.charger.isEmpty()) return null
    fun live(r: CcChargerRole) = ids.charger[r]?.let(entities::get)?.takeIf { !it.unavailable }
    return CcChargerView(
        source = sourceName(ids.chargerSource),
        powerKw = live(CcChargerRole.POWER)?.let { CcRooms.watts(it) }?.let { it / W_PER_KW },
        sessionKwh = live(CcChargerRole.SESSION)?.let(::kwh),
        status = live(CcChargerRole.STATUS)?.state?.let(::status),
        connected = live(CcChargerRole.CONNECTED)?.isOn,
        sessionCost = live(CcChargerRole.COST)?.numeric,
        gridVolts = live(CcChargerRole.GRID_VOLTAGE)?.numeric?.takeIf { it > MIN_GRID_V },
    )
  }

  fun sourceName(platform: String?): String = when (platform) {
    "chargepoint" -> "ChargePoint"
    else -> "Wall Connector"
  }

  /** Wall Connector / ChargePoint status words as people say them. */
  fun status(raw: String): String = when (raw.lowercase().replace(' ', '_')) {
    "charging", "charging_reduced" -> "Charging"
    "connected", "ready", "negotiating", "waiting_car" -> "Plugged in"
    "charging_finished" -> "Finished"
    "not_connected" -> "Not connected"
    "not_charging" -> "Not charging"
    "error" -> "Fault"
    "booting" -> "Starting up"
    else -> raw.replace('_', ' ').replaceFirstChar { it.uppercase() }
  }

  private fun kwh(e: CcEntity): Double? = e.numeric?.let { if (e.unit.equals("Wh", ignoreCase = true)) it / W_PER_KW else it }

  /** The price entity's value when its unit is per kWh (e.g. "USD/kWh", "$/kWh"). */
  fun price(ids: CcTeslaIds, entities: Map<String, CcEntity>): Double? =
      ids.price?.let(entities::get)?.takeIf { it.unit?.endsWith("/kWh", ignoreCase = true) == true }?.numeric?.takeIf { it >= 0 }

  /** Statistic id → bucket start (ms) → change. HA ≥ 2023 sends ms; older sends ISO strings. */
  fun stats(result: Any?): Map<String, Map<Long, Double>> {
    val o = result as? JSONObject ?: return emptyMap()
    val out = HashMap<String, Map<Long, Double>>()
    o.keys().forEach { id ->
      out[id] = objects(o.optJSONArray(id)).mapNotNull { r ->
        val start = r.opt("start").let { s -> (s as? Number)?.toLong() ?: CcEntityParser.isoMs(s as? String) }
        if (start <= 0 || r.isNull("change")) null else start to r.optDouble("change", 0.0)
      }.toMap()
    }
    return out
  }

  /** Day buckets for [stat]; a missing day is 0 kWh, a negative change (meter reset) is ignored. */
  fun week(stat: String, stats: Map<String, Map<Long, Double>>, starts: List<Long>, price: Double?): CcChargeWeek =
      CcChargeWeek(starts, starts.map { (stats[stat]?.get(it) ?: 0.0).coerceAtLeast(0.0) }, price)

  private fun objects(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
}
