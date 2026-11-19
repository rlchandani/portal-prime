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

/**
 * What the Tesla screen reads from one car, its home charger and (optionally) an electricity price.
 * Found once per registry load from HA's entity + device registries (pure, unit tested): Tesla
 * Fleet, Teslemetry and Tessie share `translation_key`s, so roles are matched on (domain, key);
 * entities without a key fall back to their id suffix. ChargePoint is used only for the account's
 * own session — never the hundreds of public stations it also exposes.
 */
internal enum class CcCarRole(val domain: String, val keys: List<String>, val suffixes: List<String> = emptyList()) {
  BATTERY("sensor", listOf("charge_state_battery_level", "charge_state_usable_battery_level"), listOf("_battery_level", "_battery")),
  RANGE("sensor", listOf("charge_state_battery_range", "charge_state_est_battery_range"), listOf("_battery_range", "_range")),
  CHARGING("sensor", listOf("charge_state_charging_state"), listOf("_charging")),
  CHARGER_POWER("sensor", listOf("charge_state_charger_power"), listOf("_charger_power")),
  ENERGY_ADDED("sensor", listOf("charge_state_charge_energy_added"), listOf("_charge_energy_added", "_energy_added")),
  TIME_TO_FULL("sensor", listOf("charge_state_minutes_to_full_charge", "charge_state_time_to_full_charge"), listOf("_time_to_full_charge")),
  CHARGE_LIMIT("number", listOf("charge_state_charge_limit_soc"), listOf("_charge_limit")),
  CHARGE_CURRENT("number", listOf("charge_state_charge_current_request"), listOf("_charge_current")),
  CHARGE_SWITCH("switch", listOf("charge_state_charging_state", "charge_state_charge_enable_request", "charge"), listOf("_charge", "_charger")),
  CABLE("binary_sensor", listOf("charge_state_conn_charge_cable"), listOf("_charge_cable", "_plugged_in")),
  CHARGE_PORT("cover", listOf("charge_state_charge_port_door_open"), listOf("_charge_port_door", "_charge_port")),
  STATUS("binary_sensor", listOf("state", "status"), listOf("_status")),
  STATE("sensor", listOf("state"), listOf("_state")),
  LOCATION("device_tracker", listOf("location"), listOf("_location")),
  INSIDE_TEMP("sensor", listOf("climate_state_inside_temp"), listOf("_inside_temperature", "_inside_temp")),
  OUTSIDE_TEMP("sensor", listOf("climate_state_outside_temp"), listOf("_outside_temperature", "_outside_temp")),
  CLIMATE("climate", listOf("driver_temp", "climate_state_cabin", "primary"), listOf("_climate", "_hvac_climate_system")),
  LOCK("lock", listOf("vehicle_state_locked"), listOf("_lock", "_door_lock", "_doors")),
  SENTRY("switch", listOf("vehicle_state_sentry_mode"), listOf("_sentry_mode")),
  ODOMETER("sensor", listOf("vehicle_state_odometer"), listOf("_odometer")),
  UPDATE("update", listOf("vehicle_state_software_update_status"), listOf("_update", "_software_update")),
  TYRE_FL("sensor", listOf("vehicle_state_tpms_pressure_fl"), listOf("_tire_pressure_front_left", "_tpms_front_left")),
  TYRE_FR("sensor", listOf("vehicle_state_tpms_pressure_fr"), listOf("_tire_pressure_front_right", "_tpms_front_right")),
  TYRE_RL("sensor", listOf("vehicle_state_tpms_pressure_rl"), listOf("_tire_pressure_rear_left", "_tpms_rear_left")),
  TYRE_RR("sensor", listOf("vehicle_state_tpms_pressure_rr"), listOf("_tire_pressure_rear_right", "_tpms_rear_right")),
  TYRE_WARN_FL("binary_sensor", listOf("vehicle_state_tpms_soft_warning_fl")),
  TYRE_WARN_FR("binary_sensor", listOf("vehicle_state_tpms_soft_warning_fr")),
  TYRE_WARN_RL("binary_sensor", listOf("vehicle_state_tpms_soft_warning_rl")),
  TYRE_WARN_RR("binary_sensor", listOf("vehicle_state_tpms_soft_warning_rr")),
  DOOR_FL("binary_sensor", listOf("vehicle_state_df"), listOf("_front_driver_door")),
  DOOR_FR("binary_sensor", listOf("vehicle_state_pf"), listOf("_front_passenger_door")),
  DOOR_RL("binary_sensor", listOf("vehicle_state_dr"), listOf("_rear_driver_door")),
  DOOR_RR("binary_sensor", listOf("vehicle_state_pr"), listOf("_rear_passenger_door")),
  WINDOW_FL("binary_sensor", listOf("vehicle_state_fd_window"), listOf("_front_driver_window")),
  WINDOW_FR("binary_sensor", listOf("vehicle_state_fp_window"), listOf("_front_passenger_window")),
  WINDOW_RL("binary_sensor", listOf("vehicle_state_rd_window"), listOf("_rear_driver_window")),
  WINDOW_RR("binary_sensor", listOf("vehicle_state_rp_window"), listOf("_rear_passenger_window")),
  FRUNK("cover", listOf("vehicle_state_ft"), listOf("_frunk")),
  TRUNK("cover", listOf("vehicle_state_rt"), listOf("_trunk")),
  WAKE("button", listOf("wake"), listOf("_wake", "_wake_up")),
  FLASH("button", listOf("flash_lights"), listOf("_flash_lights")),
  HORN("button", listOf("honk"), listOf("_honk_horn", "_honk")),
}

