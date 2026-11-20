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

/** Car tiles' guards and directions, the charger's parsing of HA statistics, and the phone's Tesla JSON. */
class ControlCenterTeslaCoverageTest {
  private fun e(id: String, state: String, vararg attrs: Pair<String, Any?>) = CcEntity(id, state, mapOf(*attrs))

  private fun actions(vararg roles: Pair<CcCarRole, CcEntity>): List<CcCarAction> {
    val ids = CcTeslaIds(car = roles.associate { it.first to it.second.id } + (CcCarRole.BATTERY to "sensor.y_battery"))
    val ents = (roles.map { it.second } + e("sensor.y_battery", "70")).associateBy { it.id }
    return CcTeslaActions.list(ids, ents, CcTeslaCar.view(ids, ents, CcPhase.LIVE)!!)
  }

  // --- control tiles ----------------------------------------------------------------------

  @Test fun everyCarTileIsGuardedAndOnlyOpeningConfirms() {
    val a = actions(CcCarRole.CHARGE_SWITCH to e("switch.y_charge", "on"), CcCarRole.CLIMATE to e("climate.y", "heat_cool"),
        CcCarRole.LOCK to e("lock.y", "unlocked"), CcCarRole.FLASH to e("button.y_flash", "unknown"),
        CcCarRole.HORN to e("button.y_horn", "2026-01-01T00:00:00+00:00"), CcCarRole.FRUNK to e("cover.y_frunk", "closed"),
        CcCarRole.TRUNK to e("cover.y_trunk", "closed"), CcCarRole.CHARGE_PORT to e("cover.y_port", "open"))
    assertEquals(listOf("charge", "climate", "lock", "flash", "horn", "frunk", "trunk", "port"), a.map { it.key })
    assertTrue(a.all { it.call.guard != CcGuard.NONE })
    val by = a.associateBy { it.key }
    assertEquals("Stop charging", by.getValue("charge").label)
    assertEquals("turn_off", by.getValue("charge").call.service)
    assertTrue(by.getValue("charge").on)
    assertEquals("Climate off", by.getValue("climate").label)
    assertEquals("turn_off", by.getValue("climate").call.service)
    assertEquals("Lock", by.getValue("lock").label)
    assertEquals(CcGuard.VEHICLE, by.getValue("lock").call.guard)
    assertTrue(by.getValue("lock").on)
    assertEquals(CcGuard.CONFIRM, by.getValue("frunk").call.guard)
    assertEquals("Open trunk", by.getValue("trunk").label)
    assertEquals(CcGuard.CONFIRM, by.getValue("trunk").call.guard)
    assertEquals("Close port", by.getValue("port").label)
    assertEquals(CcGuard.VEHICLE, by.getValue("port").call.guard)
    assertEquals("Open port", actions(CcCarRole.CHARGE_PORT to e("cover.y_port", "closed")).single().label)
  }

  @Test fun offAndUnavailableTilesFlipOrDisappear() {
    val a = actions(CcCarRole.CHARGE_SWITCH to e("switch.y_charge", "off"), CcCarRole.CLIMATE to e("climate.y", "off"),
        CcCarRole.LOCK to e("lock.y", "unavailable"), CcCarRole.FLASH to e("button.y_flash", "unavailable"),
        CcCarRole.TRUNK to e("cover.y_trunk", "unknown"))
    assertEquals(listOf("charge", "climate"), a.map { it.key })
    assertEquals("Start charging", a[0].label)
    assertEquals("Precondition", a[1].label)
    assertFalse(a[1].on)
    assertTrue(actions(CcCarRole.HORN to e("button.y_horn", "unknown")).single().call.guard == CcGuard.VEHICLE)
  }

  @Test fun theChargeTileShowsWhenPluggedInOrUnknown() {
    val plugged = actions(CcCarRole.CHARGE_SWITCH to e("switch.y_charge", "off"), CcCarRole.CABLE to e("binary_sensor.y_cable", "on"))
    assertEquals(listOf("charge"), plugged.map { it.key })
  }

