/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject

/**
 * Energy for the Control Center (pure, unit tested). Live flow comes from **power** sensors (W/kW)
 * recognised by name; totals come from HA's energy preferences (`energy/get_prefs`) and recorder
 * statistics (`recorder/statistics_during_period`, `change` per hour/day). Anything the house
 * doesn't have stays null so the screen can say so instead of inventing numbers.
 */
internal data class CcEnergyNow(
    val homeW: Double?,
    val solarW: Double?,
    /** Positive = importing from the grid, negative = exporting. */
    val gridW: Double?,
    /** Positive = battery discharging into the home, negative = charging. */
    val batteryW: Double?,
    val batteryPct: Int?,
    val chargerW: Double?,
    val consumers: List<CcConsumer>,
) {
  val hasFlow: Boolean get() = listOf(homeW, solarW, gridW, batteryW).count { it != null } >= 1
}

internal data class CcConsumer(val entityId: String, val name: String, val watts: Double)

/** The stat ids HA's energy dashboard is set up with. */
internal data class CcEnergyPrefs(
    val gridIn: List<String> = emptyList(),
    val gridOut: List<String> = emptyList(),
    val solar: List<String> = emptyList(),
    val batteryIn: List<String> = emptyList(),
    val batteryOut: List<String> = emptyList(),
    val cost: List<String> = emptyList(),
    val devices: List<String> = emptyList(),
) {
  val all: List<String> get() = (gridIn + gridOut + solar + batteryIn + batteryOut + cost + devices).distinct()
  val configured: Boolean get() = gridIn.isNotEmpty() || solar.isNotEmpty()
}

/** Per-bucket kWh: [use] is what the home consumed, [solar] what the panels made. */
internal data class CcEnergySeries(val starts: List<Long>, val use: List<Double>, val solar: List<Double>, val gridIn: List<Double>, val cost: Double?) {
  val usedTotal: Double get() = use.sum()
  val solarTotal: Double get() = solar.sum()
  /** Share of use not bought from the grid, 0..1; null without data. */
  val selfPowered: Double? get() = usedTotal.takeIf { it > 0 }?.let { (1 - gridIn.sum() / it).coerceIn(0.0, 1.0) }
}

internal object CcEnergy {
  private const val W_PER_KW = 1000.0
  private const val TOP = 5
  private val SOLAR = listOf("solar", "pv_", "_pv", "photovoltaic", "inverter")
  private val GRID = listOf("grid")
  private val BATTERY = listOf("battery", "powerwall")
  private val HOME = listOf("house", "home_power", "home_consumption", "load_power", "consumption", "home_load")
  private val CHARGER = listOf("charger", "wallbox", "evse", "ev_charg")

  fun now(entities: Map<String, CcEntity>): CcEnergyNow {
    val power = entities.values.filter { CcKind.of(it) == CcKind.POWER && !it.unavailable }
    fun pick(hints: List<String>) = power.firstOrNull { e -> hints.any { it in e.id } }
    val solar = pick(SOLAR); val grid = pick(GRID); val battery = pick(BATTERY); val charger = pick(CHARGER)
    val home = power.firstOrNull { e -> HOME.any { it in e.id } && e != solar && e != grid && e != battery }
    val flows = setOfNotNull(solar, grid, battery, charger, home)
    val socs = entities.values.filter { it.domain == "sensor" && it.deviceClass == "battery" && BATTERY.any { h -> h in it.id } && "phone" !in it.id }
    val consumers = power.filter { it !in flows }.mapNotNull { e -> CcRooms.watts(e)?.takeIf { it > 1.0 }?.let { CcConsumer(e.id, e.name, it) } }
        .sortedByDescending { it.watts }.take(TOP)
    val homeW = home?.let(CcRooms::watts) ?: derivedHome(solar, grid, battery)
    return CcEnergyNow(homeW, solar?.let(CcRooms::watts), grid?.let(CcRooms::watts), battery?.let(CcRooms::watts),
        socs.firstOrNull()?.numeric?.toInt(), charger?.let(CcRooms::watts), consumers)
  }

  /** Home = solar + grid import + battery discharge, when there is no home sensor. */
  private fun derivedHome(solar: CcEntity?, grid: CcEntity?, battery: CcEntity?): Double? {
    if (solar == null && grid == null) return null
    return listOfNotNull(solar, grid, battery).mapNotNull(CcRooms::watts).sum().coerceAtLeast(0.0)
  }

  fun prefs(result: Any?): CcEnergyPrefs {
    val o = result as? JSONObject ?: return CcEnergyPrefs()
    val gridIn = ArrayList<String>(); val gridOut = ArrayList<String>(); val solar = ArrayList<String>()
    val batIn = ArrayList<String>(); val batOut = ArrayList<String>(); val cost = ArrayList<String>()
    objects(o.optJSONArray("energy_sources")).forEach { s ->
      when (s.optString("type")) {
        "grid" -> {
          objects(s.optJSONArray("flow_from")).forEach { f -> f.str("stat_energy_from")?.let(gridIn::add); f.str("stat_cost")?.let(cost::add) }
          objects(s.optJSONArray("flow_to")).forEach { f -> f.str("stat_energy_to")?.let(gridOut::add) }
        }
        "solar" -> s.str("stat_energy_from")?.let(solar::add)
        "battery" -> { s.str("stat_energy_from")?.let(batOut::add); s.str("stat_energy_to")?.let(batIn::add) }
      }
    }
    val devices = objects(o.optJSONArray("device_consumption")).mapNotNull { it.str("stat_consumption") }
    return CcEnergyPrefs(gridIn, gridOut, solar, batIn, batOut, cost, devices)
  }

  /** Statistic id → bucket start (ms) → change. HA ≥ 2023 sends ms; older sends ISO strings. */
  fun stats(result: Any?): Map<String, Map<Long, Double>> {
    val o = result as? JSONObject ?: return emptyMap()
    val out = HashMap<String, Map<Long, Double>>()
    o.keys().forEach { id ->
      val rows = objects(o.optJSONArray(id))
      out[id] = rows.mapNotNull { r ->
        val start = r.opt("start").let { s -> (s as? Number)?.toLong() ?: CcEntityParser.isoMs(s as? String) }
        if (start <= 0 || r.isNull("change")) null else start to r.optDouble("change", 0.0)
      }.toMap()
    }
    return out
  }

  /** Buckets from [starts]; use = grid in + solar − grid out + battery out − battery in (never < 0). */
  fun series(prefs: CcEnergyPrefs, stats: Map<String, Map<Long, Double>>, starts: List<Long>): CcEnergySeries {
    fun sum(ids: List<String>, t: Long) = ids.sumOf { stats[it]?.get(t) ?: 0.0 }
    val gridIn = starts.map { sum(prefs.gridIn, it) }
    val solar = starts.map { sum(prefs.solar, it) }
    val use = starts.map { t ->
      (sum(prefs.gridIn, t) + sum(prefs.solar, t) - sum(prefs.gridOut, t) + sum(prefs.batteryOut, t) - sum(prefs.batteryIn, t)).coerceAtLeast(0.0)
    }
    val cost = prefs.cost.takeIf { it.isNotEmpty() }?.let { ids -> starts.sumOf { sum(ids, it) } }
    return CcEnergySeries(starts, use, solar, gridIn, cost)
  }

  fun kw(w: Double?): String = w?.let { "%.1f".format(it / W_PER_KW) } ?: "—"

  private fun JSONObject.str(k: String): String? = optString(k).takeIf { it.isNotBlank() && it != "null" }

  private fun objects(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
}
