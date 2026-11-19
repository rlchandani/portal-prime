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
import org.junit.Assert.assertNull
import org.junit.Test

/** The hero's idle tile, the time-to-limit estimate, paint names and the new registry roles. */
class ControlCenterTeslaIdleTest {
  private val ids = CcTeslaIds(model = "Model Y", car = mapOf(CcCarRole.BATTERY to "sensor.b", CcCarRole.CHARGE_LIMIT to "number.l",
      CcCarRole.CHARGING to "sensor.cs", CcCarRole.CABLE to "binary_sensor.c", CcCarRole.CHARGE_CURRENT to "number.a"),
      paint = "sensor.tesla_exterior_color")

  private fun view(pct: String, state: String, cable: String, paint: String = "unknown") = CcTeslaCar.view(ids, listOf(
      CcEntity("sensor.b", pct), CcEntity("number.l", "80"), CcEntity("sensor.cs", state), CcEntity("binary_sensor.c", cable),
      CcEntity("number.a", "48"), CcEntity("sensor.tesla_exterior_color", paint)).associateBy { it.id }, CcPhase.LIVE)!!

  private val wc = CcChargerView("Wall Connector", 0.0, 0.0, "Not connected", false, null, gridVolts = 233.6)

  @Test fun unplugged_estimatesTheHomeChargeToTheLimit() {
    val idle = CcTeslaIdle.of(view("72", "disconnected", "off"), wc, ids.model)
    assertEquals("Not plugged in", idle.title)
    // 8% of ~75 kWh at 48 A × 233.6 V (11.2 kW) at 90% efficiency ≈ 36 min.
    assertEquals("About 36 min to 80% at 11 kW", idle.detail)
  }

  @Test fun pluggedPausedCompleteAndAtLimit() {
    assertEquals("Plugged in · not charging", CcTeslaIdle.of(view("60", "disconnected", "on"), wc, ids.model).title)
    assertEquals("Plugged in · paused", CcTeslaIdle.of(view("60", "stopped", "on"), wc, ids.model).title)
    val done = CcTeslaIdle.of(view("80", "complete", "on"), wc, ids.model)
    assertEquals("Charge complete", done.title); assertEquals("At your 80% limit", done.detail)
    // No charger voltage: 240 V nominal.
    assertEquals(11.52, CcTeslaIdle.rateKw(48, null)!!, 0.001)
    assertNull(CcTeslaIdle.rateKw(null, 240.0))
    assertNull(CcTeslaIdle.of(view("60", "disconnected", "off").copy(chargeAmps = null), null, null).detail)
    assertEquals(2 * 60L, CcTeslaIdle.minutesTo(20, 80, 95.0, 31.67))
  }

  @Test fun durations() {
    assertEquals("45 min", CcTeslaText.duration(45)); assertEquals("2 h", CcTeslaText.duration(120))
    assertEquals("1 h 5 min", CcTeslaText.duration(65))
  }

  @Test fun paint_fromHaNames_withNeutralFallback() {
    assertEquals("Grey", CcTeslaPaint.of("MidnightSilver").name)
    assertEquals("Quicksilver", CcTeslaPaint.of("Quicksilver").name)
    assertEquals("Pearl White", CcTeslaPaint.of("Pearl White Multi-Coat").name)
    assertEquals("Red", CcTeslaPaint.of("UltraRed").name)
    assertEquals("Midnight Cherry", CcTeslaPaint.of("MidnightCherryRed").name)
    assertEquals("Deep Blue", CcTeslaPaint.of("DeepBlue").name)
    assertEquals("Black", CcTeslaPaint.of("SolidBlack").name)
    assertEquals(CcTeslaPaint.NEUTRAL, CcTeslaPaint.of("unknown"))
    assertEquals(CcTeslaPaint.NEUTRAL, CcTeslaPaint.of(null))
    assertEquals(CcTeslaPaint.NEUTRAL, CcTeslaPaint.of("Chartreuse"))
    assertEquals("Red", view("50", "disconnected", "off", paint = "RedMulticoat").paint.name)
    assertEquals(CcTeslaPaint.NEUTRAL, view("50", "disconnected", "off").paint)
  }

  @Test fun registry_findsPaintChargeCurrentAndGridVoltage() {
    fun ent(id: String, platform: String, key: String?, device: String?) = JSONObject(mapOf("entity_id" to id, "platform" to platform,
        "translation_key" to (key ?: JSONObject.NULL), "device_id" to (device ?: JSONObject.NULL)))
    val ids = CcTeslaRegistry.parse(JSONArray(), JSONArray(listOf(
        ent("sensor.car_battery_level", "tesla_fleet", "charge_state_battery_level", "car"),
        ent("number.car_charge_current", "tesla_fleet", "charge_state_charge_current_request", "car"),
        ent("sensor.tesla_exterior_color", "mqtt", null, "tm"),
        ent("sensor.wc_grid_voltage", "tesla_wall_connector", "grid_v", "wc"))))
    assertEquals("number.car_charge_current", ids.car[CcCarRole.CHARGE_CURRENT])
    assertEquals("sensor.tesla_exterior_color", ids.paint)
    assertEquals("sensor.wc_grid_voltage", ids.charger[CcChargerRole.GRID_VOLTAGE])
    assertEquals(true, "sensor.tesla_exterior_color" in ids.all)
  }
}