/** The home charger's roles (Tesla Wall Connector, Tesla Fleet's Wall Connector power, ChargePoint session). */
internal enum class CcChargerRole(val domain: String, val keys: List<String>, val suffixes: List<String> = emptyList()) {
  POWER("sensor", listOf("total_power_w", "wall_connector_power"), listOf("_total_power", "_wall_connector_power", "_session_power")),
  SESSION("sensor", listOf("session_energy_wh"), listOf("_session_energy")),
  LIFETIME("sensor", listOf("energy_kwh"), listOf("_lifetime_energy")),
  STATUS("sensor", listOf("status"), listOf("_status", "_session_state")),
  CONNECTED("binary_sensor", listOf("vehicle_connected"), listOf("_vehicle_connected")),
  GRID_VOLTAGE("sensor", listOf("grid_v"), listOf("_grid_voltage")),
  COST("sensor", emptyList(), listOf("_session_cost")),
}

/** The entity ids behind the Tesla screen. Empty [car] = no Tesla in HA (the "not set up" state). */
@Immutable
internal data class CcTeslaIds(
    val name: String? = null,
    val model: String? = null,
    val car: Map<CcCarRole, String> = emptyMap(),
    val charger: Map<CcChargerRole, String> = emptyMap(),
    /** Which integration the charger comes from (tesla_wall_connector, tesla_fleet, chargepoint). */
    val chargerSource: String? = null,
    /** An electricity price entity (unit per kWh), when the house has one. */
    val price: String? = null,
    /** The car's paint name (e.g. TeslaMate's `sensor.<car>_exterior_color`), for the art's body colour. */
    val paint: String? = null,
) {
  val hasCar: Boolean get() = car.isNotEmpty()
  /** Everything the Tesla screen and side card read (subscribed while the Control Center is on screen). */
  val all: Set<String> get() = car.values.toSet() + charger.values + listOfNotNull(price, paint)
  /** The recorder statistic behind the week chart: the charger's lifetime meter, else the car's energy added. */
  val weekStat: String? get() = charger[CcChargerRole.LIFETIME] ?: car[CcCarRole.ENERGY_ADDED]
  /** Rail and header label: the car's HA name when it has a short one, else "Tesla". */
  val label: String get() = name?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_LABEL } ?: DEFAULT_LABEL

  companion object {
    val NONE = CcTeslaIds()
    const val DEFAULT_LABEL = "Tesla"
    private const val MAX_LABEL = 10
  }
}

internal object CcTeslaRegistry {
  val CAR_PLATFORMS = setOf("tesla_fleet", "teslemetry", "tessie", "tesla_custom")
  private const val WALL_CONNECTOR = "tesla_wall_connector"
  private const val CHARGEPOINT = "chargepoint"
  private val PRICE_HINTS = listOf("electricity_price", "energy_price", "price_per_kwh", "kwh_price", "electricity_rate", "energy_rate")

  private data class Row(val id: String, val platform: String, val key: String?, val device: String?) {
    val domain: String get() = id.substringBefore('.')
  }

