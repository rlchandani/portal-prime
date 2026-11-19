/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Registry discovery: roles by translation key, id-suffix fallback, the charger and the car's name. */
class ControlCenterTeslaTest {
  private fun ent(id: String, platform: String, key: String?, device: String? = "car", extra: Map<String, Any?> = emptyMap()) =
      JSONObject(mapOf("entity_id" to id, "platform" to platform, "translation_key" to (key ?: JSONObject.NULL), "device_id" to (device ?: JSONObject.NULL)) + extra)

  private val devices = JSONArray(listOf(
      JSONObject(mapOf("id" to "car", "name" to "Stargate", "model" to "Model Y", "name_by_user" to JSONObject.NULL)),
      JSONObject(mapOf("id" to "wc", "name" to "Wall Connector", "model" to "Gen 3"))))

  private val fleet = listOf(
      ent("sensor.stargate_battery_level", "tesla_fleet", "charge_state_battery_level"),
      ent("sensor.stargate_battery_range", "tesla_fleet", "charge_state_battery_range"),
      ent("sensor.stargate_charging", "tesla_fleet", "charge_state_charging_state"),
      ent("switch.stargate_charge", "tesla_fleet", "charge_state_charging_state"),
      ent("number.stargate_charge_limit", "tesla_fleet", "charge_state_charge_limit_soc"),
      ent("binary_sensor.stargate_status", "tesla_fleet", "state"),
      ent("lock.stargate_lock", "tesla_fleet", "vehicle_state_locked"),
      ent("cover.stargate_frunk", "tesla_fleet", "vehicle_state_ft"),
      ent("button.stargate_wake", "tesla_fleet", "wake"),
      ent("sensor.stargate_charge_energy_added", "tesla_fleet", "charge_state_charge_energy_added"),
      ent("sensor.stargate_speed", "tesla_fleet", "drive_state_speed", extra = mapOf("disabled_by" to "integration")),
      ent("sensor.wall_connector_power", "tesla_fleet", "wall_connector_power", device = "wc"),
  )

  @Test fun parse_mapsFleetRolesAndName() {
    val ids = CcTeslaRegistry.parse(devices, JSONArray(fleet))
    assertTrue(ids.hasCar)
    assertEquals("Stargate", ids.label)
    assertEquals("Model Y", ids.model)
    assertEquals("sensor.stargate_charging", ids.car[CcCarRole.CHARGING])
    assertEquals("switch.stargate_charge", ids.car[CcCarRole.CHARGE_SWITCH])
    assertEquals("binary_sensor.stargate_status", ids.car[CcCarRole.STATUS])
    assertEquals("button.stargate_wake", ids.car[CcCarRole.WAKE])
    assertNull("the wall connector's power is not the car's", ids.car[CcCarRole.CHARGER_POWER])
    // Without the local Wall Connector integration, Fleet's Wall Connector power is the charger.
    assertEquals("sensor.wall_connector_power", ids.charger[CcChargerRole.POWER])
    assertEquals("tesla_fleet", ids.chargerSource)
    assertEquals("sensor.stargate_charge_energy_added", ids.weekStat)
    assertFalse("disabled entities are skipped", "sensor.stargate_speed" in ids.all)
  }

  @Test fun parse_prefersLocalWallConnectorAndItsLifetimeMeter() {
    val wc = listOf(
        ent("sensor.tesla_wall_connector_total_power", "tesla_wall_connector", "total_power_w", "local"),
        ent("sensor.tesla_wall_connector_energy", "tesla_wall_connector", "energy_kwh", "local"),
        ent("sensor.tesla_wall_connector_session_energy", "tesla_wall_connector", "session_energy_wh", "local"),
        ent("sensor.tesla_wall_connector_status", "tesla_wall_connector", "status", "local"),
        ent("binary_sensor.tesla_wall_connector_vehicle_connected", "tesla_wall_connector", "vehicle_connected", "local"),
        ent("sensor.electricity_price", "template", null, null),
    )
    val ids = CcTeslaRegistry.parse(devices, JSONArray(fleet + wc))
    assertEquals("tesla_wall_connector", ids.chargerSource)
    assertEquals("sensor.tesla_wall_connector_total_power", ids.charger[CcChargerRole.POWER])
    assertEquals("sensor.tesla_wall_connector_energy", ids.weekStat)
    assertEquals("sensor.electricity_price", ids.price)
    assertTrue("sensor.electricity_price" in ids.all)
  }

  @Test fun parse_suffixFallback_andChargePointAccountOnly() {
    val custom = listOf(
        ent("sensor.my_car_battery_level", "tesla_custom", null),
        ent("lock.my_car_door_lock", "tesla_custom", null),
        ent("sensor.home_session_power", "chargepoint", null, "acct"),
        ent("binary_sensor.public_1_available", "chargepoint", "public_station", "station"),
    )
    val ids = CcTeslaRegistry.parse(JSONArray(), JSONArray(custom))
    assertEquals("sensor.my_car_battery_level", ids.car[CcCarRole.BATTERY])
    assertEquals("lock.my_car_door_lock", ids.car[CcCarRole.LOCK])
    assertEquals("Tesla", ids.label)
    assertEquals("chargepoint", ids.chargerSource)
    assertFalse(ids.all.any { "public" in it })
  }

  @Test fun parse_nothingTesla_isNone() {
    assertEquals(CcTeslaIds.NONE, CcTeslaRegistry.parse(null, null))
    assertEquals(CcTeslaIds.NONE, CcTeslaRegistry.parse(JSONArray(), JSONArray(listOf(ent("light.a", "hue", null)))))
    val chargerOnly = CcTeslaRegistry.parse(JSONArray(), JSONArray(listOf(ent("sensor.wc_total_power", "tesla_wall_connector", "total_power_w", "wc"))))
    assertFalse(chargerOnly.hasCar)
    assertEquals("sensor.wc_total_power", chargerOnly.charger[CcChargerRole.POWER])
  }

  @Test fun label_fallsBackForLongOrMissingNames() {
    assertEquals("Tesla", CcTeslaIds(name = "The family's long-range car").label)
    assertEquals("Tesla", CcTeslaIds(name = "  ").label)
    assertEquals("Stargate", CcTeslaIds(name = " Stargate ").label)
  }

  @Test fun interest_subscribesTheCarEvenThoughItsDiagnostic() {
    val tesla = CcTeslaIds(car = mapOf(CcCarRole.DOOR_FL to "binary_sensor.stargate_front_driver_door"))
    val reg = CcRegistry(entities = setOf("light.kitchen"), tesla = tesla)
    assertTrue("binary_sensor.stargate_front_driver_door" in CcInterest.foreground(reg, null)!!)
  }
}