  // --- charger ----------------------------------------------------------------------------

  @Test fun chargerStatusWordsAndSources() {
    val cases = mapOf("Charging" to "Charging", "charging_reduced" to "Charging", "ready" to "Plugged in", "Waiting car" to "Plugged in",
        "negotiating" to "Plugged in", "connected" to "Plugged in", "charging_finished" to "Finished", "not_connected" to "Not connected",
        "not_charging" to "Not charging", "error" to "Fault", "booting" to "Starting up", "vehicle_detected" to "Vehicle detected")
    cases.forEach { (raw, said) -> assertEquals(raw, said, CcTeslaCharger.status(raw)) }
    assertEquals("ChargePoint", CcTeslaCharger.sourceName("chargepoint"))
    assertEquals("Wall Connector", CcTeslaCharger.sourceName(null))
  }

  @Test fun chargerViewReadsOnlyLiveRolesAndConvertsUnits() {
    assertNull(CcTeslaCharger.view(CcTeslaIds(), emptyMap()))
    val roles = mapOf(CcChargerRole.SESSION to "sensor.wc_session", CcChargerRole.STATUS to "sensor.wc_status",
        CcChargerRole.CONNECTED to "binary_sensor.wc_conn", CcChargerRole.COST to "sensor.wc_cost",
        CcChargerRole.GRID_VOLTAGE to "sensor.wc_grid")
    val ents = listOf(e("sensor.wc_session", "7500", "unit_of_measurement" to "Wh"), e("sensor.wc_status", "charging"),
        e("binary_sensor.wc_conn", "unavailable"), e("sensor.wc_cost", "1.25"), e("sensor.wc_grid", "2")).associateBy { it.id }
    val v = CcTeslaCharger.view(CcTeslaIds(charger = roles, chargerSource = "chargepoint"), ents)!!
    assertEquals("ChargePoint", v.source)
    assertEquals(7.5, v.sessionKwh!!, 1e-9)
    assertEquals("Charging", v.status)
    assertNull(v.connected)
    assertEquals(1.25, v.sessionCost!!, 1e-9)
    assertNull("an idle contactor's few volts aren't the mains", v.gridVolts)
    val kwh = CcTeslaCharger.view(CcTeslaIds(charger = roles), ents + ("sensor.wc_session" to e("sensor.wc_session", "3", "unit_of_measurement" to "kWh")))!!
    assertEquals(3.0, kwh.sessionKwh!!, 1e-9)
  }

  @Test fun priceNeedsAPerKwhUnitAndANonNegativeValue() {
    val ids = CcTeslaIds(price = "sensor.price")
    fun price(state: String, unit: String?) = CcTeslaCharger.price(ids,
        mapOf("sensor.price" to e("sensor.price", state, "unit_of_measurement" to unit)))
    assertEquals(0.3, price("0.3", "USD/kWh")!!, 1e-9)
    assertEquals(0.3, price("0.3", "$/KWH")!!, 1e-9)
    assertNull(price("0.3", "USD"))
    assertNull(price("0.3", null))
    assertNull(price("-0.1", "USD/kWh"))
    assertNull(price("n/a", "USD/kWh"))
    assertNull(CcTeslaCharger.price(CcTeslaIds(), emptyMap()))
  }

  @Test fun statisticsAcceptMsOrIsoStartsAndSkipBadRows() {
    assertTrue(CcTeslaCharger.stats(null).isEmpty())
    assertTrue(CcTeslaCharger.stats(JSONArray()).isEmpty())
    val stats = CcTeslaCharger.stats(JSONObject("""{"sensor.m":[
        {"start":1000,"change":2.5},
        {"start":"1970-01-01T00:00:02+00:00","change":1},
        {"start":0,"change":9},
        {"start":"garbage","change":9},
        {"start":3000,"change":null},
        {"start":4000},
        "junk"],
        "sensor.none":null}"""))
    assertEquals(mapOf(1000L to 2.5, 2000L to 1.0), stats["sensor.m"])
    assertEquals(emptyMap<Long, Double>(), stats["sensor.none"])
  }