  private data class Device(val name: String?, val model: String?)

  /** Builds [CcTeslaIds] from `config/device_registry/list` and `config/entity_registry/list`. */
  fun parse(devices: JSONArray?, entities: JSONArray?): CcTeslaIds {
    val rows = objects(entities).mapNotNull(::row)
    val deviceInfo = objects(devices).mapNotNull { o -> o.str("id")?.let { it to Device(o.str("name_by_user") ?: o.str("name"), o.str("model")) } }.toMap()
    val carRows = rows.filter { it.platform in CAR_PLATFORMS }
    val carDevice = carDevice(carRows) ?: return chargerOnly(rows)
    val ownRows = carRows.filter { it.device == carDevice }
    val car = CcCarRole.entries.mapNotNull { role -> match(ownRows, role.domain, role.keys, role.suffixes)?.let { role to it } }.toMap()
    val (charger, source) = charger(rows, carRows)
    val info = deviceInfo[carDevice]
    return CcTeslaIds(info?.name, info?.model, car, charger, source, price(rows), paint(rows, ownRows))
  }

  /** The car = the device with a battery level (first by id, so a household with two cars is stable). */
  private fun carDevice(carRows: List<Row>): String? =
      carRows.filter { r -> r.device != null && (r.key in CcCarRole.BATTERY.keys || CcCarRole.BATTERY.suffixes.any { r.id.endsWith(it) }) && r.domain == "sensor" }
          .minByOrNull { it.id }?.device

  private fun chargerOnly(rows: List<Row>): CcTeslaIds {
    val (charger, source) = charger(rows, emptyList())
    return if (charger.isEmpty()) CcTeslaIds.NONE else CcTeslaIds(charger = charger, chargerSource = source, price = price(rows))
  }

  /** Local Wall Connector first, then Tesla Fleet's Wall Connector power, then the ChargePoint account session. */
  private fun charger(rows: List<Row>, carRows: List<Row>): Pair<Map<CcChargerRole, String>, String?> {
    val local = roles(rows.filter { it.platform == WALL_CONNECTOR })
    if (local.isNotEmpty()) return local to WALL_CONNECTOR
    val fleet = roles(carRows.filter { it.key == "wall_connector_power" })
    if (fleet.isNotEmpty()) return fleet to carRows.first { it.key == "wall_connector_power" }.platform
    val account = roles(rows.filter { it.platform == CHARGEPOINT && "_session_" in it.id })
    return if (account.isNotEmpty()) account to CHARGEPOINT else emptyMap<CcChargerRole, String>() to null
  }

  private fun roles(rows: List<Row>): Map<CcChargerRole, String> =
      CcChargerRole.entries.mapNotNull { role -> match(rows, role.domain, role.keys, role.suffixes)?.let { role to it } }.toMap()

  /** A translation-key match wins; an id-suffix match is the fallback for keyless integrations. */
  private fun match(rows: List<Row>, domain: String, keys: List<String>, suffixes: List<String>): String? {
    val inDomain = rows.filter { it.domain == domain }
    keys.forEach { k -> inDomain.firstOrNull { it.key == k }?.let { return it.id } }
    return suffixes.firstNotNullOfOrNull { s -> inDomain.filter { it.key == null && it.id.endsWith(s) }.minByOrNull { it.id }?.id }
  }

  /** The car's own paint sensor first, else any `*_exterior_color` sensor (TeslaMate publishes it over MQTT). */
  private fun paint(rows: List<Row>, own: List<Row>): String? =
      (own + rows).firstOrNull { it.domain == "sensor" && (it.key == "vehicle_config_exterior_color" || it.id.endsWith(PAINT_SUFFIX)) }?.id

  private const val PAINT_SUFFIX = "_exterior_color"

  private fun price(rows: List<Row>): String? =
      rows.filter { it.domain == "sensor" || it.domain == "input_number" }.firstOrNull { r -> PRICE_HINTS.any { it in r.id } }?.id

  private fun row(o: JSONObject): Row? {
    val id = o.str("entity_id")?.takeIf { it.contains('.') } ?: return null
    if (o.str("disabled_by") != null) return null
    return Row(id, o.str("platform") ?: "", o.str("translation_key"), o.str("device_id"))
  }

  private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).takeIf { it.isNotBlank() && it != "null" }

  private fun objects(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
}