  @Test fun weekBucketsFillMissingDaysAndIgnoreMeterResets() {
    val w = CcTeslaCharger.week("sensor.m", mapOf("sensor.m" to mapOf(1L to 4.0, 2L to -3.0)), listOf(1L, 2L, 3L), 0.2)
    assertEquals(listOf(4.0, 0.0, 0.0), w.kwh)
    assertEquals(0.0, w.today, 0.0)
    assertEquals(0.8, w.weekCost!!, 1e-9)
    assertEquals(listOf(0.0, 0.0), CcTeslaCharger.week("sensor.x", emptyMap(), listOf(1L, 2L), null).kwh)
  }

  // --- the phone's Tesla JSON -------------------------------------------------------------

  @Test fun noRegistryOrNoCarMeansNoTeslaSection() {
    assertNull(CcRemoteTesla.json(CcUiState(), 0L))
    assertNull(CcRemoteTesla.json(CcUiState(registry = CcRegistry()), 0L))
  }

  @Test fun theCarsScreenForThePhone() {
    val ids = CcTeslaIds(name = "Stargate", model = "Model Y",
        car = mapOf(CcCarRole.BATTERY to "sensor.y_battery", CcCarRole.CHARGE_LIMIT to "number.y_limit", CcCarRole.LOCK to "lock.y",
            CcCarRole.LOCATION to "device_tracker.y"),
        charger = mapOf(CcChargerRole.STATUS to "sensor.wc_status"))
    val ents = listOf(e("sensor.y_battery", "64"), e("number.y_limit", "80", "min" to 50, "max" to 100), e("lock.y", "locked"),
        e("device_tracker.y", "home", "latitude" to 51.5, "longitude" to -0.1), e("sensor.wc_status", "ready")).associateBy { it.id }
    val s = CcUiState(entities = ents, registry = CcRegistry(tesla = ids), connection = CcConnection(CcPhase.LIVE),
        chargeWeek = CcChargeWeek(listOf(0L), listOf(3.0), 0.25))
    val j = CcRemoteTesla.json(s, 1_000L)!!
    assertEquals("Stargate", j.getString("label"))
    assertEquals("Model Y", j.getString("model"))
    assertEquals(64, j.getInt("battery"))
    assertEquals(80, j.getInt("limit"))
    assertTrue(j.getBoolean("limitEntity"))
    assertEquals(50, j.getInt("limitMin"))
    val lock = j.getJSONArray("actions").getJSONObject(0)
    assertEquals("lock", lock.getString("key"))
    assertTrue("unlocking the car confirms on the phone too", lock.getBoolean("confirm"))
    assertEquals("Plugged in", j.getJSONObject("charger").getString("status"))
    assertEquals(0.25, j.getJSONObject("week").getDouble("price"), 1e-9)
    assertEquals(1, j.getJSONObject("week").getJSONArray("days").length())
    assertFalse("never coordinates", j.toString().contains("51.5"))
  }

  @Test fun aCarWithoutLimitChargerOrWeekLeavesThemOut() {
    val ids = CcTeslaIds(car = mapOf(CcCarRole.BATTERY to "sensor.y_battery"))
    val s = CcUiState(entities = mapOf("sensor.y_battery" to e("sensor.y_battery", "unavailable")), registry = CcRegistry(tesla = ids))
    val j = CcRemoteTesla.json(s, 0L)!!
    assertEquals("Tesla", j.getString("label"))
    assertFalse(j.has("model"))
    assertFalse(j.has("battery"))
    assertFalse(j.getBoolean("limitEntity"))
    assertFalse(j.has("charger"))
    assertFalse(j.has("week"))
    assertEquals(0, j.getJSONArray("actions").length())
  }
}
